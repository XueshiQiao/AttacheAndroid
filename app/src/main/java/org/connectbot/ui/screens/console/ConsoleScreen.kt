/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

package org.connectbot.ui.screens.console

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.MutableStateFlow
import org.attache.tmux.AttacheAccessoryRow
import org.attache.tmux.AttacheFileInboxDialog
import org.attache.tmux.AttacheFileKind
import org.attache.tmux.AttacheFileStorage
import org.attache.tmux.AttacheHostEntry
import org.attache.tmux.AttacheSelectionBar
import org.attache.tmux.AttacheSidebar
import org.attache.tmux.AttacheToolbarContainer
import org.attache.tmux.AttacheToolbarPrefs
import org.attache.tmux.AttacheToolbarStyleDialog
import org.attache.tmux.FileDownloadProgress
import org.attache.tmux.TmuxCopyTextDialog
import org.attache.tmux.TmuxRecentWindowsPopover
import org.attache.tmux.sendAgentCommand
import org.attache.tmux.tmuxTouch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.service.AuthBanner
import org.connectbot.service.DisconnectReason
import org.connectbot.service.PromptRequest
import org.connectbot.service.TerminalBridge
import org.connectbot.service.descriptionResource
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.ImeShortcutInputMode
import org.connectbot.terminal.ProgressState
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.ui.LoadingScreen
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.components.AuthBannerDialog
import org.connectbot.ui.components.ConnectionInfoDialog
import org.connectbot.ui.components.FloatingTextInputDialog
import org.connectbot.ui.components.InlinePrompt
import org.connectbot.ui.components.ResizeDialog
import org.connectbot.ui.components.TerminalKeyboard
import org.connectbot.ui.components.UrlScanDialog
import org.connectbot.ui.screens.keyboard.ConsoleKeyboardSettings
import org.connectbot.ui.screens.keyboard.KeyboardWorkflowDialog
import org.connectbot.ui.theme.terminal
import org.connectbot.util.PreferenceConstants
import org.connectbot.util.UrlUtils
import org.connectbot.util.rememberTerminalTypefaceResultFromStoredValue
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Check if a hardware keyboard is currently attached to the device.
 * Detects QWERTY and 12-key hardware keyboards, including Bluetooth keyboards.
 */
@Composable
private fun rememberHasHardwareKeyboard(): Boolean {
    val configuration = LocalConfiguration.current

    return remember(configuration) {
        val keyboardType = configuration.keyboard
        keyboardType == Configuration.KEYBOARD_QWERTY ||
            keyboardType == Configuration.KEYBOARD_12KEY
    }
}

@VisibleForTesting
const val AUTO_HIDE_DELAY_MS = 3000L

internal object ConsoleTestTags {
    const val AUTH_BANNER_MESSAGE = "auth_banner_message"
}

internal fun handleConsoleShortcut(
    keyEvent: KeyEvent,
    volumeKeysChangeFontSize: Boolean,
    copySelection: () -> Unit,
    pasteClipboardContents: () -> Unit,
    increaseFontSize: () -> Unit,
    decreaseFontSize: () -> Unit,
): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false

    return when {
        // Ctrl+Shift+C: copy selection
        keyEvent.key == Key.C && keyEvent.isCtrlPressed && keyEvent.isShiftPressed -> {
            copySelection()
            true
        }

        // Ctrl+Shift+V: paste clipboard content
        keyEvent.key == Key.V && keyEvent.isCtrlPressed && keyEvent.isShiftPressed -> {
            pasteClipboardContents()
            true
        }

        // Ctrl+Shift+= (Ctrl++): increase font size
        keyEvent.isCtrlPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Equals -> {
            increaseFontSize()
            true
        }

        // Ctrl+Shift+-: decrease font size
        keyEvent.isCtrlPressed && keyEvent.isShiftPressed && keyEvent.key == Key.Minus -> {
            decreaseFontSize()
            true
        }

        // Volume keys: change font size
        volumeKeysChangeFontSize && keyEvent.key == Key.VolumeUp -> {
            increaseFontSize()
            true
        }

        volumeKeysChangeFontSize && keyEvent.key == Key.VolumeDown -> {
            decreaseFontSize()
            true
        }

        else -> false
    }
}

@VisibleForTesting
internal fun sessionSwipeTarget(
    currentIndex: Int,
    sessionCount: Int,
    dragX: Float,
    dragY: Float,
    viewportWidth: Int,
    touchSlop: Float,
    selectionActive: Boolean = false,
): Int? {
    if (selectionActive || sessionCount < 2 || viewportWidth <= 0) {
        return null
    }

    val minimumSwipeDistance = max(touchSlop * 3f, viewportWidth * 0.18f)
    if (abs(dragX) < minimumSwipeDistance || abs(dragX) < abs(dragY) * 1.5f) {
        return null
    }

    val targetIndex = if (dragX < 0f) {
        (currentIndex + 1).coerceAtMost(sessionCount - 1)
    } else {
        (currentIndex - 1).coerceAtLeast(0)
    }

    return targetIndex.takeIf { it != currentIndex }
}

@VisibleForTesting
internal fun shouldRecordSoftwareKeyboardDismissal(
    hasImeBeenVisible: Boolean,
    systemImeVisible: Boolean,
    showSoftwareKeyboard: Boolean,
    windowFocused: Boolean,
    anyModalActive: Boolean,
    resizeSuspended: Boolean,
): Boolean = hasImeBeenVisible && !systemImeVisible && showSoftwareKeyboard &&
    windowFocused && !anyModalActive && !resizeSuspended

@VisibleForTesting
internal fun shouldShowSoftwareKeyboardForSessionOpen(
    previousBridgeId: UUID?,
    previousSessionOpen: Boolean,
    currentBridgeId: UUID?,
    sessionOpen: Boolean,
    hasHardwareKeyboard: Boolean,
): Boolean = currentBridgeId != null &&
    currentBridgeId == previousBridgeId &&
    sessionOpen &&
    !previousSessionOpen &&
    !hasHardwareKeyboard

@VisibleForTesting
internal fun shouldPreserveSoftwareKeyboardForBridgeChange(
    previousBridgeId: UUID?,
    currentBridgeId: UUID?,
    showSoftwareKeyboard: Boolean,
    hasHardwareKeyboard: Boolean,
): Boolean = previousBridgeId != null &&
    currentBridgeId != null &&
    previousBridgeId != currentBridgeId &&
    showSoftwareKeyboard &&
    !hasHardwareKeyboard

@VisibleForTesting
internal fun shouldDismissKeyboardOnDownwardSwipe(
    imeVisible: Boolean,
    showSoftwareKeyboard: Boolean,
    inlineInputActive: Boolean,
): Boolean = !inlineInputActive && (imeVisible || showSoftwareKeyboard)

@VisibleForTesting
internal fun isNonTmuxTerminalScrollable(
    bridge: TerminalBridge,
    pgUpDnGestureEnabled: Boolean,
): Boolean {
    if (pgUpDnGestureEnabled) return true
    val scrollbackConfigured = (bridge.host?.scrollbackLines ?: 140) > 0
    val rows = bridge.terminalEmulator?.dimensions?.rows ?: 0
    return scrollbackConfigured && rows > 0
}

private fun Modifier.dismissKeyboardOnDownwardScroll(
    key: Any?,
    selectionActive: () -> Boolean,
    canScroll: () -> Boolean,
    onDismissKeyboard: () -> Unit,
): Modifier = pointerInput(key) {
    val slop = viewConfiguration.touchSlop
    val dismissThresholdPx = max(slop * 2.5f, 24.dp.toPx())
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (selectionActive() || !canScroll()) return@awaitEachGesture
        var scrolling = false
        var keyboardDismissedInGesture = false
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (selectionActive()) return@awaitEachGesture
            if (event.changes.size > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            val total = change.position - down.position
            if (!scrolling) {
                if (!moved && change.uptimeMillis - down.uptimeMillis >= longPress) return@awaitEachGesture
                if (abs(total.x) > slop || abs(total.y) > slop) moved = true
                if (abs(total.y) > slop && abs(total.y) > abs(total.x)) {
                    scrolling = true
                } else if (moved) {
                    return@awaitEachGesture
                }
            }
            if (scrolling && !keyboardDismissedInGesture) {
                val deltaY = change.position.y - change.previousPosition.y
                if (
                    deltaY > 0f &&
                    total.y > dismissThresholdPx &&
                    abs(total.y) > abs(total.x) * 1.5f
                ) {
                    keyboardDismissedInGesture = true
                    onDismissKeyboard()
                }
            }
        }
    }
}

private fun Modifier.sessionSwipeNavigation(
    currentIndex: Int,
    sessionCount: Int,
    selectionActive: Boolean,
    onSwipeToSession: (Int) -> Unit,
    onInteraction: () -> Unit,
): Modifier = pointerInput(currentIndex, sessionCount, selectionActive) {
    if (selectionActive) {
        return@pointerInput
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val pointerId = down.id
        var dragX = 0f
        var dragY = 0f
        var horizontalSwipeLocked = false
        var verticalGestureLocked = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                break
            }

            val delta = change.positionChange()
            dragX += delta.x
            dragY += delta.y

            if (!horizontalSwipeLocked && !verticalGestureLocked) {
                val absX = abs(dragX)
                val absY = abs(dragY)
                if (absX > viewConfiguration.touchSlop && absX > absY * 1.5f) {
                    horizontalSwipeLocked = true
                } else if (absY > viewConfiguration.touchSlop && absY > absX) {
                    verticalGestureLocked = true
                }
            }

            if (horizontalSwipeLocked) {
                change.consume()
            }
        }

        if (horizontalSwipeLocked) {
            sessionSwipeTarget(
                currentIndex = currentIndex,
                sessionCount = sessionCount,
                dragX = dragX,
                dragY = dragY,
                viewportWidth = size.width,
                touchSlop = viewConfiguration.touchSlop,
                selectionActive = selectionActive,
            )?.let { target ->
                onInteraction()
                onSwipeToSession(target)
            }
        }
    }
}

