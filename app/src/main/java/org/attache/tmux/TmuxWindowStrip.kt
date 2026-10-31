package org.attache.tmux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalDimensions
import timber.log.Timber
import kotlin.math.abs

/** Runs a tmux command from a tap. A refusal is not an error worth a
 * dialog: the window or pane closed under the finger, and tmux's next
 * notification redraws everything from its own state. */
private fun CoroutineScope.tmuxAction(name: String, action: suspend () -> Unit) {
    launch {
        try {
            action()
        } catch (e: TmuxCommandError) {
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
fun Modifier.tmuxTouch(control: TmuxControl, dimensions: () -> TerminalDimensions?): Modifier =
    pointerInput(control) {
        val slop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        val scope = CoroutineScope(currentCoroutineContext())
        var fling: kotlinx.coroutines.Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A new touch stops a fling still running, as in any list.
            fling?.cancel()
            fling = null
            val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
            val dims = dimensions()
            val rows = dims?.rows?.takeIf { it > 0 } ?: return@awaitEachGesture
            val columns = dims.columns.takeIf { it > 0 } ?: return@awaitEachGesture
            val lineHeight = size.height.toFloat() / rows
            val cellWidth = size.width.toFloat() / columns
            var scrolling = false
            var target: TmuxPane? = null
            var moved = false
            var pending = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
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
                                    val lines = (carry / lineHeight).toInt()
                                    if (lines != 0) {
                                        control.scroll(pane.id, lines, column, row)
                                        carry -= lines * lineHeight
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
                    } else if (moved) {
                        return@awaitEachGesture
                    }
                }
                velocity.addPosition(change.uptimeMillis, change.position)
                if (scrolling) {
                    // Finger down is older text, which is tmux's scroll-up.
                    pending += change.position.y - change.previousPosition.y
                    val lines = (pending / lineHeight).toInt()
                    if (lines != 0) {
                        val pane = target ?: return@awaitEachGesture
                        control.scroll(
                            pane.id,
                            lines,
                            column = (down.position.x / cellWidth).toInt() - pane.left + 1,
                            row = (down.position.y / lineHeight).toInt() - pane.top + 1,
                        )
                        pending -= lines * lineHeight
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
 * The current session's name and, beside it, the current window's — the
 * title bar is too narrow on a phone for a chip per window. A tap opens every
 * session on the server, each with its windows — the phone's version of the
 * desktop app's rail — and switching windows happens there. A tap on a
 * session or a window moves this connection there; nothing changes locally
 * until tmux reports it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TmuxSessionsButton(control: TmuxControl, modifier: Modifier = Modifier) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val current = state.sessions.firstOrNull { it.id == state.sessionId }
    val window = state.windows.firstOrNull { it.active }
    TextButton(onClick = { open = true }, modifier = modifier.testTag("tmuxSessions")) {
        Text((current?.name ?: "tmux") + " ▾", maxLines = 1, softWrap = false)
        if (window != null) {
            Text(
                "  ${window.index}: ${window.name}",
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tmuxCurrentWindow"),
            )
        }
    }
    if (!open) return
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { open = false }) {
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.testTag("tmuxSessionList"),
        ) {
            state.sessions.forEach { session ->
                item(key = session.id) {
                    val isCurrent = session.id == state.sessionId
                    androidx.compose.material3.ListItem(
                        headlineContent = {
                            Text(
                                session.name,
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            )
                        },
                        supportingContent = {
                            Text("${session.windows.size} windows" + if (isCurrent) " · here" else if (session.attached) " · attached elsewhere" else "")
                        },
                        modifier = Modifier
                            .clickable {
                                open = false
                                scope.tmuxAction("switch-client") { control.switchTo(session.id) }
                            }
                            .testTag("tmuxSession-${session.id}"),
                    )
                }
                items(session.windows, key = { session.id + it.id }) { window ->
                    val here = session.id == state.sessionId && window.active
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("${window.index}: ${window.name}", maxLines = 1) },
                        leadingContent = { Text(if (here) "●" else " ") },
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .clickable {
                                open = false
                                scope.tmuxAction("switch-client") { control.switchTo(window.id) }
                            }
                            .testTag("tmuxSessionWindow-${window.id}"),
                    )
                }
            }
        }
    }
}

