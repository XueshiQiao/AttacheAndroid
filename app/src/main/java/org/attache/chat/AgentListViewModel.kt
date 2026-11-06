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
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxControl
import org.connectbot.data.EntityIds
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.ui.navigation.NavArgs
import java.util.UUID
import javax.inject.Inject

data class AgentListUiState(
    val hostName: String = "",
    val connection: ChatConnection = ChatConnection.CONNECTING,
    val agents: List<AgentPane> = emptyList(),
    val error: String? = null,
    /** TEMPORARY (measurement build): how long the first list took. */
    val timing: String? = null,
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

    // TEMPORARY (measurement build): ms after the page opened that the
    // tmux channel came, the first pane list, and the first agent reports.
    private val openedAt = System.nanoTime()
    private val timing = MutableStateFlow<String?>(null)
    private var controlMs = 0L
    private var panesMs = 0L

    private fun sinceOpen() = (System.nanoTime() - openedAt) / 1_000_000

    val uiState: StateFlow<AgentListUiState> =
        combine(link.hostName, link.connection, agents, link.error, pollError) { host, connection, list, linkError, error ->
            AgentListUiState(host, connection, list, error ?: linkError)
        }.combine(timing) { state, t -> state.copy(timing = t) }
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
                .collectLatest { tmux ->
                    if (tmux != null) {
                        if (controlMs == 0L) controlMs = sinceOpen()
                        poll(tmux)
                    }
                }
        }
    }

    fun setTerminalManager(manager: TerminalManager) = link.attach(manager)

    fun reconnect() = link.reconnect()

    private var polls = 0
    private var sessions: List<AgentSession> = emptyList()

    /**
     * Polled rather than subscribed: three seconds is fast enough to see an
     * agent start waiting, and a `list-panes` every three seconds is a few
     * hundred bytes on a link that is relayed through Europe on 5G.
     */
    private suspend fun poll(tmux: TmuxControl) {
        while (true) {
            try {
                val lines = tmux.query(AgentPanes.LIST_COMMAND)
                val panes = withContext(dispatchers.default) { AgentPanes.parse(lines) }
                if (panesMs == 0L) panesMs = sinceOpen()
                // The panes first, with what the agents said last time: the
                // scripts take most of a second with a dozen agy screens to
                // read, and the list need not wait for them to be shown.
                agents.value = AgentPanes.merge(panes, sessions)
                // What the agents say for themselves, every other poll: it
                // runs a script on the host, half a second of it reading
                // agy's screens, and states move slower than that.
                if (polls++ % 2 == 0) {
                    sessions = readSessions(tmux)
                    agents.value = AgentPanes.merge(panes, sessions)
                    if (timing.value == null) {
                        timing.value = "Open ms: channel $controlMs · panes $panesMs · agents ${sinceOpen()}"
                    }
                }
                AgentDirectory.put(hostId, agents.value)
                pollError.value = null
            } catch (e: CancellationException) {
                // Either this poll was cancelled, or the control channel
                // closed under it, which also surfaces as a cancellation; the
                // bridge then publishes the next channel and a new poll starts.
                currentCoroutineContext().ensureActive()
                return
            } catch (e: Exception) {
                pollError.value = e.message ?: "tmux did not answer"
            }
            delay(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 3_000L
    }
}

/** Every agent's own report of its live sessions, or none if the host would
 * not run the script; the tmux options still stand in then. */
internal suspend fun readSessions(
    tmux: TmuxControl,
    providers: List<AgentProvider> = AgentProviders.all,
    pane: String? = null,
): List<AgentSession> = try {
    HostScripts.read(tmux::query, providers, pane)
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    emptyList()
}
