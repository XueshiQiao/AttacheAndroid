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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The chat mode's appearance, changed where it is seen: shown over the chat
 * itself, so each tap previews at once, as the terminal mode's settings do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatAppearanceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current by ChatAppearance.theme.collectAsState()
    val scale by ChatAppearance.textScale.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (choice in ChatThemeChoice.entries) {
                        ThemeSwatch(choice, selected = choice == current) { ChatAppearance.setTheme(context, choice) }
                    }
                }
                Text("Text size", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { ChatAppearance.setTextScale(context, scale - 0.05f) },
                        enabled = scale > ChatAppearance.MIN_SCALE,
                    ) { Text("A−") }
                    Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FilledTonalButton(
                        onClick = { ChatAppearance.setTextScale(context, scale + 0.05f) },
                        enabled = scale < ChatAppearance.MAX_SCALE,
                    ) { Text("A+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** A theme as its page colour with a dot of its accent, named beneath. */
@Composable
private fun ThemeSwatch(choice: ChatThemeChoice, selected: Boolean, onClick: () -> Unit) {
    val palette = choice.palette
    val page = palette?.let { Color(it.background) } ?: MaterialTheme.colorScheme.background
    val accent = palette?.let { Color(it.accent) } ?: MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .padding(4.dp)
                .background(page, CircleShape),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(accent, CircleShape),
            )
        }
        Text(
            choice.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
