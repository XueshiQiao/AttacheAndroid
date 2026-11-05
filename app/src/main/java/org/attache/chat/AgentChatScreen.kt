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

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.attache.tmux.AGENT_SLASH_COMMANDS
import org.attache.tmux.AttacheFileInboxDialog
import org.attache.tmux.AttacheFileStorage
import org.attache.tmux.FileDownloadProgress
import org.attache.tmux.PendingImageAttachment
import org.connectbot.ui.LocalTerminalManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    onNavigateBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenAgent: (AgentPane) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentChatViewModel = hiltViewModel(),
) {
    val terminalManager = LocalTerminalManager.current
    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAppearance by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    if (showStatus) {
        state.status?.let { st -> StatusSheet(st, agent = state.agent, onDismiss = { showStatus = false }) } ?: run { showStatus = false }
    }
    if (showAppearance) ChatAppearanceDialog(onDismiss = { showAppearance = false })
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }
    val openTerminal = { viewModel.openInTerminal(onOpenTerminal) }
    val follow = remember { FollowState() }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        viewModel.attachImages(context.contentResolver, uris)
    }

    // The file inbox is the terminal mode's own dialog, on the same channel:
    // received files, the host's recent files and folders, and downloads.
    var showFiles by remember { mutableStateOf(false) }
    val tmux by viewModel.tmuxControl.collectAsStateWithLifecycle()
    var download by remember { mutableStateOf<FileDownloadProgress?>(null) }
    LaunchedEffect(tmux) {
        val control = tmux ?: return@LaunchedEffect
        launch { control.activeDownload.collect { download = it } }
        launch {
            control.completedDownloads.collect { file ->
                val result = snackbar.showSnackbar("Saved ${file.name}", actionLabel = "Open", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    AttacheFileStorage.openFileExternally(context, file) { viewModel.reportNotice(it) }
                }
            }
        }
    }
    if (showFiles) {
        AttacheFileInboxDialog(
            control = tmux,
            activeDownload = download,
            onStartDownload = { path -> tmux?.enqueueDownload(path) },
            onCancelDownload = {
                tmux?.cancelActiveDownload()
                download = null
            },
            onDismiss = { showFiles = false },
            onShowMessage = viewModel::reportNotice,
        )
    }

    // The other agents, a swipe from the left edge or a tap on the title
    // away, as the terminal mode's sidebar is: switching should not mean
    // going back to the list first.
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val visits by RecentAgents.visits.collectAsStateWithLifecycle()
    val recents = remember(visits, state.allAgents) {
        RecentAgents.offer(visits[viewModel.hostId].orEmpty(), state.allAgents, current = viewModel.paneId)
    }
    val switchTo = { agent: AgentPane ->
        drawerScope.launch { drawer.close() }
        if (agent.paneId != viewModel.paneId) onOpenAgent(agent)
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp)) {
                AgentDrawer(agents = state.allAgents, current = viewModel.paneId, onPick = switchTo)
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            // The bottom is kept clear by hand (bottomSafeInset), which also
            // covers the systems whose navigation bar inset reads zero.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { drawerScope.launch { drawer.open() } },
                            ) {
                                Text(
                                    state.agent?.title ?: "Agent",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Other agents")
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = state.status != null) { showStatus = true },
                            ) {
                                state.agent?.let {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(phaseColor(it.phase), CircleShape),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                val status = state.status
                                if (status != null) {
                                    StatusLine(status)
                                } else {
                                    Text(
                                        subtitle(state),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAppearance = true }) {
                            Icon(Icons.Outlined.Palette, contentDescription = "Appearance")
                        }
                        IconButton(onClick = openTerminal) {
                            Icon(Icons.Filled.Terminal, contentDescription = "Open terminal")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .padding(bottom = bottomSafeInset()),
            ) {
                Box(Modifier.weight(1f)) {
                    when {
                        state.connection == ChatConnection.NEEDS_TERMINAL -> Centered(
                            "The connection is asking for a password or a host key.",
                            action = "Answer in terminal" to openTerminal,
                        )

                        state.connection == ChatConnection.DISCONNECTED -> Centered(
                            "Disconnected.",
                            action = "Reconnect" to viewModel::reconnect,
                        )

                        state.paneGone -> Centered("This pane has been closed.")

                        state.mirror != null -> ScreenMirror(state.mirror.orEmpty(), onOpenTerminal = openTerminal)

                        state.transcript == TranscriptStatus.MISSING && state.items.isEmpty() -> Centered(
                            "This agent's conversation could not be found.",
                            action = "Open terminal" to openTerminal,
                        )

                        state.transcript == TranscriptStatus.LOADING && state.items.isEmpty() -> Centered("Loading…", progress = true)

                        else -> Conversation(state, follow, onLoadEarlier = viewModel::loadEarlier)
                    }
                }
                if (state.agentExited && !state.paneGone) {
                    Text(
                        "The agent is no longer running in this pane.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.agent?.phase == AgentPhase.WORKING && !state.agentExited) {
                    WorkingBar(state.activity, sinceEpochSec = state.agent?.sinceEpochSec)
                }
                state.panel?.let { panel ->
                    PanelDock(panel, onKey = viewModel::panelKey)
                }
                if (state.agent?.phase == AgentPhase.NEEDS_YOU) {
                    val prompt = state.prompt
                    if (prompt != null) {
                        PromptDock(prompt, onAnswer = viewModel::answer)
                    } else {
                        UnreadablePromptDock(onOpenTerminal = openTerminal)
                    }
                }
                Composer(
                    enabled = state.connection == ChatConnection.READY && !state.paneGone && !state.agentExited &&
                        state.agent?.phase != AgentPhase.NEEDS_YOU && state.panel == null,
                    agentName = state.agent?.kind?.replaceFirstChar { it.uppercase() } ?: "the agent",
                    images = state.images,
                    onPickImages = {
                        try {
                            imagePicker.launch("image/*")
                        } catch (_: ActivityNotFoundException) {
                            viewModel.reportNotice("No image picker found")
                        }
                    },
                    onRemoveImage = viewModel::removeImage,
                    onOpenFiles = { showFiles = true },
                    recents = recents,
                    onPickRecent = switchTo,
                    working = state.agent?.phase == AgentPhase.WORKING && !state.agentExited && state.panel == null,
                    onStop = viewModel::stop,
                    onSend = { text ->
                        // What the person just sent is always brought into view,
                        // wherever they had scrolled to.
                        follow.stick = true
                        viewModel.send(text)
                    },
                )
            }
        }
    }
}

