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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.attache.tmux.TmuxControl
import org.connectbot.data.EntityIds
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.ui.navigation.NavArgs
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class AgentListUiState(
    val hostName: String = "",
    val connection: ChatConnection = ChatConnection.CONNECTING,
    val agents: List<AgentPane> = emptyList(),
    val error: String? = null,
    /** The host's window tags, in its list's order. */
    val tags: List<WindowTag> = emptyList(),
    /** Each window's tag ids, keyed by window id (`@7`). */
    val windowTags: Map<String, List<String>> = emptyMap(),
) {
    /** The tags [agent]'s window carries, in the list's order. */
    fun tagsOf(agent: AgentPane): List<WindowTag> {
        val ids = windowTags[agent.windowId] ?: return emptyList()
        return tags.filter { it.id in ids }
    }
}

/** The chat mode's first screen: every agent in tmux, by what it needs. */
@HiltViewModel
class AgentListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    val hostId: UUID = EntityIds.parse(savedStateHandle.get<String>(NavArgs.HOST_ID))
    private val link = ChatLink(hostId, viewModelScope, dispatchers)

    // What this host's list was when last read, shown at once on return.
    private val agents = MutableStateFlow(AgentDirectory.get(hostId))
    private val pollError = MutableStateFlow<String?>(null)
    private val tagList = MutableStateFlow<List<WindowTag>>(emptyList())
    private val windowTags = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    val uiState: StateFlow<AgentListUiState> =
        combine(
            combine(link.hostName, link.connection, agents, link.error, pollError) { host, connection, list, linkError, error ->
                AgentListUiState(host, connection, list, error ?: linkError)
            },
            tagList,
            windowTags,
        ) { state, tags, byWindow -> state.copy(tags = tags, windowTags = byWindow) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AgentListUiState())

    /** Whether the list is on screen. It polls only then: behind a chat page
     * it would run every agent's script on the channel the chat page is
     * waiting on, for a list nobody sees. */
    private val shown = MutableStateFlow(false)

    fun setShown(value: Boolean) {
        shown.value = value
        link.setShown(value)
    }

    init {
        viewModelScope.launch {
            link.control.combine(shown) { tmux, visible -> tmux.takeIf { visible } }
                .collectLatest { tmux -> if (tmux != null) poll(tmux) }
        }
        viewModelScope.launch {
            link.control.combine(shown) { tmux, visible -> tmux.takeIf { visible } }
                .collectLatest { tmux -> if (tmux != null) followTags(tmux) }
        }
    }

    /**
     * Reads the tags when the list appears and again each time tmux reports
     * that they changed (`TmuxControl.tagRevisions`) — told, never asked on
     * a timer. Each report rereads every window's tags; the tag list itself
     * only when it is the list's revision that moved, or a window names a
     * tag the list does not have yet (made on another client a moment ago).
     */
    private suspend fun followTags(tmux: TmuxControl) {
        var listRevision: String? = null
        var first = true
        tmux.tagRevisions.collect { revisions ->
            if (first || revisions.list != listRevision) {
                listRevision = revisions.list
                readTagList(tmux)
            }
            first = false
            readWindowTags(tmux)
        }
    }

    private suspend fun readTagList(tmux: TmuxControl) {
        val read = try {
            AttacheCli.tags(tmux)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        // A host that did not answer keeps the list it had.
        if (read != null) tagList.value = read
    }

    private val askedAbout = mutableSetOf<String>()

    private suspend fun readWindowTags(tmux: TmuxControl) {
        val read = AttacheCli.windowTags(tmux) ?: return
        windowTags.value = read
        val known = tagList.value.map { it.id }.toSet()
        val unknown = read.values.flatten().filter { it !in known && it !in askedAbout }
        if (unknown.isNotEmpty()) {
            askedAbout += unknown
            readTagList(tmux)
        }
    }

    /** Puts [tag] on [agent]'s window, or takes it off. */
    fun toggleTag(agent: AgentPane, tag: WindowTag, onDone: (Boolean) -> Unit) {
        val carried = windowTags.value[agent.windowId].orEmpty().toSet()
        val wanted = if (tag.id in carried) carried - tag.id else carried + tag.id
        setWindowTags(agent, wanted, onDone)
    }

    private fun setWindowTags(agent: AgentPane, wanted: Set<String>, onDone: (Boolean) -> Unit) {
        val tmux = link.control.value ?: return onDone(false)
        // In the list's order, so the chips do not reshuffle by which tag
        // went on first.
        val ordered = tagList.value.map { it.id }.filter { it in wanted }
        viewModelScope.launch {
            val ok = AttacheCli.setWindowTags(tmux, agent.windowId, ordered)
            readWindowTags(tmux)
            onDone(ok)
        }
    }

    /** Makes a tag and, when [applyTo] is given, puts it on that agent's
     * window. [onDone] gets why it was not made, or null. */
    fun createTag(name: String, color: String, applyTo: AgentPane?, onDone: (String?) -> Unit) {
        val tmux = link.control.value ?: return onDone("the host is not connected")
        viewModelScope.launch {
            val (tag, why) = AttacheCli.saveTag(tmux, null, name, color)
            readTagList(tmux)
            if (tag != null && applyTo != null) {
                val carried = windowTags.value[applyTo.windowId].orEmpty().toSet()
                setWindowTags(applyTo, carried + tag.id) {}
            }
            onDone(why)
        }
    }

    /** Renames or recolours [tag]. [onDone] gets why not, or null. */
    fun updateTag(tag: WindowTag, name: String, color: String, onDone: (String?) -> Unit) {
        val tmux = link.control.value ?: return onDone("the host is not connected")
        viewModelScope.launch {
            val (_, why) = AttacheCli.saveTag(tmux, tag.id, name, color)
            readTagList(tmux)
            onDone(why)
        }
    }

    /** Forgets [tag]; attache-cli takes it off every window. */
    fun deleteTag(tag: WindowTag, onDone: (String?) -> Unit) {
        val tmux = link.control.value ?: return onDone("the host is not connected")
        viewModelScope.launch {
            val why = AttacheCli.removeTag(tmux, tag.id)
            readTagList(tmux)
            readWindowTags(tmux)
            onDone(why)
        }
    }

    fun setTerminalManager(manager: TerminalManager) = link.attach(manager)

    val tmuxControl: StateFlow<TmuxControl?> get() = link.control

    fun reconnect() = link.reconnect()

    /** Renames [agent]'s pane on the host; the list's own watch
     * then shows it, within a couple of seconds. [onDone] gets whether the
     * host wrote it. */
    fun rename(agent: AgentPane, name: String, onDone: (Boolean) -> Unit) {
        val tmux = link.control.value
        if (tmux == null) return onDone(false)
        viewModelScope.launch {
            val ok = try {
                AttacheCli.rename(tmux, agent.paneId, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            onDone(ok)
        }
    }

    /** Closes [agent]'s pane on the host, after the person confirmed it; the
     * list's own watch then drops the row. [onDone] gets whether tmux took it. */
    fun close(agent: AgentPane, onDone: (Boolean) -> Unit) {
        val tmux = link.control.value
        if (tmux == null) return onDone(false)
        viewModelScope.launch {
            val ok = try {
                tmux.killPane(agent.paneId)
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            onDone(ok)
        }
    }

    /**
     * Pushed rather than polled: `attache-cli agents --watch` reads every
     * agent on the host every 1.5 s (0.08 s with 26 agents) and sends the
     * list over an exec channel of its own only when something changed, so
     * an agent that starts waiting shows within a couple of seconds and the
     * control channel the chat page waits on carries nothing for it.
     */
    private suspend fun poll(tmux: TmuxControl) {
        while (true) {
            val cli = tmux.cli()
            if (cli == null) {
                pollError.value = "attache-cli could not be installed or run on this host"
            } else {
                val lines = JsonLines()
                try {
                    tmux.follow(AttacheCli.watchCommand(cli)) { chunk ->
                        for (line in lines.feed(chunk)) {
                            val event = try {
                                JSONObject(line)
                            } catch (_: Exception) {
                                continue
                            }
                            if (event.optString("t") != "agents") continue
                            val found = AttacheCli.agentsEvent(event)
                            agents.value = found
                            AgentDirectory.put(hostId, found)
                            pollError.value = null
                        }
                    }
                } catch (e: CancellationException) {
                    // Either this watch was cancelled, or the channel closed
                    // under it, which also surfaces as a cancellation; the
                    // bridge then publishes the next channel and a new one
                    // starts.
                    currentCoroutineContext().ensureActive()
                    return
                } catch (e: Exception) {
                    pollError.value = e.message ?: "tmux did not answer"
                }
            }
            if (tmux.isClosed) return
            delay(RETRY_MS)
        }
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
