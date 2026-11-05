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

/** Claude Code's live status line: `✻ Jitterbugging… (13s · ↓ 649 tokens)`. */
data class AgentActivity(
    /** The word with its ellipsis, `Jitterbugging…`. */
    val verb: String,
    /** What is in the brackets, minus the key hint; null when there is none. */
    val detail: String?,
)

/**
 * Reads the status line Claude Code draws while a turn runs.
 *
 * It sits a few rows above the input box, with only Claude's tips between
 * them, so it is looked for there and nowhere else: an agent's own output can
 * hold a bullet that ends in an ellipsis, and that must not read as a status.
 *
 * Captured from Claude Code on this machine, 2026-11-05:
 * ```
 * ✻ Jitterbugging… (13s · ↓ 649 tokens · thought for 7s)
 *   ⎿  Tip: Run Claude Code locally or remotely using the Claude desktop app
 *
 * ────────────────────────────────
 * ❯
 * ────────────────────────────────
 * ```
 */
object AgentActivities {
    // The glyphs Claude cycles through for its spinner.
    private val LINE = Regex("^\\s*[*·✢✳✶✻✽]\\s+(\\S[^(]*?…)\\s*(?:\\((.*)\\))?\\s*$")
    private const val SEARCH_ROWS = 8

    fun detect(rows: List<String>): AgentActivity? {
        val lines = rows.map { it.trimEnd() }
        val rules = lines.indices.filter { isRule(lines[it]) }
        // The input box is drawn between two rules; its top one is where to
        // start looking up from. With a single rule, that one.
        val boxTop = when {
            rules.size >= 2 -> rules[rules.size - 2]
            rules.size == 1 -> rules[0]
            else -> return null
        }
        for (i in boxTop - 1 downTo maxOf(0, boxTop - SEARCH_ROWS)) {
            val m = LINE.matchEntire(lines[i]) ?: continue
            val detail = m.groupValues[2]
                .split("·")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("esc to") }
                .joinToString(" · ")
            return AgentActivity(m.groupValues[1].trim(), detail.ifEmpty { null })
        }
        return null
    }

    private fun isRule(line: String): Boolean {
        val t = line.trim()
        return t.length >= 20 && t.all { it == '─' }
    }
}
