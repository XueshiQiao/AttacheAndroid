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

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.attache.tmux.TmuxControl
import org.json.JSONObject

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
    /** The provider's own week: the first weekly window it reports, which
     * is the whole account's (Claude's "Weekly", Gemini's "Gemini weekly")
     * and not a model's or a third party's share of it. */
    val weekly: UsageWindow?
        get() = windows.firstOrNull { it.kind == "weekly" }

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
                    kind = w.optString("kind"),
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
    /** `session` (5 hours), `weekly` or `other`. */
    val kind: String,
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

/** The providers worth a line: one that never answered on this host (its
 * CLI is not installed there) is left out rather than shown as an error,
 * since it is simply not something this person uses here. */
internal fun shownProviders(usage: Map<String, ProviderUsage>): List<ProviderUsage> = usage.values
    .filter { it.windows.isNotEmpty() }
    .sortedBy { PROVIDER_ORDER.indexOf(it.provider).let { i -> if (i < 0) PROVIDER_ORDER.size else i } }

/**
 * Follows `attache-cli usage --watch` on [tmux] until cancelled or the
 * channel closes, handing each provider's line to [onUsage] and what the
 * desktop app said to [onDesktop]: `ok`, `not_running`, `failed` or
 * `unsupported` (attache-cli 0.3.3). The asking is the Mac desktop app's —
 * Claude's login is in the macOS keychain, which an SSH login cannot read
 * (XueshiQiao/Attache#39), so attache-cli has the app refresh AIUsageKit's
 * cache — and the phone only listens. A broken channel is never surfaced:
 * usage is a side panel, and the next attempt comes soon.
 */
