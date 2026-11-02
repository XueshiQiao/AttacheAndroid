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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    private val _styleFlow = MutableStateFlow(AttacheToolbarStyle.COPILOT_DOCK)
    val styleFlow: StateFlow<AttacheToolbarStyle> = _styleFlow.asStateFlow()

    private val _isDarkFlow = MutableStateFlow(true)
    val isDarkFlow: StateFlow<Boolean> = _isDarkFlow.asStateFlow()

    @Volatile private var initialized = false

    fun syncFromContext(context: Context): AttacheToolbarStyle {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val style = AttacheToolbarStyle.fromId(prefs.getString(PREF_KEY, AttacheToolbarStyle.COPILOT_DOCK.id))
        _styleFlow.value = style
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

    fun updateDarkTheme(isDark: Boolean) {
        _isDarkFlow.value = isDark
    }
}

/**
 * Dialog allowing the user to pick between Scheme B (`TERMINAL_DECK`) and
 * Scheme D (`COPILOT_DOCK`) with immediate live preview.
 */
@Composable
fun AttacheToolbarStyleDialog(
    currentStyle: AttacheToolbarStyle,
    onSelectStyle: (AttacheToolbarStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("底部双排快捷栏风格", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
 */
@Composable
fun AttacheToolbarContainer(
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme(),
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
                content = content,
            )
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
                        content = content,
                    )
                }
            }
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
                // 1. /cmd (Slash menu)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TerminalDeckKey(
                        label = "/cmd",
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

                // 2. tmux (Pane / Window actions)
                TmuxActionsButton(
                    control = control,
                    expanded = tmuxMenuOpen,
                    onOpen = onOpenTmuxMenu,
                    onDismiss = onDismissTmuxMenu,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onClick ->
                    TerminalDeckKey(
                        label = "tmux",
                        textColor = cyanText,
                        borderColor = cyanBorder,
                        bgColor = cyanBg,
                        tag = "tmuxActions",
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 3. +img (Send image)
                TerminalDeckKey(
                    label = "+img",
                    textColor = defaultKeyText,
                    borderColor = defaultKeyBorder,
                    bgColor = defaultKeyBg,
                    tag = "accessoryImage",
                    onClick = { onInteraction(); onPickImage() },
                    modifier = Modifier.weight(1f),
                )

                // 4. inbox (File Inbox)
                TerminalDeckKey(
                    label = "inbox",
                    textColor = defaultKeyText,
                    borderColor = defaultKeyBorder,
                    bgColor = defaultKeyBg,
                    tag = "accessoryFileInbox",
                    onClick = { onInteraction(); onOpenFileInbox() },
                    modifier = Modifier.weight(1f),
                )

                // 5. copy (Extract pane text)
                TerminalDeckKey(
                    label = "copy",
                    textColor = defaultKeyText,
                    borderColor = defaultKeyBorder,
                    bgColor = defaultKeyBg,
                    tag = "accessoryCopy",
                    onClick = { onInteraction(); onCopyText() },
                    modifier = Modifier.weight(1f),
                )

                // 6. paste (Clipboard paste)
                TerminalDeckKey(
                    label = "paste",
                    textColor = greenText,
                    borderColor = greenBorder,
                    bgColor = greenBg,
                    tag = "accessoryPaste",
                    onClick = { onInteraction(); onPaste() },
                    modifier = Modifier.weight(1f),
                )

                // 7. ^C (Interrupt)
                TerminalDeckKey(
                    label = "^C",
                    textColor = amberText,
                    borderColor = amberBorder,
                    bgColor = amberBg,
                    tag = "accessoryTextInput",
                    onClick = { onInteraction(); onInterrupt() },
                    modifier = Modifier.weight(0.85f),
                )

                // 8. ^U (Clear input line)
                TerminalDeckKey(
                    label = "^U",
                    textColor = redText,
                    borderColor = redBorder,
                    bgColor = redBg,
                    tag = "accessoryClear",
                    onClick = { onInteraction(); onClearInput() },
                    modifier = Modifier.weight(0.85f),
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
                // 1. ✦ /指令
                Box(Modifier.weight(1.12f).fillMaxHeight()) {
                    CopilotDockChip(
                        label = "✦ /指令",
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

                // 2. 🖼 传图
                CopilotDockChip(
                    label = "🖼 传图",
                    isDark = isDark,
                    tag = "accessoryImage",
                    onClick = { onInteraction(); onPickImage() },
                    modifier = Modifier.weight(1f),
                )

                // 3. 📂 收件箱
                CopilotDockChip(
                    label = "📂 文件",
                    isDark = isDark,
                    tag = "accessoryFileInbox",
                    onClick = { onInteraction(); onOpenFileInbox() },
                    modifier = Modifier.weight(1f),
                )

                // 4. 📋 提取
                CopilotDockChip(
                    label = "📋 提取",
                    isDark = isDark,
                    tag = "accessoryCopy",
                    onClick = { onInteraction(); onCopyText() },
                    modifier = Modifier.weight(1f),
                )

                // 5. 📥 粘贴
                CopilotDockChip(
                    label = "📥 粘贴",
                    isDark = isDark,
                    tag = "accessoryPaste",
                    onClick = { onInteraction(); onPaste() },
                    modifier = Modifier.weight(1f),
                )

                // 6. ⊞ 窗格
                TmuxActionsButton(
                    control = control,
                    expanded = tmuxMenuOpen,
                    onOpen = onOpenTmuxMenu,
                    onDismiss = onDismissTmuxMenu,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onClick ->
                    CopilotDockChip(
                        label = "⊞ 窗格",
                        isDark = isDark,
                        tag = "tmuxActions",
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 7. ⌫ 清空
                CopilotDockChip(
                    label = "⌫ 清空",
                    isDanger = true,
                    isDark = isDark,
                    tag = "accessoryClear",
                    onClick = { onInteraction(); onClearInput() },
                    modifier = Modifier.weight(1f),
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
