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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.connectbot.ui.LocalTerminalManager

/** Shown when a tmux host is tapped: the terminal as before, or the chat
 * mode. The terminal comes first because it is the one that always works. */
@Composable
fun ModeChooserDialog(
    hostName: String,
    onTerminal: () -> Unit,
    onChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(hostName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOption(
                    title = "Terminal",
                    detail = "tmux in a terminal, exactly as before",
                    onClick = onTerminal,
                )
                ModeOption(
                    title = "Chat (experimental)",
                    detail = "Your agents as a list and a conversation",
                    onClick = onChat,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModeOption(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    onNavigateBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenAgent: (AgentPane) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val terminalManager = LocalTerminalManager.current
    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAppearance by remember { mutableStateOf(false) }
    if (showAppearance) ChatAppearanceDialog(onDismiss = { showAppearance = false })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.hostName.ifEmpty { "Agents" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subtitle(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Open terminal")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.connection) {
                ChatConnection.CONNECTING -> CenteredMessage("Connecting…", progress = true)

                ChatConnection.NEEDS_TERMINAL -> CenteredMessage(
                    "The connection is asking for a password or a host key.",
                    action = "Answer in terminal" to onOpenTerminal,
                )

                ChatConnection.DISCONNECTED -> CenteredMessage(
                    state.error ?: "Disconnected.",
                    action = "Reconnect" to viewModel::reconnect,
                )

                ChatConnection.READY -> if (state.agents.isEmpty()) {
                    CenteredMessage("No agents are running in tmux.")
                } else {
                    AgentList(state.agents, onOpenAgent)
                }
            }
        }
    }
}

private fun subtitle(state: AgentListUiState): String = when (state.connection) {
    ChatConnection.READY -> {
        val waiting = state.agents.count { it.phase == AgentPhase.NEEDS_YOU }
        val total = "${state.agents.size} agent" + if (state.agents.size == 1) "" else "s"
        if (waiting > 0) "$total · $waiting need you" else total
    }

    else -> "Chat · experimental"
}

@Composable
private fun AgentList(agents: List<AgentPane>, onOpen: (AgentPane) -> Unit) {
    // One clock for every row, ticking once a minute: the ages are whole
    // minutes, so a faster tick would redraw the list to change nothing.
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val groups = agents.groupBy { it.phase }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (phase in AgentPhase.entries) {
            val inPhase = groups[phase].orEmpty()
            if (inPhase.isEmpty()) continue
            item(key = "header-$phase") {
                Text(
                    "${phaseLabel(phase)}  ${inPhase.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(inPhase, key = { it.paneId }) { agent ->
                AgentRow(agent, nowSec, onClick = { onOpen(agent) })
            }
        }
    }
}

@Composable
private fun AgentRow(agent: AgentPane, nowSec: Long, onClick: () -> Unit) {
    val emphasised = agent.phase == AgentPhase.NEEDS_YOU
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (emphasised) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(phaseColor(agent.phase), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    agent.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(agent.kind.ifEmpty { "agent" }, agent.sessionName, agent.windowName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            agent.sinceEpochSec?.let { since ->
                Spacer(Modifier.width(8.dp))
                Text(
                    age(nowSec - since),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    progress: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
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
        Text(text, style = MaterialTheme.typography.bodyLarge)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}

internal fun phaseLabel(phase: AgentPhase): String = when (phase) {
    AgentPhase.NEEDS_YOU -> "Needs you"
    AgentPhase.WORKING -> "Working"
    AgentPhase.DONE -> "Done"
    AgentPhase.IDLE -> "Idle"
}

internal fun phaseColor(phase: AgentPhase): Color = when (phase) {
    AgentPhase.NEEDS_YOU -> Color(0xFFE5484D)
    AgentPhase.WORKING -> Color(0xFFF5A524)
    AgentPhase.DONE -> Color(0xFF30A46C)
    AgentPhase.IDLE -> Color(0xFF8B8D98)
}

internal fun age(seconds: Long): String = when {
    seconds < 60 -> "now"
    seconds < 3_600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3_600}h"
    else -> "${seconds / 86_400}d"
}