internal suspend fun followUsage(
    tmux: TmuxControl,
    onUsage: (ProviderUsage) -> Unit,
    onDesktop: (String) -> Unit,
) {
    while (true) {
        val cli = tmux.cli() ?: return
        val lines = JsonLines()
        try {
            tmux.follow(AttacheCli.usageWatchCommand(cli)) { chunk ->
                for (line in lines.feed(chunk)) {
                    val event = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
                    if (event.optString("t") == "usage_desktop") {
                        onDesktop(event.optString("state"))
                    } else {
                        ProviderUsage.parse(event)?.let(onUsage)
                    }
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return
        } catch (_: Exception) {
        }
        if (tmux.isClosed) return
        delay(USAGE_RETRY_MS)
    }
}

private const val USAGE_RETRY_MS = 30_000L

/** Each provider's own colour, as on the desktop rail; Grok's light stone
 * turns to a slate on a light sheet, where it would not show. */
@Composable
private fun providerColor(provider: String): Color = when (provider) {
    "claude" -> Color(0xFFD97857)
    "antigravity" -> Color(0xFF669EF5)
    else -> if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFF6B7280) else Color(0xFFD1D6E0)
}

/**
 * The usage section at the bottom of the agent drawer, where the desktop
 * keeps its rail's: fixed under the list, which scrolls above it, on a
 * Material 3 filled card (the owner's pick of six ways to set it off from
 * the list, 2026-11-08; a divider read as too hard). Folded,
 * it is one row per provider with that provider's weekly window; a tap
 * unfolds every window, and a tap on its heading folds it back. Unfolded it
 * takes room from the list instead of covering it, and scrolls on its own
 * past about half the screen.
 *
 * Usage is the one thing the phone may depend on the Mac desktop app for
 * (the owner's rule, 2026-11-08), so [desktop] `not_running` is said here
 * plainly: beside the last numbers, or alone when there are none.
 */
@Composable
fun UsageSection(usage: Map<String, ProviderUsage>, desktop: String?, modifier: Modifier = Modifier) {
    val shown = shownProviders(usage)
    val appMissing = desktop == "not_running"
    if (shown.isEmpty() && !appMissing) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        // The countdowns are whole minutes, but the heading's age counts
        // seconds while it is under a minute.
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The oldest of the providers' times: "2m ago" then holds for every
    // number on the card, not just the freshest one.
    val updated = shown.mapNotNull { it.storedAt }.minOrNull()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .fillMaxWidth()
            // The same margin below as at the sides, inside the safe area:
            // the screen's own rounded corner already keeps the card's
            // corners clear of the edge (the owner's call, 2026-11-08).
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
            ) {
                Text("Usage", style = MaterialTheme.typography.labelMedium, color = muted)
                updated?.let {
                    Text(
                        "  · ${updatedAgo(nowSec - it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                    )
                }
                // A refresh that failed keeps the last good numbers on screen;
                // this says they are not current, and why when it is known.
                if (appMissing && shown.isNotEmpty()) {
                    Text(
                        "  · Attaché not running on the Mac",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (desktop == "failed" || shown.any { it.error != null }) {
                    Text(
                        "  · refresh failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (expanded) "Show less usage" else "Show all usage",
                    tint = muted,
                )
            }
            if (shown.isEmpty()) {
                Text(
                    "Open Attaché on this Mac to see usage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                )
            } else if (!expanded) {
                Column(Modifier.clickable { expanded = true }) {
                    for (p in shown) {
                        val week = p.weekly ?: continue
                        UsageRow(
                            name = p.label,
                            detail = "Weekly",
                            window = week,
                            color = providerColor(p.provider),
                            nowSec = nowSec,
                            dot = true,
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (p in shown) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                        ) {
                            Dot(providerColor(p.provider))
                            Spacer(Modifier.width(10.dp))
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            p.plan?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                        for (w in p.windows) {
                            UsageRow(name = w.title, detail = null, window = w, color = providerColor(p.provider), nowSec = nowSec, dot = false)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/**
 * One window: its name and figures, then its bar. The bar and the gap in
 * it are the reading, as the bar and the tick are on the desktop: colour
 * short of the gap is spending slower than the clock, colour past it will
 * run out before the reset.
 */
@Composable
private fun UsageRow(name: String, detail: String?, window: UsageWindow, color: Color, nowSec: Long, dot: Boolean) {
    val used = window.usedAt(nowSec)
    val elapsed = window.elapsedAt(nowSec)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot) {
                Dot(color)
                Spacer(Modifier.width(10.dp))
            }
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Spacer(Modifier.weight(1f))
            val left = window.resetsAt?.let { it - nowSec }?.takeIf { it > 0 }
            Text(
                listOfNotNull(used?.let { "${it.toInt()}%" }, left?.let(::countdown)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
        Spacer(Modifier.height(7.dp))
        UsageBar(((used ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), elapsed, color)
    }
}

/**
 * The used part in the provider's colour running straight into the grey
 * track, and the whole bar cut by a small gap where the clock is (the
 * owner's design, 2026-11-08, in place of Material 3's gap between the two
 * parts, which meant nothing). Colour past the gap is spending ahead of
 * the clock.
 */
@Composable
private fun UsageBar(used: Float, elapsed: Float?, color: Color) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val h = size.height
        val w = size.width
        val gap = 3.dp.toPx()
        val fx = used * w
        val pieces = if (elapsed == null) {
            listOf(0f to w)
        } else {
            val ex = elapsed * w
            listOf(0f to ex - gap / 2, ex + gap / 2 to w)
        }
        for ((l, r) in pieces) {
            if (r - l < 1f) continue
            clipPath(Path().apply { addRoundRect(RoundRect(l, 0f, r, h, CornerRadius(h / 2))) }) {
                drawRect(color, topLeft = Offset.Zero, size = Size(fx, h))
                drawRect(track, topLeft = Offset(fx, 0f), size = Size(w - fx, h))
            }
        }
    }
}

/** How long ago the numbers were read: `just now`, `35s ago`, `2m ago`,
 * `3h ago`, `2d ago`. */
internal fun updatedAgo(seconds: Long): String = when {
    seconds < 10 -> "just now"
    seconds < 60 -> "${seconds}s ago"
    seconds < 3_600 -> "${seconds / 60}m ago"
    seconds < 86_400 -> "${seconds / 3_600}h ago"
    else -> "${seconds / 86_400}d ago"
}

/** `4h 54m`, `3d 2h`, `12m`. */
internal fun countdown(seconds: Long): String = when {
    seconds < 3_600 -> "${(seconds + 59) / 60}m"
    seconds < 86_400 -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    else -> "${seconds / 86_400}d ${seconds % 86_400 / 3_600}h"
}
