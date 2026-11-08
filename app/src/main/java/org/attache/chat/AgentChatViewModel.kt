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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    /** The channel stopped answering; a new one is being made. */
    val linkLost: Boolean = false,
    val agent: AgentPane? = null,
    /** The pane is still there but no agent runs in it any more. */
    val agentExited: Boolean = false,
    val paneGone: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    /** Reading began part-way into the file; older turns are not shown. */
    val earlierNotLoaded: Boolean = false,
    val transcript: TranscriptStatus = TranscriptStatus.LOADING,
    val prompt: ApprovalPrompt? = null,
    /** A question Claude asked (`AskUserQuestion`), while it waits. */
    val question: QuestionPrompt? = null,
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

    /** What the header shows, as attache-cli reads it from the agent's own
     * files (and, for Claude, its status line snapshot). */
    @Volatile private var transcriptStatus: AgentStatus? = null

    private fun currentStatus(): AgentStatus? = transcriptStatus

    /** Whether the page is in front of the person: only then does an agent
     * that finished count as seen. */
    @Volatile private var onScreen = false

    fun setOnScreen(value: Boolean) {
        onScreen = value
        if (value) control?.let { tmux -> viewModelScope.launch { markSeen(tmux) } }
    }

    /** The subscriptions' usage, for the drawer's bottom section: what this
     * host said last at once, then followed while the drawer is open. */
    private val _usage = MutableStateFlow(UsageDirectory.get(hostId))
    val usage: StateFlow<Map<String, ProviderUsage>> = _usage.asStateFlow()

    /** What attache-cli last heard from the Mac desktop app, which does the
     * asking: `ok`, `not_running`, `failed`, `unsupported`, or null before
     * the first answer (or from an attache-cli older than 0.3.3). */
    private val _usageDesktop = MutableStateFlow<String?>(null)
    val usageDesktop: StateFlow<String?> = _usageDesktop.asStateFlow()
    private val usageShown = MutableStateFlow(false)

    /** Whether the drawer is open. Usage is followed only then: each opening
     * also has the host run `aiusage`, so the numbers are minutes old at most
     * when looked at, and nothing runs for a drawer nobody opens. */
    fun setUsageShown(value: Boolean) {
        usageShown.value = value
    }

    // The conversation shown, kept by [Conversations] beyond this page.
    @Volatile private var liveConversation: LiveConversation? = null

    @Volatile private var livePath: String? = null


    init {
        RecentAgents.visit(hostId, paneId)
        // The row this page was opened from: the list read it moments ago,
        // so the transcript is followed at once rather than after the
        // agent's script has been run again.
        val known = AgentDirectory.get(hostId)
        val row = known.firstOrNull { it.paneId == paneId }
        _uiState.update { it.copy(agent = row ?: it.agent, allAgents = known) }
        if (row != null) {
            row.transcriptPath?.let {
                transcriptKind = row.kind
                transcriptPath.value = it
            }
        }
        viewModelScope.launch {
            link.connection.collect { c -> _uiState.update { it.copy(connection = c) } }
        }
        viewModelScope.launch {
            link.control.combine(usageShown) { tmux, shown -> tmux.takeIf { shown } }
                .collectLatest { tmux ->
                    if (tmux != null) {
                        followUsage(
                            tmux,
                            onUsage = { p ->
                                _usage.update { it + (p.provider to p) }
                                UsageDirectory.put(hostId, _usage.value)
                            },
                            onDesktop = { _usageDesktop.value = it },
                        )
                    }
                }
        }
        viewModelScope.launch {
            link.control.collectLatest { tmux ->
                control = tmux
                if (tmux == null) return@collectLatest
                _uiState.update { it.copy(linkLost = false) }
                coroutineScope {
                    launch { heartbeat(tmux) }
                    launch { watchPane(tmux) }
                    launch { transcriptPath.collectLatest { path -> follow(tmux, path) } }
                }
            }
        }
    }

    /**
     * Asks the channel something trivial every 15 s. A connection that died
     * quietly (the network went, the phone slept) still has its channel
     * object, so nothing else here noticed, and the page showed a live
     * conversation that could not be reached. No answer in 6 s: the page
     * says the connection was lost and a new one is made.
     */
    private suspend fun heartbeat(tmux: TmuxControl) {
        while (true) {
            delay(HEARTBEAT_MS)
            val answered = try {
                withTimeout(HEARTBEAT_TIMEOUT_MS) { tmux.query("display-message -p ok") }
                true
            } catch (e: Exception) {
                if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                false
            }
            if (!answered) {
                Timber.w("chat: the tmux channel stopped answering; reconnecting")
                _uiState.update { it.copy(linkLost = true) }
                link.declareDead()
                return
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
                // attache-cli types it: copy mode left first, a `/` or `!`
                // command typed key by key, anything else as one bracketed
                // paste, then Enter, holding the pane so a message from the
                // tablet at the same moment cannot interleave, and only into
                // Claude's own input box: pasted into a picker, its Enter
                // would choose something.
                val result = AttacheCli.send(tmux, paneId, body)
                if (!result.sent) {
                    _uiState.update { s ->
                        s.copy(pending = s.pending.filterNot { it.id == pending.id }, notice = AttacheCli.refusal(result.why))
                    }
                    return@launch
                }
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
                // Only while the turn is visibly still running, which the
                // host checks right before pressing it. An Esc that arrives
                // after the turn ended lands on the idle prompt, and two of
                // those open Claude's rewind menu, a step away from cutting
                // the conversation back and losing its prompt cache.
                val result = AttacheCli.key(tmux, paneId, "Escape", "working")
                if (!result.sent) _uiState.update { it.copy(notice = AttacheCli.refusal(result.why)) }
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
     * Answers the menu on screen by its number. attache-cli reads the screen
     * again on the host and presses the digit only if the very same menu is
     * still there: answered at the desk in the meantime, a stray digit would
     * land in the agent's input box instead.
     */
    fun answer(option: ApprovalPrompt.Option) {
        val tmux = control ?: return
        val shown = _uiState.value.prompt ?: return
        viewModelScope.launch {
            try {
                // Claude Code selects on the digit alone; an Enter after it
                // would submit whatever sits in its input box. The host
                // presses it only while this very menu is on screen.
                val result = AttacheCli.key(tmux, paneId, option.number.toString(), "approval:${shown.fingerprint}")
                if (result.sent) {
                    _uiState.update { it.copy(prompt = null) }
                } else {
                    _uiState.update { it.copy(notice = AttacheCli.refusal(result.why)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(notice = "Not sent: ${e.message}") }
            }
        }
    }

    /**
     * Picks an answer to the question on screen (for a multiple choice, ticks
     * it). attache-cli moves the cursor there, reads the screen again, and
     * presses Enter only if the cursor arrived on that very question. What
     * the card shows next comes from the next screen read, not from here.
     */
    fun choose(option: QuestionPrompt.Option) {
        if (option.typed) return
        chooseRow(option.number.toString())
    }

    /** A multiple choice's Submit row: that question is answered, on to the
     * next. */
    fun submitChoices() {
        if (_uiState.value.question?.submitRow != true) return
        chooseRow("submit")
    }

    private fun chooseRow(row: String) {
        val tmux = control ?: return
        val shown = _uiState.value.question ?: return
        viewModelScope.launch {
            try {
                val result = AttacheCli.choose(tmux, paneId, row, shown.fingerprint)
                if (!result.sent) _uiState.update { it.copy(notice = AttacheCli.refusal(result.why)) }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(notice = "Not sent: ${e.message}") }
            }
        }
    }

    /** The other agents, for the drawer and the recent list. The same
     * read-only `list-panes` the agent list sends, a little less often. */
    private var agentsRefresh: Job? = null

    /**
     * The agent switcher's list, read anew when it is opened, and only then:
     * it is every agent on the host, a dozen agy screens read each time, on
     * the channel this page's own reads are waiting on. Until it answers the
     * switcher shows what the list page read.
     */
    fun refreshAllAgents() {
        val tmux = control ?: return
        if (agentsRefresh?.isActive == true) return
        agentsRefresh = viewModelScope.launch {
            try {
                val agents = AttacheCli.agents(tmux) ?: return@launch
                AgentDirectory.put(hostId, agents)
                _uiState.update { it.copy(allAgents = agents) }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Timber.w(e, "agent list refresh failed")
            }
        }
    }

    /**
     * This pane's agent and screen, pushed by attache-cli (`agents --watch
     * --pane`) over an exec channel of its own: the host reads them every
     * half second and sends only what changed, so nothing is polled over
     * the link and the approval dock does not lag the screen.
     */
    private suspend fun watchPane(tmux: TmuxControl) = coroutineScope {
        launch { mirrorLoop(tmux) }
        val cli = tmux.cli()
        if (cli == null) {
            _uiState.update { it.copy(transcript = TranscriptStatus.MISSING) }
            return@coroutineScope
        }
        while (true) {
            val lines = JsonLines()
            try {
                tmux.follow(AttacheCli.watchCommand(cli, paneId)) { chunk ->
                    for (line in lines.feed(chunk)) {
                        val event = try {
                            JSONObject(line)
                        } catch (_: Exception) {
                            continue
                        }
                        when (event.optString("t")) {
                            "agents" -> onAgent(tmux, AttacheCli.agentsEvent(event).firstOrNull())
                            "screen" -> onScreen(AttacheCli.screen(event))
                        }
                    }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return@coroutineScope
            } catch (e: Exception) {
                Timber.w(e, "pane watch failed")
            }
            // The channel is gone; otherwise attache-cli ended on its own, so
            // start it again shortly.
            if (tmux.isClosed) return@coroutineScope
            delay(WATCH_RETRY_MS)
        }
    }

    @Volatile private var lastScreen: ScreenReading? = null

    private suspend fun onAgent(tmux: TmuxControl, agent: AgentPane?) {
        if (agent == null) {
            val stillThere = try {
                tmux.query("display-message -p -t $paneId '#{pane_id}'").firstOrNull()?.trim() == paneId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            if (!stillThere) {
                _uiState.update { it.copy(paneGone = true, prompt = null, question = null) }
                return
            }
        }
        agent?.transcriptPath?.let {
            transcriptKind = agent.kind
            transcriptPath.value = it
        }
        if (agent?.phase == AgentPhase.DONE && onScreen) markSeen(tmux, agent)
        val followed = agent?.kind in FOLLOWED
        if (transcriptPath.value == null && (agent == null || !followed)) {
            _uiState.update { it.copy(transcript = TranscriptStatus.MISSING) }
        }
        _uiState.update { s ->
            s.copy(
                agent = agent ?: s.agent,
                agentExited = agent == null,
                paneGone = false,
                status = currentStatus(),
            )
        }
        lastScreen?.let(::onScreen)
    }

    /**
     * What attache-cli read off the screen. The approval dock and the panel
     * dock follow the agent's phase as before: a menu is answered only while
     * Claude says it waits, and so is a question; a panel is offered only
     * while it neither waits nor works.
     */
    private fun onScreen(reading: ScreenReading) {
        lastScreen = reading
        _uiState.update { s ->
            val phase = s.agent?.phase
            s.copy(
                prompt = if (phase == AgentPhase.NEEDS_YOU) reading.approval else null,
                question = if (phase == AgentPhase.NEEDS_YOU) reading.question else null,
                activity = if (phase == AgentPhase.WORKING) reading.activity else null,
                panel = if (s.agent != null && phase != AgentPhase.NEEDS_YOU && phase != AgentPhase.WORKING) reading.panel else null,
            )
        }
    }

    /**
     * No conversation to read for this agent: no reader for it yet (Codex),
     * none started yet (a fresh agy), or its file could not be followed. Its
     * screen is shown instead, so what it is doing and what it answered can
     * be followed without leaving the chat. The visible screen only, with
     * its colours; a few KB a refresh, and only while this page is open on
     * such an agent. Claude keeps its own messages. Read-only, so polled.
     */
    private suspend fun mirrorLoop(tmux: TmuxControl) {
        while (true) {
            val s = _uiState.value
            val agent = s.agent
            val followed = agent?.kind in FOLLOWED
            val noConversation = transcriptPath.value == null ||
                (s.transcript == TranscriptStatus.MISSING && s.items.isEmpty())
            val mirror = try {
                if (agent != null && !s.agentExited && agent.kind != "claude" && (!followed || noConversation)) {
                    tmux.query("capture-pane -p -e -J -t $paneId").dropLastWhile { it.replace(AnsiText.SEQUENCE, "").isBlank() }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "mirror read failed")
                s.mirror
            }
            if (mirror != s.mirror) _uiState.update { it.copy(mirror = mirror) }
            delay(MIRROR_POLL_MS)
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
                // or open its rewind menu; the host checks it is still open
                // right before pressing.
                // Enter chooses whatever is selected, so it names the panel
                // the person was looking at; the rest only move or close.
                val shown = lastScreen?.panelFingerprint
                val condition = if (key == "Enter") {
                    shown?.let { "panel:$it" } ?: run {
                        _uiState.update { it.copy(notice = "The panel is still being read, so nothing was sent") }
                        return@launch
                    }
                } else {
                    "panel"
                }
                val result = AttacheCli.key(tmux, paneId, key, condition)
                if (!result.sent) _uiState.update { it.copy(panel = null, notice = AttacheCli.refusal(result.why)) }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(notice = "Not sent: ${e.message}") }
            }
        }
    }

    private suspend fun follow(tmux: TmuxControl, path: String?) {
        if (path == null || !AttacheCli.isFollowablePath(path)) {
            _uiState.update { it.copy(transcript = if (path == null) TranscriptStatus.LOADING else TranscriptStatus.MISSING) }
            return
        }
        // Another conversation in the same pane (a `/clear` starts a new
        // file) shows only itself: the one before it leaves the page at once.
        if (livePath != null && livePath != path) {
            _uiState.update { it.copy(items = emptyList(), transcript = TranscriptStatus.LOADING, earlierNotLoaded = false) }
        }
        livePath = path
        val kind = transcriptKind ?: _uiState.value.agent?.kind ?: "claude"
        if (kind !in FOLLOWED) return
        val conversation = Conversations.get(hostId, path, kind, dispatchers.default)
        liveConversation = conversation
        conversation.follow(tmux)
        // Until a new path or a new channel replaces this one.
        conversation.view.collect(::publish)
    }

    /**
     * Puts the history just before what is shown in front of it. Called when
     * the top of the conversation scrolls into view.
     */
    fun loadEarlier() {
        val tmux = control ?: return
        val conversation = liveConversation ?: return
        if (_uiState.value.loadingEarlier) return
        _uiState.update { it.copy(loadingEarlier = true) }
        viewModelScope.launch {
            try {
                if (!conversation.loadEarlier(tmux)) _uiState.update { it.copy(notice = "Could not load earlier messages") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(notice = "Could not load earlier messages") }
            } finally {
                _uiState.update { it.copy(loadingEarlier = false) }
            }
        }
    }

    private fun publish(view: ConversationView) {
        if (view.items.isEmpty() && view.state != TranscriptStatus.LIVE) {
            _uiState.update { it.copy(transcript = view.state, items = emptyList(), earlierNotLoaded = false) }
            return
        }
        val items = view.items
        transcriptStatus = view.status
        val status = currentStatus()
        _uiState.update { s ->
            s.copy(
                items = items,
                transcript = view.state,
                earlierNotLoaded = (view.historyStart ?: 0L) > 0L,
                // A sent message is confirmed once the agent has written it
                // down; until then its bubble stays marked as unconfirmed.
                pending = s.pending.filterNot { PendingMessages.confirmed(it, items) },
                status = status ?: s.status,
            )
        }
    }

    /**
     * Tells the host the person has seen what the agent did, so the list
     * shows it Idle rather than Done: when the page is in front of them and
     * the agent has finished, as it is opened or as the turn ends.
     */
    private suspend fun markSeen(tmux: TmuxControl, agent: AgentPane? = _uiState.value.agent) {
        if (agent == null || agent.phase != AgentPhase.DONE) return
        val conversation = agent.conversation ?: return
        try {
            AttacheCli.seen(tmux, agent.kind, conversation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val HEARTBEAT_MS = 15_000L
        private const val HEARTBEAT_TIMEOUT_MS = 6_000L

        const val PANE_ARG = "pane"
        private const val MIRROR_POLL_MS = 2_000L
        private const val WATCH_RETRY_MS = 3_000L
        private const val CONFIRM_MS = 15_000L

        /** The agents attache-cli reads conversations for. */
        private val FOLLOWED = setOf("claude", "agy")
        private val PANEL_KEYS = setOf("Up", "Down", "Left", "Right", "Enter", "Escape", "Tab")
    }
}
