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

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.attache.tmux.MAX_ATTACHED_IMAGES
import org.attache.tmux.PendingImageAttachment
import org.attache.tmux.TmuxControl
import org.attache.tmux.decodeImageThumbnail
import org.connectbot.data.EntityIds
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.ui.navigation.NavArgs
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

enum class TranscriptStatus { LOADING, LIVE, MISSING }

/** A message the phone sent that the transcript has not shown yet. */
data class PendingMessage(
    val id: Long,
    val text: String,
    val sentAtMs: Long,
    val unconfirmed: Boolean = false,
    /** What the transcript has to contain for this to count as arrived:
     * the typed words, since Claude Code may record attached images as
     * image blocks rather than as the paths that were typed. */
    val match: String = text,
    /** The last row shown when it went out. The bubble is drawn right
     * after that row, and only prompts after it can confirm it. */
    val anchorKey: String? = null,
)

/**
 * Where unconfirmed messages sit, and when one counts as arrived.
 *
 * Both rules are anchored to the row that was last when the message went
 * out. Drawn at the end of the list instead, a message the transcript never
 * confirmed stayed below everything said after it, which is how three slash
 * commands ended up under a later message on 2026-11-05. Matched against the
 * whole conversation instead, a second `/context` was confirmed at once by
 * the first.
 */
internal object PendingMessages {
    fun confirmed(pending: PendingMessage, items: List<ChatItem>): Boolean {
        val anchor = pending.anchorKey?.let { key -> items.indexOfFirst { it.key == key } } ?: -1
        return items.drop(anchor + 1).any { item ->
            item is ChatItem.UserText && (pending.match.isEmpty() || item.text.trim().contains(pending.match))
        }
    }

    fun interleave(items: List<ChatItem>, pending: List<PendingMessage>): List<Any> {
        val keys = items.mapTo(HashSet()) { it.key }
        val byAnchor = pending.filter { it.anchorKey in keys }.groupBy { it.anchorKey }
        val out = ArrayList<Any>(items.size + pending.size)
        for (item in items) {
            out.add(item)
            byAnchor[item.key]?.let(out::addAll)
        }
        out.addAll(pending.filter { it.anchorKey !in keys })
        return out
    }
}

data class AgentChatUiState(
    val connection: ChatConnection = ChatConnection.CONNECTING,
    val agent: AgentPane? = null,
    /** The pane is still there but no agent runs in it any more. */
    val agentExited: Boolean = false,
    val paneGone: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    /** Reading began part-way into the file; older turns are not shown. */
    val earlierNotLoaded: Boolean = false,
    val transcript: TranscriptStatus = TranscriptStatus.LOADING,
    val prompt: ApprovalPrompt? = null,
    val pending: List<PendingMessage> = emptyList(),
    /** Images picked for the next message, uploading or uploaded. */
    val images: List<PendingImageAttachment> = emptyList(),
    val notice: String? = null,
    /** Claude's live status line while it works; null when unreadable. */
    val activity: AgentActivity? = null,
    val loadingEarlier: Boolean = false,
    /** What is on screen while Claude has a panel or picker open over its
     * input box, after `/usage`, `/model` and the like; null when none. */
    val panel: List<String>? = null,
    /** Every agent on the host, for switching without going back. */
    val allAgents: List<AgentPane> = emptyList(),
    /** What the agent's own status line says: model, effort, context. */
    val status: AgentStatus? = null,
    /** The pane as it looks now, colour codes and all, for an agent whose
     * conversation cannot be read: null while there is a transcript. */
    val mirror: List<String>? = null,
)

