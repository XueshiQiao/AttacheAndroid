package org.attache.tmux

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Visual and ergonomic style applied to BOTH the upper Attaché action row
 * and the lower terminal modifier/arrow key row.
 */
enum class AttacheToolbarStyle(
    val id: String,
    val title: String,
    val subtitle: String,
) {
    TERMINAL_DECK(
        id = "terminal_deck",
        title = "方案 B · 极客纯血 Terminal 机械盘",
        subtitle = "全等宽字体机械键帽 + 琥珀橙/极客蓝/翠绿语义分色 + 独立 ^C/^U 键",
    ),
    COPILOT_DOCK(
        id = "copilot_dock",
        title = "方案 D · 现代 AI Copilot 悬浮胶囊坞",
        subtitle = "16dp 圆角悬浮玻璃坞 + 彩色胶囊按钮 + 无边框轻量化辅助键",
    ),
    ;

    companion object {
        fun fromId(id: String?): AttacheToolbarStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
                ?: COPILOT_DOCK
    }
}

object AttacheToolbarPrefs {
    const val PREF_KEY = "attache_toolbar_style"
    const val PREF_ICON_ONLY_KEY = "attache_toolbar_icon_only"
    private val _styleFlow = MutableStateFlow(AttacheToolbarStyle.COPILOT_DOCK)
    val styleFlow: StateFlow<AttacheToolbarStyle> = _styleFlow.asStateFlow()

    private val _iconOnlyFlow = MutableStateFlow(false)
    val iconOnlyFlow: StateFlow<Boolean> = _iconOnlyFlow.asStateFlow()

    private val _isDarkFlow = MutableStateFlow(true)
    val isDarkFlow: StateFlow<Boolean> = _isDarkFlow.asStateFlow()

    @Volatile private var initialized = false

    fun syncFromContext(context: Context): AttacheToolbarStyle {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val style = AttacheToolbarStyle.fromId(prefs.getString(PREF_KEY, AttacheToolbarStyle.COPILOT_DOCK.id))
        _styleFlow.value = style
        _iconOnlyFlow.value = prefs.getBoolean(PREF_ICON_ONLY_KEY, false)
        initialized = true
        return style
    }

    fun ensureLoaded(context: Context) {
        if (!initialized) {
            syncFromContext(context)
        }
    }

    fun setStyle(context: Context, style: AttacheToolbarStyle) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_KEY, style.id)
        }
        _styleFlow.value = style
        initialized = true
    }

    fun setIconOnly(context: Context, iconOnly: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_ICON_ONLY_KEY, iconOnly)
        }
        _iconOnlyFlow.value = iconOnly
        initialized = true
    }

    fun updateDarkTheme(isDark: Boolean) {
        _isDarkFlow.value = isDark
    }
}

/**
 * Dialog allowing the user to pick between Scheme B (`TERMINAL_DECK`) and
 * Scheme D (`COPILOT_DOCK`) and toggle whether the top accessory row shows
 * icon-only (`🎤`, `/`, `🖼`, `📂`, `📋`, `📥`, `⊞`, `⌫`) or icon + label.
 */
@Composable
fun AttacheToolbarStyleDialog(
    currentStyle: AttacheToolbarStyle,
    onSelectStyle: (AttacheToolbarStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val iconOnly by AttacheToolbarPrefs.iconOnlyFlow.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("底部双排快捷栏设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "第一排按钮显示",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(
                        false to "图标 + 文字",
                        true to "仅图标 (🎤  /  📂)",
                    ).forEach { (onlyIcons, label) ->
                        val selected = iconOnly == onlyIcons
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { AttacheToolbarPrefs.setIconOnly(context, onlyIcons) },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "整体视觉风格 (B / D)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                AttacheToolbarStyle.entries.forEach { option ->
                    val selected = option == currentStyle
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectStyle(option) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectStyle(option) },
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
    )
}

/**
 * Wraps BOTH [AttacheAccessoryRow] and the lower `TerminalKeyboard` row into a
 * unified container matching either Scheme B (Terminal Deck) or Scheme D (Copilot Dock).
 * When [inlineInputActive] is true, the two button rows are replaced in-place by an
 * inline voice/text input box with a bottom-right Send button.
 */
