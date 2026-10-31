package org.attache.tmux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SshSession
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Trial-only wiring. Every command this file builds names the isolated
 * `-L attache-mobile-test` server, so nothing in the trial can reach a real
 * tmux server and the sessions on it — a leftover `-L`-less command is a
 * server-level accident, not a session-level one.
 *
 * A host opts in by its nickname: `tmux <session-name>`.
 */
object TmuxTrial {
    const val SOCKET = "attache-mobile-test"
    private const val PREFIX = "tmux "
    private val SESSION_NAME = Regex("^[A-Za-z0-9_-]+$")

    // A non-interactive exec channel does not read the login shell's profile,
    // so Homebrew's tmux is not on PATH there. Both commands assume an
    // sh-family login shell.
    private const val PATH_SETUP = "PATH=/opt/homebrew/bin:/usr/local/bin:\$PATH"

    /** The session name, or null when this host is not a trial host. The
     * name is interpolated into a command line, so it is restricted to
     * characters no shell or tmux parser gives a meaning to. */
    fun sessionFor(nickname: String): String? {
        if (!nickname.startsWith(PREFIX)) return null
        return nickname.removePrefix(PREFIX).trim().takeIf { SESSION_NAME.matches(it) }
    }

    // `new-session -A` rather than `attach`: the isolated server does not
    // survive a reboot, and with `exec` a failed attach ends the login shell,
    // which ConnectBot answers by closing the page before anything is shown.
    // `-u` because an exec channel carries no UTF-8 locale, and without one
    // tmux 3.6a replaces control bytes in replies with `_` — measured over
    // this exact channel: the \u0001 field separators came back as `_` and
    // the window list parsed to nothing.
    fun controlCommand(session: String): String {
        require(SESSION_NAME.matches(session)) { "not a session name: $session" }
        return "$PATH_SETUP tmux -u -L $SOCKET -C new-session -A -s '$session'"
    }

    fun attachCommand(session: String): String {
        require(SESSION_NAME.matches(session)) { "not a session name: $session" }
        return "export $PATH_SETUP; exec tmux -L $SOCKET new-session -A -s '$session'"
    }
}

data class TmuxWindow(val id: String, val index: Int, val name: String, val active: Boolean)

class TmuxCommandError(lines: List<String>) : Exception(lines.joinToString("\n"))

/**
 * A tmux control-mode client on its own SSH channel, beside the channel the
 * terminal draws from. tmux owns the window list; this only asks for it and
 * turns taps into commands that name windows by id.
 *
 * It lives exactly as long as its channel: when the channel ends — tmux
 * exited, the session went away, the SSH connection closed — the read loop
 * ends, every waiting command fails, and [onEnded] runs once.
 */
