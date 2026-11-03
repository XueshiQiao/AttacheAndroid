package org.attache.tmux

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    fun controlCommand(t: Target) =
        "export $PATH_SETUP; pkill -f '[t]mux -u ${t.socketArg}-C new-session -A -s' 2>/dev/null || true; exec tmux -u ${t.socketArg}-C new-session -A -s '${t.session}'"

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
    val mouseAnyFlag: Boolean = false,
    val mouseSgrFlag: Boolean = true,
    val alternateOn: Boolean = false,
)

/** Metadata of a file or directory discovered on the remote host for browsing and one-tap pull. */
data class RemoteFileEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val isDirectory: Boolean = false,
)

/** A recently visited window across any tmux session on the server (MRU order, max 5). */
data class TmuxRecentWindow(
    val sessionName: String,
    val windowId: String,
    val windowIndex: Int,
    val windowName: String,
    val lastVisitedMs: Long = System.currentTimeMillis(),
)

internal const val MAX_RECENT_WINDOWS = 5

internal fun updateRecentWindows(
    existing: List<TmuxRecentWindow>,
    current: TmuxRecentWindow?,
    validSessions: List<TmuxSession> = emptyList(),
    seedFromSessionsIfEmpty: Boolean = false,
): List<TmuxRecentWindow> {
    val windowsBySessionName = if (validSessions.isNotEmpty()) {
        validSessions.associate { s -> s.name to s.windows.associateBy { it.id } }
    } else {
        emptyMap()
    }
    val pruned = if (windowsBySessionName.isNotEmpty()) {
        existing.mapNotNull { entry ->
            val sessionMap = windowsBySessionName[entry.sessionName] ?: return@mapNotNull null
            val liveWin = sessionMap[entry.windowId] ?: return@mapNotNull null
            entry.copy(windowIndex = liveWin.index, windowName = liveWin.name)
        }
    } else {
        existing
    }

    val updated = if (current == null) {
        pruned.take(MAX_RECENT_WINDOWS)
    } else {
        val head = pruned.firstOrNull()
        if (head != null && head.sessionName == current.sessionName && head.windowId == current.windowId) {
            buildList {
                add(head.copy(windowIndex = current.windowIndex, windowName = current.windowName))
                addAll(pruned.drop(1))
            }.take(MAX_RECENT_WINDOWS)
        } else {
            buildList {
                add(current)
                for (item in pruned) {
                    if (item.windowId != current.windowId) {
                        add(item)
                    }
                }
            }.take(MAX_RECENT_WINDOWS)
        }
    }

    if (!seedFromSessionsIfEmpty || existing.isNotEmpty() || validSessions.isEmpty()) {
        return updated
    }

    val result = updated.toMutableList()
    val seenWindowIds = result.mapTo(hashSetOf()) { it.windowId }
    val now = current?.lastVisitedMs ?: System.currentTimeMillis()
    for (s in validSessions) {
        if (result.size >= MAX_RECENT_WINDOWS) break
        val activeWin = s.windows.firstOrNull { it.active } ?: continue
        if (seenWindowIds.add(activeWin.id)) {
            result += TmuxRecentWindow(
                sessionName = s.name,
                windowId = activeWin.id,
                windowIndex = activeWin.index,
                windowName = activeWin.name,
                lastVisitedMs = now - result.size * 60_000L,
            )
        }
    }
    val orderedSessions = if (current != null) {
        validSessions.sortedByDescending { it.name == current.sessionName }
    } else {
        validSessions
    }
    for (s in orderedSessions) {
        for (w in s.windows) {
            if (result.size >= MAX_RECENT_WINDOWS) break
            if (seenWindowIds.add(w.id)) {
                result += TmuxRecentWindow(
                    sessionName = s.name,
                    windowId = w.id,
                    windowIndex = w.index,
                    windowName = w.name,
                    lastVisitedMs = now - result.size * 60_000L,
                )
            }
        }
    }
    return result
}

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
    /** Current working directory of the active pane, if known. */
    val activePanePath: String = "",
    /** Up to [MAX_RECENT_WINDOWS] most-recently visited windows across all sessions (index 0 = current). */
    val recentWindows: List<TmuxRecentWindow> = emptyList(),
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
    /** Uploads image bytes with the given extension to `/tmp` on the remote
     * host and returns its remote path, or null on failure. */
    private val upload: suspend (ByteArray, String, (Long, Long) -> Unit) -> String? = { _, _, _ -> null },
    /** Streams a remote file into the given OutputStream with byte progress. */
    private val download: suspend (String, java.io.OutputStream, (Long, Long) -> Unit) -> Boolean = { _, _, _ -> false },
    /** Application context so pushed files download in the background service even when UI is backgrounded. */
    private val appContext: android.content.Context? = null,
) {
    private val _state = MutableStateFlow(TmuxState())
    val state: StateFlow<TmuxState> = _state.asStateFlow()

    private val _incomingPushFiles = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Remote file paths pushed from the computer via `attache-send <file>`. */
    val incomingPushFiles: SharedFlow<String> = _incomingPushFiles.asSharedFlow()

    private val _activeDownload = MutableStateFlow<FileDownloadProgress?>(null)
    val activeDownload: StateFlow<FileDownloadProgress?> = _activeDownload.asStateFlow()

    private val _completedDownloads = MutableSharedFlow<LocalInboxFile>(extraBufferCapacity = 16)
    val completedDownloads: SharedFlow<LocalInboxFile> = _completedDownloads.asSharedFlow()

    @Volatile private var currentDownloadJob: Job? = null
    private val downloadQueue = Channel<String>(Channel.UNLIMITED)
    private val activeOrQueuedPaths = java.util.Collections.synchronizedSet( linkedSetOf<String>() )
    @Volatile private var lastHandledPushToken: String =
        appContext?.let { AttacheFileStorage.getLastHandledPushToken(it) }.orEmpty()
    private val handledPushTokens = java.util.Collections.synchronizedSet(
        linkedSetOf<String>().apply {
            if (lastHandledPushToken.isNotEmpty()) add(lastHandledPushToken)
        },
    )
    private val pushCheckLock = Mutex()

    /** Scroll the finger has asked for, positive back in history. One
     * loop drains it and merges what has piled up for the same pane, so a
     * fast swipe becomes a few commands rather than one per frame. */
    private data class ScrollRequest(val pane: String, val lines: Int, val column: Int, val row: Int)
    private val scrollRequests = Channel<ScrollRequest>(Channel.UNLIMITED)
    private val startNanos = System.nanoTime()
    private fun nowMs(): Long = (System.nanoTime() - startNanos) / 1_000_000L
    @Volatile private var lastScrollActivityAtMs = -SCROLL_REFRESH_DEBOUNCE_MS

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
                // Attaché: tmux sends a control client every byte every pane in
                // the session produces, while the terminal is drawn from its own
                // PTY channel. `no-output` is per-client and stops tmux sending
                // `%output` lines on this control channel at all, while keeping
                // command replies and layout/window notifications intact.
                try {
                    send("refresh-client -f no-output,ignore-size")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("no-output failed: %s", e.message)
                }
                for (request in refreshRequests) {
                    while (true) {
                        val sinceScrollMs = nowMs() - lastScrollActivityAtMs
                        if (sinceScrollMs >= SCROLL_REFRESH_DEBOUNCE_MS) break
                        delay(SCROLL_REFRESH_DEBOUNCE_MS - sinceScrollMs)
                    }
                    refresh()
                }
            }
            jobs += scope.launch {
                val binDir = appContext?.let { AttacheFileStorage.getRemoteBinDir(it) } ?: "~/.local/bin"
                val autoSyncRules = appContext?.let { AttacheFileStorage.isAutoSyncAgentRulesEnabled(it) } ?: true
                installRemoteSendHelper(binDir, autoSyncRules)
                handleRemotePushIfAny()
            }
            jobs += scope.launch {
                for (remotePath in downloadQueue) {
                    val ctx = appContext
                    if (ctx != null) {
                        val job = scope.launch {
                            try {
                                val displayName = remotePath.trim().substringAfterLast('/').ifBlank { "file" }
                                _activeDownload.value = FileDownloadProgress(displayName, 0L, 0L)
                                val received = AttacheFileStorage.receiveRemoteFile(
                                    context = ctx,
                                    control = this@TmuxControl,
                                    remotePath = remotePath,
                                    onProgress = { progress -> _activeDownload.value = progress },
                                )
                                if (received != null) {
                                    _completedDownloads.tryEmit(received)
                                }
                            } finally {
                                activeOrQueuedPaths.remove(remotePath)
                                _activeDownload.value = null
                            }
                        }
                        currentDownloadJob = job
                        job.join()
                        currentDownloadJob = null
                    } else {
                        activeOrQueuedPaths.remove(remotePath)
                        _incomingPushFiles.tryEmit(remotePath)
                    }
                }
            }
            jobs += scope.launch {
                // Leading-edge + in-flight coalescing: the first scroll after
                // idle goes out immediately (0ms delay); while sendScroll awaits
                // tmux's reply (or within MIN_SCROLL_INTERVAL_MS on fast LAN),
                // subsequent requests pile up in scrollRequests and merge per
                // pane into a single `-N $n` command on the next iteration.
                var lastScrollStartedAtMs = -MIN_SCROLL_INTERVAL_MS
                for (first in scrollRequests) {
                    val elapsed = nowMs() - lastScrollStartedAtMs
                    if (elapsed < MIN_SCROLL_INTERVAL_MS) {
                        delay(MIN_SCROLL_INTERVAL_MS - elapsed)
                    }
                    val merged = LinkedHashMap<String, ScrollRequest>()
                    var next: ScrollRequest? = first
                    while (next != null) {
                        val r = next
                        merged[r.pane] = merged[r.pane]?.let { r.copy(lines = it.lines + r.lines) } ?: r
                        next = scrollRequests.tryReceive().getOrNull()
                    }
                    lastScrollStartedAtMs = nowMs()
                    lastScrollActivityAtMs = lastScrollStartedAtMs
                    for (r in merged.values) {
                        if (r.lines != 0) {
                            sendScroll(r)
                        }
                    }
                    lastScrollActivityAtMs = nowMs()
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
        downloadQueue.close()
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        session.close()
        onEnded(this)
    }

    suspend fun selectWindow(id: String) {
        // Ids cannot carry a quote, a space or a newline; a name could.
        require(WINDOW_ID.matches(id)) { "not a window id: $id" }
        send("select-window -t $id")
    }

    suspend fun renameWindow(windowId: String, newName: String) {
        require(WINDOW_ID.matches(windowId)) { "not a window id: $windowId" }
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        send("rename-window -t ${shSingleQuote(windowId)} ${shSingleQuote(trimmed)}")
        refresh()
    }

    /**
     * Sends [text] to any tmux [windowId] (even in a background session) using a
     * named tmux paste buffer (`attache_share`) so multi-line text and special
     * characters arrive intact in `gemini` / `claude` / shell prompts.
     */
    suspend fun sendPromptToWindow(
        windowId: String,
        text: String,
        switchToTarget: Boolean = false,
        submitWithEnter: Boolean = true,
    ) {
        require(WINDOW_ID.matches(windowId)) { "not a window id: $windowId" }
        if (switchToTarget) {
            switchTo(windowId)
            delay(80)
        }
        val inMode = try {
            send("display-message -p -t ${shSingleQuote(windowId)} '#{pane_in_mode}'")
                .firstOrNull()?.trim() == "1"
        } catch (_: Exception) {
            false
        }
        if (inMode) {
            try {
                send("send-keys -X -t ${shSingleQuote(windowId)} cancel")
            } catch (_: Exception) {
            }
        }
        val singleLineText = text.replace(Regex("[\\r\\n]+"), " ").trim()
        if (singleLineText.isNotEmpty()) {
            send("set-buffer -b attache_share -- ${shSingleQuote(singleLineText)}")
            send("paste-buffer -d -p -b attache_share -t ${shSingleQuote(windowId)}")
        }
        if (submitWithEnter) {
            delay(180)
            send("send-keys -t ${shSingleQuote(windowId)} Enter")
        }
        refresh()
    }

    private fun shSingleQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    suspend fun newWindow() {
        val session = _state.value.sessionId ?: return
        // Quoted: unquoted, tmux's parser would read `$3` as a variable.
        send("new-window -t '$session:' -c '#{pane_current_path}'")
    }

    /** The window after (or before) the active one, wrapping as tmux's own
     * `prefix n` / `prefix p` do. */
    suspend fun stepWindow(forward: Boolean) {
        val session = _state.value.sessionId ?: return
        send("${if (forward) "next-window" else "previous-window"} -t '$session'")
    }

    suspend fun uploadImage(
        bytes: ByteArray,
        ext: String,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String? = upload(bytes, ext, onProgress)

    suspend fun downloadRemoteFile(
        remotePath: String,
        output: java.io.OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean = download(remotePath, output, onProgress)

    fun enqueueDownload(remotePath: String) {
        val trimmed = remotePath.trim()
        if (trimmed.isNotEmpty() && activeOrQueuedPaths.add(trimmed)) {
            downloadQueue.trySend(trimmed)
        }
    }

    private fun enqueueAutoPushDownload(remotePath: String) {
        val trimmed = remotePath.trim()
        if (trimmed.isEmpty()) return
        if (AttacheFileStorage.isAlreadyInstalledOrOlderAttacheApk(trimmed)) {
            Timber.i("Skipping auto-download of already-installed Attaché APK: %s", trimmed)
            return
        }
        enqueueDownload(trimmed)
    }

    fun cancelActiveDownload() {
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        activeOrQueuedPaths.clear()
        _activeDownload.value = null
    }

    /**
     * Queries the active pane's current working directory (`#{pane_current_path}`).
     */
    suspend fun queryActivePanePath(): String {
        val pane = _state.value.activePane?.id
        val targetArg = if (pane != null && PANE_ID.matches(pane)) "-t $pane " else ""
        val path = try {
            send("display-message -p ${targetArg}'#{pane_current_path}'").firstOrNull()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (path.isNotEmpty()) {
            _state.value = _state.value.copy(activePanePath = path)
        }
        return path.ifEmpty { _state.value.activePanePath }
    }

    /**
     * Lists files on the remote host for one-tap downloading to the phone/Pad.
     * - When [recentArtifactsOnly] is false, lists regular files directly in [directory]
     *   (or the active pane's current directory when null/blank), newest first.
     * - When [recentArtifactsOnly] is true, recursively scans up to depth 5 under [directory]
     *   for APKs, images, videos, archives, and PDFs (`.apk`, `.mp4`, `.mov`, `.mkv`, `.webm`,
     *   `.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.pdf`, `.zip`), newest first.
     */
    suspend fun listRemoteFiles(
        directory: String? = null,
        recentArtifactsOnly: Boolean = false,
    ): Pair<String, List<RemoteFileEntry>> {
        val rawDir = directory?.trim()?.takeIf { it.isNotEmpty() } ?: queryActivePanePath().ifEmpty { "~" }
        val quotedDir = "'" + rawDir.replace("'", "'\"'\"'") + "'"
        val perlScript = if (recentArtifactsOnly) {
            """
            use File::Find;
            my ${'$'}d = ${'$'}ARGV[0];
            ${'$'}d =~ s{^~(?=/|${'$'})}{${'$'}ENV{HOME}};
            my @roots = (${'$'}d);
            for my ${'$'}extra ("${'$'}ENV{HOME}/Downloads", "/tmp") {
              push @roots, ${'$'}extra if -d ${'$'}extra && ${'$'}extra ne ${'$'}d;
            }
            my %seen;
            my @out;
            for my ${'$'}root (@roots) {
              next unless -d ${'$'}root;
              my ${'$'}maxDepth = (${'$'}root eq "/tmp" || ${'$'}root eq "${'$'}ENV{HOME}/Downloads") ? 2 : 5;
              find({
                preprocess => sub {
                  my ${'$'}rel = ${'$'}File::Find::dir;
                  ${'$'}rel =~ s{^\Q${'$'}root\E/?}{};
                  my ${'$'}depth = (${'$'}rel eq '' ? 0 : scalar(split('/', ${'$'}rel)));
                  return () if ${'$'}depth >= ${'$'}maxDepth;
                  return grep { ${'$'}_ !~ /^\./ && ${'$'}_ ne 'node_modules' && ${'$'}_ ne 'caches' && ${'$'}_ ne 'intermediates' } @_;
                },
                wanted => sub {
                  return unless -f ${'$'}_;
                  return unless /\.(apk|mp4|mov|mkv|webm|png|jpe?g|webp|gif|pdf|zip|txt|md|log|json)$/i;
                  my ${'$'}full = ${'$'}File::Find::name;
                  return if ${'$'}seen{${'$'}full}++;
                  my @s = stat(${'$'}_);
                  push @out, [${'$'}full, ${'$'}_, ${'$'}s[7] || 0, ${'$'}s[9] || 0] if @s;
                }
              }, ${'$'}root);
            }
            @out = sort { ${'$'}b->[3] <=> ${'$'}a->[3] } @out;
            splice(@out, 60) if @out > 60;
            print "DIR:${'$'}d (+ ~/Downloads, /tmp)\n";
            for my ${'$'}r (@out) { print join("\x01", @{${'$'}r}, "F") . "\n"; }
            """.trimIndent()
        } else {
            """
            my ${'$'}d = ${'$'}ARGV[0];
            ${'$'}d =~ s{^~(?=/|${'$'})}{${'$'}ENV{HOME}};
            ${'$'}d =~ s{/+${'$'}}{} if length(${'$'}d) > 1;
            my (@dirs, @files);
            if (opendir(my ${'$'}dh, ${'$'}d)) {
              while (my ${'$'}f = readdir(${'$'}dh)) {
                next if ${'$'}f =~ /^\./;
                my ${'$'}p = "${'$'}d/${'$'}f";
                my @s = stat(${'$'}p);
                next unless @s;
                if (-d ${'$'}p) {
                  push @dirs, [${'$'}p, ${'$'}f, 0, ${'$'}s[9] || 0, "D"];
                } elsif (-f ${'$'}p) {
                  push @files, [${'$'}p, ${'$'}f, ${'$'}s[7] || 0, ${'$'}s[9] || 0, "F"];
                }
              }
              closedir(${'$'}dh);
            }
            @dirs = sort { ${'$'}b->[3] <=> ${'$'}a->[3] } @dirs;
            @files = sort { ${'$'}b->[3] <=> ${'$'}a->[3] } @files;
            my @out = (@dirs, @files);
            splice(@out, 100) if @out > 100;
            print "DIR:${'$'}d\n";
            for my ${'$'}r (@out) { print join("\x01", @{${'$'}r}) . "\n"; }
            """.trimIndent()
        }
        val quotedPerl = "'" + perlScript.replace("'", "'\"'\"'") + "'"
        val output = exec("perl -e $quotedPerl $quotedDir") ?: return rawDir to emptyList()
        var resolvedDir = rawDir
        val entries = mutableListOf<RemoteFileEntry>()
        for (line in output.lineSequence()) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.startsWith("DIR:")) {
                resolvedDir = trimmed.removePrefix("DIR:").ifBlank { rawDir }
                continue
            }
            val parts = trimmed.split(SEP)
            if (parts.size >= 4) {
                val size = parts[2].toLongOrNull() ?: continue
                val mtime = parts[3].toLongOrNull() ?: 0L
                val isDir = parts.getOrNull(4) == "D"
                entries += RemoteFileEntry(
                    path = parts[0],
                    name = parts[1],
                    sizeBytes = size,
                    modifiedEpochSec = mtime,
                    isDirectory = isDir,
                )
            }
        }
        return resolvedDir to entries
    }

    /**
     * Installs `attache-send` into [remoteBinDir] (default `~/.local/bin`) on the remote host,
     * adds [remoteBinDir] to the tmux server's global `PATH`, and optionally injects the
     * `attache-send` usage rule into `~/.claude/CLAUDE.md` and `~/.gemini/GEMINI.md` on the
     * remote host so any user who installs Attaché gets out-of-the-box Agent file transfer.
     */
    suspend fun installRemoteSendHelper(
        remoteBinDir: String = "~/.local/bin",
        syncAgentRules: Boolean = true,
    ): Boolean {
        val cleanBinDir = remoteBinDir.trim().ifBlank { "~/.local/bin" }
        val quotedBinDir = "'" + cleanBinDir.replace("'", "'\"'\"'") + "'"
        val d = "$"
        val syncFlag = if (syncAgentRules) "1" else "0"
        val script = """
            RAW_BIN=$quotedBinDir
            case "${d}RAW_BIN" in
              "~"*) BIN_DIR="${d}HOME${d}{RAW_BIN#\~}" ;;
              *) BIN_DIR="${d}RAW_BIN" ;;
            esac
            mkdir -p "${d}BIN_DIR" "${d}HOME/.local/share/attache" || exit 1
            CMD_PATH="${d}BIN_DIR/attache-send"
            cat > "${d}CMD_PATH" << 'EOF' && chmod +x "${d}CMD_PATH"
            #!/bin/sh
            if [ -z "${d}1" ]; then
              echo "Usage: attache-send <file-path> [more-files...]" >&2
              exit 1
            fi
            export PATH=/opt/homebrew/bin:/usr/local/bin:${d}PATH
            mkdir -p "${d}HOME/.local/share/attache"
            LAST_ABS=""
            for ARG in "${d}@"; do
              DIR=${d}(cd "${d}(dirname "${d}ARG")" 2>/dev/null && pwd)
              ABS="${d}DIR/${d}(basename "${d}ARG")"
              if [ ! -f "${d}ABS" ]; then
                echo "attache-send: file not found: ${d}ARG" >&2
                exit 1
              fi
              printf '%s\n' "${d}ABS" >> "${d}HOME/.local/share/attache/outbox.queue"
              LAST_ABS="${d}ABS"
              echo "Queued for Attaché mobile inbox: ${d}ABS"
            done
            TOKEN="${d}(date +%s)-${d}${d}:${d}LAST_ABS"
            tmux set -g @attache_push "${d}TOKEN" 2>/dev/null || true
            for S in ${d}(tmux list-sessions -F '#S' 2>/dev/null); do
              W=${d}(tmux display-message -p -t "${d}S" '#W' 2>/dev/null)
              tmux rename-window -t "${d}S" "${d}W" 2>/dev/null || true
            done
            EOF
            ln -sf "${d}CMD_PATH" /tmp/attache-send 2>/dev/null || true
            export PATH=/opt/homebrew/bin:/usr/local/bin:${d}PATH
            tmux set-environment -g PATH "${d}BIN_DIR:/opt/homebrew/bin:/usr/local/bin:${d}PATH" 2>/dev/null || true
            if [ "$syncFlag" = "1" ]; then
              for RFILE in "${d}HOME/.claude/CLAUDE.md" "${d}HOME/.gemini/GEMINI.md"; do
                RDIR=${d}(dirname "${d}RFILE")
                mkdir -p "${d}RDIR" 2>/dev/null || true
                if [ ! -f "${d}RFILE" ] || ! grep -q "attache-send" "${d}RFILE" 2>/dev/null; then
                  printf '\n## 向手机 / Pad 发送文件 (Attaché File Transfer)\n- 当用户要求「把这个文件发给我」「传到我手机上」「发到 Pad 上」「把 APK / 视频 / 图片发给我」时，直接运行：\n  `%s/attache-send "<文件绝对路径>"`\n- 该命令会通过 tmux 触发用户的 Attaché 移动端自动将文件流式下载到手机/Pad 的公共收件箱目录。\n' "${d}BIN_DIR" >> "${d}RFILE"
                fi
              done
            fi
            echo "OK:${d}CMD_PATH"
        """.trimIndent()
        return try {
            exec(script)?.contains("OK:") == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Atomically drains `~/.local/share/attache/outbox.queue` on the remote host and
     * enqueues each file path for background download into `Download/Attache/`.
     */
    private suspend fun drainRemotePushQueue(): List<String> {
        val d = "$"
        val out = try {
            exec(
                """
                Q="${d}HOME/.local/share/attache/outbox.queue"
                if [ -s "${d}Q" ]; then
                  mv "${d}Q" "${d}Q.draining.${d}${d}" 2>/dev/null && cat "${d}Q.draining.${d}${d}" && rm -f "${d}Q.draining.${d}${d}"
                fi
                """.trimIndent(),
            )
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val paths = out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/") }
            .distinct()
            .toList()
        paths.forEach { enqueueAutoPushDownload(it) }
        return paths
    }

    private suspend fun handleRemotePushIfAny() {
        pushCheckLock.withLock {
            val pushValue = send("show-options -gqv @attache_push").firstOrNull()?.trim().orEmpty()
            if (pushValue.isEmpty()) {
                drainRemotePushQueue()
                return@withLock
            }
            val alreadyHandled = pushValue == lastHandledPushToken ||
                !handledPushTokens.add(pushValue) ||
                (appContext != null && AttacheFileStorage.isPushTokenHandled(appContext, pushValue))
            if (alreadyHandled) {
                lastHandledPushToken = pushValue
                return@withLock
            }
            val isFirstObservation = lastHandledPushToken.isEmpty()
            lastHandledPushToken = pushValue
            appContext?.let { AttacheFileStorage.markPushTokenHandled(it, pushValue) }
            val tokenEpoch = pushValue.substringBefore('-').toLongOrNull()
            val isStaleOnConnect = isFirstObservation &&
                tokenEpoch != null &&
                tokenEpoch >= 1_700_000_000L &&
                (System.currentTimeMillis() / 1000L - tokenEpoch) > 120L
            val drained = drainRemotePushQueue()
            if (drained.isEmpty() && !isStaleOnConnect) {
                val colonIdx = pushValue.indexOf(':')
                val remotePath = if (colonIdx >= 0) pushValue.substring(colonIdx + 1).trim() else pushValue
                if (remotePath.startsWith("/")) {
                    enqueueAutoPushDownload(remotePath)
                }
            }
        }
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
        if (pane.inMode) {
            updatePaneFlags(pane.id, inMode = false)
            send("send-keys -X -t ${pane.id} cancel")
        }
    }

    /**
     * Captures the active pane's visible screen plus up to [historyLines] of
     * scrollback as plain text, joining wrapped lines (`-J`) and stripping
     * trailing blank lines so the user can freely select and copy across pages.
     */
    suspend fun capturePaneText(historyLines: Int = 500): String {
        val pane = _state.value.activePane?.id ?: return ""
        require(PANE_ID.matches(pane)) { "not a pane id: $pane" }
        val count = historyLines.coerceIn(50, 5000)
        return send("capture-pane -p -J -S -$count -t $pane")
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
    }

    /** Scrolls the given pane, positive back in history, as a mouse
     * wheel over it would — the pane is not selected. [column] and [row]
     * are where the finger is, 1-based within the pane. Non-suspending, for
     * a gesture. */
    fun scroll(paneId: String, lines: Int, column: Int, row: Int) {
        require(PANE_ID.matches(paneId)) { "not a pane id: $paneId" }
        if (lines != 0) {
            lastScrollActivityAtMs = nowMs()
            scrollRequests.trySend(ScrollRequest(paneId, lines, column.coerceAtLeast(1), row.coerceAtLeast(1)))
        }
    }

    private fun updatePaneFlags(
        paneId: String,
        inMode: Boolean? = null,
        mouseAnyFlag: Boolean? = null,
        mouseSgrFlag: Boolean? = null,
        alternateOn: Boolean? = null,
    ): TmuxPane? {
        val current = _state.value
        var updatedPane: TmuxPane? = null
        val newPanes = current.panes.map { p ->
            if (p.id != paneId) {
                p
            } else {
                p.copy(
                    inMode = inMode ?: p.inMode,
                    mouseAnyFlag = mouseAnyFlag ?: p.mouseAnyFlag,
                    mouseSgrFlag = mouseSgrFlag ?: p.mouseSgrFlag,
                    alternateOn = alternateOn ?: p.alternateOn,
                ).also { updatedPane = it }
            }
        }
        if (updatedPane != null && newPanes != current.panes) {
            _state.value = current.copy(panes = newPanes)
        }
        return updatedPane
    }

    private suspend fun queryPaneFlags(paneId: String): TmuxPane? {
        val line = send(
            "display-message -p -t $paneId '#{pane_in_mode}$SEP#{mouse_any_flag}$SEP#{mouse_sgr_flag}$SEP#{alternate_on}'",
        ).firstOrNull() ?: return _state.value.panes.firstOrNull { it.id == paneId }
        val f = line.split(SEP)
        if (f.size < 4) return _state.value.panes.firstOrNull { it.id == paneId }
        return updatePaneFlags(
            paneId = paneId,
            inMode = f[0] == "1",
            mouseAnyFlag = f[1] == "1",
            mouseSgrFlag = f[2] != "0",
            alternateOn = f[3] == "1",
        )
    }

    @Volatile private var lastScrollSentAtMs = -10_000L

    /**
     * Dispatches a scroll using flat single-block tmux commands so each `send`
     * produces exactly one `%begin ... %end` reply block on tmux 3.6a (nested
     * `if-shell -F` and `;` chains emit 2-5 blocks per call, desynchronizing
     * `pending`).
     *
     *   in copy mode already       → scroll tmux's history (`send-keys -X`)
     *   program reads the mouse    → send wheel events (`send-keys -H -N`)
     *   on the alternate screen    → send Up / Down keys
     *   otherwise (going back)     → enter `copy-mode -e` then `scroll-up`
     */
    private suspend fun sendScroll(request: ScrollRequest) {
        val (paneId, lines, column, row) = request
        if (lines == 0) return
        val n = abs(lines)
        val up = lines > 0
        val now = nowMs()
        val isNewGesture = now - lastScrollSentAtMs > 400L
        lastScrollSentAtMs = now
        try {
            var pane = _state.value.panes.firstOrNull { it.id == paneId }
            val wasMouseAny = pane?.mouseAnyFlag == true
            // Refresh live flags once at the start of a new gesture when not
            // already in copy-mode, in case a full-screen TUI (Claude Code /
            // Antigravity) was started or exited inside the shell pane since
            // the last window refresh.
            if (pane == null || (!pane.inMode && !pane.mouseAnyFlag && isNewGesture)) {
                pane = queryPaneFlags(paneId) ?: pane
            }
            if (pane == null) return
            when {
                pane.inMode -> {
                    try {
                        send("send-keys -X -N $n -t $paneId ${if (up) "scroll-up" else "scroll-down"}")
                    } catch (e: TmuxCommandError) {
                        // `copy-mode -e` exits automatically at the bottom of
                        // history; clear cached `inMode` if a trailing swipe
                        // arrives after it exited.
                        updatePaneFlags(paneId, inMode = false)
                    }
                }
                pane.mouseAnyFlag -> {
                    val ticks = if (!wasMouseAny) (n / 3).coerceAtLeast(1) else n
                    send("send-keys -H -N $ticks -t $paneId ${wheelBytes(up, column, row, sgr = pane.mouseSgrFlag)}")
                    if (isNewGesture) refreshRequests.trySend(Unit)
                }
                pane.alternateOn -> {
                    send("send-keys -N $n -t $paneId ${if (up) "Up" else "Down"}")
                    if (isNewGesture) refreshRequests.trySend(Unit)
                }
                up -> {
                    updatePaneFlags(paneId, inMode = true)
                    send("copy-mode -e -t $paneId")
                    send("send-keys -X -N $n -t $paneId scroll-up")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
                        "#{pane_height}$SEP#{pane_active}$SEP#{pane_in_mode}$SEP#{mouse_any_flag}$SEP#{mouse_sgr_flag}$SEP#{alternate_on}'",
                ).mapNotNull { line ->
                    val f = line.split(SEP)
                    if (f.size < 7 || !PANE_ID.matches(f[0])) return@mapNotNull null
                    val n = f.subList(1, 5).map { it.toIntOrNull() ?: return@mapNotNull null }
                    TmuxPane(
                        id = f[0],
                        left = n[0],
                        top = n[1],
                        width = n[2],
                        height = n[3],
                        active = f[5] == "1",
                        inMode = f[6] == "1",
                        mouseAnyFlag = f.getOrNull(7) == "1",
                        mouseSgrFlag = f.getOrNull(8) != "0",
                        alternateOn = f.getOrNull(9) == "1",
                    )
                }
            }
            val currentSessionName = sessions.firstOrNull { it.id == current }?.name
            val currentRecent = if (currentSessionName != null && active != null) {
                TmuxRecentWindow(
                    sessionName = currentSessionName,
                    windowId = active.id,
                    windowIndex = active.index,
                    windowName = active.name,
                    lastVisitedMs = System.currentTimeMillis(),
                )
            } else {
                null
            }
            val recentWindows = updateRecentWindows(
                existing = _state.value.recentWindows,
                current = currentRecent,
                validSessions = sessions,
                seedFromSessionsIfEmpty = false,
            )
            _state.value = TmuxState(
                sessionId = current,
                sessions = sessions,
                windows = windows,
                panes = panes,
                zoomed = zoomed,
                activePanePath = _state.value.activePanePath,
                recentWindows = recentWindows,
            )
            publishShareShortcuts(sessions, current)
            // Check if the computer queued a file via `attache-send <file>`
            handleRemotePushIfAny()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("refresh failed: %s", e.message)
        }
    }

    private fun publishShareShortcuts(sessions: List<TmuxSession>, currentSessionId: String?) {
        val ctx = appContext ?: return
        if (sessions.isEmpty()) return
        val targetSession = sessions.firstOrNull { it.name.equals("Share", ignoreCase = true) }
            ?: sessions.firstOrNull { it.id == currentSessionId }
            ?: sessions.firstOrNull()
            ?: return
        val topWindows = targetSession.windows.take(4)
        if (topWindows.isEmpty()) return
        val signature = "${targetSession.name}:" + topWindows.joinToString("|") { "${it.id}=${it.name}" }
        if (signature == lastPublishedShortcutsSignature) return
        lastPublishedShortcutsSignature = signature

        scope.launch(Dispatchers.Default) {
            runCatching {
                val newIds = topWindows.mapTo(hashSetOf()) { "$SHORTCUT_ID_PREFIX${it.id}" }
                val staleIds = ShortcutManagerCompat.getDynamicShortcuts(ctx)
                    .map { it.id }
                    .filter { it.startsWith(SHORTCUT_ID_PREFIX) && it !in newIds }
                if (staleIds.isNotEmpty()) {
                    ShortcutManagerCompat.removeDynamicShortcuts(ctx, staleIds)
                }
                val shareActivityComponent = ComponentName(ctx.packageName, "org.connectbot.ui.AttacheQuickShareActivity")
                val mainActivityComponent = ComponentName(ctx.packageName, "org.connectbot.ui.MainActivity")
                topWindows.forEachIndexed { index, window ->
                    val displayName = window.name.ifBlank { "Window ${window.index}" }
                    val icon = createShareShortcutIcon(index, window.index, displayName)
                    val person = Person.Builder()
                        .setName(displayName)
                        .setKey(window.id)
                        .setIcon(icon)
                        .build()
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        component = shareActivityComponent
                        type = "text/plain"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(EXTRA_SHARE_WINDOW_ID, window.id)
                        putExtra(EXTRA_SHARE_SESSION_NAME, targetSession.name)
                    }
                    val shortcut = ShortcutInfoCompat.Builder(ctx, "$SHORTCUT_ID_PREFIX${window.id}")
                        .setActivity(mainActivityComponent)
                        .setShortLabel(displayName)
                        .setLongLabel("$displayName (${targetSession.name})")
                        .setIcon(icon)
                        .setPerson(person)
                        .setIntent(shareIntent)
                        .setCategories(setOf(SHARE_TARGET_CATEGORY))
                        .setLongLived(true)
                        .setRank(index)
                        .build()
                    ShortcutManagerCompat.pushDynamicShortcut(ctx, shortcut)
                }
            }.onFailure { e ->
                Timber.w(e, "Failed to publish Direct Share shortcuts")
            }
        }
    }

    private fun createShareShortcutIcon(orderIndex: Int, windowIndex: Int, windowName: String): IconCompat {
        val sizePx = 108
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgColors = intArrayOf(
            0xFF0284C7.toInt(), // Sky blue
            0xFF0D9488.toInt(), // Teal
            0xFFD97706.toInt(), // Amber
            0xFF7C3AED.toInt(), // Purple
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColors[orderIndex % bgColors.size]
            style = Paint.Style.FILL
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, bgPaint)
        val glyph = windowName.trim().firstOrNull { !it.isWhitespace() }?.toString() ?: "$windowIndex"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 48f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val textY = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(glyph, sizePx / 2f, textY, textPaint)
        return IconCompat.createWithBitmap(bmp)
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

    /**
     * Switches this connection (both terminal and control client) to [item],
     * whether [item] is in the current session or another session on the server,
     * and moves [item] to index 0 of [TmuxState.recentWindows].
     */
    suspend fun selectRecentWindow(item: TmuxRecentWindow) {
        require(WINDOW_ID.matches(item.windowId)) { "not a window id: ${item.windowId}" }
        val currentSession = _state.value.sessions.firstOrNull { it.id == _state.value.sessionId }
        val updated = updateRecentWindows(
            existing = _state.value.recentWindows,
            current = item.copy(lastVisitedMs = System.currentTimeMillis()),
        )
        _state.value = _state.value.copy(recentWindows = updated)
        if (currentSession != null && currentSession.name == item.sessionName) {
            selectWindow(item.windowId)
        } else {
            switchTo(item.windowId)
        }
        refreshRequests.trySend(Unit)
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
                // Also sanitize any embedded CR/LF so a single `send()` never
                // splits into multiple tmux -CC control commands.
                val safeCommand = command.replace('\r', ' ').replace('\n', ' ')
                withContext(NonCancellable) { session.write((safeCommand + "\n").toByteArray()) }
            } catch (e: Exception) {
                // Nothing reached tmux, so nothing will answer this entry;
                // left queued it would take the next command's reply.
                synchronized(pending) { pending.remove(reply) }
                if (e is CancellationException) throw e
                close()
                throw CancellationException("tmux control channel write failed").apply { initCause(e) }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "tmux -C stderr closed")
        } finally {
            if (text.size() > 0) Timber.w("tmux -C stderr: %s", text.toString(Charsets.UTF_8.name()))
        }
    }

    companion object {
        @Volatile private var lastPublishedShortcutsSignature: String = ""
        const val SHARE_TARGET_CATEGORY = "me.xueshi.attache.category.SHARE_TARGET"
        const val SHORTCUT_ID_PREFIX = "attache_share_"
        const val EXTRA_SHARE_WINDOW_ID = "attache_share_window_id"
        const val EXTRA_SHARE_SESSION_NAME = "attache_share_session_name"
        private const val SEP = "\u0001"
        private const val MIN_SCROLL_INTERVAL_MS = 8L
        private const val SCROLL_REFRESH_DEBOUNCE_MS = 180L
        private val SESSION_ID = Regex("^\\$\\d+$")
        private val WINDOW_ID = Regex("^@\\d+$")
        private val PANE_ID = Regex("^%\\d+$")
        private val DIGITS = Regex("^\\d+$")
        private val CLIENT_TTY = Regex("^/dev/[A-Za-z0-9/]+$")
        private val REFRESH_ON = listOf(
            "%window-add", "%window-close", "%window-renamed", "%unlinked-window-",
            "%session-window-changed", "%session-changed", "%sessions-changed",
            "%window-pane-changed", "%layout-change", "%pane-mode-changed", "%session-renamed",
        )
    }
}
