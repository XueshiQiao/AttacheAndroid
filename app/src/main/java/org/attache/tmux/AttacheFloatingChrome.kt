package org.attache.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colors of the "floating glass" chrome (docs/chrome-unify, scheme D): the
 * capsules and the sidebar panel are tinted from the terminal's own paper
 * (One Dark `#21252B` / cream `#F7F3EA`) instead of a separate sidebar gray.
 *
 * Compose has no cheap backdrop blur, so the glass is a high-alpha tint of the
 * paper rather than a real blur.
 */
@Immutable
data class AttacheGlassPalette(
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val accent: Color,
    val selectedBg: Color,
    val green: Color,
    val button: Color,
    val glass: Color,
    val portraitPanel: Color,
    val glassBorder: Color,
    val shadow: Color,
    val scrim: Color,
)

private val LightGlassPalette = AttacheGlassPalette(
    paper = Color(0xFFF7F3EA),
    ink = Color(0xFF1F2328),
    muted = Color(0xFF6F6A61),
    faint = Color(0xFF9C978C),
    line = Color(0x1446371E),
    accent = Color(0xFF3B6EA8),
    selectedBg = Color(0x293B6EA8),
    green = Color(0xFF3C9A5F),
    button = Color(0x1146371E),
    glass = Color(0xEEFBF8F1),
    portraitPanel = Color(0xFFF7F3EA),
    glassBorder = Color(0xBFFFFFFF),
    shadow = Color(0xFF463214),
    scrim = Color(0x3D14120E),
)

private val DarkGlassPalette = AttacheGlassPalette(
    paper = Color(0xFF21252B),
    ink = Color(0xFFD7DAE0),
    muted = Color(0xFF9AA1AD),
    faint = Color(0xFF6C737F),
    line = Color(0x12FFFFFF),
    accent = Color(0xFF61AFEF),
    selectedBg = Color(0x2961AFEF),
    green = Color(0xFF98C379),
    button = Color(0x11FFFFFF),
    glass = Color(0xEE2A2E36),
    portraitPanel = Color(0xFF242830),
    glassBorder = Color(0x17FFFFFF),
    shadow = Color(0xFF000000),
    scrim = Color(0x61000000),
)

fun attacheGlassPalette(isDark: Boolean): AttacheGlassPalette = if (isDark) DarkGlassPalette else LightGlassPalette

/** Geometry of the floating chrome, shared with the terminal layout. */
object AttacheChrome {
    /** Inset of the floating panel from the screen edges. */
    val PanelInset = 8.dp

    /**
     * Unified bottom gap above the bottom safe-area inset shared by BOTH the
     * left sidebar panel and the bottom 2-row toolbar so their bottom edges
     * always align on the exact same horizontal line in portrait and landscape.
     */
    val BottomGap = 6.dp

    /** Minimum bottom safe-area inset when system gesture pill is hidden (e.g. vivo Pad). */
    val MinBottomSafeInset = 15.dp

    /** Docked panel width in landscape. */
    val LandscapePanelWidth = 236.dp

    /** Drawer panel width in portrait (clamped to the screen). */
    val PortraitPanelWidth = 248.dp

    /** Horizontal room the terminal leaves for the docked landscape panel. */
    val LandscapePanelSlot = PanelInset + LandscapePanelWidth + PanelInset

    val CapsuleHeight = 40.dp
    val CapsuleTopGap = 4.dp

    /** Gap between the capsules and the first terminal row. */
    val CapsuleBottomGap = 6.dp

    /** Height reserved at the top of the panel for the merged title capsule. */
    val PanelHeaderHeight = 46.dp
}

private val motion = tween<Dp>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * The tmux console's whole top chrome in scheme D: a left capsule (back,
 * session ▾ window title, sidebar toggle), a right capsule with [actions], and
 * the floating sidebar panel. When the panel is open the left capsule loses its
 * glass and sits in the panel's header, so title and panel read as one piece.
 *
 * Z-order: right capsule < scrim (portrait) < panel < left capsule.
 */