private fun subtitle(state: AgentChatUiState): String {
    val agent = state.agent ?: return "Chat · experimental"
    val phase = when (agent.phase) {
        AgentPhase.NEEDS_YOU -> "needs you"
        AgentPhase.WORKING -> "working"
        AgentPhase.DONE -> "done"
        AgentPhase.IDLE -> "idle"
    }
    return listOf(agent.kind.ifEmpty { "agent" }, agent.sessionName, agent.windowName, phase).joinToString(" · ")
}

@Composable
internal fun Conversation(state: AgentChatUiState, follow: FollowState, onLoadEarlier: () -> Unit) {
    // Newest at the bottom: a reversed layout, so index 0 is the last row.
    val rows = remember(state.items, state.pending, state.earlierNotLoaded) {
        buildList<Any> {
            if (state.earlierNotLoaded) add(EarlierMarker)
            addAll(ToolGroups.group(PendingMessages.interleave(state.items, state.pending)))
        }.asReversed()
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Whether to follow new rows is decided when the person's own scroll
    // ends, never by what an insertion did to the position: a lazy list keeps
    // the row that was in view where it is, so a row added below it lands
    // off screen and the list no longer reads as being at the bottom.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow.stick = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80
        }
    }
    val newest = rows.firstOrNull()?.let(::rowKey)
    LaunchedEffect(newest) {
        if (follow.stick && rows.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            // Bottom: a short conversation sits by the input box, as in any
            // chat, rather than at the top of an empty screen.
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows, key = ::rowKey) { row ->
                when (row) {
                    is ChatItem.UserText -> UserBubble(
                        row.text,
                        caption = if (row.queued) "Sent while it was working" else null,
                        modifier = Modifier.padding(top = TURN_GAP),
                    )

                    is PendingMessage -> UserBubble(
                        row.text,
                        caption = if (row.unconfirmed) "Not confirmed yet — check the terminal" else "Sending…",
                        faded = true,
                        modifier = Modifier.padding(top = TURN_GAP),
                    )

                    is ToolGroup -> ToolGroupCard(row)

                    is ChatItem.AgentText -> MarkdownText(row.text, Modifier.fillMaxWidth())

                    is ChatItem.Thinking -> ThinkingRow(row.text)

                    is ChatItem.CommandOutput -> CommandOutputCard(row)

                    is ChatItem.ToolStep -> ToolRow(row)

                    is ChatItem.Note -> Text(
                        row.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    EarlierMarker -> EarlierRow(loading = state.loadingEarlier, loadedCount = state.items.size, onLoad = onLoadEarlier)
                }
            }
        }
        if (!follow.stick) {
            SmallFloatingActionButton(
                onClick = {
                    follow.stick = true
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest")
            }
        }
    }
}

