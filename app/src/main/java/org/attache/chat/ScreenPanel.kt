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

/**
 * Tells whether Claude Code has a panel or picker open over its input box,
 * the way `/usage`, `/model`, `/resume` and `/effort` open one, and if so
 * hands back what is on screen.
 *
 * One rule for all of them rather than one per command: while such a panel
 * is up, Claude's input box (a `❯` line between two rules) is not drawn, or
 * the bottom of the screen names the keys a dialog takes. Measured with
 * `/usage` on Claude Code 2.1, 2026-11-05: the panel took the whole screen,
 * scrolled, and the input box came back only after Esc. A command added to
 * Claude Code tomorrow opens the same kind of panel and is covered with no
 * change here.
 */
object ScreenPanels {
    private const val SHOWN_LINES = 40
    private const val BOX_SEARCH = 14

    fun detect(rows: List<String>): List<String>? {
        val lines = rows.map { it.trimEnd() }.dropLastWhile { it.isBlank() }.dropWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        val footer = lines.takeLast(3).joinToString(" ").lowercase()
        val dialogKeys = ("esc to" in footer && "esc to interrupt" !in footer) || "enter to" in footer
        if (inputBoxVisible(lines) && !dialogKeys) return null
        return lines.takeLast(SHOWN_LINES)
    }

    private fun inputBoxVisible(lines: List<String>): Boolean {
        val from = maxOf(0, lines.size - BOX_SEARCH)
        val rules = (from until lines.size).filter { isRule(lines[it]) }
        for (r in rules.indices) {
            for (s in r + 1 until rules.size) {
                if ((rules[r] + 1 until rules[s]).any { lines[it].trimStart().startsWith("❯") }) return true
            }
        }
        return false
    }

    private fun isRule(line: String): Boolean {
        val t = line.trim()
        return t.length >= 20 && t.all { it == '─' }
    }
}
