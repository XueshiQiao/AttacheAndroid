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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.attache.tmux.TmuxControl
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import timber.log.Timber
import java.util.UUID

enum class ChatConnection {
    /** An attempt is in flight, or the connection is up and its `tmux -C`
     * channel is still opening. */
    CONNECTING,

    /** The connection is waiting on a password, a passphrase or a host key,
     * and only the terminal screen knows how to ask for those. */
    NEEDS_TERMINAL,
    READY,

    /** Nothing is connecting: the bridge is gone, or it is kept with its
     * connection ended and waits for someone to reconnect it. */
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

    /** The channel stopped answering the heartbeat; cleared by the next
     * channel that comes up. */
    private val _lost = MutableStateFlow(false)
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    /** Whether the page holding this link is in front of the person. Only
     * then is the connection probed and brought back on its own. */
    private val shown = MutableStateFlow(false)

    /** Automatic attempts since the link was last ready or last came on
     * screen; spaces the next one out. */
    @Volatile private var retries = 0

    /** The channel the heartbeat gave up on. On a dead socket it can stay
     * published on the bridge well after a new attempt has started. */
    @Volatile private var deadControl: TmuxControl? = null

    fun setShown(value: Boolean) {
        if (value && !shown.value) retries = 0
        shown.value = value
    }

    fun attach(terminalManager: TerminalManager) {
        if (manager === terminalManager) return
        manager = terminalManager
        job?.cancel()
        job = scope.launch {
            launch { keepConnected(terminalManager) }
            launch { heartbeat() }
            ensureBridge(terminalManager)
            terminalManager.bridgesFlow
                .map { bridges -> bridges.firstOrNull { it.host.id == hostId } }
                .distinctUntilChanged()
                .collectLatest { bridge ->
                    if (bridge == null) {
                        _control.value = null
                        _connection.value = ChatConnection.DISCONNECTED
                    } else {
                        watch(bridge, terminalManager)
                    }
                }
        }
    }

    /**
     * The channel stopped answering though the connection still says it is
     * up — a network that went away under it, a phone that slept: the socket
     * can take minutes to notice. Ends it as lost; [keepConnected] then
     * connects again.
     */
    private suspend fun declareDead(terminalManager: TerminalManager, tmux: TmuxControl) {
        _lost.value = true
        deadControl = tmux
        _control.value = null
        withContext(dispatchers.io) {
            terminalManager.bridgesFlow.value.find { it.host.id == hostId }
                ?.dispatchDisconnect(org.connectbot.service.DisconnectReason.NETWORK_LOST)
        }
    }

    /** The person asked: a fresh attempt now, even over one that is still
     * in flight, since an attempt on a dead route can hang for a long time. */
    fun reconnect() {
        val terminalManager = manager ?: return
        retries = 0
        scope.launch { ensureBridge(terminalManager, force = true) }
    }

    /**
     * Brings the connection back while the page is on screen. A connection
     * that ends in the background is kept by ConnectBot as disconnected and
     * left for someone to press Reconnect, and the page used to say
     * "Connecting…" over it indefinitely — the owner found that one press
     * connected at once (2026-11-09). Waits for a network first, so attempts
     * are not spent where they cannot succeed, and backs off between failed
     * ones so a host that refuses at once is not hammered.
     */
    private suspend fun keepConnected(terminalManager: TerminalManager) {
        combine(shown, _connection) { visible, c -> visible && c == ChatConnection.DISCONNECTED }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                if (!wanted) return@collectLatest
                // An attempt that starts turns the state to CONNECTING and
                // cancels this; one that changed nothing goes round again.
                while (true) {
                    delay(if (retries == 0) 0L else minOf(RETRY_BASE_MS shl minOf(retries - 1, 5), RETRY_MAX_MS))
                    while (terminalManager.connectivityMonitor.getCurrentNetworkInfo()?.isConnected == false) {
                        delay(NETWORK_POLL_MS)
                    }
                    retries++
                    Timber.i("chat: the connection is down while on screen; reconnecting (attempt $retries)")
                    ensureBridge(terminalManager)
                }
            }
    }

    /**
     * Asks the channel something trivial while the page is on screen: once
     * on coming to the front, then every 15 s. A connection that died
     * quietly still has its channel object, so nothing else notices, and
     * the page shows a live conversation that cannot be reached. No answer
     * in 6 s: the connection is declared lost.
     */
    private suspend fun heartbeat() {
        combine(shown, _control) { visible, tmux -> tmux.takeIf { visible } }
            .collectLatest { tmux ->
                if (tmux == null) return@collectLatest
                while (true) {
                    val answered = try {
                        withTimeout(HEARTBEAT_TIMEOUT_MS) { tmux.query("display-message -p ok") }
                        true
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        false
                    }
                    if (!answered) {
                        Timber.w("chat: the tmux channel stopped answering; reconnecting")
                        manager?.let { declareDead(it, tmux) }
                        return@collectLatest
                    }
                    delay(HEARTBEAT_MS)
                }
            }
    }

    private suspend fun ensureBridge(terminalManager: TerminalManager, force: Boolean = false) {
        withContext(dispatchers.io) {
            try {
                // Both pages of a host hold a link and may ask at the same
                // moment; a second startConnection would cancel the first.
                connectLock.withLock {
                    val existing = terminalManager.bridgesFlow.value.find { it.host.id == hostId }
                    if (existing == null) {
                        if (terminalManager.openConnectionForHostId(hostId) == null) _error.value = "Host not found"
                    } else if ((existing.isDisconnected || existing.transport?.isConnected() != true) &&
                        (force || !existing.isConnecting)
                    ) {
                        terminalManager.requestReconnect(existing)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = e.message ?: "Could not connect"
            }
        }
    }

    /** What the bridge says, re-read whenever any bridge's state changes:
     * [TerminalBridge.isDisconnected] has no flow of its own. A disconnected
     * bridge is said as such before anything else, since a channel on a dead
     * socket may not have ended yet. */
    private suspend fun watch(bridge: TerminalBridge, terminalManager: TerminalManager) {
        _hostName.value = bridge.host.nickname
        combine(
            bridge.promptManager.promptState,
            bridge.tmuxControl,
            terminalManager.hostStatusChangedFlow.onStart { emit(Unit) },
        ) { prompt, tmux, _ -> prompt to tmux }
            .collect { (prompt, published) ->
                val tmux = published?.takeIf { !bridge.isDisconnected && it !== deadControl }
                val state = when {
                    prompt != null -> ChatConnection.NEEDS_TERMINAL
                    bridge.isDisconnected -> ChatConnection.DISCONNECTED
                    tmux == null -> ChatConnection.CONNECTING
                    else -> ChatConnection.READY
                }
                _control.value = tmux
                if (state == ChatConnection.READY) {
                    retries = 0
                    _lost.value = false
                }
                _connection.value = state
            }
    }

    private companion object {
        val connectLock = Mutex()
        const val HEARTBEAT_MS = 15_000L
        const val HEARTBEAT_TIMEOUT_MS = 6_000L
        const val RETRY_BASE_MS = 2_000L
        const val RETRY_MAX_MS = 30_000L
        const val NETWORK_POLL_MS = 2_000L
    }
}
