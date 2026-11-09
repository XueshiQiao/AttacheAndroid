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
)

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

    val uiState: StateFlow<AgentListUiState> =
        combine(link.hostName, link.connection, agents, link.error, pollError) { host, connection, list, linkError, error ->
            AgentListUiState(host, connection, list, error ?: linkError)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentListUiState())

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
