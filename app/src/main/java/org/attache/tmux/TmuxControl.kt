package org.attache.tmux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import kotlin.math.abs

/**
 * Trial-only wiring. A host opts in by its nickname:
 *
 *   `tmux <session>`            the user's own tmux server
 *   `tmux -L <socket> <session>` an isolated server, for testing — every
 *                               command then carries `-L`, so nothing can
 *                               reach the real server and the agents in it
 */
object TmuxTrial {
    data class Target(val socket: String?, val session: String) {
        val socketArg: String get() = socket?.let { "-L $it " } ?: ""
    }

    private val NICKNAME = Regex("^tmux (?:-L ([A-Za-z0-9_-]+) )?([A-Za-z0-9_-]+)$")

    // A non-interactive exec channel does not read the login shell's profile,
    // so Homebrew's tmux is not on PATH there. Both commands assume an
    // sh-family login shell.
    private const val PATH_SETUP = "PATH=/opt/homebrew/bin:/usr/local/bin:\$PATH"

    /** The target, or null when this host is not a trial host. Names are
     * interpolated into command lines, so they are restricted to characters
     * no shell or tmux parser gives a meaning to. */
    fun targetFor(nickname: String): Target? {
        val m = NICKNAME.matchEntire(nickname.trim()) ?: return null
        return Target(m.groupValues[1].ifEmpty { null }, m.groupValues[2])
    }

    // `new-session -A` rather than `attach`: with `exec` a failed attach ends
    // the login shell, which ConnectBot answers by closing the page before
    // anything is shown.
    // `-u` because an exec channel carries no UTF-8 locale, and without one
    // tmux 3.6a replaces control bytes in replies with `_` — measured over
    // this exact channel: the \u0001 field separators came back as `_` and
    // the window list parsed to nothing.
    fun controlCommand(t: Target) = "$PATH_SETUP tmux -u ${t.socketArg}-C new-session -A -s '${t.session}'"

    fun attachCommand(t: Target) = "export $PATH_SETUP; exec tmux ${t.socketArg}new-session -A -s '${t.session}'"
}

data class TmuxWindow(val id: String, val index: Int, val name: String, val active: Boolean)

data class TmuxSession(val id: String, val name: String, val attached: Boolean, val windows: List<TmuxWindow>)

/** A pane of the active window, in that window's cell coordinates. */
data class TmuxPane(
    val id: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val active: Boolean,
    val inMode: Boolean,
)

