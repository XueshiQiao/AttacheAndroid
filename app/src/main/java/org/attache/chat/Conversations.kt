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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxControl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** What has been read of a conversation so far. */
data class ConversationView(
    val items: List<ChatItem> = emptyList(),
    val status: AgentStatus? = null,
    /** The file offset the rows start at; history lies above it. */
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
 * open page always has. What it costs is one `attache-cli follow` on the host
 * per kept conversation, sending only what its agent writes.
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

/**
 * One conversation, followed by `attache-cli follow` on a scope of its own
 * rather than a page's, so that it outlives the page. attache-cli reads the
 * transcript on the host and sends rows: the first read, then each change,
 * from the first row that changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveConversation internal constructor(
    internal val key: String,
    val path: String,
    private val kind: String,
    dispatcher: CoroutineDispatcher,
) {
    /** Events are applied in order, off the main thread: one lane. */
    private val lane = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + lane)

    private val _view = MutableStateFlow(ConversationView())
    val view: StateFlow<ConversationView> = _view.asStateFlow()

    // What the current follow has sent. On the lane only.
    private var items: List<ChatItem> = emptyList()
    private var status: AgentStatus? = null
    private var start: Long? = null

    /** Set to read from further back on the next follow (earlier history). */
    @Volatile private var before: Long? = null

    private var job: Job? = null
    private var control: TmuxControl? = null

    /** Follows the conversation over [tmux], unless that is already happening. */
    @Synchronized
    fun follow(tmux: TmuxControl) {
        if (job?.isActive == true && control === tmux) return
        restart(tmux)
    }

    @Synchronized
    private fun restart(tmux: TmuxControl) {
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
        val cli = tmux.cli()
        if (cli == null) {
            _view.update { it.copy(state = TranscriptStatus.MISSING) }
            return
        }
        while (true) {
            val lines = JsonLines()
            // What was shown stays until the new read has something to show:
            // after a reconnect, or while earlier history loads, the old rows.
            var fresh = true
            val ok = tmux.follow(AttacheCli.followCommand(cli, kind, path, before)) { chunk ->
                for (line in lines.feed(chunk)) {
                    val event = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
                    when (event.optString("t")) {
                        "waiting" -> if (items.isEmpty()) _view.update { it.copy(state = TranscriptStatus.LOADING) }

                        "hello" -> {
                            start = event.optLong("start")
                            fresh = true
                        }

                        "items" -> {
                            val from = event.optInt("from")
                            val rows = event.optJSONArray("items") ?: JSONArray()
                            val parsed = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let(AttacheCli::item) }
                            items = if (fresh) parsed else items.take(from) + parsed
                            fresh = false
                            before = null
                            publish()
                        }

                        "status" -> {
                            status = AttacheCli.status(event)
                            if (!fresh) publish()
                        }
                    }
                }
            }
            if (!ok && items.isEmpty()) _view.update { it.copy(state = TranscriptStatus.MISSING) }
            // The channel is gone; otherwise attache-cli ended on its own, so
            // try again shortly.
            if (tmux.isClosed) return
            delay(RETRY_MS)
        }
    }

    private fun publish() {
        _view.value = ConversationView(items, status, start, TranscriptStatus.LIVE)
    }

    /**
     * Reads from further back: a new follow from before where this one
     * started, which sends every row again with the earlier ones in front.
     */
    suspend fun loadEarlier(tmux: TmuxControl): Boolean {
        val from = withContext(lane) { start }?.takeIf { it > 0 } ?: return true
        before = from
        restart(tmux)
        return true
    }

    internal fun close() = scope.cancel()

    private companion object {
        const val RETRY_MS = 3_000L
    }
}

/**
 * Bytes in, complete lines out. The unfinished tail is kept as raw bytes and
 * never decoded on its own: a read can end in the middle of a multi-byte
 * character, and decoding half of it turns it into a replacement character
 * that no later read can repair.
 */
class JsonLines {
    private val pending = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var from = 0
        for (i in chunk.indices) {
            if (chunk[i] != '\n'.code.toByte()) continue
            pending.write(chunk, from, i - from)
            from = i + 1
            pending.toByteArray().toString(Charsets.UTF_8).takeIf { it.isNotBlank() }?.let(lines::add)
            pending.reset()
        }
        pending.write(chunk, from, chunk.size - from)
        return lines
    }
}