/**
 * One agent as a conversation.
 *
 * Nothing here is authored by the phone. The conversation is the agent's own
 * transcript file, followed over SSH; whether it is waiting, and on what, is
 * `@agent_state` and the bottom of its pane; and everything the person does
 * reaches the agent as keys into that same pane. So the agent stays the one
 * in tmux, and `tmux attach` at the desk shows exactly what the phone did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    val hostId: UUID = EntityIds.parse(savedStateHandle.get<String>(NavArgs.HOST_ID))

    /** `%42`; the route carries the digits, because `%` is URL syntax. */
    val paneId: String = "%" + savedStateHandle.get<String>(PANE_ARG).orEmpty().filter(Char::isDigit)

    private val link = ChatLink(hostId, viewModelScope, dispatchers)
    private val _uiState = MutableStateFlow(AgentChatUiState())
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    private val transcriptPath = MutableStateFlow<String?>(null)

    /** Whose transcript [transcriptPath] is, set before it: the follow can
     * start before the refresh that found the path has put the agent in
     * the UI state, and agy's file read as Claude's shows nothing. */
    @Volatile private var transcriptKind: String? = null
    private var control: TmuxControl? = null
    private var nextPendingId = 1L
    private var nextImageId = 1L
    private var statReads = 0

    // What the status is put together from: the payload in `@agent_stat`
    // when present, the transcript's own account, and the model an agent
    // keeps in its settings (agy).
    @Volatile private var payloadStatus: AgentStatus? = null

    @Volatile private var transcriptStatus: AgentStatus? = null

    @Volatile private var sessionModel: String? = null

    private fun currentStatus(): AgentStatus? {
        val kind = _uiState.value.agent?.kind
        val settings = sessionModel?.let { model ->
            // agy's model carries its effort in brackets: `… (Medium)`.
            val effort = Regex("\\(([^)]+)\\)\\s*$").find(model)?.groupValues?.get(1)?.lowercase()
            AgentStatus(source = kind ?: "agent", model = model, effort = effort)
        }
        // agy's transcript has the tokens but no model; its settings have
        // the model.
        val transcript = transcriptStatus
        val files = when {
            transcript == null -> settings
            transcript.model == null && settings != null -> transcript.copy(model = settings.model, effort = settings.effort)
            else -> transcript
        }
        return AgentStatusMerge.merge(payloadStatus, files)
    }

    // The conversation being followed, and the file offset its earliest
    // line starts at. Touched on [transcriptLane] only.
    private var liveTranscript: AgentTranscript? = null

    @Volatile private var livePath: String? = null
    private var historyStart: Long? = null

    /** The conversation before a `/clear`, kept above the new one with a
     * divider, since the new file starts empty and the person just watched
     * the old one. */
    @Volatile private var carryOver: List<ChatItem> = emptyList()

    /** After a slash command the screen is watched for a panel for a while,
     * since a panel changes nothing tmux or the transcript would report. */
    @Volatile private var panelWatchUntilMs = 0L
    private var lastSlash: String? = null
    private var lastSlashAtMs = 0L

    /** Parsing a megabyte of JSON is not for the main thread, and the
     * transcript is not thread-safe: one lane for all of it. */
    private val transcriptLane = dispatchers.default.limitedParallelism(1)

    init {
        RecentAgents.visit(hostId, paneId)
        viewModelScope.launch {
            link.connection.collect { c -> _uiState.update { it.copy(connection = c) } }
        }
        viewModelScope.launch {
            link.control.collectLatest { tmux ->
                control = tmux
                if (tmux == null) return@collectLatest
                coroutineScope {
                    launch { watchPane(tmux) }
                    launch { watchAgents(tmux) }
                    launch { transcriptPath.collectLatest { path -> follow(tmux, path) } }
                }
            }
        }
    }

    /** For the tools that take the channel itself, such as the file inbox. */
    val tmuxControl: StateFlow<TmuxControl?> get() = link.control

    fun setTerminalManager(manager: TerminalManager) = link.attach(manager)

    fun reconnect() = link.reconnect()

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

    fun reportNotice(message: String) = _uiState.update { it.copy(notice = message) }

    /** Points this connection's terminal at the agent's window first, so the
     * terminal opens on it. Navigation happens whatever tmux says: the
     * terminal is the fallback and must always open. */
    fun openInTerminal(then: () -> Unit) {
        val tmux = control
        val windowId = _uiState.value.agent?.windowId
        viewModelScope.launch {
            try {
                if (tmux != null && windowId != null) tmux.switchTo(windowId)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Timber.w(e, "could not switch to %s", windowId)
            }
            then()
        }
    }

    /**
     * Types [text] into the agent's prompt and presses Enter.
     *
     * Refused while the agent is waiting on a menu: there, letters are menu
     * keys, and a sentence would answer a question nobody read.
     */
    fun send(text: String) {
        val words = text.replace(Regex("[\\r\\n]+"), " ").trim()
        val tmux = control ?: return
        val state = _uiState.value
        if (state.images.any { it.remotePath == null }) {
            _uiState.update { it.copy(notice = "Wait for the images to finish uploading") }
            return
        }
        // Image paths first, as the terminal mode sends them: Claude Code
        // reads an image from a path in the prompt.
        val paths = state.images.mapNotNull { it.remotePath }
        val body = (paths + listOfNotNull(words.ifEmpty { null })).joinToString(" ")
        if (body.isEmpty()) return
        if (state.agent?.phase == AgentPhase.NEEDS_YOU) {
            _uiState.update { it.copy(notice = "Answer the question first") }
            return
        }
        val shown = if (paths.isEmpty()) words else listOf(words, "(${paths.size} image${if (paths.size == 1) "" else "s"})").filter { it.isNotEmpty() }.joinToString(" ")
        val pending = PendingMessage(
            id = nextPendingId++,
            text = shown,
            sentAtMs = System.currentTimeMillis(),
            match = words,
            anchorKey = state.items.lastOrNull()?.key,
        )
        _uiState.update { it.copy(pending = it.pending + pending, images = emptyList()) }
        viewModelScope.launch {
            try {
                val inMode = tmux.query("display-message -p -t $paneId '#{pane_in_mode}'").firstOrNull()?.trim() == "1"
                if (inMode) tmux.query("send-keys -X -t $paneId cancel")
                if (body.startsWith("/")) {
                    lastSlash = body.substringBefore(' ')
                    lastSlashAtMs = System.currentTimeMillis()
                    panelWatchUntilMs = lastSlashAtMs + PANEL_WATCH_MS
                }
                if (body.startsWith("/") || body.startsWith("!")) {
                    // Typed, not pasted: `/` and `!` mean something to
                    // Claude Code only as the first key of the line, which
                    // is how the terminal mode's command keys send them too.
                    tmux.query("send-keys -t $paneId -l -- ${quote(body)}")
                } else {
                    // A bracketed paste (`-p`), so text arrives as one piece
                    // and nothing in it is read as a key binding.
                    tmux.query("set-buffer -b attache_chat -- ${quote(body)}")
                    tmux.query("paste-buffer -d -p -b attache_chat -t $paneId")
                }
                delay(150)
                tmux.query("send-keys -t $paneId Enter")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { s ->
                    s.copy(pending = s.pending.filterNot { it.id == pending.id }, notice = "Not sent: ${e.message}")
                }
                return@launch
            }
            delay(CONFIRM_MS)
            _uiState.update { s -> s.copy(pending = s.pending.map { if (it.id == pending.id) it.copy(unconfirmed = true) else it }) }
        }
    }

    /** Claude Code stops a turn on Esc; the turn can be continued after. */
    fun stop() {
        val tmux = control ?: return
        viewModelScope.launch {
            try {
                // Only while the turn is visibly still running. An Esc that
                // arrives after it ended lands on the idle prompt, and two of
                // those open Claude's rewind menu, a step away from cutting
                // the conversation back and losing its prompt cache.
                if (AgentActivities.detect(capture(tmux)) == null) {
                    _uiState.update { it.copy(notice = "It has already stopped, so nothing was sent") }
                    refreshPane(tmux)
                    return@launch
                }
                tmux.query("send-keys -t $paneId Escape")
                delay(600)
                refreshPane(tmux)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(notice = "Not sent: ${e.message}") }
            }
        }
    }

    /**
     * Uploads picked images to `/tmp` on the host, the way the terminal
     * mode's image key does, one at a time with their progress shown.
     */
    fun attachImages(resolver: ContentResolver, uris: List<Uri>) {
        val tmux = control ?: return
        val room = (MAX_ATTACHED_IMAGES - _uiState.value.images.size).coerceAtLeast(0)
        if (uris.size > room) {
            _uiState.update { it.copy(notice = "At most $MAX_ATTACHED_IMAGES images per message") }
        }
        val accepted = uris.take(room)
        if (accepted.isEmpty()) return
        viewModelScope.launch {
            val staged = withContext(dispatchers.io) {
                accepted.mapNotNull { uri ->
                    val bytes = try {
                        resolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        Timber.w(e, "could not read a picked image")
                        null
                    }
                    if (bytes == null || bytes.isEmpty()) return@mapNotNull null
                    val ext = when (resolver.getType(uri)) {
                        "image/jpeg" -> "jpg"
                        "image/webp" -> "webp"
                        "image/gif" -> "gif"
                        else -> "png"
                    }
                    Triple(nextImageId++, bytes to ext, decodeImageThumbnail(bytes))
                }
            }
            if (staged.isEmpty()) {
                _uiState.update { it.copy(notice = "Could not read the image") }
                return@launch
            }
            _uiState.update { s ->
                s.copy(images = s.images + staged.map { (id, _, thumb) -> PendingImageAttachment(id, thumb, null, 0.02f) })
            }
            for ((id, payload, _) in staged) {
                val (bytes, ext) = payload
                val path = try {
                    tmux.uploadImage(bytes, ext) { done, total ->
                        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0.02f, 0.99f) else 0.5f
                        _uiState.update { s -> s.copy(images = s.images.map { if (it.id == id) it.copy(progressFraction = fraction) else it }) }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                _uiState.update { s ->
                    if (path == null) {
                        s.copy(images = s.images.filterNot { it.id == id }, notice = "Could not upload an image")
                    } else {
                        s.copy(images = s.images.map { if (it.id == id) it.copy(remotePath = path, progressFraction = 1f) else it })
                    }
                }
            }
        }
    }

    fun removeImage(id: Long) = _uiState.update { s -> s.copy(images = s.images.filterNot { it.id == id }) }

    /**
     * Answers the menu on screen by its number. The screen is read again
     * first and the digit goes out only if the very same menu is still
     * there: answered at the desk in the meantime, a stray digit would land
     * in the agent's input box instead.
     */
    fun answer(option: ApprovalPrompt.Option) {
        val tmux = control ?: return
        val shown = _uiState.value.prompt ?: return
        viewModelScope.launch {
            try {
                val now = ApprovalPrompts.detect(capture(tmux))
                if (now == null || now.fingerprint != shown.fingerprint) {
                    _uiState.update { it.copy(prompt = now, notice = "The question changed, so nothing was sent") }
                    return@launch
                }
                // Claude Code selects on the digit alone; an Enter after it
                // would submit whatever sits in its input box.
                tmux.query("send-keys -t $paneId ${option.number}")
                _uiState.update { it.copy(prompt = null) }
                delay(800)
                refreshPane(tmux)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(notice = "Not sent: ${e.message}") }
            }
        }
    }

    /** The other agents, for the drawer and the recent list. The same
     * read-only `list-panes` the agent list sends, a little less often. */
    private suspend fun watchAgents(tmux: TmuxControl) {
        while (true) {
            try {
                val lines = tmux.query(AgentPanes.LIST_COMMAND)
                val sessions = readSessions(tmux)
                val agents = withContext(dispatchers.default) { AgentPanes.merge(AgentPanes.parse(lines), sessions) }
                _uiState.update { it.copy(allAgents = agents) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return
            } catch (e: Exception) {
                Timber.w(e, "agent list refresh failed")
            }
            delay(AGENTS_POLL_MS)
        }
    }

    private suspend fun watchPane(tmux: TmuxControl) {
        while (true) {
            try {
                refreshPane(tmux)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return
            } catch (e: Exception) {
                Timber.w(e, "pane refresh failed")
            }
            val watchingPanel = _uiState.value.panel != null || System.currentTimeMillis() < panelWatchUntilMs
            delay(if (watchingPanel) PANEL_POLL_MS else PANE_POLL_MS)
        }
    }

    /** The keys a panel takes, and nothing else: the phone never types into
     * one, since what a letter does there depends on the panel. */
    fun panelKey(key: String) {
        require(key in PANEL_KEYS) { "not a panel key: $key" }
        val tmux = control ?: return
        viewModelScope.launch {
            try {
                // The panel may have closed since it was drawn. A key meant for
                // it would then reach Claude's prompt, where Tab and Esc have
                // meanings of their own that can change the session's settings
                // or open its rewind menu.
                if (ScreenPanels.detect(tmux.query("capture-pane -p -J -t $paneId")) == null) {
                    _uiState.update { it.copy(panel = null, notice = "The panel has closed, so nothing was sent") }
                    return@launch
                }
                tmux.query("send-keys -t $paneId $key")
                delay(250)
                refreshPane(tmux)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(notice = "Not sent: ${e.message}") }
            }
        }
    }

    private suspend fun refreshPane(tmux: TmuxControl) {
        val rows = try {
            tmux.query("list-panes -t $paneId -F ${AgentPanes.FORMAT}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // `can't find pane`: the pane was closed.
            _uiState.update { it.copy(paneGone = true, prompt = null) }
            return
        }
        val mine = rows.firstOrNull { it.startsWith(paneId + AgentPanes.SEP) }
        if (mine == null) {
            _uiState.update { it.copy(paneGone = true, prompt = null) }
            return
        }
        val parsed = AgentPanes.parse(listOf(mine)).firstOrNull()
        // The agent's own report for this pane, read every refresh: it is
        // what says the agent is waiting on the person, and the approval
        // dock should not lag it. Only this agent's provider is asked.
        val provider = parsed?.let { AgentProviders.forKind(it.kind) }
        val ownSessions = provider?.let { p ->
            HostScripts.sessionsCommand(listOf(p))?.let { cmd ->
                try {
                    HostScripts.parse(tmux.query(cmd), listOf(p))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }
        }.orEmpty()
        val agent = parsed?.let { AgentPanes.merge(listOf(it), ownSessions).first() }
        val session = ownSessions.firstOrNull { it.paneId == paneId }
        session?.transcriptPath?.let {
            transcriptKind = session.kind
            transcriptPath.value = it
        }
        sessionModel = session?.model

        // The status payload, when the desktop app's status line wrapper (or
        // a hand-added one) writes it, is extra detail and read one refresh
        // in three: agy's runs to 12.7 KB (measured 2026-11-05, its
        // subagents ride along), 6 KB a second at the full rate over a
        // relayed 5G link for numbers that move once a turn.
        if (statReads++ % STAT_EVERY == 0) {
            val stat = tmux.query("display-message -p -t $paneId '#{@agent_stat}'").joinToString("\n")
            // Only the running agent's own payload counts: a pane keeps the
            // one a previous agent wrote, and a Claude payload left in a pane
            // now running agy would otherwise put the old Claude conversation
            // on this agent's page.
            payloadStatus = agent?.let { AgentStatuses.forAgent(AgentStatuses.parse(stat), it.kind) }
            if (transcriptPath.value == null && payloadStatus?.source == "claude") {
                transcriptPathOf(stat)?.let {
                    transcriptKind = "claude"
                    transcriptPath.value = it
                }
            }
        }
        if (transcriptPath.value == null && provider?.transcriptReader() == null) {
            _uiState.update { it.copy(transcript = TranscriptStatus.MISSING) }
        } else if (transcriptPath.value == null && session == null && payloadStatus == null) {
            // Claude, but neither its session file nor a payload named a
            // transcript: nothing to follow.
            _uiState.update { it.copy(transcript = TranscriptStatus.MISSING) }
        }
        val status = currentStatus()
        // No conversation to read for this agent: no reader for it yet
        // (Codex), none started yet (a fresh agy), or its file could not be
        // followed. Show its screen instead, so what it is doing and what it
        // answered can be followed without leaving the chat. The visible
        // screen only, with its colours; a few KB a refresh, and only while
        // this page is open on such an agent. Claude keeps its own messages.
        val noConversation = transcriptPath.value == null ||
            (_uiState.value.transcript == TranscriptStatus.MISSING && _uiState.value.items.isEmpty())
        val mirror = if (provider != null && provider !== ClaudeProvider && (provider.transcriptReader() == null || noConversation)) {
            tmux.query("capture-pane -p -e -J -t $paneId").dropLastWhile { it.replace(AnsiText.SEQUENCE, "").isBlank() }
        } else {
            null
        }
        val watchPanel = agent != null && agent.phase != AgentPhase.NEEDS_YOU && agent.phase != AgentPhase.WORKING &&
            (_uiState.value.panel != null || System.currentTimeMillis() < panelWatchUntilMs)
        val screen = when {
            agent?.phase == AgentPhase.NEEDS_YOU || agent?.phase == AgentPhase.WORKING -> capture(tmux)

            // The visible screen only: a panel is what is drawn now, and
            // scrollback above it would read as part of it.
            watchPanel -> tmux.query("capture-pane -p -J -t $paneId")

            else -> null
        }
        val prompt = if (agent?.phase == AgentPhase.NEEDS_YOU) screen?.let(ApprovalPrompts::detect) else null
        val activity = if (agent?.phase == AgentPhase.WORKING) screen?.let(AgentActivities::detect) else null
        val panel = if (watchPanel) screen?.let(ScreenPanels::detect) else null
        _uiState.update { s ->
            s.copy(
                agent = agent ?: s.agent,
                agentExited = agent == null,
                paneGone = false,
                prompt = prompt,
                activity = activity,
                panel = panel,
                status = status,
                mirror = mirror,
            )
        }
    }

    private suspend fun capture(tmux: TmuxControl): List<String> = tmux.query("capture-pane -p -J -t $paneId -S -40")

    private suspend fun follow(tmux: TmuxControl, path: String?) {
        if (path == null || !TranscriptFollower.isFollowablePath(path)) {
            _uiState.update { it.copy(transcript = if (path == null) TranscriptStatus.LOADING else TranscriptStatus.MISSING) }
            return
        }
        val previous = _uiState.value.items
        val cleared = lastSlash == "/clear" && System.currentTimeMillis() - lastSlashAtMs < CLEAR_WINDOW_MS
        carryOver = if (livePath != null && livePath != path && cleared && previous.isNotEmpty()) {
            previous + ChatItem.Note("clear-$path", "New conversation (/clear)")
        } else {
            emptyList()
        }
        while (true) {
            _uiState.update { it.copy(transcript = TranscriptStatus.LOADING, items = carryOver, earlierNotLoaded = false) }
            withContext(transcriptLane) {
                val transcript = ((transcriptKind ?: _uiState.value.agent?.kind)?.let(AgentProviders::forKind) ?: ClaudeProvider).transcriptReader()
                    ?: return@withContext
                val splitter = LineSplitter()
                liveTranscript = transcript
                livePath = path
                historyStart = null
                var publishJob: Job? = null
                coroutineScope {
                    val ok = tmux.follow(TranscriptFollower.command(path)) { chunk ->
                        val lines = splitter.feed(chunk)
                        if (lines.isEmpty() && splitter.startOffset == null) return@follow
                        if (historyStart == null) historyStart = splitter.startOffset
                        lines.forEach(transcript::accept)
                        // Coalesced: an agent mid-turn appends several times
                        // a second, and the list need not redraw for each.
                        publishJob?.cancel()
                        publishJob = launch {
                            delay(PUBLISH_DELAY_MS)
                            publish(transcript)
                        }
                    }
                    publishJob?.join()
                    if (!ok || splitter.startOffset == null) {
                        _uiState.update { it.copy(transcript = TranscriptStatus.MISSING) }
                    }
                }
            }
            // The command ended: the file went away, or the channel did.
            // Either way try again shortly; a new path cancels this loop.
            delay(RETRY_MS)
        }
    }

    /**
     * Puts the history just before what is shown in front of it. Called when
     * the top of the conversation scrolls into view.
     */
    fun loadEarlier() {
        val tmux = control ?: return
        if (_uiState.value.loadingEarlier) return
        _uiState.update { it.copy(loadingEarlier = true) }
        viewModelScope.launch(transcriptLane) {
            try {
                val transcript = liveTranscript ?: return@launch
                val path = livePath ?: return@launch
                val end = historyStart?.takeIf { it > 0 } ?: return@launch
                // At least one line always comes back, so each load gets
                // further up however long the records are.
                val text = tmux.run(TranscriptFollower.earlierCommand(path, end))
                val start = text?.substringBefore('\n')?.removePrefix(TranscriptFollower.HEADER)?.trim()?.toLongOrNull()
                if (text == null || start == null) {
                    _uiState.update { it.copy(notice = "Could not load earlier messages") }
                    return@launch
                }
                // The file was followed anew while this was in flight.
                if (transcript !== liveTranscript) return@launch
                val lines = text.substringAfter('\n', "").split('\n').filter { it.isNotBlank() }
                transcript.prepend(lines)
                historyStart = minOf(start, end)
                publish(transcript)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(notice = "Could not load earlier messages") }
            } finally {
                _uiState.update { it.copy(loadingEarlier = false) }
            }
        }
    }

    private fun publish(transcript: AgentTranscript) {
        val items = carryOver + transcript.snapshot
        transcriptStatus = transcript.status
        val status = currentStatus()
        _uiState.update { s ->
            s.copy(
                items = items,
                transcript = TranscriptStatus.LIVE,
                earlierNotLoaded = (historyStart ?: 0L) > 0L,
                // A sent message is confirmed once the agent has written it
                // down; until then its bubble stays marked as unconfirmed.
                pending = s.pending.filterNot { PendingMessages.confirmed(it, items) },
                status = status ?: s.status,
            )
        }
    }

    private fun transcriptPathOf(stat: String): String? = try {
        JSONObject(stat.trim()).optString("transcript_path").ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val PANE_ARG = "pane"
        private const val PANE_POLL_MS = 2_000L
        private const val PUBLISH_DELAY_MS = 120L
        private const val RETRY_MS = 3_000L
        private const val CONFIRM_MS = 15_000L
        private const val PANEL_WATCH_MS = 30_000L
        private const val PANEL_POLL_MS = 1_000L
        private const val AGENTS_POLL_MS = 4_000L
        private const val STAT_EVERY = 3
        private const val CLEAR_WINDOW_MS = 120_000L
        private val PANEL_KEYS = setOf("Up", "Down", "Left", "Right", "Enter", "Escape", "Tab")
    }
}
