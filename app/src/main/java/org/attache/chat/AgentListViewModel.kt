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
)

/** The chat mode's first screen: every agent in tmux, by what it needs. */
@HiltViewModel
class AgentListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    val hostId: UUID = EntityIds.parse(savedStateHandle.get<String>(NavArgs.HOST_ID))
    private val link = ChatLink(hostId, viewModelScope, dispatchers)

    private val agents = MutableStateFlow<List<AgentPane>>(emptyList())
    private val pollError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AgentListUiState> =
        combine(link.hostName, link.connection, agents, link.error, pollError) { host, connection, list, linkError, error ->
            AgentListUiState(host, connection, list, error ?: linkError)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentListUiState())

    init {
        viewModelScope.launch {
            link.control.collectLatest { tmux -> if (tmux != null) poll(tmux) }
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
                // What the agents say for themselves, every other poll: it
                // runs a script on the host, a quarter second of it reading
                // agy's screens, and states move slower than that.
                if (polls++ % 2 == 0) sessions = readSessions(tmux)
                agents.value = withContext(dispatchers.default) { AgentPanes.merge(AgentPanes.parse(lines), sessions) }
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
internal suspend fun readSessions(tmux: TmuxControl, providers: List<AgentProvider> = AgentProviders.all): List<AgentSession> = try {
    HostScripts.read(tmux::query, providers)
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    emptyList()
}
