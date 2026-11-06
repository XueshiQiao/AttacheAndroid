/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.attache.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxControl
import java.util.UUID

/** What has been read of a conversation so far. */
data class ConversationView(
    val items: List<ChatItem> = emptyList(),
    val status: AgentStatus? = null,
    /** The file offset of the earliest line read; history lies above it. */
    val historyStart: Long? = null,
    val state: TranscriptStatus = TranscriptStatus.LOADING,
)

/**
 * The conversations opened last, kept read and followed while their page is
 * closed, so that going back to one shows it as it is now, at once: the
 * owner switches between a handful of agents all the time, and reading the
 * same 600 KB again on every switch was most of the wait.
 *
 * Followed rather than caught up on return: catching up from an offset needs
 * to know the file was only appended to since, which a rewind may not
 * honour; a follow that never stopped sees whatever the file does, as the
 * open page always has. What it costs is one `tail -f` on the host per kept
 * conversation, sending only what its agent writes.
 *
 * Kept for as long as the connection lasts: when its channel closes, a
 * conversation forgets itself, and the next visit reads it anew.
 */
object Conversations {
    private const val KEEP = 8

    private val open = LinkedHashMap<String, LiveConversation>(16, 0.75f, true)

    /** The conversation at [path], kept or new; the least recent beyond
     * [KEEP] is closed. */
    @Synchronized
    fun get(host: UUID, path: String, kind: String, dispatcher: CoroutineDispatcher): LiveConversation {
        val key = "$host $kind $path"
        open[key]?.let { return it }
        val made = LiveConversation(key, path, kind, dispatcher)
        open[key] = made
        while (open.size > KEEP) {
            val eldest = open.entries.first()
            open.remove(eldest.key)
            eldest.value.close()
        }
        return made
    }

    @Synchronized
    internal fun forget(conversation: LiveConversation) {
        if (open[conversation.key] === conversation) open.remove(conversation.key)
        conversation.close()
    }
}

/** One transcript, read and followed on a scope of its own rather than a
 * page's, so that it outlives the page. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveConversation internal constructor(
    internal val key: String,
    val path: String,
    private val kind: String,
    dispatcher: CoroutineDispatcher,
) {
    /** Parsing a megabyte of JSON is not for the main thread, and a
     * transcript is not thread-safe: one lane for all of it. */
    private val lane = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + lane)

    private val _view = MutableStateFlow(ConversationView())
    val view: StateFlow<ConversationView> = _view.asStateFlow()

    // The reader being shown and where its earliest line starts. On the lane only.
    private var transcript: AgentTranscript? = null
    private var historyStart: Long? = null

    private var job: Job? = null
    private var control: TmuxControl? = null

    /** Follows the file over [tmux], unless that is already happening. */
    @Synchronized
    fun follow(tmux: TmuxControl) {
        if (job?.isActive == true && control === tmux) return
        // Before the cancel: the old loop, ending, must not take a channel
        // it no longer follows for this conversation's.
        control = tmux
        job?.cancel()
        job = scope.launch {
            try {
                followLoop(tmux)
            } finally {
                // The connection ended: everything read over it goes.
                if (tmux.isClosed && control === tmux) Conversations.forget(this@LiveConversation)
            }
        }
    }

    private suspend fun followLoop(tmux: TmuxControl) {
        while (true) {
            val reader = (AgentProviders.forKind(kind) ?: ClaudeProvider).transcriptReader() ?: return
            val splitter = LineSplitter()
            var started = false
            var publishJob: Job? = null
            val ok = coroutineScope {
                val ok = tmux.follow(TranscriptFollower.command(path)) { chunk ->
                    val lines = splitter.feed(chunk)
                    if (lines.isEmpty() && splitter.startOffset == null) return@follow
                    // What was shown stays until the new read has something
                    // to show: after a reconnect, the old rows, not a spinner.
                    if (!started) {
                        started = true
                        transcript = reader
                        historyStart = splitter.startOffset
                    }
                    lines.forEach(reader::accept)
                    // Coalesced: an agent mid-turn appends several times a
                    // second, and the list need not redraw for each.
                    publishJob?.cancel()
                    publishJob = launch {
                        delay(PUBLISH_DELAY_MS)
                        publish(reader)
                    }
                }
                publishJob?.join()
                ok
            }
            if (!ok || !started) _view.update { it.copy(state = TranscriptStatus.MISSING) }
            // The channel is gone; otherwise the file went away, so try
            // again shortly.
            if (tmux.isClosed) return
            delay(RETRY_MS)
        }
    }

    private fun publish(reader: AgentTranscript) {
        _view.value = ConversationView(reader.snapshot, reader.status, historyStart, TranscriptStatus.LIVE)
    }

    /**
     * Puts the history just before what is shown in front of it. False when
     * it could not be read.
     */
    suspend fun loadEarlier(tmux: TmuxControl): Boolean = withContext(lane) {
        val reader = transcript ?: return@withContext true
        val end = historyStart?.takeIf { it > 0 } ?: return@withContext true
        // At least one line always comes back, so each load gets further up
        // however long the records are.
        val text = tmux.run(TranscriptFollower.earlierCommand(path, end))
        val start = text?.substringBefore('\n')?.removePrefix(TranscriptFollower.HEADER)?.trim()?.toLongOrNull()
            ?: return@withContext false
        // The file was followed anew while this was in flight.
        if (reader !== transcript) return@withContext true
        reader.prepend(text.substringAfter('\n', "").split('\n').filter { it.isNotBlank() })
        historyStart = minOf(start, end)
        publish(reader)
        true
    }

    internal fun close() = scope.cancel()

    private companion object {
        const val PUBLISH_DELAY_MS = 120L
        const val RETRY_MS = 3_000L
    }
}
