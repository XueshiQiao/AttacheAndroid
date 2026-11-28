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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The colour each tag colour name is drawn in: the same hues as the
 * desktop's `TagPalette`, so one tag looks the same on both. */
internal fun tagColor(name: String): Color = when (name) {
    "red" -> Color(0xFFE84D45)
    "orange" -> Color(0xFFF28C33)
    "yellow" -> Color(0xFFEDC42E)
    "green" -> Color(0xFF4DBA61)
    "teal" -> Color(0xFF2EB3B3)
    "blue" -> Color(0xFF4587F2)
    "purple" -> Color(0xFF9E6BE6)
    "pink" -> Color(0xFFED66AB)
    else -> Color(0xFF999EA8)
}

/**
 * A window's tags under its row: a small chip each, a coloured dot and the
 * name. The name is in the theme's own text colour and the colour is in the
 * dot alone, so a yellow tag stays readable on a light theme.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: List<WindowTag>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (tag in tags) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(tagColor(tag.color), CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tag.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Which tags [agent]'s window carries: a tick each, a tap puts it on or
 * takes it off at once. The pencil beside a tag edits it (name, colour,
 * delete); New tag makes one and puts it on this window.
 */
@Composable
fun AgentTagsDialog(
    agent: AgentPane,
    tags: List<WindowTag>,
    carried: Set<String>,
    onToggle: (WindowTag) -> Unit,
    onNew: () -> Unit,
    onEdit: (WindowTag) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column {
                Text(
                    "On window “${agent.windowName}”. A window without a tag is easy to tell apart as one you can close when it is done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                if (tags.isEmpty()) {
                    Text("No tags yet.", style = MaterialTheme.typography.bodyMedium)
                }
                for (tag in tags) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggle(tag) },
                    ) {
                        Checkbox(checked = tag.id in carried, onCheckedChange = { onToggle(tag) })
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(tagColor(tag.color), CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onEdit(tag) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit ${tag.name}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onNew) { Text("New tag…") } },
    )
}

/**
 * Making a tag ([tag] null) or changing one: a name and a colour. Deleting
 * is offered only for an existing tag, and only opens [onDelete]'s own
 * confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditDialog(
    tag: WindowTag?,
    onSave: (name: String, color: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(tag?.name ?: "") }
    // Gray is the fallback colour, so a new tag starts on the next one.
    var color by remember { mutableStateOf(tag?.color ?: AttacheCli.TAG_COLORS[2]) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { if (name.isNotBlank()) onSave(name.trim(), color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tag == null) "New tag" else "Edit tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Follow up") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Spacer(Modifier.size(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (option in AttacheCli.TAG_COLORS) {
                        val chosen = option == color
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(tagColor(option))
                                .border(
                                    width = if (chosen) 3.dp else 0.dp,
                                    color = if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { color = option },
                        )
                    }
                }
                if (onDelete != null) {
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onDelete) {
                        Text("Delete tag…", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = name.isNotBlank()) { Text(if (tag == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The second step before a tag is deleted: it comes off every window that
 * carries it, and there is no undo. The windows themselves stay. */
@Composable
fun DeleteTagDialog(tag: WindowTag, windows: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${tag.name}”?") },
        text = {
            Text(
                when (windows) {
                    0 -> "No window carries it. The windows themselves are not affected."
                    1 -> "It comes off the one window that carries it. The window itself is not affected."
                    else -> "It comes off the $windows windows that carry it. The windows themselves are not affected."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
