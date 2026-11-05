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

/** A Claude Code permission menu read off the bottom of its pane. */
data class ApprovalPrompt(
    /** What the menu is about: the tool header, its description and the
     * command or path, as Claude drew them. */
    val subject: List<String>,
    val question: String,
    val options: List<Option>,
    /** The rows the prompt was read from, to check it is still on screen
     * before a key is sent. */
    val fingerprint: String,
) {
    data class Option(
        val number: Int,
        val label: String,
        /** Why this answer needs a second tap; null when one tap is enough. */
        val risk: String?,
    )
}

/**
 * Recognises Claude Code's permission menu in `capture-pane` text.
 *
 * **Deliberately narrow.** A miss costs one trip to the terminal; a wrong
 * match sends a digit somewhere it means something else. So it only answers
 * when every part of the menu is where Claude draws it: the `Esc to cancel`
 * footer at the very bottom, numbered options 1..n directly above it, and a
 * question ending in `?` directly above those. Anything else, including
 * Claude's multi-question form, is left to the terminal.
 *
 * The layout was read from real captures of Claude Code 2.1.x:
 * ```
 *  Bash command
 *  Create empty file x.txt
 * ╌╌╌╌╌╌
 *  touch /tmp/x.txt
 * ╌╌╌╌╌╌
 *  Do you want to proceed?
 *  ❯ 1. Yes
 *    2. Yes, and always allow access to /tmp from this project
 *    3. No
 *
 *  Esc to cancel · Tab to amend
 * ```
 */
object ApprovalPrompts {
    private val OPTION = Regex("^\\s*(?:❯\\s*)?(\\d{1,2})\\.\\s+(.+?)\\s*$")
    private val RULE = Regex("^\\s*[─━╌┄-]{8,}\\s*$")
    private const val SUBJECT_LIMIT = 14

    private val RISKY_COMMAND = listOf(
        Regex("\\bgit\\s+push\\b") to "pushes to a remote",
        Regex("\\bgit\\s+reset\\s+--hard\\b") to "throws away local changes",
        Regex("\\bgit\\s+(?:clean|checkout\\s+--|restore)\\b") to "throws away local changes",
        Regex("\\brm\\s+-[a-zA-Z]*[rf]") to "deletes files",
        Regex("\\bsudo\\b") to "runs as root",
        Regex("\\b(?:drop|truncate)\\s+(?:table|database)\\b", RegexOption.IGNORE_CASE) to "deletes data",
        Regex("\\bkill(?:all)?\\b|\\bpkill\\b") to "stops processes",
        Regex("--force\\b") to "forces past a safety check",
    )

    fun detect(rows: List<String>): ApprovalPrompt? {
        val lines = rows.map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty() || !lines.last().contains("Esc to cancel")) return null

        // Options sit directly above the footer, optionally after one blank.
        var i = lines.size - 2
        while (i >= 0 && lines[i].isBlank()) i--
        val options = ArrayList<Pair<Int, String>>()
        while (i >= 0) {
            val line = lines[i]
            val m = OPTION.matchEntire(line)
            if (m != null) {
                options.add(0, m.groupValues[1].toInt() to m.groupValues[2])
            } else if (line.isNotBlank() && line.startsWith("    ")) {
                // A wrapped option, e.g. `(shift+tab)` under its label. Read
                // bottom-up it comes before the option it belongs to, so it is
                // marked and folded in below.
                options.add(0, -1 to line.trim())
            } else {
                break
            }
            i--
        }
        // A continuation with no option above it is not this menu.
        if (options.firstOrNull()?.first == -1) return null
        val folded = ArrayList<Pair<Int, String>>()
        for ((n, label) in options) {
            if (n == -1) {
                val (pn, pl) = folded.removeAt(folded.size - 1)
                folded += pn to "$pl $label"
            } else {
                folded += n to label
            }
        }
        if (folded.size < 2 || folded.map { it.first } != (1..folded.size).toList()) return null

        while (i >= 0 && lines[i].isBlank()) i--
        if (i < 0) return null
        val question = lines[i].trim()
        if (!question.endsWith("?")) return null

        // The subject runs up from the question to the solid rule Claude
        // draws across the top of the dialog.
        val subject = ArrayList<String>()
        var j = i - 1
        while (j >= 0 && subject.size < SUBJECT_LIMIT * 2) {
            val line = lines[j]
            if (RULE.matches(line) && line.trim().first() == '─') break
            if (!RULE.matches(line) && line.isNotBlank() && !line.trim().startsWith("Tip:")) {
                subject.add(0, line.trim().removePrefix("│").trim())
            }
            j--
        }
        val shownSubject = if (subject.size > SUBJECT_LIMIT) subject.take(SUBJECT_LIMIT) + "…" else subject
        val commandRisk = RISKY_COMMAND.firstOrNull { (re, _) -> subject.any { re.containsMatchIn(it) } }?.second

        return ApprovalPrompt(
            subject = shownSubject,
            question = question,
            options = folded.map { (n, label) -> ApprovalPrompt.Option(n, label, riskOf(label, commandRisk)) },
            fingerprint = lines.takeLast(folded.size + 4).joinToString("\n"),
        )
    }

    private fun riskOf(label: String, commandRisk: String?): String? {
        val lower = label.lowercase()
        val affirmative = lower.startsWith("yes")
        return when {
            !affirmative -> null

            "auto mode" in lower -> "turns off these questions for the rest of the session"

            "always" in lower || "don't ask again" in lower || "accept edits" in lower ->
                "stops asking about this from now on"

            commandRisk != null -> commandRisk

            else -> null
        }
    }
}
