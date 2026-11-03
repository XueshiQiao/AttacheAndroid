package org.attache.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalDimensions
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs

/** Runs a tmux command from a tap. A refusal is not an error worth a
 * dialog: the window or pane closed under the finger, and tmux's next
 * notification redraws everything from its own state. */
private fun CoroutineScope.tmuxAction(name: String, action: suspend () -> Unit) {
    launch {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("%s failed: %s", name, e.message)
        }
    }
}

/** One chip per tmux window, sized to sit in the title bar. A tap sends
 * `select-window` and changes nothing locally: the chip moves when tmux
 * says the active window moved. */
@Composable
fun TmuxWindowChips(control: TmuxControl, modifier: Modifier = Modifier) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    LazyRow(
        contentPadding = PaddingValues(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.testTag("tmuxWindowStrip"),
    ) {
        items(state.windows, key = { it.id }) { window ->
            FilterChip(
                selected = window.active,
                onClick = { scope.tmuxAction("select-window") { control.selectWindow(window.id) } },
                label = { Text("${window.index}: ${window.name}", maxLines = 1) },
                modifier = Modifier.height(32.dp).testTag("tmuxWindow-${window.id}"),
            )
        }
    }
}

/** The tmux operations that otherwise need the prefix key, one tap away.
 * Open state belongs to the caller, which owns the keyboard and focus. */
@Composable
fun TmuxActionsButton(
    control: TmuxControl,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    trigger: @Composable (onClick: () -> Unit) -> Unit = { onClick ->
        TextButton(onClick = onClick, modifier = Modifier.testTag("tmuxActions")) { Text("tmux") }
    },
) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun run(name: String, action: suspend () -> Unit) {
        onDismiss()
        scope.tmuxAction(name, action)
    }
    Box(modifier) {
        trigger(onOpen)
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text("New window") }, onClick = { run("new-window") { control.newWindow() } })
            DropdownMenuItem(text = { Text("Split side by side") }, onClick = { run("split") { control.split(sideBySide = true) } })
            DropdownMenuItem(text = { Text("Split top and bottom") }, onClick = { run("split") { control.split(sideBySide = false) } })
            if (state.panes.size > 1) {
                DropdownMenuItem(text = { Text("Next pane") }, onClick = { run("select-pane") { control.nextPane() } })
                DropdownMenuItem(
                    text = { Text(if (state.zoomed) "Restore pane" else "Zoom pane") },
                    onClick = { run("resize-pane -Z") { control.toggleZoom() } },
                )
            }
            if (state.activePane?.inMode == true) {
                DropdownMenuItem(text = { Text("Leave scroll mode") }, onClick = { run("cancel") { control.exitCopyMode() } })
            }
        }
    }
}

/**
 * A vertical swipe scrolls tmux's history, and a tap selects the pane under
 * the finger. Both go to tmux as commands: the terminal library sends no
 * mouse events, and its own scrollback is empty under tmux, which draws on
 * the alternate screen.
 *
 * Read on the Initial pass so the swipe is taken before the terminal's own
 * scroll sees it. Left alone: two-finger gestures (zoom), horizontal swipes
 * (ConnectBot switches sessions with those), and anything that started as a
 * long press (text selection).
 */
