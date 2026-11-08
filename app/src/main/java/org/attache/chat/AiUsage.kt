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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One AI subscription's quota windows, as `attache-cli usage --watch` passes
 * them on from AIUsageKit on the host. Nothing is worked out here except the
 * clock: how far into a window it is, which only the phone's own time can
 * say between two updates.
 */
data class ProviderUsage(
    val provider: String,
    val plan: String?,
    val windows: List<UsageWindow>,
    val storedAt: Long?,
    /** The last ask failed and nothing has succeeded since. */
    val error: String?,
) {
    val label: String
        get() = when (provider) {
            "claude" -> "Claude"
            "antigravity" -> "Gemini"
            "grok" -> "Grok"
            else -> provider
        }

    companion object {
        /** A `usage` line, or null for anything else. */
        fun parse(o: JSONObject): ProviderUsage? {
            if (o.optString("t") != "usage") return null
            val provider = o.optString("provider").ifEmpty { return null }
            val ws = o.optJSONArray("windows")
            val windows = (0 until (ws?.length() ?: 0)).mapNotNull { i ->
                val w = ws?.optJSONObject(i) ?: return@mapNotNull null
                UsageWindow(
                    id = w.optString("id"),
                    title = w.optString("title"),
                    usedPercent = w.optDoubleOrNull("used_percent"),
                    resetsAt = w.optLongOrNull("resets_at"),
                    windowSeconds = w.optDoubleOrNull("window_seconds"),
                )
            }
            return ProviderUsage(
                provider = provider,
                plan = o.optStringOrNull("plan"),
                windows = windows,
                storedAt = o.optLongOrNull("stored_at"),
                error = o.optStringOrNull("error"),
            )
        }

        private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null
        private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
        private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null
    }
}

data class UsageWindow(
    val id: String,
    val title: String,
    val usedPercent: Double?,
    val resetsAt: Long?,
    val windowSeconds: Double?,
) {
    /** Past its reset the stored number is the old window's: show nothing
     * rather than a full bar that is no longer true. */
    fun usedAt(nowSec: Long): Double? = if (resetsAt != null && nowSec >= resetsAt) 0.0 else usedPercent

    /** How much of the window has simply gone by, 0..1. */
    fun elapsedAt(nowSec: Long): Float? {
        val end = resetsAt ?: return null
        val length = windowSeconds?.takeIf { it > 0 } ?: return null
        val gone = (nowSec - (end - length)) / length
        return gone.toFloat().coerceIn(0f, 1f)
    }
}

/** What each host's usage was when last read, shown at once on return. */
object UsageDirectory {
    private val byHost = ConcurrentHashMap<UUID, Map<String, ProviderUsage>>()

    fun put(host: UUID, usage: Map<String, ProviderUsage>) {
        byHost[host] = usage
    }

    fun get(host: UUID): Map<String, ProviderUsage> = byHost[host].orEmpty()
}

private val PROVIDER_ORDER = listOf("claude", "antigravity", "grok")

/**
 * The quota card above the agents. A provider that never answered on this
 * host (its CLI is not installed there) is left out rather than shown as an
 * error: it is simply not something this person uses here.
 */
@Composable
fun UsageCard(usage: Map<String, ProviderUsage>, nowSec: Long, modifier: Modifier = Modifier) {
    val shown = usage.values
        .filter { it.windows.isNotEmpty() }
        .sortedBy { PROVIDER_ORDER.indexOf(it.provider).let { i -> if (i < 0) PROVIDER_ORDER.size else i } }
    if (shown.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (p in shown) ProviderBlock(p, nowSec)
        }
    }
}

@Composable
private fun ProviderBlock(p: ProviderUsage, nowSec: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            p.plan?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            // How old the numbers are, and that the last refresh failed:
            // the bars still show the last good reading.
            val stale = p.storedAt?.let { (nowSec - it).let { a -> if (a < 60) "updated just now" else "updated ${age(a)} ago" } }.orEmpty()
            val note = if (p.error != null) listOf(stale, "refresh failed").filter { it.isNotEmpty() }.joinToString(" · ") else stale
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for (w in p.windows) UsageGauge(w, nowSec)
    }
}

/**
 * One window: the bar is how much was spent, the tick is how much of the
 * window has gone by. The comparison is the reading, as on the desktop's
 * rail: a bar short of the tick is spending slower than the clock, a bar
 * past it will run out before the reset.
 */
@Composable
private fun UsageGauge(w: UsageWindow, nowSec: Long) {
    val used = w.usedAt(nowSec)
    val elapsed = w.elapsedAt(nowSec)
    val fraction = ((used ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val ahead = elapsed != null && used != null && fraction > elapsed + 0.02f
    val barColor = when {
        fraction >= 0.9f -> Color(0xFFE5484D)
        ahead -> Color(0xFFF5A524)
        else -> Color(0xFF30A46C)
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                w.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val left = w.resetsAt?.let { it - nowSec }?.takeIf { it > 0 }
            Text(
                listOfNotNull(used?.let { "${it.toInt()}%" }, left?.let { "resets in ${countdown(it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(barColor),
            )
            elapsed?.let {
                Box(
                    Modifier
                        .offset(x = (maxWidth - 2.dp) * it)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}

/** `4h 54m`, `3d 2h`, `12m`. */
internal fun countdown(seconds: Long): String = when {
    seconds < 3_600 -> "${(seconds + 59) / 60}m"
    seconds < 86_400 -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    else -> "${seconds / 86_400}d ${seconds % 86_400 / 3_600}h"
}