@Composable
fun AttacheToolbarContainer(
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme(),
    inlineInputActive: Boolean = false,
    onDismissInlineInput: () -> Unit = {},
    onSendInlineText: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        AttacheToolbarPrefs.ensureLoaded(context)
    }
    LaunchedEffect(isDark) {
        AttacheToolbarPrefs.updateDarkTheme(isDark)
    }
    val style by AttacheToolbarPrefs.styleFlow.collectAsState()

    when (style) {
        AttacheToolbarStyle.TERMINAL_DECK -> {
            val containerBg = if (isDark) Color(0xFF090C10) else Color(0xFFEAECEF)
            val containerBorder = if (isDark) Color(0xFF21262D) else Color(0xFFD0D7DE)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .background(containerBg)
                    .border(width = 0.5.dp, color = containerBorder)
                    .padding(horizontal = 5.dp, vertical = 4.dp),
            ) {
                if (inlineInputActive) {
                    InlineVoiceTextInputBar(
                        style = style,
                        isDark = isDark,
                        onDismiss = onDismissInlineInput,
                        onSend = onSendInlineText,
                    )
                } else {
                    content()
                }
            }
        }
        AttacheToolbarStyle.COPILOT_DOCK -> {
            val outerBackdrop = if (isDark) Color(0xFF090C10) else Color(0xFFF3F2EE)
            val dockBg = if (isDark) Color(0xFF171D29) else Color(0xFFFFFFFF)
            val dockBorder = if (isDark) Color(0x26FFFFFF) else Color(0x1F0F172A)
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .background(outerBackdrop)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = dockBg,
                    border = BorderStroke(1.dp, dockBorder),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 5.dp, vertical = 5.dp),
                    ) {
                        if (inlineInputActive) {
                            InlineVoiceTextInputBar(
                                style = style,
                                isDark = isDark,
                                onDismiss = onDismissInlineInput,
                                onSend = onSendInlineText,
                            )
                        } else {
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineVoiceTextInputBar(
    style: AttacheToolbarStyle,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val isTerminalDeck = style == AttacheToolbarStyle.TERMINAL_DECK
    val boxShape = if (isTerminalDeck) RoundedCornerShape(6.dp) else RoundedCornerShape(12.dp)
    val boxBg = when {
        isTerminalDeck && isDark -> Color(0xFF121720)
        isTerminalDeck && !isDark -> Color(0xFFFFFFFF)
        isDark -> Color(0xFF0F141E)
        else -> Color(0xFFF1F5F9)
    }
    val boxBorder = when {
        isTerminalDeck && isDark -> Color(0xFF30363D)
        isTerminalDeck && !isDark -> Color(0xFFCBD5E1)
        isDark -> Color(0x3358A6FF)
        else -> Color(0x332563EB)
    }
    val textColor = if (isDark) Color(0xFFE6EDF3) else Color(0xFF0F172A)
    val hintColor = if (isDark) Color(0xFF8B949E) else Color(0xFF64748B)
    val accentBg = when {
        isTerminalDeck && isDark -> Color(0xFF238636)
        isTerminalDeck && !isDark -> Color(0xFF16A34A)
        isDark -> Color(0xFF2563EB)
        else -> Color(0xFF2563EB)
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 128.dp)
            .clip(boxShape)
            .background(boxBg)
            .border(1.dp, boxBorder, boxShape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("inlineVoiceInputBar"),
    ) {
        // Top-left / Left collapse button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.Top)
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isDark) Color(0x1AFFFFFF) else Color(0x140F172A))
                .clickable { onDismiss() }
                .testTag("inlineInputCollapse"),
        ) {
            Text(
                text = "✕",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = hintColor,
            )
        }

        // Center multi-line voice/text input field
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 58.dp, max = 114.dp)
                .padding(vertical = 2.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "直接用键盘语音说话或输入内容…",
                    color = hintColor,
                    fontSize = 13.5.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = 14.sp,
                    fontFamily = if (isTerminalDeck) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(if (isDark) Color(0xFF58A6FF) else Color(0xFF2563EB)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("inlineInputField"),
            )
        }

        // Bottom-right Send button
        val canSend = text.isNotEmpty()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(34.dp)
                .clip(if (isTerminalDeck) RoundedCornerShape(5.dp) else RoundedCornerShape(999.dp))
                .background(if (canSend) accentBg else accentBg.copy(alpha = 0.38f))
                .clickable(enabled = canSend) {
                    val toSend = text
                    text = ""
                    onSend(toSend)
                }
                .padding(horizontal = 14.dp)
                .testTag("inlineInputSend"),
        ) {
            Text(
                text = "发送 ↵",
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isTerminalDeck) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

/**
 * A slash command an agent CLI takes at its prompt. [only] names the one
 * agent that knows it, shown so a tap on one the pane's agent lacks is not a
 * surprise; null means Claude Code and Antigravity both do.
 */
data class SlashCommand(val text: String, val summary: String, val only: String? = null)

/** Fixed rather than read from the pane: the prompt is the agent's own
 * drawing, and nothing tmux reports says which commands it accepts. Every
 * entry is plain `/[a-z-]+`, so no shell or terminal gives it a meaning. */
val AGENT_SLASH_COMMANDS = listOf(
    SlashCommand("/clear", "New conversation"),
    SlashCommand("/compact", "Summarise to free context"),
    SlashCommand("/resume", "Earlier conversation", only = "Claude"),
    SlashCommand("/model", "Switch model"),
    SlashCommand("/context", "Context usage", only = "Claude"),
    SlashCommand("/usage", "Quota and rate limits"),
    SlashCommand("/plan", "Plan first"),
    SlashCommand("/goal", "Work until done", only = "Antigravity"),
    SlashCommand("/exit", "Exit agent"),
)

/**
 * Types [command] at the agent's prompt without submitting it, leaving it in
 * the input line for the user to add arguments or press Return.
 */
fun sendAgentCommand(bridge: org.connectbot.service.TerminalBridge, command: String) {
    require(SLASH_COMMAND.matches(command)) { "not a slash command: $command" }
    bridge.injectString(command)
}

private val SLASH_COMMAND = Regex("^/[a-z][a-z-]*$")

/**
 * The row above the terminal keys on a tmux host: renders either as
 * Scheme B (`TERMINAL_DECK` · 极客纯血 Terminal 机械盘) or
 * Scheme D (`COPILOT_DOCK` · 现代 AI Copilot 悬浮胶囊坞).
 */
@Composable
fun AttacheAccessoryRow(
    control: TmuxControl,
    onTextInput: () -> Unit,
    onPaste: () -> Unit,
    onSendSlashCommand: (String) -> Unit,
    slashMenuOpen: Boolean,
    onOpenSlashMenu: () -> Unit,
    onDismissSlashMenu: () -> Unit,
    tmuxMenuOpen: Boolean,
    onOpenTmuxMenu: () -> Unit,
    onDismissTmuxMenu: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
    onOpenFileInbox: () -> Unit = {},
    onCopyText: () -> Unit = {},
    onClearInput: () -> Unit = {},
    onInterrupt: () -> Unit = {},
    isDark: Boolean = isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        AttacheToolbarPrefs.ensureLoaded(context)
    }
    LaunchedEffect(isDark) {
        AttacheToolbarPrefs.updateDarkTheme(isDark)
    }
    val style by AttacheToolbarPrefs.styleFlow.collectAsState()
    val iconOnly by AttacheToolbarPrefs.iconOnlyFlow.collectAsState()

    when (style) {
        AttacheToolbarStyle.TERMINAL_DECK -> {
            val defaultKeyBg = if (isDark) Color(0xFF121720) else Color(0xFFFFFFFF)
            val defaultKeyBorder = if (isDark) Color(0xFF2B323C) else Color(0xFFCBD5E1)
            val defaultKeyText = if (isDark) Color(0xFFC9D1D9) else Color(0xFF1E293B)

            val cyanText = if (isDark) Color(0xFF58A6FF) else Color(0xFF1D4ED8)
            val cyanBorder = if (isDark) Color(0x6658A6FF) else Color(0xFF93C5FD)
            val cyanBg = if (isDark) Color(0x1858A6FF) else Color(0xFFDBEAFE)

            val greenText = if (isDark) Color(0xFF3FB950) else Color(0xFF15803D)
            val greenBorder = if (isDark) Color(0x663FB950) else Color(0xFF86EFAC)
            val greenBg = if (isDark) Color(0x173FB950) else Color(0xFFDCFCE7)

            val amberText = if (isDark) Color(0xFFD29922) else Color(0xFFB45309)
            val amberBorder = if (isDark) Color(0x66D29922) else Color(0xFFFCD34D)
            val amberBg = if (isDark) Color(0x14D29922) else Color(0xFFFEF3C7)

            val redText = if (isDark) Color(0xFFF85149) else Color(0xFFDC2626)
            val redBorder = if (isDark) Color(0x66F85149) else Color(0xFFFCA5A5)
            val redBg = if (isDark) Color(0x14F85149) else Color(0xFFFEE2E2)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .height(35.dp)
                    .testTag("attacheAccessoryRow"),
            ) {
                // 1. Top-left Voice/Text Input toggle
                TerminalDeckKey(
                    label = if (iconOnly) "🎤" else "🎤说",
                    textColor = amberText,
                    borderColor = amberBorder,
                    bgColor = amberBg,
                    tag = "accessoryTextInput",
                    onClick = { onInteraction(); onTextInput() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 0.95f),
                )

                // 2. /cmd (Slash menu)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TerminalDeckKey(
                        label = if (iconOnly) "/" else "/cmd",
                        textColor = cyanText,
                        borderColor = cyanBorder,
                        bgColor = cyanBg,
                        tag = "accessorySlash",
                        onClick = onOpenSlashMenu,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SlashCommandDropdown(
                        expanded = slashMenuOpen,
                        onDismiss = onDismissSlashMenu,
                        onSendSlashCommand = onSendSlashCommand,
                    )
                }

                // 3. tmux (Pane / Window actions)
                TmuxActionsButton(
                    control = control,
                    expanded = tmuxMenuOpen,
                    onOpen = onOpenTmuxMenu,
                    onDismiss = onDismissTmuxMenu,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onClick ->
                    TerminalDeckKey(
                        label = if (iconOnly) "⊞" else "tmux",
                        textColor = cyanText,
                        borderColor = cyanBorder,
                        bgColor = cyanBg,
                        tag = "tmuxActions",
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 4. +img (Send image)
                TerminalDeckKey(
                    label = if (iconOnly) "🖼" else "+img",
                    textColor = defaultKeyText,
                    borderColor = defaultKeyBorder,
                    bgColor = defaultKeyBg,
                    tag = "accessoryImage",
                    onClick = { onInteraction(); onPickImage() },
                    modifier = Modifier.weight(1f),
                )

                // 5. inbox (File Inbox)
                TerminalDeckKey(
                    label = if (iconOnly) "📂" else "inbox",
                    textColor = defaultKeyText,
                    borderColor = defaultKeyBorder,
                    bgColor = defaultKeyBg,
                    tag = "accessoryFileInbox",
                    onClick = { onInteraction(); onOpenFileInbox() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 1.05f),
                )

                // 6. copy (Extract pane text)
                TerminalDeckKey(
                    label = if (iconOnly) "📋" else "copy",
                    textColor = defaultKeyText,
                    borderColor = defaultKeyBorder,
                    bgColor = defaultKeyBg,
                    tag = "accessoryCopy",
                    onClick = { onInteraction(); onCopyText() },
                    modifier = Modifier.weight(1f),
                )

                // 7. paste (Clipboard paste)
                TerminalDeckKey(
                    label = if (iconOnly) "📥" else "paste",
                    textColor = greenText,
                    borderColor = greenBorder,
                    bgColor = greenBg,
                    tag = "accessoryPaste",
                    onClick = { onInteraction(); onPaste() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 1.05f),
                )

                // 8. ^U (Clear input line)
                TerminalDeckKey(
                    label = if (iconOnly) "⌫" else "^U",
                    textColor = redText,
                    borderColor = redBorder,
                    bgColor = redBg,
                    tag = "accessoryClear",
                    onClick = { onInteraction(); onClearInput() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 0.8f),
                )
            }
        }

        AttacheToolbarStyle.COPILOT_DOCK -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .testTag("attacheAccessoryRow"),
            ) {
                // 1. Top-left Voice/Text Input toggle
                CopilotDockChip(
                    label = if (iconOnly) "🎤" else "🎤说",
                    isAiAccent = true,
                    isDark = isDark,
                    tag = "accessoryTextInput",
                    onClick = { onInteraction(); onTextInput() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 0.95f),
                )

                // 2. ✦ /指令
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CopilotDockChip(
                        label = if (iconOnly) "/" else "/指令",
                        isAiAccent = true,
                        isDark = isDark,
                        tag = "accessorySlash",
                        onClick = onOpenSlashMenu,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SlashCommandDropdown(
                        expanded = slashMenuOpen,
                        onDismiss = onDismissSlashMenu,
                        onSendSlashCommand = onSendSlashCommand,
                    )
                }

                // 3. 🖼 传图
                CopilotDockChip(
                    label = if (iconOnly) "🖼" else "🖼图",
                    isDark = isDark,
                    tag = "accessoryImage",
                    onClick = { onInteraction(); onPickImage() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 0.95f),
                )

                // 4. 📂 文件
                CopilotDockChip(
                    label = if (iconOnly) "📂" else "📂文件",
                    isDark = isDark,
                    tag = "accessoryFileInbox",
                    onClick = { onInteraction(); onOpenFileInbox() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 1.05f),
                )

                // 5. 📋 提取
                CopilotDockChip(
                    label = if (iconOnly) "📋" else "📋复制",
                    isDark = isDark,
                    tag = "accessoryCopy",
                    onClick = { onInteraction(); onCopyText() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 1.05f),
                )

                // 6. 📥 粘贴
                CopilotDockChip(
                    label = if (iconOnly) "📥" else "📥粘贴",
                    isDark = isDark,
                    tag = "accessoryPaste",
                    onClick = { onInteraction(); onPaste() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 1.05f),
                )

                // 7. ⊞ 窗格
                TmuxActionsButton(
                    control = control,
                    expanded = tmuxMenuOpen,
                    onOpen = onOpenTmuxMenu,
                    onDismiss = onDismissTmuxMenu,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onClick ->
                    CopilotDockChip(
                        label = if (iconOnly) "⊞" else "⊞窗格",
                        isDark = isDark,
                        tag = "tmuxActions",
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 8. ⌫ 清空
                CopilotDockChip(
                    label = if (iconOnly) "⌫" else "⌫清",
                    isDanger = true,
                    isDark = isDark,
                    tag = "accessoryClear",
                    onClick = { onInteraction(); onClearInput() },
                    modifier = Modifier.weight(if (iconOnly) 1f else 0.9f),
                )
            }
        }
    }
}