/** Whether the conversation follows new rows to the bottom. */
@Stable
class FollowState {
    var stick by mutableStateOf(true)
}

private fun rowKey(row: Any): String = when (row) {
    is ChatItem -> row.key
    is PendingMessage -> "pending-${row.id}"
    is ToolGroup -> row.key
    else -> "earlier"
}

/** Consecutive tool calls, drawn as one card so what the agent did stands
 * apart from what it said. */
internal data class ToolGroup(val key: String, val steps: List<ChatItem.ToolStep>)

internal object ToolGroups {
    /** Keyed by its first step, so a group that grows keeps its place. */
    fun group(rows: List<Any>): List<Any> {
        val out = ArrayList<Any>(rows.size)
        var run = ArrayList<ChatItem.ToolStep>()
        fun close() {
            if (run.isNotEmpty()) out.add(ToolGroup("group-${run.first().key}", run))
            run = ArrayList()
        }
        for (row in rows) {
            if (row is ChatItem.ToolStep) {
                run.add(row)
            } else {
                close()
                out.add(row)
            }
        }
        close()
        return out
    }
}

@Composable
private fun ToolGroupCard(group: ToolGroup) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                // A rail down the marks, joining the steps into one run.
                .drawBehind {
                    val x = 7.dp.toPx()
                    drawLine(rail, Offset(x, 12.dp.toPx()), Offset(x, size.height - 12.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                },
        ) {
            for (step in group.steps) ToolRow(step)
        }
    }
}

private object EarlierMarker