private val noTmuxControl = MutableStateFlow<org.attache.tmux.TmuxControl?>(null)

/**
 * Terminal sequence sent by the "清空" (Clear) accessory button:
 * - `\u0005` (Ctrl+E): move cursor to end of current logical line
 * - `\u0015` x 64 (Ctrl+U): delete all soft-wrapped visual rows and lines back to buffer start
 * - `\u000b` x 64 (Ctrl+K): delete any remaining lines forward from buffer start to buffer end
 */
internal val CLEAR_INPUT_SEQUENCE: String = "\u0005" + "\u0015".repeat(64) + "\u000b".repeat(64)

private data class ImageUploadState(
    val uploadedBytes: Long,
    val totalBytes: Long,
)

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

@Composable
private fun ConsoleTerminalPage(
    bridge: TerminalBridge,
    disconnected: Boolean,
    connecting: Boolean,
    isActive: Boolean,
    keyboardAlwaysVisible: Boolean,
    pgUpDnGestureEnabled: Boolean,
    showSoftwareKeyboard: Boolean,
    resizeSuspended: Boolean,
    forceSize: Pair<Int, Int>?,
    termFocusRequester: FocusRequester,
    showExtraKeyboard: Boolean,
    hasPlayedKeyboardAnimation: Boolean,
    imeVisible: Boolean,
    handleTerminalInteraction: () -> Unit,
    onTerminalTap: () -> Unit,
    onShowSoftwareKeyboardChange: (Boolean) -> Unit,
    onImeVisibilityChange: (Boolean) -> Unit,
    onTextInputRequest: () -> Unit,
    onDisconnectRequest: () -> Unit,
    onKeyboardScrollInProgressChange: (Boolean) -> Unit,
    onSelectionControllerChange: (SelectionController) -> Unit,
    onComposeControllerChange: (ComposeController) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPasteRequest: () -> Unit,
    onInterceptKey: (KeyEvent) -> Boolean,
    onReconnect: () -> Unit,
    snackbarHostState: SnackbarHostState,
    showImeToggleKey: Boolean,
    isComposeModeActive: Boolean,
    onToggleComposeMode: () -> Unit,
    onShortcutModifierChange: () -> Unit,
    modifier: Modifier = Modifier,
    terminalModifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
    inlineInputActive: Boolean = false,
    autoStartVoiceOnOpen: Boolean = true,
    pendingImages: List<org.attache.tmux.PendingImageAttachment> = emptyList(),
    onRemovePendingImage: (Long) -> Unit = {},
    onDismissInlineInput: () -> Unit = {},
    onSendInlineText: (String) -> Unit = {},
    onPickInlineImage: (() -> Unit)? = null,
    accessoryRow: (@Composable () -> Unit)? = null,
    recentWindowsPopover: (@Composable (androidx.compose.ui.unit.Dp) -> Unit)? = null,
) {
    val keyboardDensity = LocalDensity.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val localView = androidx.compose.ui.platform.LocalView.current
    var keyboardHeight by remember { mutableStateOf(0.dp) }
    var consoleHeight by remember { mutableStateOf(480.dp) }
    val hideSoftKeyboard = remember(keyboardController, localView, onShowSoftwareKeyboardChange) {
        {
            onShowSoftwareKeyboardChange(false)
            keyboardController?.hide()
            localView.context
                .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(localView.windowToken, 0)
            Unit
        }
    }
    Box(modifier = modifier.onSizeChanged { consoleHeight = with(keyboardDensity) { it.height.toDp() } }) {
        val fontResult = rememberTerminalTypefaceResultFromStoredValue(bridge.fontFamily)
        val coroutineScope = rememberCoroutineScope()
        val fontSize by bridge.fontSizeFlow.collectAsState()
        val delKeyMode by bridge.delKeyModeFlow.collectAsState()

        LaunchedEffect(bridge, isActive) {
            if (isActive) bridge.transportMessages.collect { snackbarHostState.showSnackbar(it) }
        }

        LaunchedEffect(fontResult.loadFailed, fontResult.isLoading) {
            if (fontResult.loadFailed && !fontResult.isLoading) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Failed to load font '${fontResult.requestedFontName}'. Using system default.",
                    )
                }
            }
        }

        val termBgColor = androidx.compose.ui.graphics.Color(
            if (isDarkTheme) TerminalBridge.ATTACHE_DARK_BG else TerminalBridge.ATTACHE_LIGHT_BG,
        )
        val termFgColor = androidx.compose.ui.graphics.Color(
            if (isDarkTheme) TerminalBridge.ATTACHE_DARK_FG else TerminalBridge.ATTACHE_LIGHT_FG,
        )

        Terminal(
            terminalEmulator = bridge.terminalEmulator,
            modifier = Modifier
                .fillMaxSize()
                .background(termBgColor)
                .padding(
                    bottom = if (keyboardAlwaysVisible) keyboardHeight else 0.dp,
                )

                .then(terminalModifier)
                .testTag("terminal"),
            typeface = fontResult.typeface,
            initialFontSize = fontSize.sp,
            backgroundColor = termBgColor,
            foregroundColor = termFgColor,
            keyboardEnabled = true,
            showSoftKeyboard = showSoftwareKeyboard && isActive && !inlineInputActive,
            resizeSuspended = resizeSuspended,
            focusRequester = termFocusRequester,
            forcedSize = forceSize,
            modifierManager = bridge.keyHandler,
            onSelectionControllerAvailable = { controller ->
                if (isActive) {
                    onSelectionControllerChange(controller)
                }
            },
            onComposeControllerAvailable = { controller ->
                if (isActive) {
                    onComposeControllerChange(controller)
                }
            },
            onTerminalTap = onTerminalTap,
            onImeVisibilityChanged = { visible ->
                if (isActive) {
                    onImeVisibilityChange(visible)
                }
            },
            onHyperlinkClick = onOpenUrl,
            delKeyMode = delKeyMode,
            onPasteRequest = onPasteRequest,
            onInterceptKey = onInterceptKey,
            onPageGesture = if (pgUpDnGestureEnabled) {
                { key ->
                    bridge.keyHandler.sendPressedKey(key)
                    bridge.tryKeyVibrate()
                }
            } else {
                null
            },
        )

        SideEffect {
            bridge.onTextInputRequest = onTextInputRequest
            bridge.onEnterKeySent = if (isActive) hideSoftKeyboard else null
        }

        if (isActive) {
            AnimatedVisibility(
                visible = showExtraKeyboard || inlineInputActive,
                enter = fadeIn(animationSpec = tween(durationMillis = 100)),
                exit = fadeOut(animationSpec = tween(durationMillis = 100)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .testTag("terminal_keyboard"),
            ) {
                // Attaché: a tmux host's own row sits above the configured
                // keys and is measured with them, so the terminal is padded
                // for both.
                AttacheToolbarContainer(
                    isDark = isDarkTheme,
                    inlineInputActive = inlineInputActive,
                    autoStartVoice = autoStartVoiceOnOpen,
                    pendingImages = pendingImages,
                    onRemovePendingImage = onRemovePendingImage,
                    onDismissInlineInput = {
                        hideSoftKeyboard()
                        onDismissInlineInput()
                    },
                    onSendInlineText = { text ->
                        hideSoftKeyboard()
                        onSendInlineText(text)
                    },
                    onPickImage = onPickInlineImage,
                    modifier = Modifier.onSizeChanged { keyboardHeight = with(keyboardDensity) { it.height.toDp() } },
                ) {
                    accessoryRow?.invoke()
                    TerminalKeyboard(
                        modifier = Modifier.heightIn(max = consoleHeight / 2),
                        bridge = bridge,
                        onInteraction = { handleTerminalInteraction() },
                        onHideIme = {
                            hideSoftKeyboard()
                        },
                        onShowIme = {
                            onShowSoftwareKeyboardChange(true)
                        },
                        onOpenTextInput = onTextInputRequest,
                        onPasteRequest = onPasteRequest,
                        onScrollInProgressChange = onKeyboardScrollInProgressChange,
                        imeVisible = imeVisible,
                        playAnimation = !hasPlayedKeyboardAnimation,
                        showImeToggleKey = showImeToggleKey,
                        isComposeModeActive = isComposeModeActive,
                        onToggleComposeMode = onToggleComposeMode,
                        onShortcutModifierChange = onShortcutModifierChange,
                    )
                }
            }

            recentWindowsPopover?.invoke(
                if (showExtraKeyboard || inlineInputActive) keyboardHeight else 0.dp,
            )

            val promptState by bridge.promptManager.promptState.collectAsState()

            InlinePrompt(
                promptRequest = promptState,
                onResponse = { response ->
                    bridge.promptManager.respond(response)
                },
                onCancel = {
                    bridge.promptManager.cancelPrompt()
                },
                onDismiss = {
                    termFocusRequester.requestFocus()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            AnimatedVisibility(
                visible = disconnected && !connecting && promptState == null,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val terminalColors = MaterialTheme.colorScheme.terminal
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(terminalColors.overlayBackground)
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(bridge.disconnectReason.descriptionResource()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = terminalColors.overlayText,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDisconnectRequest) {
                            Text(
                                stringResource(R.string.console_menu_close),
                                color = terminalColors.overlayText,
                            )
                        }
                        Button(
                            onClick = onReconnect,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(R.string.console_menu_reconnect))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConsoleScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPortForwards: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    viewModel: ConsoleViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val terminalManager = LocalTerminalManager.current
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Capture latest callback for use in effects
    val currentOnNavigateToSettings by rememberUpdatedState(onNavigateToSettings)

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }

    // Read preferences
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val keyboardAlwaysVisible = remember { prefs.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, true) }
    val swipeSessionsEnabled = remember {
        prefs.getBoolean(PreferenceConstants.SWIPE_SESSIONS, false)
    }
    val showImeToggleKey = remember { prefs.getBoolean(PreferenceConstants.IME_TOGGLE_KEY, true) }
    val imeShortcutInputMode = remember {
        val storedMode = prefs.getString(
            PreferenceConstants.IME_SHORTCUT_INPUT_MODE,
            ImeShortcutInputMode.FORCE_ASCII.name,
        )
        ImeShortcutInputMode.entries.firstOrNull { it.name == storedMode }
            ?: ImeShortcutInputMode.FORCE_ASCII
    }
    val pgUpDnGestureEnabled = remember {
        prefs.getBoolean(PreferenceConstants.PG_UPDN_GESTURE, false)
    }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean(PreferenceConstants.FULLSCREEN, false)) }
    var titleBarHide by remember { mutableStateOf(prefs.getBoolean(PreferenceConstants.TITLEBARHIDE, false)) }
    val volumeKeysChangeFontSize = remember { prefs.getBoolean(PreferenceConstants.VOLUME_FONT, true) }
    val keepScreenAwake = remember { prefs.getBoolean(PreferenceConstants.KEEP_ALIVE, true) }

    // Keyboard state
    val hasHardwareKeyboard = rememberHasHardwareKeyboard()
    var showSoftwareKeyboard by remember { mutableStateOf(!hasHardwareKeyboard) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val navigateBack: () -> Unit = {
        // Hide while the console still owns input; disposal may run after focus
        // has moved, when the terminal's guarded IME cleanup cannot hide it.
        showSoftwareKeyboard = false
        keyboardController?.hide()
        onNavigateBack()
    }
    val currentOnNavigateBack by rememberUpdatedState(navigateBack)

    var rotation by remember(hasHardwareKeyboard) {
        val prefValue = prefs.getString(PreferenceConstants.ROTATION, PreferenceConstants.ROTATION_DEFAULT)
        mutableStateOf(
            if (prefValue == PreferenceConstants.ROTATION_DEFAULT) {
                if (hasHardwareKeyboard) {
                    PreferenceConstants.ROTATION_LANDSCAPE
                } else {
                    PreferenceConstants.ROTATION_PORTRAIT
                }
            } else {
                prefValue
            },
        )
    }

    val termFocusRequester = remember { FocusRequester() }

    var forceSize: Pair<Int, Int>? by remember { mutableStateOf(null) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var landscapeSidebarOpen by rememberSaveable { mutableStateOf(true) }
    var portraitDrawerOpen by rememberSaveable { mutableStateOf(false) }

    var themeMode by remember {
        mutableStateOf(org.connectbot.util.ThemeMode.fromString(prefs.getString(PreferenceConstants.THEME_MODE, null)))
    }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        org.connectbot.util.ThemeMode.LIGHT -> false
        org.connectbot.util.ThemeMode.DARK -> true
        org.connectbot.util.ThemeMode.SYSTEM -> systemDark
    }
    val unifiedConsoleBg = if (isDarkTheme) {
        androidx.compose.ui.graphics.Color(TerminalBridge.ATTACHE_DARK_BG)
    } else {
        androidx.compose.ui.graphics.Color(TerminalBridge.ATTACHE_LIGHT_BG)
    }
    val unifiedConsoleFg = if (isDarkTheme) {
        androidx.compose.ui.graphics.Color(TerminalBridge.ATTACHE_DARK_FG)
    } else {
        androidx.compose.ui.graphics.Color(TerminalBridge.ATTACHE_LIGHT_FG)
    }
    LaunchedEffect(isDarkTheme, unifiedConsoleBg) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        @Suppress("DEPRECATION")
        window.statusBarColor = if (isDarkTheme) TerminalBridge.ATTACHE_DARK_BG else TerminalBridge.ATTACHE_LIGHT_BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (isDarkTheme) TerminalBridge.ATTACHE_DARK_BG else TerminalBridge.ATTACHE_LIGHT_BG
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkTheme
            isAppearanceLightNavigationBars = !isDarkTheme
        }
    }
    fun toggleTheme() {
        val next = if (isDarkTheme) org.connectbot.util.ThemeMode.LIGHT else org.connectbot.util.ThemeMode.DARK
        themeMode = next
        prefs.edit { putString(PreferenceConstants.THEME_MODE, next.name) }
    }

    LaunchedEffect(isLandscape) {
        if (isLandscape && portraitDrawerOpen) {
            portraitDrawerOpen = false
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var resizeSuspended by remember { mutableStateOf(false) }
    var menuImeWasVisible by remember { mutableStateOf(false) }
    var showTmuxMenu by remember { mutableStateOf(false) }
    var showSlashMenu by remember { mutableStateOf(false) }
    var showRecentWindowsPopover by remember { mutableStateOf(false) }
    var showCopyTextDialog by remember { mutableStateOf(false) }
    var showFileInboxDialog by remember { mutableStateOf(false) }
    var showToolbarStyleDialog by remember { mutableStateOf(false) }
    val currentToolbarStyle by AttacheToolbarPrefs.styleFlow.collectAsState()
    LaunchedEffect(context) { AttacheToolbarPrefs.ensureLoaded(context) }
    var fileDownloadState by remember { mutableStateOf<FileDownloadProgress?>(null) }
    var fileDownloadJob by remember { mutableStateOf<Job?>(null) }
    var showImagePicker by remember { mutableStateOf(false) }
    var imageUploadState by remember { mutableStateOf<ImageUploadState?>(null) }
    var imageUploadJob by remember { mutableStateOf<Job?>(null) }
    var showUrlScanDialog by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showSessionPickerDialog by remember { mutableStateOf(false) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var autoStartVoiceOnOpen by remember { mutableStateOf(true) }
    var pendingImages by remember { mutableStateOf<List<org.attache.tmux.PendingImageAttachment>>(emptyList()) }
    var showExtraKeyboard by remember { mutableStateOf(true) } // Start visible to show animation
    var hasPlayedKeyboardAnimation by remember { mutableStateOf(false) }
    var showTitleBar by remember { mutableStateOf(!titleBarHide) }
    // Non-state holders for auto-hide jobs to avoid unnecessary recompositions
    val autoHideJobRef = remember {
        object {
            var keyboardJob: Job? = null
            var titleBarJob: Job? = null
        }
    }
    var scannedUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectionController by remember { mutableStateOf<SelectionController?>(null) }
    var composeController by remember { mutableStateOf<ComposeController?>(null) }
    var imeVisible by remember { mutableStateOf(false) }
    var keyboardScrollInProgress by remember { mutableStateOf(false) }
    var previousBridgeIdForImeState by remember { mutableStateOf<UUID?>(null) }
    var ignoreImeHiddenForBridgeId by remember { mutableStateOf<UUID?>(null) }
    var previousBridgeIdForSessionOpen by remember { mutableStateOf<UUID?>(null) }
    var previousSessionOpen by remember { mutableStateOf(false) }

    val currentBridge = uiState.bridges
        .getOrNull(uiState.currentBridgeIndex)
    val currentBridgeId = currentBridge?.host?.id
    val currentTmuxControl by (currentBridge?.tmuxControl ?: noTmuxControl).collectAsState()

    LaunchedEffect(currentBridge, isDarkTheme) {
        currentBridge?.applyThemeAppearance(isDarkTheme)
    }

    LaunchedEffect(currentBridge, currentTmuxControl != null) {
        val bridge = currentBridge ?: return@LaunchedEffect
        if (currentTmuxControl != null) {
            val savedFontSize = prefs.getFloat("attache_font_size_sp", 14f)
            bridge.updateFontSizeSp(savedFontSize)
        }
    }

    fun changeFontSize(delta: Float) {
        val bridge = currentBridge ?: return
        val current = bridge.fontSizeFlow.value.takeIf { it > 0f } ?: 14f
        val next = (current + delta).coerceIn(8f, 32f)
        bridge.updateFontSizeSp(next)
        prefs.edit { putFloat("attache_font_size_sp", next) }
    }
    var showKeyboardSettings by rememberSaveable(currentBridgeId) { mutableStateOf(false) }
    var showConnectionInfoDialog by remember(currentBridgeId) { mutableStateOf(false) }
    val automationState by currentBridge?.automationState?.collectAsState()
        ?: remember { mutableStateOf(org.connectbot.service.automation.AutomationState()) }

    val consoleOwner = remember(terminalManager, currentBridge) { Any() }
    LifecycleResumeEffect(terminalManager, currentBridge) {
        terminalManager?.setVisibleConsole(consoleOwner, currentBridge)
        onPauseOrDispose {
            terminalManager?.clearVisibleConsole(consoleOwner)
        }
    }

    DisposableEffect(terminalManager, currentBridge) {
        onDispose {
            currentBridge?.let { terminalManager?.onConsoleClosed(consoleOwner, it) }
        }
    }

    // Get current prompt state to check if biometric prompt is active
    val promptState by currentBridge?.promptManager?.promptState?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val authBanners by currentBridge?.authBanners?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val currentAuthBanner = authBanners.firstOrNull()
    var wasBiometricPromptActive by remember { mutableStateOf(false) }
    val isBiometricPromptActive = promptState is PromptRequest.BiometricPrompt

    // Check if any modal (menu or dialog) is currently active
    val anyModalActive = showKeyboardSettings || showToolbarStyleDialog || showMenu || showTmuxMenu || showSlashMenu || showCopyTextDialog || showFileInboxDialog || showImagePicker || showUrlScanDialog || showResizeDialog ||
        showDisconnectDialog || showSessionPickerDialog || showConnectionInfoDialog ||
        portraitDrawerOpen || isBiometricPromptActive || currentAuthBanner != null

    fun restartTitleBarTimer() {
        autoHideJobRef.titleBarJob?.cancel()
        if (titleBarHide && !showMenu) {
            autoHideJobRef.titleBarJob = coroutineScope.launch {
                delay(AUTO_HIDE_DELAY_MS)
                showTitleBar = false
            }
        }
    }

    /**
     * Manages visibility of the extra keyboard and title bar based on preferences.
     * Terminal taps reveal both; keyboard interactions reset only the keyboard timer.
     *
     * @param isTerminalTap Whether this call was triggered by a terminal tap or title bar action.
     * @param isInteraction Whether this call was triggered by a user interaction (tap, key press, scroll).
     *                      If false, only the timer is managed without forcing visibility to true.
     * @param resetTitleBarTimer Whether this interaction should extend title bar visibility.
     */
    fun handleTerminalInteraction(
        isTerminalTap: Boolean = false,
        isInteraction: Boolean = true,
        resetTitleBarTimer: Boolean = true,
    ) {
        autoHideJobRef.keyboardJob?.cancel()
        if (resetTitleBarTimer) restartTitleBarTimer()

        if (isInteraction || keyboardScrollInProgress) {
            // Show emulated keyboard on any interaction or while scrolling (unless always visible)
            if (!keyboardAlwaysVisible) {
                showExtraKeyboard = true
            }
            // Show title bar temporarily ONLY when terminal is tapped (if auto-hide enabled)
            if (titleBarHide && isTerminalTap) {
                showTitleBar = true
            }
        }

        // Ensure they are shown if they should be permanent
        if (keyboardAlwaysVisible) showExtraKeyboard = true
        if (!titleBarHide) showTitleBar = true

        if (!keyboardScrollInProgress && !keyboardAlwaysVisible) {
            autoHideJobRef.keyboardJob = coroutineScope.launch {
                delay(AUTO_HIDE_DELAY_MS)
                showExtraKeyboard = false
                hasPlayedKeyboardAnimation = true
            }
        }
    }

    fun selectBridgePreservingKeyboard(index: Int) {
        val targetBridgeId = uiState.bridges.getOrNull(index)?.host?.id
        if (
            shouldPreserveSoftwareKeyboardForBridgeChange(
                previousBridgeId = currentBridgeId,
                currentBridgeId = targetBridgeId,
                showSoftwareKeyboard = showSoftwareKeyboard,
                hasHardwareKeyboard = hasHardwareKeyboard,
            )
        ) {
            ignoreImeHiddenForBridgeId = targetBridgeId
        }
        viewModel.selectBridge(index)
    }

    fun selectHostFromSidebar(targetHostId: UUID) {
        val existingIndex = uiState.bridges.indexOfFirst { it.host.id == targetHostId }
        if (existingIndex != -1) {
            selectBridgePreservingKeyboard(existingIndex)
        } else {
            viewModel.openHostConnection(targetHostId)
        }
        termFocusRequester.requestFocus()
    }

    val hostEntries = remember(uiState.bridges, uiState.savedHosts, uiState.currentBridgeIndex, uiState.revision) {
        val connectedById = uiState.bridges.associateBy { it.host.id }
        val orderedHosts = buildList {
            uiState.bridges.forEach { add(it.host) }
            uiState.savedHosts.forEach { saved ->
                if (saved.id !in connectedById) add(saved)
            }
        }
        orderedHosts.map { host ->
            val b = connectedById[host.id]
            val isCurrent = b != null && uiState.bridges.getOrNull(uiState.currentBridgeIndex)?.host?.id == host.id
            val isConnected = b != null && !b.isDisconnected && !b.isConnecting
            val hostLabel = host.hostname.ifBlank { host.protocol }
            val subtitle = when {
                b == null -> "$hostLabel · 点击连接"
                b.isDisconnected -> "$hostLabel · 已断开"
                b.isConnecting -> "$hostLabel · 连接中…"
                else -> "$hostLabel · 已连接"
            }
            AttacheHostEntry(
                id = host.id,
                name = host.nickname.ifBlank { host.hostname },
                subtitle = subtitle,
                connected = isConnected,
                current = isCurrent,
            )
        }
    }

    BackHandler(enabled = !isLandscape && portraitDrawerOpen) {
        portraitDrawerOpen = false
        termFocusRequester.requestFocus()
    }

    // Resume title bar auto-hide when the overflow menu closes.
    LaunchedEffect(showMenu) {
        if (!showMenu) {
            restartTitleBarTimer()
        }
    }

    // Apply fullscreen mode and display cutout settings
    LaunchedEffect(fullscreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window

        try {
            if (fullscreen) {
                // Enable fullscreen mode - hide system bars
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // Disable fullscreen mode - show system bars
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (e: IllegalArgumentException) {
            // Handle foldable device state issues
            Timber.e(e, "Error setting fullscreen mode (foldable device?)")
        }
    }

    // Defer navigation until after lifecycle dispatch has finished. Popping from
    // ON_RESUME synchronously can re-enter Navigation while it is updating entries.
    // Observing lifecycle state also retries a background disconnect on resume.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    LaunchedEffect(uiState.bridges.isEmpty(), uiState.isLoading, lifecycleState) {
        if (uiState.bridges.isEmpty() && !uiState.isLoading &&
            lifecycle.currentState == Lifecycle.State.RESUMED
        ) {
            currentOnNavigateBack()
        }
    }

    // Request focus on terminal when screen appears (e.g., returning from navigation)
    LaunchedEffect(Unit) {
        termFocusRequester.requestFocus()
        // Initial auto-hide timer start (without forcing show)
        handleTerminalInteraction(isInteraction = false)
    }

    // Track actual IME visibility using WindowInsets to detect user dismissing with back button
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeHeight = with(density) { imeInsets.getBottom(density).toDp() }
    val systemImeVisible = imeHeight > 0.dp
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imeAnimating = WindowInsets.imeAnimationSource.getBottom(density) !=
        WindowInsets.imeAnimationTarget.getBottom(density)

    // Popup dismissal precedes window focus restoration and the IME animation.
    // Retain the PTY size through that transition, including menus opening dialogs.
    LaunchedEffect(anyModalActive, windowFocused, imeAnimating, systemImeVisible, showSoftwareKeyboard) {
        if (resizeSuspended && !anyModalActive && windowFocused && !imeAnimating) {
            if (menuImeWasVisible && showSoftwareKeyboard && !systemImeVisible) {
                // Focus can return before Android even starts restoring the IME.
                // A visibility/animation change cancels this wait. If Android
                // declines the request, eventually accept the keyboard-free size.
                delay(1000)
            }
            withFrameNanos { }
            resizeSuspended = false
        }
    }
    var hasImeBeenVisible by remember { mutableStateOf(false) }
    val latestCurrentBridgeId by rememberUpdatedState(currentBridgeId)

    // Sync our state when user dismisses IME externally (back button)
    LaunchedEffect(systemImeVisible) {
        if (systemImeVisible) {
            hasImeBeenVisible = true
            ignoreImeHiddenForBridgeId = null
        }
        // Only sync to hidden state after IME has been visible at least once.
        // This prevents canceling the keyboard before it has a chance to show.
        // A focusable popup may temporarily hide the IME. Keep the user's request
        // intact so termlib can restore it when the terminal window regains focus.
        // Observe visibility transitions only: ending resize suspension must not
        // reinterpret the same temporary hidden state as a Back-button dismissal.
        if (
            shouldRecordSoftwareKeyboardDismissal(
                hasImeBeenVisible = hasImeBeenVisible,
                systemImeVisible = systemImeVisible,
                showSoftwareKeyboard = showSoftwareKeyboard,
                windowFocused = windowFocused,
                anyModalActive = anyModalActive,
                resizeSuspended = resizeSuspended,
            )
        ) {
            if (ignoreImeHiddenForBridgeId == latestCurrentBridgeId) {
                termFocusRequester.requestFocus()
            } else {
                showSoftwareKeyboard = false
            }
        }
        imeVisible = systemImeVisible
    }

    // Show software keyboard after biometric prompt completes (unless hardware keyboard is connected)
    LaunchedEffect(isBiometricPromptActive) {
        if (wasBiometricPromptActive && !isBiometricPromptActive && !hasHardwareKeyboard) {
            showSoftwareKeyboard = true
        }
        wasBiometricPromptActive = isBiometricPromptActive
    }

    val hasMultipleSessions = uiState.bridges.size > 1 && !uiState.isLoading
    val swipeBetweenSessions = swipeSessionsEnabled && hasMultipleSessions
    val terminalSelectionActive = selectionController?.isSelectionActive == true

    // Upgrade default single-character long-press selection to WORD mode so
    // tapping/long-pressing on a word selects the full token immediately.
    LaunchedEffect(terminalSelectionActive) {
        if (terminalSelectionActive) {
            selectionController?.setSelectionMode(org.connectbot.terminal.SelectionMode.WORD)
        }
    }
    // These values are computed from bridge state and will recompute when uiState.revision changes
    val sessionOpen = currentBridge?.isSessionOpen == true
    val disconnected = currentBridge?.isDisconnected == true
    val connecting = currentBridge?.isConnecting == true
    val canForwardPorts = currentBridge?.canFowardPorts() == true
    val snackbarHostState = remember { SnackbarHostState() }

    val isConnectionActive = currentBridge != null && !disconnected
    val keepScreenOn = keepScreenAwake && isConnectionActive

    LaunchedEffect(currentBridgeId) {
        if (
            shouldPreserveSoftwareKeyboardForBridgeChange(
                previousBridgeId = previousBridgeIdForImeState,
                currentBridgeId = currentBridgeId,
                showSoftwareKeyboard = showSoftwareKeyboard,
                hasHardwareKeyboard = hasHardwareKeyboard,
            )
        ) {
            ignoreImeHiddenForBridgeId = currentBridgeId
        }
        previousBridgeIdForImeState = currentBridgeId
    }

    // Show software keyboard when the current session transitions to open,
    // while preserving the user's current keyboard state across bridge switches.
    LaunchedEffect(currentBridgeId, sessionOpen, hasHardwareKeyboard) {
        if (
            shouldShowSoftwareKeyboardForSessionOpen(
                previousBridgeId = previousBridgeIdForSessionOpen,
                previousSessionOpen = previousSessionOpen,
                currentBridgeId = currentBridgeId,
                sessionOpen = sessionOpen,
                hasHardwareKeyboard = hasHardwareKeyboard,
            )
        ) {
            showSoftwareKeyboard = true
        }
        previousBridgeIdForSessionOpen = currentBridgeId
        previousSessionOpen = sessionOpen
    }

    // Reset selection and compose controllers when bridge changes
    LaunchedEffect(currentBridge) {
        selectionController = null
        composeController = null
        if (currentBridge != null) {
            termFocusRequester.requestFocus()
        }
    }

    // Initialize forceSize from profile when bridge changes
    LaunchedEffect(currentBridge) {
        currentBridge?.let { bridge ->
            val rows = bridge.profileForceSizeRows
            val cols = bridge.profileForceSizeColumns
            if (rows != null && cols != null) {
                forceSize = Pair(rows, cols)
            } else {
                forceSize = null
            }
        }
    }

    // Show snackbar for network status messages
    LaunchedEffect(Unit) {
        viewModel.networkStatusMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Show snackbar when there's an error
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                withDismissAction = true,
            )
        }
    }

    val noUrlHandlerMessage = stringResource(R.string.console_url_no_handler)

    val urlNotSupportedMessage = stringResource(R.string.console_url_not_supported)

    fun openUrl(url: String) {
        UrlUtils.openUrl(context, url).onFailure { e ->
            coroutineScope.launch {
                val message = if (e is ActivityNotFoundException) {
                    noUrlHandlerMessage
                } else {
                    urlNotSupportedMessage
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    var titleBarHeight by remember { mutableStateOf(0.dp) }

    fun pasteClipboardContents() {
        currentBridge?.let { bridge ->
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()

            if (!clip.isNullOrBlank()) {
                bridge.injectString(clip)
            }
        }
    }

    val latestBridge by rememberUpdatedState(currentBridge)
    val latestShowTextInputDialog by rememberUpdatedState(showTextInputDialog)
    val latestPendingImages by rememberUpdatedState(pendingImages)
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        showImagePicker = false
        resizeSuspended = false
        val bridge = latestBridge ?: return@rememberLauncherForActivityResult
        @Suppress("USELESS_ELVIS")
        val control = (bridge.tmuxControl ?: noTmuxControl).value ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) {
            if (!latestShowTextInputDialog) {
                termFocusRequester.requestFocus()
            }
            return@rememberLauncherForActivityResult
        }
        val remainingSlots = (org.attache.tmux.MAX_ATTACHED_IMAGES - latestPendingImages.size).coerceAtLeast(0)
        if (remainingSlots <= 0) {
            android.widget.Toast.makeText(
                context,
                "最多同时添加 ${org.attache.tmux.MAX_ATTACHED_IMAGES} 张图片",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (uris.size > remainingSlots) {
            android.widget.Toast.makeText(
                context,
                "最多同时添加 ${org.attache.tmux.MAX_ATTACHED_IMAGES} 张图片，已添加前 $remainingSlots 张",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        val acceptedUris = uris.take(remainingSlots)
        // Automatically expand our inline input bar so the user sees the image thumbnail
        // preview strip above the input box (without forcing the keyboard open unless already open).
        if (!latestShowTextInputDialog) {
            autoStartVoiceOnOpen = false
            showTextInputDialog = true
        }
        val baseId = android.os.SystemClock.uptimeMillis()
        val job = coroutineScope.launch(Dispatchers.IO) {
            try {
                val staged = acceptedUris.mapIndexedNotNull { idx, uri ->
                    val bytes = try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to read picked image")
                        null
                    }
                    if (bytes == null || bytes.isEmpty()) {
                        null
                    } else {
                        val ext = when (context.contentResolver.getType(uri)) {
                            "image/png" -> "png"
                            "image/jpeg" -> "jpg"
                            "image/webp" -> "webp"
                            else -> "png"
                        }
                        val thumb = org.attache.tmux.decodeImageThumbnail(bytes)
                        Triple(baseId + idx, bytes to ext, thumb)
                    }
                }
                if (staged.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        imageUploadState = null
                        snackbarHostState.showSnackbar("Failed to read image")
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val newItems = staged.map { (attachmentId, _, thumb) ->
                        org.attache.tmux.PendingImageAttachment(
                            id = attachmentId,
                            thumbnail = thumb,
                            remotePath = null,
                            progressFraction = 0.02f,
                        )
                    }
                    pendingImages = (pendingImages + newItems).take(org.attache.tmux.MAX_ATTACHED_IMAGES)
                }
                for ((attachmentId, payload, _) in staged) {
                    val (bytes, ext) = payload
                    val remotePath = control.uploadImage(bytes, ext) { uploaded, totalBytes ->
                        val frac = if (totalBytes > 0L) {
                            (uploaded.toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 0.99f)
                        } else {
                            0.5f
                        }
                        pendingImages = pendingImages.map { item ->
                            if (item.id == attachmentId) item.copy(progressFraction = frac) else item
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (remotePath != null) {
                            pendingImages = pendingImages.map { item ->
                                if (item.id == attachmentId) {
                                    item.copy(remotePath = remotePath, progressFraction = 1f)
                                } else {
                                    item
                                }
                            }
                        } else {
                            pendingImages = pendingImages.filterNot { it.id == attachmentId }
                            snackbarHostState.showSnackbar("Failed to upload image")
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    imageUploadState = null
                }
            }
        }
        imageUploadJob = job
    }

    fun startRemoteFileDownload(remotePath: String) {
        val control = currentTmuxControl ?: return
        control.enqueueDownload(remotePath)
    }

    LaunchedEffect(currentTmuxControl) {
        val control = currentTmuxControl ?: return@LaunchedEffect
        launch {
            control.activeDownload.collect { progress ->
                fileDownloadState = progress
            }
        }
        launch {
            control.completedDownloads.collect { received ->
                val actionLabel = if (received.kind == AttacheFileKind.APK) "安装 APK" else "查看"
                val result = snackbarHostState.showSnackbar(
                    message = "已收到文件: ${received.name}",
                    actionLabel = actionLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    if (received.kind == AttacheFileKind.APK) {
                        AttacheFileStorage.openFileExternally(context, received) { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    } else {
                        menuImeWasVisible = imeVisible
                        resizeSuspended = true
                        showFileInboxDialog = true
                    }
                }
            }
        }
        launch {
            control.incomingPushFiles.collect { remotePath ->
                control.enqueueDownload(remotePath)
            }
        }
    }

    Scaffold(
        snackbarHost = {},
        containerColor = unifiedConsoleBg,
        contentColor = unifiedConsoleFg,
        modifier = modifier
            .fillMaxSize()
            .then(if (keepScreenOn) Modifier.keepScreenOn() else Modifier),
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .union(WindowInsets.imeAnimationTarget),
    ) { innerPadding ->
        val handleShortcut: (KeyEvent) -> Boolean = { keyEvent ->
            handleConsoleShortcut(
                keyEvent = keyEvent,
                volumeKeysChangeFontSize = volumeKeysChangeFontSize,
                copySelection = { selectionController?.copySelection() },
                pasteClipboardContents = { pasteClipboardContents() },
                increaseFontSize = { changeFontSize(1f) },
                decreaseFontSize = { changeFontSize(-1f) },
            )
        }

        // Terminal content with keyboard overlay
        val layoutDirection = LocalLayoutDirection.current
        val gestureBottom = with(density) {
            max(
                WindowInsets.mandatorySystemGestures.getBottom(density),
                WindowInsets.systemGestures.getBottom(density),
            ).toDp()
        }
        val safeDrawingBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
        val minBottomSafeArea = if (fullscreen) 0.dp else org.attache.tmux.AttacheChrome.MinBottomSafeInset
        val bottomSafeInset = maxOf(
            innerPadding.calculateBottomPadding(),
            gestureBottom,
            safeDrawingBottom,
            minBottomSafeArea,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    top = if (!titleBarHide) titleBarHeight else innerPadding.calculateTopPadding(),
                    bottom = bottomSafeInset,
                )
                .windowInsetsPadding(WindowInsets.imeAnimationTarget)
                .onPreviewKeyEvent(handleShortcut),
        ) {
            if (automationState.running || automationState.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        if (automationState.running) {
                            Text(stringResource(R.string.automation_progress, automationState.step, automationState.total))
                        }
                        automationState.error?.let { error ->
                            Text(
                                stringResource(R.string.automation_failure_step, automationState.failedStep ?: automationState.step, stringResource(error)),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (automationState.running) {
                        TextButton(onClick = { currentBridge?.cancelAutomation() }) {
                            Text(stringResource(R.string.automation_cancel_run))
                        }
                    }
                }
            }
            when {
                uiState.isLoading -> {
                    LoadingScreen(modifier = Modifier.fillMaxSize())
                }

                uiState.bridges.isNotEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        val bridge = uiState.bridges[uiState.currentBridgeIndex]
                        @Suppress("USELESS_ELVIS")
                        val pageTmuxControl by (bridge.tmuxControl ?: noTmuxControl).collectAsState()
                        val localView = androidx.compose.ui.platform.LocalView.current
                        val dismissSoftKeyboardOnSwipe by rememberUpdatedState {
                            if (
                                shouldDismissKeyboardOnDownwardSwipe(
                                    imeVisible = imeVisible,
                                    showSoftwareKeyboard = showSoftwareKeyboard,
                                    inlineInputActive = showTextInputDialog,
                                )
                            ) {
                                showSoftwareKeyboard = false
                                keyboardController?.hide()
                                localView.context
                                    .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                    ?.hideSoftInputFromWindow(localView.windowToken, 0)
                            }
                        }
                        val terminalModifier = if (swipeBetweenSessions) {
                            Modifier.sessionSwipeNavigation(
                                currentIndex = uiState.currentBridgeIndex,
                                sessionCount = uiState.bridges.size,
                                selectionActive = terminalSelectionActive,
                                onSwipeToSession = { index -> selectBridgePreservingKeyboard(index) },
                                onInteraction = { handleTerminalInteraction(isInteraction = false) },
                            )
                        } else {
                            Modifier
                        }.then(
                            // Attaché: swipes scroll tmux and taps pick a pane —
                            // except while text is being selected, whose handle
                            // drags must reach the terminal. Keep the modifier
                            // attached so activating selection mid-gesture does
                            // not recreate the pointerInput chain.
                            pageTmuxControl?.let { control ->
                                Modifier.tmuxTouch(
                                    control = control,
                                    selectionActive = { selectionController?.isSelectionActive == true },
                                    dimensions = { bridge.terminalEmulator.dimensions },
                                    onDismissKeyboard = { dismissSoftKeyboardOnSwipe() },
                                )
                            } ?: Modifier.dismissKeyboardOnDownwardScroll(
                                key = bridge,
                                selectionActive = { selectionController?.isSelectionActive == true },
                                canScroll = { isNonTmuxTerminalScrollable(bridge, pgUpDnGestureEnabled) },
                                onDismissKeyboard = { dismissSoftKeyboardOnSwipe() },
                            ),
                        )

                        Row(modifier = Modifier.fillMaxSize()) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isLandscape && landscapeSidebarOpen && pageTmuxControl != null,
                                enter = expandHorizontally(expandFrom = Alignment.Start, animationSpec = tween(durationMillis = 180)),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.Start, animationSpec = tween(durationMillis = 180)),
                            ) {
                                // Attaché (chrome-unify D): the docked sidebar is a
                                // floating panel drawn by AttacheFloatingChrome above
                                // this layout; the terminal only leaves room for it.
                                Spacer(
                                    modifier = Modifier
                                        .width(org.attache.tmux.AttacheChrome.LandscapePanelSlot)
                                        .fillMaxHeight(),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                key(bridge.host.id) {
                                    ConsoleTerminalPage(
                                        bridge = bridge,
                                        disconnected = disconnected,
                                        connecting = connecting,
                                        isActive = true,
                                        keyboardAlwaysVisible = keyboardAlwaysVisible,
                                        pgUpDnGestureEnabled = pgUpDnGestureEnabled,
                                        showSoftwareKeyboard = showSoftwareKeyboard,
                                        resizeSuspended = resizeSuspended,
                                        forceSize = forceSize,
                                        termFocusRequester = termFocusRequester,
                                        showExtraKeyboard = showExtraKeyboard,
                                        hasPlayedKeyboardAnimation = hasPlayedKeyboardAnimation,
                                        imeVisible = imeVisible,
                                        handleTerminalInteraction = {
                                            handleTerminalInteraction(resetTitleBarTimer = false)
                                        },
                                        onTerminalTap = {
                                            showSoftwareKeyboard = true
                                            handleTerminalInteraction(isTerminalTap = true)
                                        },
                                        onShowSoftwareKeyboardChange = { showSoftwareKeyboard = it },
                                        onImeVisibilityChange = { imeVisible = it },
                                        onTextInputRequest = {
                                            autoStartVoiceOnOpen = true
                                            showTextInputDialog = true
                                        },
                                        onDisconnectRequest = {
                                            bridge.dispatchDisconnect(DisconnectReason.USER_REQUESTED)
                                        },
                                        onKeyboardScrollInProgressChange = { inProgress ->
                                            keyboardScrollInProgress = inProgress
                                            handleTerminalInteraction(isInteraction = inProgress, resetTitleBarTimer = false)
                                        },
                                        onSelectionControllerChange = { selectionController = it },
                                        onComposeControllerChange = { composeController = it },
                                        onOpenUrl = ::openUrl,
                                        onPasteRequest = ::pasteClipboardContents,
                                        onInterceptKey = handleShortcut,
                                        onReconnect = { viewModel.reconnect(bridge) },
                                        snackbarHostState = snackbarHostState,
                                        showImeToggleKey = showImeToggleKey,
                                        isComposeModeActive = composeController?.isComposeModeActive == true,
                                        onToggleComposeMode = {
                                            composeController?.toggleComposeMode()
                                        },
                                        onShortcutModifierChange = {
                                            composeController?.syncImeShortcutInputMode(imeShortcutInputMode)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        terminalModifier = terminalModifier,
                                        isDarkTheme = isDarkTheme,
                                        inlineInputActive = showTextInputDialog,
                                        autoStartVoiceOnOpen = autoStartVoiceOnOpen,
                                        pendingImages = pendingImages,
                                        onRemovePendingImage = { id ->
                                            pendingImages = pendingImages.filterNot { it.id == id }
                                        },
                                        onDismissInlineInput = {
                                            pendingImages = emptyList()
                                            showTextInputDialog = false
                                            showSoftwareKeyboard = false
                                            termFocusRequester.requestFocus()
                                        },
                                        onSendInlineText = { sent ->
                                            composeController?.clearComposeBuffer()
                                            val readyPaths = latestPendingImages.mapNotNull { it.remotePath }
                                            pendingImages = emptyList()
                                            val combined = buildString {
                                                if (readyPaths.isNotEmpty()) {
                                                    append(readyPaths.joinToString(" "))
                                                    append(" ")
                                                }
                                                if (sent.isNotEmpty()) {
                                                    append(sent)
                                                }
                                            }
                                            if (combined.isNotEmpty()) {
                                                bridge.injectString(combined)
                                            }
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                                { bridge.injectString("\r") },
                                                80L,
                                            )
                                            showTextInputDialog = false
                                            showSoftwareKeyboard = false
                                            termFocusRequester.requestFocus()
                                        },
                                        onPickInlineImage = pageTmuxControl?.let {
                                            {
                                                if (pendingImages.size >= org.attache.tmux.MAX_ATTACHED_IMAGES) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "最多同时添加 ${org.attache.tmux.MAX_ATTACHED_IMAGES} 张图片",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    menuImeWasVisible = imeVisible
                                                    resizeSuspended = true
                                                    autoHideJobRef.titleBarJob?.cancel()
                                                    showImagePicker = true
                                                    try {
                                                        imagePickerLauncher.launch("image/*")
                                                    } catch (e: ActivityNotFoundException) {
                                                        showImagePicker = false
                                                        resizeSuspended = false
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar("No image picker found")
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        accessoryRow = pageTmuxControl?.let { control ->
                                            {
                                                // Menus open and close the way the overflow menu
                                                // does, so the keyboard, the PTY size and the
                                                // title bar's auto-hide treat them the same.
                                                fun openMenu(open: () -> Unit) {
                                                    menuImeWasVisible = imeVisible
                                                    resizeSuspended = true
                                                    autoHideJobRef.titleBarJob?.cancel()
                                                    open()
                                                }
                                                AttacheAccessoryRow(
                                                    control = control,
                                                    onTextInput = {
                                                        autoStartVoiceOnOpen = true
                                                        showTextInputDialog = true
                                                    },
                                                    onPaste = ::pasteClipboardContents,
                                                    onSendSlashCommand = { command ->
                                                        termFocusRequester.requestFocus()
                                                        sendAgentCommand(bridge, command)
                                                    },
                                                    slashMenuOpen = showSlashMenu,
                                                    onOpenSlashMenu = { openMenu { showSlashMenu = true } },
                                                    onDismissSlashMenu = {
                                                        showSlashMenu = false
                                                        termFocusRequester.requestFocus()
                                                    },
                                                    tmuxMenuOpen = showTmuxMenu,
                                                    onOpenTmuxMenu = { openMenu { showTmuxMenu = true } },
                                                    onDismissTmuxMenu = {
                                                        showTmuxMenu = false
                                                        termFocusRequester.requestFocus()
                                                    },
                                                    recentMenuOpen = showRecentWindowsPopover,
                                                    onToggleRecentMenu = {
                                                        if (!showRecentWindowsPopover) {
                                                            autoHideJobRef.titleBarJob?.cancel()
                                                            autoHideJobRef.keyboardJob?.cancel()
                                                            showRecentWindowsPopover = true
                                                        } else {
                                                            showRecentWindowsPopover = false
                                                            termFocusRequester.requestFocus()
                                                        }
                                                    },
                                                    onInteraction = { handleTerminalInteraction(resetTitleBarTimer = false) },
                                                    onPickImage = {
                                                        openMenu {
                                                            showImagePicker = true
                                                            try {
                                                                imagePickerLauncher.launch("image/*")
                                                            } catch (e: ActivityNotFoundException) {
                                                                showImagePicker = false
                                                                resizeSuspended = false
                                                                coroutineScope.launch {
                                                                    snackbarHostState.showSnackbar("No image picker found")
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onCopyText = {
                                                        openMenu { showCopyTextDialog = true }
                                                    },
                                                    onOpenFileInbox = {
                                                        openMenu { showFileInboxDialog = true }
                                                    },
                                                    onClearInput = {
                                                        composeController?.clearComposeBuffer()
                                                        bridge.injectString(CLEAR_INPUT_SEQUENCE)
                                                        termFocusRequester.requestFocus()
                                                    },
                                                    onInterrupt = {
                                                        bridge.injectString("\u0003")
                                                        termFocusRequester.requestFocus()
                                                    },
                                                    isDark = isDarkTheme,
                                                )
                                            }
                                        },
                                        recentWindowsPopover = pageTmuxControl?.let { control ->
                                            { bottomBarHeight ->
                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = showRecentWindowsPopover,
                                                    enter = fadeIn(animationSpec = tween(durationMillis = 150)),
                                                    exit = fadeOut(animationSpec = tween(durationMillis = 120)),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(bottom = bottomBarHeight),
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .clickable(
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null,
                                                                ) {
                                                                    showRecentWindowsPopover = false
                                                                    termFocusRequester.requestFocus()
                                                                }
                                                                .testTag("tmuxRecentPopoverScrim"),
                                                        )
                                                        TmuxRecentWindowsPopover(
                                                            control = control,
                                                            isDark = isDarkTheme,
                                                            onDismiss = {
                                                                showRecentWindowsPopover = false
                                                                termFocusRequester.requestFocus()
                                                            },
                                                            modifier = Modifier
                                                                .align(Alignment.BottomCenter)
                                                                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }

                                if (terminalSelectionActive) {
                                    selectionController?.let { controller ->
                                        AttacheSelectionBar(
                                            selectionController = controller,
                                            onCopied = { copied ->
                                                if (copied.isNotEmpty()) {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            message = "已复制 ${copied.length} 个字符",
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                    }
                                                }
                                            },
                                            onOpenCopySheet = if (pageTmuxControl != null) {
                                                {
                                                    menuImeWasVisible = imeVisible
                                                    resizeSuspended = true
                                                    showCopyTextDialog = true
                                                }
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.align(Alignment.TopCenter),
                                        )
                                    }
                                }

                                imageUploadState?.let { upload ->
                                    val percent = if (upload.totalBytes > 0L) {
                                        ((upload.uploadedBytes * 100L) / upload.totalBytes).coerceIn(0L, 100L)
                                    } else {
                                        0L
                                    }
                                    val fraction = if (upload.totalBytes > 0L) {
                                        (upload.uploadedBytes.toFloat() / upload.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        tonalElevation = 6.dp,
                                        shadowElevation = 6.dp,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                            .fillMaxWidth()
                                            .testTag("imageUploadProgressCard"),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = "正在上传图片…",
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                    Text(
                                                        text = "${formatByteCount(upload.uploadedBytes)} / ${formatByteCount(upload.totalBytes)} ($percent%)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { fraction },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 6.dp),
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    imageUploadJob?.cancel()
                                                    imageUploadJob = null
                                                    imageUploadState = null
                                                    termFocusRequester.requestFocus()
                                                },
                                                modifier = Modifier
                                                    .padding(start = 8.dp)
                                                    .testTag("cancelImageUpload"),
                                            ) {
                                                Text("取消")
                                            }
                                        }
                                    }
                                }

                                fileDownloadState?.takeIf { !showFileInboxDialog }?.let { download ->
                                    val percent = if (download.totalBytes > 0L) {
                                        ((download.downloadedBytes * 100L) / download.totalBytes).coerceIn(0L, 100L)
                                    } else {
                                        0L
                                    }
                                    val fraction = if (download.totalBytes > 0L) {
                                        (download.downloadedBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        tonalElevation = 6.dp,
                                        shadowElevation = 6.dp,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                            .fillMaxWidth()
                                            .testTag("fileDownloadProgressCard"),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = "正在接收文件: ${download.fileName}",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Text(
                                                        text = "${formatByteCount(download.downloadedBytes)} / ${formatByteCount(download.totalBytes)} ($percent%)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { fraction },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 6.dp),
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    currentTmuxControl?.cancelActiveDownload()
                                                    fileDownloadJob?.cancel()
                                                    fileDownloadJob = null
                                                    fileDownloadState = null
                                                    termFocusRequester.requestFocus()
                                                },
                                                modifier = Modifier.padding(start = 8.dp),
                                            ) {
                                                Text("取消")
                                            }
                                        }
                                    }
                                }

                                // Top-aligned SnackbarHost (directly below the top navigation bar)
                                // so file-received toasts and notifications never cover the bottom toolbar buttons.
                                SnackbarHost(
                                    hostState = snackbarHostState,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }

                        // Portrait drawer: drawn full-screen (scrim over the
                        // capsules too) by AttacheFloatingChrome at the end of
                        // the Scaffold content.
                    }
                }
            }
        }

        if (showFileInboxDialog) {
            AttacheFileInboxDialog(
                control = currentTmuxControl,
                activeDownload = fileDownloadState,
                onStartDownload = { remotePath -> startRemoteFileDownload(remotePath) },
                onCancelDownload = {
                    currentTmuxControl?.cancelActiveDownload()
                    fileDownloadJob?.cancel()
                    fileDownloadJob = null
                    fileDownloadState = null
                },
                onDismiss = {
                    showFileInboxDialog = false
                    termFocusRequester.requestFocus()
                },
                onShowMessage = { msg ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(msg)
                    }
                },
            )
        }

        if (showCopyTextDialog && currentTmuxControl != null) {
            TmuxCopyTextDialog(
                control = currentTmuxControl!!,
                onDismiss = {
                    showCopyTextDialog = false
                    termFocusRequester.requestFocus()
                },
                onCopied = { copied ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "已复制 ${copied.length} 个字符到剪贴板",
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )
        }

        if (showToolbarStyleDialog) {
            val currentFontSizeSp by (currentBridge?.fontSizeFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(14f) }).collectAsState()
            AttacheToolbarStyleDialog(
                currentStyle = currentToolbarStyle,
                onSelectStyle = { style ->
                    AttacheToolbarPrefs.setStyle(context, style)
                },
                onDismiss = {
                    showToolbarStyleDialog = false
                    termFocusRequester.requestFocus()
                },
                currentFontSizeSp = currentFontSizeSp,
                onChangeFontSize = { delta -> changeFontSize(delta) },
            )
        }

        if (showKeyboardSettings && currentBridge != null) {
            KeyboardWorkflowDialog(onDismiss = { showKeyboardSettings = false }) {
                ConsoleKeyboardSettings(currentBridge, onNavigateBack = { showKeyboardSettings = false })
            }
        }
        if (showConnectionInfoDialog) {
            var details by remember(currentBridgeId) { mutableStateOf(viewModel.connectionDetails()) }
            LaunchedEffect(currentBridgeId, viewModel) {
                while (true) {
                    details = viewModel.connectionDetails()
                    delay(1_000)
                }
            }
            details?.let { details ->
                ConnectionInfoDialog(details = details, onDismiss = { showConnectionInfoDialog = false })
            }
        }

        // Dialogs
        if (showUrlScanDialog) {
            UrlScanDialog(
                urls = scannedUrls,
                onDismiss = { showUrlScanDialog = false },
                onUrlClick = { url ->
                    openUrl(url)
                },
            )
        }

        currentAuthBanner?.let { banner ->
            AuthBannerDialog(
                banner = banner,
                onDismiss = {
                    currentBridge?.dismissAuthBanner(banner.id)
                },
            )
        }

        if (showResizeDialog && currentBridge != null) {
            ResizeDialog(
                currentBridge = currentBridge,
                isForced = forceSize != null,
                onDismiss = { showResizeDialog = false },
                onResize = { width, height ->
                    // Resize the terminal emulator
                    forceSize = Pair(height, width)
                },
                onDisableForceSize = {
                    // Disable force size for this session
                    forceSize = null
                },
            )
        }

        if (showDisconnectDialog && currentBridge != null) {
            HostDisconnectDialog(
                host = currentBridge.host,
                onDismiss = { showDisconnectDialog = false },
                onConfirm = {
                    showDisconnectDialog = false
                    currentBridge.dispatchDisconnect(DisconnectReason.USER_REQUESTED)
                },
            )
        }

        if (showSessionPickerDialog && hasMultipleSessions) {
            SessionPickerDialog(
                bridges = uiState.bridges,
                currentBridgeIndex = uiState.currentBridgeIndex,
                onDismiss = { showSessionPickerDialog = false },
                onSelectBridge = { index ->
                    showSessionPickerDialog = false
                    selectBridgePreservingKeyboard(index)
                },
            )
        }

        BackHandler(enabled = showTextInputDialog) {
            pendingImages = emptyList()
            showTextInputDialog = false
            showSoftwareKeyboard = false
            termFocusRequester.requestFocus()
        }

        // Attaché (chrome-unify scheme D): on a tmux host the top bar is two
        // floating glass capsules plus the floating sidebar panel; other hosts
        // keep the overlay TopAppBar below.
        val chromeTmuxControl by (currentBridge?.tmuxControl ?: noTmuxControl).collectAsState()
        val titleBarVisible = !titleBarHide || showTitleBar
        // Attaché: in landscape the title capsule toggles the docked left
        // panel, and in portrait it floats the same panel over the terminal.
        val toggleAttacheSidebar: () -> Unit = {
            if (isLandscape) {
                val opening = !landscapeSidebarOpen
                landscapeSidebarOpen = opening
                if (opening) {
                    showSoftwareKeyboard = false
                    keyboardController?.hide()
                }
            } else {
                if (!portraitDrawerOpen) {
                    showSoftwareKeyboard = false
                    keyboardController?.hide()
                    autoHideJobRef.titleBarJob?.cancel()
                    portraitDrawerOpen = true
                } else {
                    portraitDrawerOpen = false
                    termFocusRequester.requestFocus()
                }
            }
        }
        val topBarActions: @Composable RowScope.() -> Unit = {
            val tmuxControl = chromeTmuxControl
            if (tmuxControl != null) {
                // Right glass capsule: theme ▦ (⋮ follows below). Font size
                // adjustment now lives inside the Settings (▦) dialog.
                val chipText = org.attache.tmux.attacheGlassPalette(isDarkTheme).ink
                org.attache.tmux.CapsuleActionButton(
                    onClick = { toggleTheme() },
                    modifier = Modifier.testTag("title_bar_theme_toggle"),
                ) {
                    Icon(
                        if (isDarkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        contentDescription = null,
                        tint = chipText,
                        modifier = Modifier.size(17.dp),
                    )
                }
                org.attache.tmux.CapsuleActionButton(
                    onClick = {
                        menuImeWasVisible = imeVisible
                        resizeSuspended = true
                        autoHideJobRef.titleBarJob?.cancel()
                        showToolbarStyleDialog = true
                    },
                    modifier = Modifier.testTag("title_bar_toolbar_style"),
                ) {
                    Icon(
                        Icons.Outlined.GridView,
                        contentDescription = null,
                        tint = chipText,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            if (hasMultipleSessions) {
                IconButton(
                    onClick = { showSessionPickerDialog = true },
                    modifier = if (tmuxControl != null) Modifier.size(width = 38.dp, height = 32.dp) else Modifier,
                ) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = stringResource(R.string.console_switch_session),
                    )
                }
            }

            // Text Input button — on a tmux host it is in the row
            // above the keys instead.
            if (tmuxControl == null) IconButton(
                onClick = { showTextInputDialog = true },
                enabled = currentBridge != null,
                modifier = Modifier.testTag("title_bar_text_input"),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.console_menu_text_input),
                )
            }

            // Paste button - always visible, except on a tmux host,
            // where the chips need the width more; the text input
            // dialog still takes a paste.
            if (tmuxControl == null) IconButton(
                onClick = {
                    pasteClipboardContents()
                },
                enabled = currentBridge != null,
            ) {
                Icon(
                    Icons.Default.ContentPaste,
                    contentDescription = stringResource(R.string.console_menu_paste),
                )
            }

            // More menu
            Box {
                IconButton(
                    onClick = {
                        // Refresh menu state to update enabled/disabled items
                        viewModel.refreshMenuState()
                        menuImeWasVisible = imeVisible
                        resizeSuspended = true
                        autoHideJobRef.titleBarJob?.cancel()
                        showMenu = true
                    },
                    modifier = if (tmuxControl != null) Modifier.size(width = 38.dp, height = 32.dp) else Modifier,
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.button_more_options),
                        modifier = if (tmuxControl != null) Modifier.size(19.dp) else Modifier,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = {
                        showMenu = false
                        termFocusRequester.requestFocus()
                    },
                ) {
                    DropdownMenuItem(
                        text = { Text("返回 Host 列表") },
                        onClick = {
                            showMenu = false
                            navigateBack()
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        },
                    )

                    // Reconnect (shown only when disconnected)
                    if (disconnected) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_reconnect)) },
                            onClick = {
                                showMenu = false
                                viewModel.reconnect(currentBridge)
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            },
                        )
                    }

                    if (hasMultipleSessions) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_previous_session)) },
                            onClick = {
                                showMenu = false
                                selectBridgePreservingKeyboard(uiState.currentBridgeIndex - 1)
                            },
                            enabled = uiState.currentBridgeIndex > 0,
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_next_session)) },
                            onClick = {
                                showMenu = false
                                selectBridgePreservingKeyboard(uiState.currentBridgeIndex + 1)
                            },
                            enabled = uiState.currentBridgeIndex < uiState.bridges.lastIndex,
                        )
                    }

                    // Disconnect/Close
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (!sessionOpen && disconnected) {
                                    stringResource(R.string.console_menu_close)
                                } else {
                                    stringResource(R.string.list_host_disconnect)
                                },
                            )
                        },
                        onClick = {
                            showMenu = false
                            showDisconnectDialog = true
                        },
                        enabled = currentBridge != null,
                        leadingIcon = {
                            Icon(Icons.Default.LinkOff, null)
                        },
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.console_menu_connection_info)) },
                        onClick = {
                            showMenu = false
                            showConnectionInfoDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        enabled = currentBridge != null,
                    )

                    // URL Scan
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.console_menu_urlscan)) },
                        onClick = {
                            showMenu = false
                            currentBridge?.let { bridge ->
                                scannedUrls = bridge.scanForURLs()
                                showUrlScanDialog = true
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null)
                        },
                        enabled = currentBridge != null,
                    )

                    // Resize
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.console_menu_resize)) },
                        onClick = {
                            showMenu = false
                            showResizeDialog = true
                        },
                        enabled = sessionOpen,
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("底部快捷栏风格")
                                Text(
                                    text = currentToolbarStyle.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            showToolbarStyleDialog = true
                        },
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.keyboard_settings_title)) },
                        onClick = {
                            showMenu = false
                            showKeyboardSettings = true
                        },
                        enabled = currentBridge != null,
                    )

                    // Compose mode (IME) toggle — persistent entry so users can always
                    // enable multi-byte IME composition (Japanese, Chinese, Korean, etc.)
                    // even when the optional IME key on the special-keys row is hidden.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.console_menu_compose_mode)) },
                        onClick = {
                            showMenu = false
                            composeController?.toggleComposeMode()
                        },
                        enabled = composeController != null,
                        leadingIcon = {
                            Icon(Icons.Default.Language, contentDescription = null)
                        },
                        trailingIcon = {
                            Checkbox(
                                checked = composeController?.isComposeModeActive == true,
                                onCheckedChange = null,
                            )
                        },
                    )

                    // Port Forwards (if available)
                    if (canForwardPorts) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_menu_portforwards)) },
                            onClick = {
                                showMenu = false
                                currentBridge.host.id.let {
                                    onNavigateToPortForwards(
                                        it,
                                    )
                                }
                            },
                            enabled = sessionOpen,
                        )
                    }

                    // Fullscreen toggle
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pref_fullscreen_title)) },
                        onClick = {
                            fullscreen = !fullscreen
                            prefs.edit { putBoolean("fullscreen", fullscreen) }
                        },
                        trailingIcon = {
                            Checkbox(
                                checked = fullscreen,
                                onCheckedChange = null,
                            )
                        },
                    )

                    // Title bar auto-hide toggle
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pref_titlebarhide_title)) },
                        onClick = {
                            titleBarHide = !titleBarHide
                            prefs.edit { putBoolean("titlebarhide", titleBarHide) }
                            handleTerminalInteraction(isTerminalTap = true)
                        },
                        trailingIcon = {
                            Checkbox(
                                checked = titleBarHide,
                                onCheckedChange = null,
                            )
                        },
                    )
                }
            }
        }
        chromeTmuxControl?.let { control ->
            org.attache.tmux.AttacheFloatingChrome(
                control = control,
                isDark = isDarkTheme,
                isLandscape = isLandscape,
                barVisible = titleBarVisible,
                sidebarOpen = if (isLandscape) landscapeSidebarOpen else portraitDrawerOpen,
                onBack = navigateBack,
                onToggleSidebar = toggleAttacheSidebar,
                onCloseSidebar = {
                    portraitDrawerOpen = false
                    termFocusRequester.requestFocus()
                },
                onBarHeightChanged = { titleBarHeight = it },
                actions = topBarActions,
                bottomSafeInset = bottomSafeInset,
                backContentDescription = stringResource(R.string.button_back),
                sidebar = { sidebarModifier ->
                    AttacheSidebar(
                        control = control,
                        hosts = hostEntries,
                        onSelectHost = ::selectHostFromSidebar,
                        isDark = isDarkTheme,
                        // Portrait: let the tapped row's highlight land, then
                        // close the drawer (~180ms, per the D mockup).
                        onDismiss = if (isLandscape) {
                            null
                        } else {
                            {
                                coroutineScope.launch {
                                    delay(180)
                                    portraitDrawerOpen = false
                                    termFocusRequester.requestFocus()
                                }
                            }
                        },
                        modifier = sidebarModifier,
                    )
                },
            )
        }


        // Overlay TopAppBar - always visible when titleBarHide is false,
        // or temporarily visible when titleBarHide is true and showTitleBar is true
        if (titleBarVisible) {
            val tmuxControl = chromeTmuxControl
            if (tmuxControl == null) {
                TopAppBar(
                    title = {
                        Text(
                            currentBridge?.host?.nickname
                                ?: stringResource(R.string.console_default_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .testTag("top_app_bar")
                        .onSizeChanged {
                            titleBarHeight = with(density) { it.height.toDp() }
                        },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.button_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (titleBarHide) {
                            unifiedConsoleBg.copy(alpha = 0.85f)
                        } else {
                            unifiedConsoleBg
                        },
                        titleContentColor = unifiedConsoleFg,
                        navigationIconContentColor = unifiedConsoleFg,
                        actionIconContentColor = unifiedConsoleFg,
                    ),
                    actions = topBarActions,
                )
            }

            // Progress indicator for OSC 9;4 progress reporting
            val progressState = uiState.progressState
            if (progressState != null && progressState != ProgressState.HIDDEN) {
                val progressColor = when (progressState) {
                    ProgressState.ERROR -> MaterialTheme.colorScheme.error
                    ProgressState.WARNING -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }

                if (progressState == ProgressState.INDETERMINATE) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = titleBarHeight),
                        color = progressColor,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { uiState.progressValue / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = titleBarHeight),
                        color = progressColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun HostDisconnectDialog(
    host: Host,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(stringResource(R.string.disconnect_host_alert, host.nickname))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.button_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_no))
            }
        },
    )
}

@Composable
internal fun SessionPickerDialog(
    bridges: List<TerminalBridge>,
    currentBridgeIndex: Int,
    onDismiss: () -> Unit,
    onSelectBridge: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.console_switch_session))
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(
                    count = bridges.size,
                ) { index ->
                    val bridge = bridges[index]
                    TextButton(
                        onClick = { onSelectBridge(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (index == currentBridgeIndex) {
                                "\u2022 ${bridge.host.nickname}"
                            } else {
                                bridge.host.nickname
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        },
    )
}