class TmuxControl(
    private val session: SshSession,
    private val scope: CoroutineScope,
    private val onEnded: (TmuxControl) -> Unit,
) {
    private val _windows = MutableStateFlow<List<TmuxWindow>>(emptyList())
    val windows: StateFlow<List<TmuxWindow>> = _windows.asStateFlow()

    private val sendLock = Mutex()

    /** Guarded by itself, together with [closed]: a command is either queued
     * before close drains the queue, or refused. */
    private val pending = ArrayDeque<CompletableDeferred<List<String>>>()
    private var closed = false

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    fun start() {
        synchronized(jobs) {
            jobs += scope.launch {
                try {
                    readLoop()
                } catch (e: Exception) {
                    if (e !is CancellationException) Timber.w(e, "tmux control channel failed")
                } finally {
                    close()
                }
            }
            jobs += scope.launch { drainStderr() }
            jobs += scope.launch {
                for (request in refreshRequests) refresh()
            }
        }
        refreshRequests.trySend(Unit)
    }

    fun close() {
        val waiting = synchronized(pending) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear() }
        }
        waiting.forEach { it.cancel() }
        refreshRequests.close()
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        session.close()
        onEnded(this)
    }

    suspend fun selectWindow(id: String) {
        // Ids cannot carry a quote, a space or a newline; a name could.
        require(WINDOW_ID.matches(id)) { "not a window id: $id" }
        send("select-window -t $id")
    }

    private suspend fun refresh() {
        val lines = try {
            send("list-windows -F '#{window_id}$SEP#{window_index}$SEP#{window_active}$SEP#{window_name}'")
        } catch (e: TmuxCommandError) {
            Timber.w("list-windows failed: %s", e.message)
            return
        }
        _windows.value = lines.mapNotNull { line ->
            val f = line.split(SEP, limit = 4)
            if (f.size != 4 || !WINDOW_ID.matches(f[0])) return@mapNotNull null
            TmuxWindow(id = f[0], index = f[1].toIntOrNull() ?: return@mapNotNull null, active = f[2] == "1", name = f[3])
        }
    }

    /** Throws [TmuxCommandError] when tmux answers `%error`, and
     * [CancellationException] when the channel ends first. */
    private suspend fun send(command: String): List<String> {
        val reply = CompletableDeferred<List<String>>()
        // Queue and write under one lock, so the order of `pending` is the
        // order tmux receives the commands in.
        sendLock.withLock {
            synchronized(pending) {
                if (closed) throw CancellationException("tmux control channel closed")
                pending.addLast(reply)
            }
            try {
                // Not cancellable: a write abandoned halfway would leave
                // tmux a partial command line and `pending` a reply that
                // either is or is not coming, with no way to tell which.
                withContext(NonCancellable) { session.write((command + "\n").toByteArray()) }
            } catch (e: Exception) {
                // Nothing reached tmux, so nothing will answer this entry;
                // left queued it would take the next command's reply.
                synchronized(pending) { pending.remove(reply) }
                throw e
            }
        }
        // Cancelled here, the entry stays queued on purpose: the command was
        // sent, and its reply block is on its way to be consumed by it.
        return reply.await()
    }

    private suspend fun readLoop() {
        val line = ByteArrayOutputStream()
        var block: MutableList<String>? = null
        var blockGuard = ""
        var blockIsOurs = false
        for (chunk in session.stdout) {
            for (b in chunk) {
                if (b != '\n'.code.toByte()) {
                    line.write(b.toInt())
                    continue
                }
                // Each line is decoded whole, so a UTF-8 character split
                // across chunks is reassembled first. No UTF-8 byte is 0x0A.
                val text = line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
                line.reset()
                val current = block
                if (current != null) {
                    // A block ends only on a guard with its own time, number
                    // and flags, so an output line that happens to start with
                    // `%end` does not end it.
                    val ended = text == "%end $blockGuard" || text == "%error $blockGuard"
                    if (!ended) {
                        current += text
                        continue
                    }
                    // The flags field is the only thing that says whether
                    // a block answers a command this client sent: the
                    // attach handshake and commands tmux runs for hooks
                    // arrive as blocks too, with flags 0.
                    if (blockIsOurs) {
                        val reply = synchronized(pending) { pending.removeFirstOrNull() }
                        if (text.startsWith("%error")) {
                            reply?.completeExceptionally(TmuxCommandError(current))
                        } else {
                            reply?.complete(current)
                        }
                    }
                    block = null
                    continue
                }
                when {
                    text.startsWith("%begin ") -> {
                        block = mutableListOf()
                        blockGuard = text.removePrefix("%begin ")
                        blockIsOurs = blockGuard.split(' ').getOrNull(2) == "1"
                    }
                    REFRESH_ON.any { text.startsWith(it) } -> refreshRequests.trySend(Unit)
                    text.startsWith("%exit") -> Timber.i("tmux -C exited: %s", text)
                }
            }
        }
    }

    private suspend fun drainStderr() {
        val text = ByteArrayOutputStream()
        try {
            for (chunk in session.stderr) text.write(chunk)
        } finally {
            if (text.size() > 0) Timber.w("tmux -C stderr: %s", text.toString(Charsets.UTF_8.name()))
        }
    }

    private companion object {
        const val SEP = "\u0001"
        val WINDOW_ID = Regex("^@\\d+$")
        val REFRESH_ON = listOf(
            "%window-add", "%window-close", "%window-renamed", "%unlinked-window-",
            "%session-window-changed", "%session-changed", "%sessions-changed",
        )
    }
}