@Composable
fun AttacheFloatingChrome(
    control: TmuxControl,
    isDark: Boolean,
    isLandscape: Boolean,
    barVisible: Boolean,
    sidebarOpen: Boolean,
    onBack: () -> Unit,
    onToggleSidebar: () -> Unit,
    onCloseSidebar: () -> Unit,
    onBarHeightChanged: (Dp) -> Unit,
    actions: @Composable RowScope.() -> Unit,
    sidebar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    bottomSafeInset: Dp = 0.dp,
    backContentDescription: String? = null,
) {
    val palette = attacheGlassPalette(isDark)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top).asPaddingValues()
    val startInset = safe.calculateLeftPadding(layoutDirection)
    val endInset = safe.calculateRightPadding(layoutDirection)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .coerceAtLeast(safe.calculateTopPadding())
    var rightCapsuleWidth by remember { mutableStateOf(0.dp) }

    CompositionLocalProvider(LocalContentColor provides palette.ink) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val screenWidth = maxWidth
            val panelWidth = if (isLandscape) {
                AttacheChrome.LandscapePanelWidth
            } else {
                minOf(AttacheChrome.PortraitPanelWidth, screenWidth - startInset - endInset - 56.dp)
            }
            val panelTop = statusTop + AttacheChrome.CapsuleTopGap
            val maxClosedCapsuleWidth = (screenWidth - startInset - endInset - rightCapsuleWidth - 24.dp)
                .coerceAtLeast(110.dp)
            val leftShown = barVisible || sidebarOpen

            // Terminal top inset: status bar + capsule row + breathing gap, so
            // no terminal row sits under the capsules.
            val barHeight = statusTop + AttacheChrome.CapsuleTopGap + AttacheChrome.CapsuleHeight +
                AttacheChrome.CapsuleBottomGap
            LaunchedEffect(barHeight, barVisible) {
                if (barVisible) onBarHeightChanged(barHeight)
            }

            // Right capsule.
            if (barVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = statusTop + AttacheChrome.CapsuleTopGap)
                        .height(AttacheChrome.CapsuleHeight)
                        .testTag("top_app_bar"),
                ) {
                    GlassCapsule(
                        palette = palette,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = endInset + 10.dp)
                            .onSizeChanged { rightCapsuleWidth = with(density) { it.width.toDp() } }
                            .testTag("attacheRightCapsule"),
                        contentPadding = 4.dp,
                        content = actions,
                    )
                }
            }

            // Portrait scrim covers the whole screen, capsules included.
            AnimatedVisibility(
                visible = !isLandscape && sidebarOpen,
                enter = fadeIn(animationSpec = tween(durationMillis = 220)),
                exit = fadeOut(animationSpec = tween(durationMillis = 200)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.scrim)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onCloseSidebar() }
                        .testTag("attacheDrawerScrim"),
                )
            }

            // Floating sidebar panel.
            val slidePx = with(density) { 18.dp.roundToPx() }
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                    slideInHorizontally(animationSpec = tween(durationMillis = 220)) { -slidePx } +
                    scaleIn(
                        initialScale = 0.985f,
                        transformOrigin = TransformOrigin(0f, 0.5f),
                        animationSpec = tween(durationMillis = 220),
                    ),
                exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                    slideOutHorizontally(animationSpec = tween(durationMillis = 180)) { -slidePx } +
                    scaleOut(
                        targetScale = 0.985f,
                        transformOrigin = TransformOrigin(0f, 0.5f),
                        animationSpec = tween(durationMillis = 180),
                    ),
                modifier = Modifier
                    .padding(
                        start = startInset + AttacheChrome.PanelInset,
                        top = panelTop,
                        bottom = bottomSafeInset + AttacheChrome.BottomGap,
                    ),
            ) {
                val shape = RoundedCornerShape(22.dp)
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .shadow(
                            elevation = 14.dp,
                            shape = shape,
                            ambientColor = palette.shadow.copy(alpha = 0.35f),
                            spotColor = palette.shadow.copy(alpha = 0.45f),
                        )
                        .clip(shape)
                        .background(if (isLandscape) palette.glass else palette.portraitPanel)
                        .border(0.5.dp, palette.glassBorder, shape)
                        // Swallow taps on the panel's empty areas so they don't
                        // reach the scrim / terminal underneath.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .testTag("attacheSidebarPanel"),
                ) {
                    Spacer(modifier = Modifier.fillMaxWidth().height(AttacheChrome.PanelHeaderHeight))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(1.dp)
                            .background(palette.line),
                    )
                    sidebar(Modifier.fillMaxWidth().weight(1f))
                }
            }

            // Left capsule: glass & dynamically sized to its text when closed;
            // merged into the panel header at full panel width when open.
            if (leftShown) {
                val left by animateDpAsState(
                    startInset + if (sidebarOpen) AttacheChrome.PanelInset + 6.dp else 10.dp,
                    motion,
                    label = "capsuleLeft",
                )
                val top by animateDpAsState(
                    panelTop + if (sidebarOpen) 2.dp else 0.dp,
                    motion,
                    label = "capsuleTop",
                )
                GlassCapsule(
                    palette = palette,
                    merged = sidebarOpen,
                    modifier = Modifier
                        .offset(x = left, y = top)
                        .then(
                            if (sidebarOpen) {
                                Modifier.width(panelWidth - 12.dp)
                            } else {
                                Modifier.widthIn(max = maxClosedCapsuleWidth)
                            },
                        )
                        .testTag("attacheLeftCapsule"),
                    contentPadding = 3.dp,
                ) {
                    CapsuleTitle(
                        control = control,
                        palette = palette,
                        onClick = onToggleSidebar,
                        modifier = Modifier.weight(1f, fill = sidebarOpen),
                    )
                    CapsuleIconButton(onClick = onToggleSidebar, testTag = "attacheSidebarToggle") {
                        SidebarGlyph(color = palette.ink, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** A rounded-full glass capsule; [merged] fades the glass out (panel header). */
@Composable
fun GlassCapsule(
    palette: AttacheGlassPalette,
    modifier: Modifier = Modifier,
    merged: Boolean = false,
    contentPadding: Dp = 3.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val glassAlpha by animateFloatAsState(
        if (merged) 0f else 1f,
        tween(durationMillis = 220),
        label = "capsuleGlass",
    )
    val shape = RoundedCornerShape(percent = 50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(AttacheChrome.CapsuleHeight)
            .then(
                if (glassAlpha > 0.01f) {
                    Modifier.shadow(
                        elevation = 10.dp * glassAlpha,
                        shape = shape,
                        ambientColor = palette.shadow.copy(alpha = 0.30f),
                        spotColor = palette.shadow.copy(alpha = 0.40f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(palette.glass.copy(alpha = palette.glass.alpha * glassAlpha))
            .border(0.5.dp, palette.glassBorder.copy(alpha = palette.glassBorder.alpha * glassAlpha), shape)
            .padding(horizontal = contentPadding),
        content = content,
    )
}

/** Round 36×34 icon button used inside the capsules. */
@Composable
fun CapsuleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width = 36.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        content()
    }
}

/** Text/icon button of the right capsule (theme ▦ ⋮). */
@Composable
fun CapsuleActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 38.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        content()
    }
}

/** Shows only the active `window` name when collapsed (fallback to session name if blank). */
@Composable
private fun CapsuleTitle(
    control: TmuxControl,
    palette: AttacheGlassPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by control.state.collectAsState()
    val current = state.sessions.firstOrNull { it.id == state.sessionId }
    val window = state.windows.firstOrNull { it.active }
    val displayTitle = window?.name?.takeIf { it.isNotBlank() } ?: current?.name ?: "tmux"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp)
            .testTag("tmuxSessions"),
    ) {
        Text(
            text = displayTitle,
            color = palette.accent,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("tmuxCurrentWindow"),
        )
        Text(
            text = " ▾",
            color = palette.muted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** Outline "sidebar" glyph: a rounded window with a left pane. */
@Composable
fun SidebarGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        val w = size.width - inset * 2
        val h = size.height * 0.78f
        val top = (size.height - h) / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, top),
            size = Size(w, h),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(width = stroke),
        )
        val x = inset + w * 0.36f
        drawLine(color, Offset(x, top), Offset(x, top + h), strokeWidth = stroke)
        drawLine(
            color,
            Offset(inset + w * 0.12f, top + h * 0.3f),
            Offset(inset + w * 0.24f, top + h * 0.3f),
            strokeWidth = stroke,
        )
    }
}
