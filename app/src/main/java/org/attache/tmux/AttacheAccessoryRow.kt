package org.attache.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import timber.log.Timber

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
 * The row above the terminal keys on a tmux host: what used to crowd the
 * title bar, plus the agent's slash commands. Every menu's open state is the
 * caller's, which owns the keyboard, the PTY size and focus.
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
) {
    val scope = rememberCoroutineScope()
    fun step(forward: Boolean) {
        onInteraction()
        scope.launch {
            try {
                control.stepWindow(forward)
            } catch (e: TmuxCommandError) {
                Timber.w("step window failed: %s", e.message)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("attacheAccessoryRow"),
    ) {
        AccessoryButton(onClick = { onInteraction(); onTextInput() }, tag = "accessoryTextInput", modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Edit, contentDescription = "Text input")
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AccessoryButton(onClick = onOpenSlashMenu, tag = "accessorySlash", modifier = Modifier.fillMaxWidth()) {
                Text("/", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = slashMenuOpen, onDismissRequest = onDismissSlashMenu) {
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
                            onDismissSlashMenu()
                            onSendSlashCommand(command.text)
                        },
                        modifier = Modifier.testTag("slash-${command.text.removePrefix("/")}"),
                    )
                }
            }
        }
        TmuxActionsButton(
            control = control,
            expanded = tmuxMenuOpen,
            onOpen = onOpenTmuxMenu,
            onDismiss = onDismissTmuxMenu,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { onClick ->
            AccessoryButton(onClick = onClick, tag = "tmuxActions", modifier = Modifier.fillMaxWidth()) {
                Text("tmux", style = MaterialTheme.typography.labelLarge)
            }
        }
        AccessoryButton(onClick = { step(forward = false) }, tag = "accessoryPreviousWindow", modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous window")
        }
        AccessoryButton(onClick = { step(forward = true) }, tag = "accessoryNextWindow", modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next window")
        }
        AccessoryButton(onClick = { onInteraction(); onPaste() }, tag = "accessoryPaste", modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
        }
    }
}

@Composable
private fun AccessoryButton(
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.fillMaxHeight().testTag(tag),
    ) { content() }
}

private val ROW_HEIGHT = 40.dp
