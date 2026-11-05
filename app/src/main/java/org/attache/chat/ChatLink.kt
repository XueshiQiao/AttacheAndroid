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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxControl
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import java.util.UUID

enum class ChatConnection {
    CONNECTING,

    /** The connection is waiting on a password, a passphrase or a host key,
     * and only the terminal screen knows how to ask for those. */
    NEEDS_TERMINAL,
    READY,
    DISCONNECTED,
}

/**
 * The chat mode's hold on a host's connection. It rides on the same
 * [TerminalBridge] the terminal screen uses, so switching between the two
 * never opens a second connection, and everything the terminal mode set up
 * (Tailscale, keys, the `tmux -C` channel) is reused as it is.
 */
class ChatLink(
    private val hostId: UUID,
    private val scope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
) {
    private var manager: TerminalManager? = null
    private var job: Job? = null

    private val _connection = MutableStateFlow(ChatConnection.CONNECTING)
    val connection: StateFlow<ChatConnection> = _connection.asStateFlow()

    private val _hostName = MutableStateFlow("")
    val hostName: StateFlow<String> = _hostName.asStateFlow()

    /** The live `tmux -C` channel, or null while there is none. */
    private val _control = MutableStateFlow<TmuxControl?>(null)
    val control: StateFlow<TmuxControl?> = _control.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun attach(terminalManager: TerminalManager) {
        if (manager === terminalManager) return
        manager = terminalManager
        job?.cancel()
        job = scope.launch {
            ensureBridge(terminalManager)
            terminalManager.bridgesFlow
                .map { bridges -> bridges.firstOrNull { it.host.id == hostId } }
                .distinctUntilChanged()
                .collectLatest { bridge ->
                    if (bridge == null) {
                        _control.value = null
                        _connection.value = ChatConnection.DISCONNECTED
                    } else {
                        watch(bridge)
                    }
                }
        }
    }

    fun reconnect() {
        val terminalManager = manager ?: return
        _connection.value = ChatConnection.CONNECTING
        scope.launch { ensureBridge(terminalManager) }
    }

    private suspend fun ensureBridge(terminalManager: TerminalManager) {
        withContext(dispatchers.io) {
            try {
                val existing = terminalManager.bridgesFlow.value.find { it.host.id == hostId }
                if (existing == null) {
                    if (terminalManager.openConnectionForHostId(hostId) == null) _error.value = "Host not found"
                } else if ((existing.isDisconnected || existing.transport?.isConnected() != true) && !existing.isConnecting) {
                    terminalManager.requestReconnect(existing)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = e.message ?: "Could not connect"
            }
        }
    }

    private suspend fun watch(bridge: TerminalBridge) {
        _hostName.value = bridge.host.nickname
        combine(bridge.promptManager.promptState, bridge.tmuxControl) { prompt, tmux -> prompt to tmux }
            .collect { (prompt, tmux) ->
                _control.value = tmux
                _connection.value = when {
                    prompt != null -> ChatConnection.NEEDS_TERMINAL
                    tmux == null -> ChatConnection.CONNECTING
                    else -> ChatConnection.READY
                }
            }
    }
}