/** What tmux last reported. Nothing in it is authored here. */
data class TmuxState(
    /** The session this connection is showing. */
    val sessionId: String? = null,
    /** Every session on the server, each with its windows. */
    val sessions: List<TmuxSession> = emptyList(),
    /** The windows of [sessionId]. */
    val windows: List<TmuxWindow> = emptyList(),
    val panes: List<TmuxPane> = emptyList(),
    val zoomed: Boolean = false,
) {
    val activePane: TmuxPane? get() = panes.firstOrNull { it.active }
}

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
    /** Runs one shell command on the same SSH connection and returns its
     * output, or null on failure. */
    private val exec: suspend (String) -> String?,
) {
    private val _state = MutableStateFlow(TmuxState())
    val state: StateFlow<TmuxState> = _state.asStateFlow()

    /** Scroll the finger has asked for, positive back in history. One
     * loop drains it and merges what has piled up for the same pane, so a
     * fast swipe becomes a few commands rather than one per frame. */
    private data class ScrollRequest(val pane: String, val lines: Int, val column: Int, val row: Int)
    private val scrollRequests = Channel<ScrollRequest>(Channel.UNLIMITED)

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
            jobs += scope.launch {
                // Every 50ms what piled up is merged per pane and sent
                // without waiting for tmux's reply: over a relayed link one
                // round trip measured 580-780ms, and waiting for each made
                // a swipe advance in jerks of that size.
                for (first in scrollRequests) {
                    delay(SCROLL_BATCH_MS)
                    val merged = LinkedHashMap<String, ScrollRequest>()
                    var next: ScrollRequest? = first
                    while (next != null) {
                        val r = next
                        merged[r.pane] = merged[r.pane]?.let { r.copy(lines = it.lines + r.lines) } ?: r
                        next = scrollRequests.tryReceive().getOrNull()
                    }
                    merged.values.forEach { r -> scope.launch { sendScroll(r) } }
                }
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
        scrollRequests.close()
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        session.close()
        onEnded(this)
    }

    suspend fun selectWindow(id: String) {
        // Ids cannot carry a quote, a space or a newline; a name could.
        require(WINDOW_ID.matches(id)) { "not a window id: $id" }
        send("select-window -t $id")
    }

    suspend fun newWindow() {
        val session = _state.value.sessionId ?: return
        // Quoted: unquoted, tmux's parser would read `$3` as a variable.
        send("new-window -t '$session:' -c '#{pane_current_path}'")
    }

    suspend fun split(sideBySide: Boolean) {
        val pane = _state.value.activePane ?: return
        send("split-window ${if (sideBySide) "-h" else "-v"} -t ${pane.id} -c '#{pane_current_path}'")
    }

    suspend fun toggleZoom() {
        val pane = _state.value.activePane ?: return
        send("resize-pane -Z -t ${pane.id}")
    }

    suspend fun nextPane() {
        val panes = _state.value.panes
        if (panes.size < 2) return
        val next = panes[(panes.indexOfFirst { it.active } + 1) % panes.size]
        send("select-pane -t ${next.id}")
    }

    /** The pane drawn at a cell of the terminal, from tmux's own geometry:
     * the terminal library sends tmux no mouse events at all, so nothing
     * else knows. A zoomed window shows only its active pane. Assumes the
     * status line is at the bottom. */
    fun paneAt(column: Int, row: Int): TmuxPane? {
        val current = _state.value
        if (current.zoomed) return current.activePane
        return current.panes.firstOrNull {
            column in it.left until it.left + it.width && row in it.top until it.top + it.height
        }
    }

    suspend fun selectPaneAt(column: Int, row: Int) {
        val pane = paneAt(column, row) ?: return
        if (!pane.active) send("select-pane -t ${pane.id}")
    }

    suspend fun exitCopyMode() {
        val pane = _state.value.activePane ?: return
        if (pane.inMode) send("send-keys -X -t ${pane.id} cancel")
    }

    /** Scrolls the given pane, positive back in history, as a mouse
     * wheel over it would — the pane is not selected. [column] and [row]
     * are where the finger is, 1-based within the pane. Non-suspending, for
     * a gesture. */
    fun scroll(paneId: String, lines: Int, column: Int, row: Int) {
        require(PANE_ID.matches(paneId)) { "not a pane id: $paneId" }
        if (lines != 0) scrollRequests.trySend(ScrollRequest(paneId, lines, column.coerceAtLeast(1), row.coerceAtLeast(1)))
    }

    /**
     * Decides what a scroll means the way tmux's own default `WheelUpPane`
     * binding does, and inside one command so tmux decides it against its
     * current state — never against [state], which is tmux's last report and
     * can be behind a swipe that reverses.
     *
     *   in copy mode already       → scroll tmux's history
     *   program reads the mouse    → send it wheel events (a full-screen
     *                                agent like Antigravity scrolls itself)
     *   on the alternate screen    → send it Up / Down keys
     *   otherwise (going back)     → enter copy mode, which leaves by itself
     *                                (`-e`) once scrolled back to the bottom
     *
     * Measured on the test server: Antigravity's pane reports
     * alternate_on=1, mouse_any_flag=1, history_size=0, so entering copy
     * mode there showed an empty history and froze its view.
     */
    private suspend fun sendScroll(request: ScrollRequest) {
        val (pane, lines, column, row) = request
        if (lines == 0) return
        val n = abs(lines)
        val up = lines > 0
        val history = "send-keys -X -N $n -t $pane ${if (up) "scroll-up" else "scroll-down"}"
        val wheel = "if-shell -F -t $pane '#{mouse_sgr_flag}' " +
            "{ send-keys -H -N $n -t $pane ${wheelBytes(up, column, row, sgr = true)} } " +
            "{ send-keys -H -N $n -t $pane ${wheelBytes(up, column, row, sgr = false)} }"
        val keys = "send-keys -N $n -t $pane ${if (up) "Up" else "Down"}"
        val otherwise = if (up) "{ copy-mode -e -t $pane ; $history }" else "''"
        val command = "if-shell -F -t $pane '#{pane_in_mode}' { $history } " +
            "{ if-shell -F -t $pane '#{mouse_any_flag}' { $wheel } " +
            "{ if-shell -F -t $pane '#{alternate_on}' { $keys } $otherwise } }"
        try {
            send(command)
        } catch (e: TmuxCommandError) {
            Timber.w("scroll failed: %s", e.message)
        }
    }

    /** One wheel event at a cell, as hex for `send-keys -H` (`-N` repeats
     * it — measured on tmux 3.6a: `-H -N 7` moved less --mouse 7 lines): SGR
     * (`ESC [ < 64 ; x ; y M`) when the program asked for it, otherwise the
     * original encoding, which cannot address past column or row 223. */
    private fun wheelBytes(up: Boolean, column: Int, row: Int, sgr: Boolean): String {
        val button = if (up) 64 else 65
        val one = if (sgr) {
            "\u001b[<$button;$column;${row}M".toByteArray()
        } else {
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + button).toByte(),
                (32 + column.coerceAtMost(223)).toByte(), (32 + row.coerceAtMost(223)).toByte())
        }
        return one.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun refresh() {
        try {
            val current = send("display-message -p '#{session_id}'").firstOrNull()?.takeIf { SESSION_ID.matches(it) }
            val sessionLines = send("list-sessions -F '#{session_id}$SEP#{session_attached}$SEP#{session_name}'")
            val windowLines = send(
                "list-windows -a -F '#{session_id}$SEP#{window_id}$SEP#{window_index}$SEP#{window_active}$SEP" +
                    "#{window_zoomed_flag}$SEP#{window_name}'",
            )
            var zoomed = false
            val windowsBySession = windowLines.mapNotNull { line ->
                val f = line.split(SEP, limit = 6)
                if (f.size != 6 || !SESSION_ID.matches(f[0]) || !WINDOW_ID.matches(f[1])) return@mapNotNull null
                val index = f[2].toIntOrNull() ?: return@mapNotNull null
                val window = TmuxWindow(id = f[1], index = index, active = f[3] == "1", name = f[5])
                if (f[0] == current && window.active) zoomed = f[4] == "1"
                f[0] to window
            }.groupBy({ it.first }, { it.second })
            val sessions = sessionLines.mapNotNull { line ->
                val f = line.split(SEP, limit = 3)
                if (f.size != 3 || !SESSION_ID.matches(f[0])) return@mapNotNull null
                TmuxSession(f[0], f[2], f[1] != "0", windowsBySession[f[0]].orEmpty())
            }
            val windows = windowsBySession[current].orEmpty()
            val active = windows.firstOrNull { it.active }
            val panes = if (active == null) {
                emptyList()
            } else {
                send(
                    "list-panes -t ${active.id} -F '#{pane_id}$SEP#{pane_left}$SEP#{pane_top}$SEP#{pane_width}$SEP" +
                        "#{pane_height}$SEP#{pane_active}$SEP#{pane_in_mode}'",
                ).mapNotNull { line ->
                    val f = line.split(SEP)
                    if (f.size != 7 || !PANE_ID.matches(f[0])) return@mapNotNull null
                    val n = f.subList(1, 5).map { it.toIntOrNull() ?: return@mapNotNull null }
                    TmuxPane(f[0], n[0], n[1], n[2], n[3], active = f[5] == "1", inMode = f[6] == "1")
                }
            }
            _state.value = TmuxState(current, sessions, windows, panes, zoomed)
        } catch (e: TmuxCommandError) {
            Timber.w("refresh failed: %s", e.message)
        }
    }

    /** The tmux client drawing this connection's terminal, as its tty. */
    @Volatile private var terminalClient: String? = null

    /**
     * Finds the terminal's tmux client among every client on the server.
     * Both of this connection's clients — the terminal's and this control
     * one — are children of the same `sshd-session` process (measured on
     * macOS: one per connection, parent of every channel's command), and no
     * other connection's are. tmux has no format for a parent pid, so one
     * `ps` runs on a channel of its own.
     */
    private suspend fun findTerminalClient(): String? {
        terminalClient?.let { return it }
        val own = send("display-message -p '#{client_pid}'").firstOrNull()?.takeIf { DIGITS.matches(it) } ?: return null
        val clients = send("list-clients -F '#{client_pid}$SEP#{client_control_mode}$SEP#{client_name}'").mapNotNull {
            val f = it.split(SEP, limit = 3)
            if (f.size == 3 && DIGITS.matches(f[0])) Triple(f[0], f[1] == "1", f[2]) else null
        }
        val pids = (clients.map { it.first } + own).distinct().joinToString(",")
        val parents = exec("ps -o pid=,ppid= -p $pids")?.lines()?.mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size == 2) f[0] to f[1] else null
        }?.toMap() ?: return null
        val parent = parents[own] ?: return null
        val name = clients.firstOrNull { (pid, control, _) -> !control && parents[pid] == parent }?.third
        // A client name is a tty path here; it goes into a command line.
        terminalClient = name?.takeIf { CLIENT_TTY.matches(it) }
        return terminalClient
    }

    /** Moves this connection — its terminal and this control client — to a
     * session, or to one window of any session. */
    suspend fun switchTo(target: String) {
        require(SESSION_ID.matches(target) || WINDOW_ID.matches(target)) { "not a session or window id: $target" }
        val tty = findTerminalClient() ?: throw TmuxCommandError(listOf("cannot find this connection's terminal client"))
        // Quoted: unquoted, tmux's parser would read `$3` as a variable.
        try {
            send("switch-client -c $tty -t '$target' ; switch-client -t '$target'")
        } catch (e: TmuxCommandError) {
            terminalClient = null // the tty may have been reused; look again next time
            throw e
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
        const val SCROLL_BATCH_MS = 50L
        val SESSION_ID = Regex("^\\$\\d+$")
        val WINDOW_ID = Regex("^@\\d+$")
        val PANE_ID = Regex("^%\\d+$")
        val DIGITS = Regex("^\\d+$")
        val CLIENT_TTY = Regex("^/dev/[A-Za-z0-9/]+$")
        val REFRESH_ON = listOf(
            "%window-add", "%window-close", "%window-renamed", "%unlinked-window-",
            "%session-window-changed", "%session-changed", "%sessions-changed",
            "%window-pane-changed", "%layout-change", "%pane-mode-changed", "%session-renamed",
        )
    }
}
