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
    val usage: Map<String, ProviderUsage> = emptyMap(),
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
    private val usage = MutableStateFlow(UsageDirectory.get(hostId))

    val uiState: StateFlow<AgentListUiState> =
        combine(link.hostName, link.connection, agents, link.error, pollError) { host, connection, list, linkError, error ->
            AgentListUiState(host, connection, list, error ?: linkError)
        }.combine(usage) { state, u -> state.copy(usage = u) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AgentListUiState())

    /** Whether the list is on screen. It polls only then: behind a chat page
     * it would run every agent's script on the channel the chat page is
     * waiting on, for a list nobody sees. */
    private val shown = MutableStateFlow(false)

    fun setShown(value: Boolean) {
        shown.value = value
    }

    init {
        viewModelScope.launch {
            link.control.combine(shown) { tmux, visible -> tmux.takeIf { visible } }
                .collectLatest { tmux -> if (tmux != null) poll(tmux) }
        }
        viewModelScope.launch {
            link.control.combine(shown) { tmux, visible -> tmux.takeIf { visible } }
                .collectLatest { tmux -> if (tmux != null) watchUsage(tmux) }
        }
    }

    fun setTerminalManager(manager: TerminalManager) = link.attach(manager)

    val tmuxControl: StateFlow<TmuxControl?> get() = link.control

    fun reconnect() = link.reconnect()

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

    /**
     * The subscriptions' usage, from `attache-cli usage --watch` on a channel
     * of its own. The host does all of it — AIUsageKit asks each provider's
     * CLI there every five minutes and shares one answer with the desktop
     * app — so the phone only listens, and only while the list is shown.
     * A failure here is never the list's error: usage is a side panel.
     */
    private suspend fun watchUsage(tmux: TmuxControl) {
        while (true) {
            val cli = tmux.cli() ?: return
            val lines = JsonLines()
            try {
                tmux.follow(AttacheCli.usageWatchCommand(cli)) { chunk ->
                    for (line in lines.feed(chunk)) {
                        val event = try {
                            JSONObject(line)
                        } catch (_: Exception) {
                            continue
                        }
                        val p = ProviderUsage.parse(event) ?: continue
                        usage.value = usage.value + (p.provider to p)
                        UsageDirectory.put(hostId, usage.value)
                    }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return
            } catch (_: Exception) {
            }
            if (tmux.isClosed) return
            delay(USAGE_RETRY_MS)
        }
    }

    private companion object {
        const val USAGE_RETRY_MS = 30_000L
        const val RETRY_MS = 3_000L
    }
}