@Composable
private fun SlashCommandDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSendSlashCommand: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        AGENT_SLASH_COMMANDS.forEach { command ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(command.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text(
                            command.summary + (command.only?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    onDismiss()
                    onSendSlashCommand(command.text)
                },
                modifier = Modifier.testTag("slash-${command.text.removePrefix("/")}"),
            )
        }
    }
}

@Composable
private fun TerminalDeckKey(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFC9D1D9),
    borderColor: Color = Color(0xFF2B323C),
    bgColor: Color = Color(0xFF121720),
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun CopilotDockChip(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAiAccent: Boolean = false,
    isDanger: Boolean = false,
    isDark: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    val borderColor = when {
        isAiAccent -> if (isDark) Color(0x73818CF8) else Color(0x664F46E5)
        isDark -> Color(0x16FFFFFF)
        else -> Color(0x140F172A)
    }
    val textColor = when {
        isAiAccent -> if (isDark) Color(0xFFA5B4FC) else Color(0xFF4338CA)
        isDanger -> if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
        isDark -> Color(0xFFE6EDF3)
        else -> Color(0xFF1E293B)
    }
    val bgModifier = if (isAiAccent) {
        Modifier.background(
            brush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(Color(0x476366F1), Color(0x473B82F6))
                } else {
                    listOf(Color(0x286366F1), Color(0x283B82F6))
                },
            ),
            shape = shape,
        )
    } else {
        Modifier.background(
            color = if (isDark) Color(0x0FFFFFFF) else Color(0x0D0F172A),
            shape = shape,
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .then(bgModifier)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag(tag),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
