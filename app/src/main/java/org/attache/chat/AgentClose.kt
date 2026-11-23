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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * What a long press on an agent's row offers. Renaming and closing are two
 * items worded as what they do, and closing only opens [CloseAgentDialog]:
 * nothing is ended from the menu itself.
 */
@Composable
fun AgentRowMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onClose: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename…") },
            onClick = {
                onDismiss()
                onRename()
            },
        )
        DropdownMenuItem(
            text = { Text("Close…", color = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismiss()
                onClose()
            },
            modifier = Modifier.testTag("agentRowClose"),
        )
    }
}

/**
 * The second step before a chat is closed. Closing kills the agent's tmux
 * pane — the agent and anything else running in it end at once, a turn in
 * progress included, and nothing brings them back — so the dialog says
 * exactly that, and which window it is in, before [onConfirm] runs.
 */
@Composable
fun CloseAgentDialog(agent: AgentPane, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close “${agent.title}”?") },
        text = {
            Text(
                "This ends the agent and closes its pane in window “${agent.windowName}” " +
                    "of session “${agent.sessionName}”. Work in progress is lost, and it cannot be undone. " +
                    "Other panes in that window stay; the window closes when this was its last pane.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("agentCloseConfirm")) {
                Text("Close", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
