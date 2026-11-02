package org.attache.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Host entry shown in the first tier of [AttacheSidebar].
 */
data class AttacheHostEntry(
    val id: UUID,
    val name: String,
    val subtitle: String,
    val connected: Boolean,
    val current: Boolean,
)

private val RailBg = Color(0xFF1E1F22)
private val RailHeaderBg = Color(0xFF18191B)
private val RailEdge = Color(0xFF2C2E33)
private val RailDivider = Color(0xFF27292E)
private val RailActiveHostBg = Color(0xFF282B31)
private val RailAccentBlue = Color(0xFF4E7FB8)
private val RailText = Color(0xFFE3E3E6)
private val RailMuted = Color(0xFF9A9CA3)
private val RailFaint = Color(0xFF686A70)
private val RailGoodGreen = Color(0xFF5FBF7F)

/**
 * Unified Attaché sidebar component used in both orientations:
 * - In landscape mode, docked on the left side of the terminal (`216.dp` wide).
 * - In portrait mode, slides out from the left edge as a drawer when the top bar
 *   session/window button is tapped.
 *
 * Displays a three-tier hierarchy: Hosts -> Sessions -> Windows.
 */
@Composable
fun AttacheSidebar(
    control: TmuxControl,
    hosts: List<AttacheHostEntry>,
    onSelectHost: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val state by control.state.collectAsState()
    val scope = rememberCoroutineScope()
    val collapsedSessions = remember { mutableStateMapOf<String, Boolean>() }

    Row(modifier = modifier.fillMaxHeight().testTag("attacheSidebar")) {
        Column(
            modifier = Modifier
                .width(if (onDismiss != null) 248.dp else 216.dp)
                .fillMaxHeight()
                .background(RailBg),
        ) {
            // Top bar of the sidebar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(RailHeaderBg)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = "ATTACHÉ",
                    color = RailMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .border(1.dp, Color(0xFF32353C), RoundedCornerShape(5.dp))
                            .clickable {
                                onDismiss?.invoke()
                                scope.tmuxAction("new-window") { control.newWindow() }
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                            .testTag("tmuxSidebarNewWindow"),
                    ) {
                        Text(
                            text = "+ Window",
                            color = RailText,
                            fontSize = 11.sp,
                        )
                    }
                    if (onDismiss != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .border(1.dp, Color(0xFF32353C), RoundedCornerShape(5.dp))
                                .clickable { onDismiss() }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                .testTag("tmuxSidebarClose"),
                        ) {
                            Text(
                                text = "✕",
                                color = RailMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = RailDivider)

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("tmuxSessionList"),
            ) {
                if (hosts.isNotEmpty()) {
                    item(key = "header-hosts") {
                        Text(
                            text = "HOSTS (主机)",
                            color = RailFaint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    items(hosts, key = { "host-${it.id}" }) { host ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (host.current) RailActiveHostBg else Color.Transparent)
                                .clickable {
                                    onDismiss?.invoke()
                                    onSelectHost(host.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("attacheHost-${host.id}"),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (host.connected) RailGoodGreen else RailFaint),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = host.name,
                                    color = if (host.current) RailText else RailMuted,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (host.current) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (host.subtitle.isNotBlank()) {
                                    Text(
                                        text = host.subtitle,
                                        color = RailFaint,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "divider-hosts") {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = RailDivider,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }

                item(key = "header-sessions") {
                    Text(
                        text = "SESSIONS & WINDOWS",
                        color = RailFaint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                state.sessions.forEach { session ->
                    val isCurrentSession = session.id == state.sessionId
                    val isCollapsed = collapsedSessions[session.id] == true
                    item(key = "session-${session.id}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    collapsedSessions[session.id] = false
                                    onDismiss?.invoke()
                                    scope.tmuxAction("switch-client") { control.switchTo(session.id) }
                                }
                                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                                .testTag("tmuxSession-${session.id}"),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        collapsedSessions[session.id] = !isCollapsed
                                    }
                                    .testTag("tmuxSessionToggle-${session.id}"),
                            ) {
                                Text(
                                    text = if (isCollapsed) "▸" else "▾",
                                    color = RailFaint,
                                    fontSize = 10.sp,
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = session.name.uppercase(),
                                color = if (isCurrentSession) RailText else RailMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${session.windows.size}",
                                color = RailFaint,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
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
                                    .padding(start = 4.dp, top = 1.dp, bottom = 1.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (here) RailAccentBlue else Color.Transparent)
                                    .clickable {
                                        onDismiss?.invoke()
                                        scope.tmuxAction("switch-client") { control.switchTo(window.id) }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                    .testTag("tmuxSessionWindow-${window.id}"),
                            ) {
                                Text(
                                    text = "${window.index}",
                                    color = if (here) Color.White.copy(alpha = 0.8f) else RailFaint,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = window.name,
                                    color = if (here) Color.White else RailMuted,
                                    fontSize = 12.5.sp,
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
        // Right border line
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(RailEdge),
        )
    }
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
        modifier = modifier.testTag("tmuxSessions"),
    ) {
        val sessionText = if (isLandscape) {
            "◧ " + (current?.name ?: "tmux")
        } else {
            (current?.name ?: "tmux") + " ▾"
        }
        Text(sessionText, maxLines = 1, softWrap = false)
        if (window != null) {
            Text(
                "  ${window.index}: ${window.name}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tmuxCurrentWindow"),
            )
        }
    }
}