@Composable
private fun UserBubble(text: String, modifier: Modifier = Modifier, caption: String? = null, faded: Boolean = false) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = if (faded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        caption?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolRow(step: ChatItem.ToolStep) {
    var open by rememberSaveable(step.key) { mutableStateOf(false) }
    val mark = when {
        step.result == null -> "…"
        step.isError -> "✗"
        else -> "✓"
    }
    val markColor = when {
        step.result == null -> MaterialTheme.colorScheme.onSurfaceVariant
        step.isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { open = !open },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // On a disc of the card's colour, so the rail runs behind it.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Text(mark, color = markColor, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(6.dp))
            Text(step.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(
                step.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Shown without a tap, as the terminal shows them: what a file
        // change changed, and the first lines a command printed. Reads,
        // searches and the rest stay one line, as they do there.
        val output = step.result?.takeIf { step.name == "Bash" && it.isNotBlank() }?.trimEnd()
        when {
            open -> {
                Spacer(Modifier.height(6.dp))
                if (step.detail.isNotBlank()) CodeBlock(step.detail)
                if (step.diff.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    DiffBlock(step.diff)
                }
                step.result?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(it.lineSequence().take(RESULT_LINES).joinToString("\n"))
                }
            }

            step.diff.isNotEmpty() -> {
                Spacer(Modifier.height(4.dp))
                DiffBlock(step.diff.take(DIFF_PREVIEW))
                if (step.diff.size > DIFF_PREVIEW) MoreLines(step.diff.size - DIFF_PREVIEW)
            }

            output != null -> {
                val lines = output.lines()
                Text(
                    lines.take(OUTPUT_PREVIEW).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = OUTPUT_PREVIEW,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                )
                if (lines.size > OUTPUT_PREVIEW) MoreLines(lines.size - OUTPUT_PREVIEW)
            }
        }
    }
}

@Composable
private fun MoreLines(count: Int) {
    Text(
        "$count more line${if (count == 1) "" else "s"}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 18.dp, top = 2.dp),
    )
}

/** Red for removed, green for added, as the terminal draws a change. */
@Composable
private fun DiffBlock(lines: List<DiffLine>) {
    val removed = Color(0x33E5484D)
    val added = Color(0x3330A46C)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Every row as wide as the widest, and never narrower than the card,
        // so a change reads as a band rather than as ragged highlights.
        BoxWithConstraints {
            val cardWidth = maxWidth
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = cardWidth),
            ) {
                for (line in lines) {
                    val background = when (line.kind) {
                        '-' -> removed
                        '+' -> added
                        else -> Color.Transparent
                    }
                    Text(
                        if (line.kind == '…') "  ⋯" else "${line.kind} ${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        color = if (line.kind == '…') MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(background)
                            .padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

/** What a slash command or a `!` command printed, under the command. */
@Composable
private fun CommandOutputCard(output: ChatItem.CommandOutput) {
    val cardColor = if (output.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    var open by rememberSaveable(output.key) { mutableStateOf(false) }
    val lines = output.text.lines()
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open },
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cardColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    AnsiText.of(
                        if (open) output.raw else output.raw.lines().take(COMMAND_PREVIEW).joinToString("\n"),
                        background = cardColor,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
        if (!open && lines.size > COMMAND_PREVIEW) MoreLines(lines.size - COMMAND_PREVIEW)
    }
}

/** Thinking, where Claude Code kept its text: the opening lines, the rest
 * on a tap. Most current transcripts keep none, and those show nothing. */
@Composable
private fun ThinkingRow(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open },
    ) {
        Text("Thinking", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else THINKING_PREVIEW,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A panel Claude has open over its input box, as it looks on screen, with
 * the keys such a panel takes. Every panel gets the same treatment, so a
 * command that opens a new one needs nothing here.
 */
@Composable
private fun PanelDock(lines: List<String>, onKey: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Claude has a panel open",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
            ) {
                Text(
                    lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, key) in listOf("↑" to "Up", "↓" to "Down", "Enter" to "Enter", "Tab" to "Tab", "Esc" to "Escape")) {
                    FilledTonalButton(
                        onClick = { onKey(key) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

/** The menu the agent is waiting on, as buttons. An answer with a risk
 * takes a second tap that says what the risk is. */
@Composable
private fun PromptDock(prompt: ApprovalPrompt, onAnswer: (ApprovalPrompt.Option) -> Unit) {
    var confirming by remember(prompt.fingerprint) { mutableStateOf<ApprovalPrompt.Option?>(null) }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (prompt.subject.isNotEmpty()) {
                CodeBlock(prompt.subject.joinToString("\n"))
            }
            Text(prompt.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            for (option in prompt.options) {
                FilledTonalButton(
                    onClick = { if (option.risk != null) confirming = option else onAnswer(option) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${option.number}. ${option.label}" + if (option.risk != null) "  ⚠" else "",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    confirming?.let { option ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("${option.number}. ${option.label}") },
            text = { Text("This ${option.risk}. Answer with it anyway?") },
            confirmButton = {
                Button(onClick = {
                    confirming = null
                    onAnswer(option)
                }) { Text("Answer") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UnreadablePromptDock(onOpenTerminal: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Waiting for you, with a question this screen can't answer yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onOpenTerminal) { Text("Terminal") }
        }
    }
}

/**
 * The top of the loaded history. Scrolling it into view is the request for
 * more, so there is nothing to tap unless loading failed and the person
 * wants to try again.
 */
@Composable
private fun EarlierRow(loading: Boolean, loadedCount: Int, onLoad: () -> Unit) {
    // Again after each load that left the top still in view, so a short
    // stretch of history keeps filling the screen; not again when a load
    // added nothing, which would only repeat the same request.
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(loadedCount) { load() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onLoad) { Text("Load earlier messages") }
        }
    }
}

/**
 * Claude's own status line, `✻ Jitterbugging… (13s · ↓ 649 tokens)`, right
 * above the input box where the eye already is. The screen is read every two
 * seconds, so the spinner turns here, on the phone, to show the bar is alive
 * between reads. When the line cannot be read it still says the agent is at
 * work, and for how long by its own clock.
 */
@Composable
internal fun WorkingBar(activity: AgentActivity?, sinceEpochSec: Long?) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val frame by transition.animateValue(
        initialValue = 0,
        targetValue = SPINNER.size,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(tween(durationMillis = SPINNER.size * 110, easing = LinearEasing)),
        label = "frame",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val detail = activity?.detail ?: sinceEpochSec?.let { "for ${elapsed(nowSec - it)}" }
    // One quiet line on the page's own background, the way Claude Code
    // draws it above its prompt; stopping lives in the send button.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
    ) {
        Text(
            SPINNER[frame.coerceIn(0, SPINNER.size - 1)],
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp),
        )
        Text(
            activity?.verb ?: "Working…",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.alpha(pulse),
        )
        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SPINNER = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")

private fun elapsed(seconds: Long): String = when {
    seconds < 0 -> "0s"
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
}

@Composable
internal fun Composer(
    enabled: Boolean,
    agentName: String,
    images: List<PendingImageAttachment>,
    onPickImages: () -> Unit,
    onRemoveImage: (Long) -> Unit,
    onOpenFiles: () -> Unit,
    onSend: (String) -> Unit,
    working: Boolean = false,
    onStop: () -> Unit = {},
    recents: List<Pair<AgentPane, Long>> = emptyList(),
    onPickRecent: (AgentPane) -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    var commandsOpen by remember { mutableStateOf(false) }
    var recentOpen by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop this turn?") },
            text = { Text("The same as pressing Esc in the terminal. You can tell it to carry on afterwards.") },
            confirmButton = {
                Button(onClick = {
                    confirmStop = false
                    onStop()
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
    val uploading = images.any { it.remotePath == null }
    val canSend = enabled && !uploading && (text.isNotBlank() || images.isNotEmpty())
    Column(Modifier.fillMaxWidth()) {
        // The terminal mode's tool keys that mean something here.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 6.dp, end = 6.dp, top = 4.dp),
        ) {
            ToolChip("Image", Icons.Filled.Image, enabled, onPickImages)
            ToolChip("Files", Icons.Filled.Folder, true, onOpenFiles)
            // The terminal mode's recent windows, as agents: the last chats
            // opened, a tap away.
            Box {
                ToolChip("Recent", Icons.Filled.History, recents.isNotEmpty()) { recentOpen = true }
                DropdownMenu(expanded = recentOpen, onDismissRequest = { recentOpen = false }) {
                    val nowMs = System.currentTimeMillis()
                    for ((agent, atMs) in recents) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(phaseColor(agent.phase), CircleShape),
                                )
                            },
                            text = {
                                Column {
                                    Text(agent.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${agent.sessionName} · ${agent.windowName} · ${age((nowMs - atMs) / 1000)} ago",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            },
                            onClick = {
                                recentOpen = false
                                onPickRecent(agent)
                            },
                        )
                    }
                }
            }
            Box {
                ToolChip("Commands", null, enabled) { commandsOpen = true }
                DropdownMenu(expanded = commandsOpen, onDismissRequest = { commandsOpen = false }) {
                    for (command in AGENT_SLASH_COMMANDS) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(command.text, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        command.summary + (command.only?.let { " · $it only" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                commandsOpen = false
                                // Into the box rather than sent, so arguments
                                // can follow and nothing runs by accident.
                                text = command.text + " "
                            },
                        )
                    }
                }
            }
        }
        if (images.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                for (image in images) ImageThumb(image, onRemove = { onRemoveImage(image.id) })
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = { Text(if (uploading) "Uploading images…" else "Message $agentName…") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // While the agent works and nothing is typed, the send button is
            // the stop button, as in any chat app; typing turns it back, and
            // a message sent then waits in Claude's queue.
            if (working && text.isBlank() && images.isEmpty()) {
                FilledTonalIconButton(onClick = { confirmStop = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(
                    enabled = canSend,
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        } else {
            Text("/", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ImageThumb(image: PendingImageAttachment, onRemove: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        image.thumbnail?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (image.remotePath == null) {
            LinearProgressIndicator(
                progress = { image.progressFraction },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
        ) {
            Text("×", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Centered(text: String, progress: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        if (progress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}

private const val RESULT_LINES = 40

/** The space above each message of the person's: where a new turn begins. */
private val TURN_GAP = 14.dp
private const val DIFF_PREVIEW = 12
private const val OUTPUT_PREVIEW = 4
private const val COMMAND_PREVIEW = 30
private const val THINKING_PREVIEW = 4

/** Every agent, by what it needs, with the one on screen marked. */
@Composable
internal fun AgentDrawer(agents: List<AgentPane>, current: String, onPick: (AgentPane) -> Unit) {
    val nowSec = System.currentTimeMillis() / 1000
    val groups = agents.groupBy { it.phase }
    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + bottomSafeInset())) {
        item {
            Text(
                "Agents",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        for (phase in AgentPhase.entries) {
            val inPhase = groups[phase].orEmpty()
            if (inPhase.isEmpty()) continue
            item(key = "h-$phase") {
                Text(
                    "${phaseLabel(phase)}  ${inPhase.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(inPhase, key = { it.paneId }) { agent ->
                NavigationDrawerItem(
                    selected = agent.paneId == current,
                    onClick = { onPick(agent) },
                    icon = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(phaseColor(agent.phase), CircleShape),
                        )
                    },
                    label = {
                        Column {
                            Text(agent.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(agent.sessionName, agent.windowName).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    badge = agent.sinceEpochSec?.let { since -> { Text(age(nowSec - since), style = MaterialTheme.typography.labelSmall) } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/**
 * The agent's own status line in a row: model, effort, how full the context
 * is and how long the prompt cache stays warm. Context turns amber past 60%
 * and red past 85%, where compaction and its cost are near.
 */
@Composable
internal fun StatusLine(status: AgentStatus) {
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        val parts = ArrayList<Pair<String, Color?>>()
        // "Gemini 4 Argon (Medium)" carries its effort in brackets, and the
        // effort follows anyway; the row is too narrow to say it twice.
        status.model?.let { parts += it.substringBefore(" (").trim() to null }
        status.effort?.let { parts += it to null }
        status.contextPercent?.let { pct ->
            val color = when {
                pct >= 85 -> Color(0xFFE5484D)
                pct >= 60 -> Color(0xFFF5A524)
                else -> null
            }
            parts += "$pct%" to color
        }
        cacheText(status, nowSec)?.let { parts += it to null }
        parts.forEachIndexed { i, (part, color) ->
            if (i > 0) append(" · ")
            if (color != null) withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun cacheText(status: AgentStatus, nowSec: Long): String? {
    val warm = status.cacheWarm ?: return null
    val left = status.cacheExpiresAt?.minus(nowSec)
    return when {
        !warm || (left != null && left <= 0) -> "cache cold"
        left != null -> "cache ${age(left)}"
        else -> "cache warm"
    }
}

/** Everything the status line carries, for when the row is not enough. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSheet(status: AgentStatus, agent: AgentPane?, onDismiss: () -> Unit) {
    val nowSec = System.currentTimeMillis() / 1000
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(status.model ?: "Agent", style = MaterialTheme.typography.titleLarge)
            val rows = buildList {
                add("Agent" to listOfNotNull(status.source, status.version).joinToString(" "))
                agent?.let { add("Where" to "${it.sessionName} · ${it.windowName}") }
                status.effort?.let { add("Effort" to it) }
                status.thinking?.let { add("Thinking" to if (it) "on" else "off") }
                status.fastMode?.let { add("Fast mode" to if (it) "on" else "off") }
                status.contextPercent?.let { pct ->
                    val of = listOfNotNull(status.contextTokens?.let(::tokens), status.contextWindow?.let(::tokens)).joinToString(" of ")
                    add("Context" to "$pct% used" + if (of.isNotEmpty()) " · $of" else "")
                }
                cacheText(status, nowSec)?.let { c ->
                    add("Prompt cache" to c + (status.cacheHitRatio?.let { " · ${Math.round(it * 100)}% hits" } ?: ""))
                }
                status.costUsd?.let { add("Cost" to "$" + String.format(java.util.Locale.US, "%.2f", it)) }
                if (status.linesAdded != null || status.linesRemoved != null) {
                    add("Lines" to "+${status.linesAdded ?: 0} −${status.linesRemoved ?: 0}")
                }
                for (limit in status.limits) {
                    val resets = limit.resetsAt?.minus(nowSec)?.takeIf { it > 0 }?.let { " · resets in ${age(it)}" } ?: ""
                    add("${limit.label} limit" to "${limit.usedPercent}% used$resets")
                }
            }
            for ((label, value) in rows) {
                Row {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}

/**
 * The pane as it looks now, for an agent whose conversation the phone cannot
 * read: its own drawing, colours kept, newest at the bottom. Read only;
 * typing still goes through the composer below.
 */
@Composable
internal fun ScreenMirror(lines: List<String>, onOpenTerminal: () -> Unit) {
    val vertical = rememberScrollState()
    LaunchedEffect(lines) { vertical.scrollTo(vertical.maxValue) }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                "Live screen · read only",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenTerminal) { Text("Open terminal") }
        }
        val card = MaterialTheme.colorScheme.surfaceContainer
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = card,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                AnsiText.of(lines.joinToString("\n"), background = card),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier
                    .verticalScroll(vertical)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}