fun Modifier.tmuxTouch(
    control: TmuxControl,
    selectionActive: () -> Boolean = { false },
    dimensions: () -> TerminalDimensions?,
    onDismissKeyboard: (() -> Unit)? = null,
): Modifier =
    pointerInput(control) {
        val slop = viewConfiguration.touchSlop
        val dismissThresholdPx = kotlin.math.max(slop * 2.5f, 24.dp.toPx())
        val longPress = viewConfiguration.longPressTimeoutMillis
        val scope = CoroutineScope(currentCoroutineContext())
        var fling: kotlinx.coroutines.Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A new touch stops a fling still running, as in any list.
            fling?.cancel()
            fling = null
            // While text selection is active, let all handle drags and taps
            // reach the terminal without recreating the pointerInput modifier.
            if (selectionActive()) return@awaitEachGesture
            val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
            val dims = dimensions()
            val rows = dims?.rows?.takeIf { it > 0 } ?: return@awaitEachGesture
            val columns = dims.columns.takeIf { it > 0 } ?: return@awaitEachGesture
            val lineHeight = size.height.toFloat() / rows
            val scrollStepPx = (lineHeight * 0.55f).coerceAtLeast(18f)
            val cellWidth = size.width.toFloat() / columns
            var scrolling = false
            var keyboardDismissedInGesture = false
            var target: TmuxPane? = null
            var moved = false
            var pending = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (selectionActive()) return@awaitEachGesture
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                val total = change.position - down.position
                if (!change.pressed) {
                    if (scrolling) {
                        val pane = target ?: return@awaitEachGesture
                        val v = velocity.calculateVelocity().y
                        val column = (down.position.x / cellWidth).toInt() - pane.left + 1
                        val row = (down.position.y / lineHeight).toInt() - pane.top + 1
                        // Momentum: keep scrolling at the release speed and
                        // let it decay, so a flick travels like it does in
                        // any native list.
                        if (abs(v) > FLING_MIN_VELOCITY) {
                            fling = scope.launch {
                                var speed = v
                                var carry = pending
                                while (abs(speed) > FLING_STOP_VELOCITY) {
                                    kotlinx.coroutines.delay(FLING_FRAME_MS)
                                    carry += speed * FLING_FRAME_MS / 1000f
                                    val lines = (carry / scrollStepPx).toInt()
                                    if (lines != 0) {
                                        control.scroll(pane.id, lines, column, row)
                                        carry -= lines * scrollStepPx
                                    }
                                    speed *= FLING_DECAY
                                }
                            }
                        }
                    }
                    if (!moved && !scrolling && change.uptimeMillis - down.uptimeMillis < longPress) {
                        val column = (down.position.x / cellWidth).toInt()
                        val row = (down.position.y / lineHeight).toInt()
                        scope.tmuxAction("select-pane") { control.selectPaneAt(column, row) }
                    }
                    return@awaitEachGesture
                }
                var justStartedScrolling = false
                if (!scrolling) {
                    // Held still past the long-press timeout: the terminal
                    // has started a selection. Checked before this event's
                    // movement counts, or a first move past the slop would
                    // hide that the finger had been still until then.
                    if (!moved && change.uptimeMillis - down.uptimeMillis >= longPress) return@awaitEachGesture
                    if (abs(total.x) > slop || abs(total.y) > slop) moved = true
                    if (abs(total.y) > slop && abs(total.y) > abs(total.x)) {
                        // The pane under the finger, as a wheel would scroll.
                        target = control.paneAt(
                            (down.position.x / cellWidth).toInt(),
                            (down.position.y / lineHeight).toInt(),
                        ) ?: return@awaitEachGesture
                        scrolling = true
                        justStartedScrolling = true
                    } else if (moved) {
                        return@awaitEachGesture
                    }
                }
                velocity.addPosition(change.uptimeMillis, change.position)
                if (scrolling) {
                    val deltaY = change.position.y - change.previousPosition.y
                    if (
                        !keyboardDismissedInGesture &&
                        deltaY > 0f &&
                        total.y > dismissThresholdPx &&
                        abs(total.y) > abs(total.x) * 1.5f
                    ) {
                        keyboardDismissedInGesture = true
                        onDismissKeyboard?.invoke()
                    }
                    // Finger down is older text, which is tmux's scroll-up.
                    // Seed with total.y on the frame that crosses touchSlop so
                    // initial travel is not lost as a dead zone.
                    if (justStartedScrolling) {
                        pending = total.y
                    } else {
                        pending += deltaY
                    }
                    val lines = (pending / scrollStepPx).toInt()
                    if (lines != 0) {
                        val pane = target ?: return@awaitEachGesture
                        control.scroll(
                            pane.id,
                            lines,
                            column = (down.position.x / cellWidth).toInt() - pane.left + 1,
                            row = (down.position.y / lineHeight).toInt() - pane.top + 1,
                        )
                        pending -= lines * scrollStepPx
                    }
                    change.consume()
                }
            }
        }
    }

private const val FLING_FRAME_MS = 16L
private const val FLING_DECAY = 0.95f
private const val FLING_MIN_VELOCITY = 400f
private const val FLING_STOP_VELOCITY = 60f

/**
 * Host entry shown in the first tier of [AttacheSidebar].
 */
data class AttacheHostEntry(
    val id: UUID,
    val name: String,
    val subtitle: String,
    val connected: Boolean,
    val current: Boolean,
)

/**
 * Unified Attaché sidebar content used in both orientations. It renders inside
 * the floating glass panel of [AttacheFloatingChrome] (docs/chrome-unify scheme
 * D), which owns the panel surface, its header (the merged title capsule) and
 * the single theme toggle:
 * - In landscape mode, the panel floats docked on the left of the terminal.
 * - In portrait mode, the panel floats over the terminal above a scrim when the
 *   title capsule is tapped.
 *
 * Displays a three-tier hierarchy: Hosts -> Sessions -> Windows.
 */
@Composable
fun AttacheSidebar(
    control: TmuxControl,
    hosts: List<AttacheHostEntry>,
    onSelectHost: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    onDismiss: (() -> Unit)? = null,
) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val collapsedSessions = remember { mutableStateMapOf<String, Boolean>() }
    var hasScrolledToActiveOnOpen by remember { mutableStateOf(false) }
    val palette = attacheGlassPalette(isDark)

    LaunchedEffect(state.sessionId, state.sessions.size) {
        val sid = state.sessionId ?: return@LaunchedEffect
        if (state.sessions.isEmpty() || hasScrolledToActiveOnOpen) return@LaunchedEffect
        collapsedSessions[sid] = false
        val targetIndex = computeActiveWindowScrollIndex(
            hostsCount = 0,
            sessions = state.sessions,
            currentSessionId = sid,
            collapsedSessions = collapsedSessions,
        )
        if (targetIndex != null) {
            hasScrolledToActiveOnOpen = true
            listState.scrollToItem((targetIndex - 2).coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxHeight().testTag("attacheSidebar")) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("tmuxSessionList"),
        ) {
            item(key = "header-sessions") {
                SidebarSectionHeader(text = "Session · Window", palette = palette) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.button)
                            .clickable {
                                onDismiss?.invoke()
                                scope.tmuxAction("new-window") { control.newWindow() }
                            }
                            .padding(horizontal = 8.dp)
                            .testTag("tmuxSidebarNewWindow"),
                    ) {
                        Text(
                            text = "＋",
                            color = palette.accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            state.sessions.forEach { session ->
                val isCurrentSession = session.id == state.sessionId
                val isCollapsed = collapsedSessions[session.id] == true
                item(key = "session-${session.id}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, top = 4.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                collapsedSessions[session.id] = false
                                onDismiss?.invoke()
                                scope.tmuxAction("switch-client") { control.switchTo(session.id) }
                            }
                            .padding(end = 8.dp)
                            .testTag("tmuxSession-${session.id}"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 26.dp, height = 30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    collapsedSessions[session.id] = !isCollapsed
                                }
                                .testTag("tmuxSessionToggle-${session.id}"),
                        ) {
                            Text(
                                text = if (isCollapsed) "▸" else "▾",
                                color = palette.faint,
                                fontSize = 10.sp,
                            )
                        }
                        Text(
                            text = session.name.uppercase(),
                            color = if (isCurrentSession) palette.ink else palette.muted,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${session.windows.size}",
                            color = palette.faint,
                            fontSize = 11.5.sp,
                        )
                    }
                }

                if (!isCollapsed) {
                    items(session.windows, key = { "win-${session.id}-${it.id}" }) { window ->
                        val here = isCurrentSession && window.active
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (here) palette.selectedBg else Color.Transparent)
                                .clickable {
                                    onDismiss?.invoke()
                                    scope.tmuxAction("switch-client") { control.switchTo(window.id) }
                                }
                                .padding(start = 12.dp, end = 10.dp)
                                .testTag("tmuxSessionWindow-${window.id}"),
                        ) {
                            Text(
                                text = "${window.index}",
                                color = if (here) palette.accent.copy(alpha = 0.75f) else palette.faint,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                text = window.name,
                                color = if (here) palette.accent else palette.ink,
                                fontSize = 14.sp,
                                fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(
    text: String,
    palette: AttacheGlassPalette,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
            .defaultMinSize(minHeight = 26.dp),
    ) {
        Text(
            text = text,
            color = palette.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

internal fun computeActiveWindowScrollIndex(
    hostsCount: Int,
    sessions: List<TmuxSession>,
    currentSessionId: String?,
    collapsedSessions: Map<String, Boolean>,
): Int? {
    if (currentSessionId == null || sessions.isEmpty()) return null
    var idx = if (hostsCount > 0) hostsCount + 2 else 0
    idx += 1 // "header-sessions"
    for (session in sessions) {
        val isCurrentSession = session.id == currentSessionId
        val sessionHeaderIdx = idx
        idx += 1 // "session-${session.id}"
        val isCollapsed = collapsedSessions[session.id] == true
        if (!isCollapsed) {
            if (isCurrentSession) {
                val activeWinPos = session.windows.indexOfFirst { it.active }
                return if (activeWinPos >= 0) {
                    idx + activeWinPos
                } else {
                    sessionHeaderIdx
                }
            }
            idx += session.windows.size
        } else if (isCurrentSession) {
            return sessionHeaderIdx
        }
    }
    return null
}

/**
 * The current session's name and, beside it, the current window's.
 * - In landscape mode, tapping toggles the left docked [AttacheSidebar].
 * - In portrait mode, tapping slides [AttacheSidebar] out from the left edge.
 */
@Composable
fun TmuxSessionsButton(
    control: TmuxControl,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val state by control.state.collectAsState()
    val current = state.sessions.firstOrNull { it.id == state.sessionId }
    val window = state.windows.firstOrNull { it.active }
    TextButton(
        onClick = { onClick?.invoke() },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = modifier.height(30.dp).testTag("tmuxSessions"),
    ) {
        val sessionText = if (isLandscape) {
            "◧ " + (current?.name ?: "tmux")
        } else {
            (current?.name ?: "tmux") + " ▾"
        }
        val fg = androidx.compose.material3.LocalContentColor.current
        Text(sessionText, fontSize = 13.sp, maxLines = 1, softWrap = false, color = fg, fontWeight = FontWeight.SemiBold)
        if (window != null) {
            Text(
                "  ${window.index}: ${window.name}",
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = fg.copy(alpha = 0.72f),
                modifier = Modifier.testTag("tmuxCurrentWindow"),
            )
        }
    }
}

