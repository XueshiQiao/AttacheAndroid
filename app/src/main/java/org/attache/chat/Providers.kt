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

import org.json.JSONObject

/**
 * Claude Code, read from what it keeps for itself.
 *
 * Each running session has `~/.claude/sessions/<pid>.json`, one line of JSON
 * Claude Code writes and removes on its own. Measured on 2.1.311, 2026-11-05:
 * it carries `sessionId`, `cwd`, `tmux` (`Dev:@4.%145`, the exact pane) and
 * `status`, which was `busy` while a turn ran, `waiting` the moment a
 * permission menu appeared (an isolated session asked to `touch` a file), and
 * `idle` otherwise. So which pane holds which session, whether it needs the
 * person, and where its transcript is all come without a hook.
 *
 * The script prints only those fields, about 250 bytes a session rather
 * than the whole file, and skips files whose process is gone.
 */
object ClaudeProvider : AgentProvider {
    override val kind = "claude"

    // Claude Code's process names itself after its version, `2.1.220`.
    private val VERSION_COMMAND = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val TMUX = Regex("^(.*):(@\\d+)\\.(%\\d+)$")

    override fun recognizes(command: String) = command == "claude" || VERSION_COMMAND.matches(command)

    override fun sessionsScript() = "for f in \"\$HOME\"/.claude/sessions/*.json; do p=\$(basename \"\$f\" .json); " +
        "case \$p in ''|*[!0-9]*) continue;; esac; kill -0 \"\$p\" 2>/dev/null || continue; " +
        "printf '{\"pid\":%s,\"home\":\"%s\"' \"\$p\" \"\$HOME\"; " +
        "grep -o -E '\"(sessionId|cwd|status|tmux|statusUpdatedAt)\":(\"([^\"\\\\]|\\\\.)*\"|[0-9]+)' \"\$f\" | " +
        "while IFS= read -r kv; do printf ',%s' \"\$kv\"; done; printf '}\\n'; done"

    override fun parseSession(line: JSONObject): AgentSession? {
        val tmux = TMUX.matchEntire(line.optString("tmux")) ?: return null
        val sessionId = line.optString("sessionId")
        val cwd = line.optString("cwd")
        val home = line.optString("home")
        return AgentSession(
            kind = kind,
            paneId = tmux.groupValues[3],
            sessionName = tmux.groupValues[1],
            windowId = tmux.groupValues[2],
            phase = when (line.optString("status")) {
                "busy" -> AgentPhase.WORKING
                "waiting" -> AgentPhase.NEEDS_YOU
                "idle" -> AgentPhase.IDLE
                else -> null
            },
            sinceEpochSec = line.optLong("statusUpdatedAt", 0L).takeIf { it > 0 }?.div(1000),
            transcriptPath = if (sessionId.isNotEmpty() && cwd.isNotEmpty() && home.isNotEmpty()) transcriptPath(home, cwd, sessionId) else null,
        )
    }

    /**
     * `~/.claude/projects/<cwd>/<session>.jsonl`, the folder named after the
     * working directory with every character that is not an ASCII letter or
     * digit turned into `-`: `/private/tmp/claude-501/-Users-…` becomes
     * `-private-tmp-claude-501--Users-…`, as the folders on this machine are.
     */
    fun transcriptPath(home: String, cwd: String, sessionId: String): String = "$home/.claude/projects/${cwd.replace(Regex("[^A-Za-z0-9]"), "-")}/$sessionId.jsonl"

    override fun transcriptReader(): AgentTranscript = ClaudeTranscript()
}

/**
 * Antigravity (`agy`). Its conversations are SQLite files of protobuf blobs
 * with no published shape, so there is no transcript to read yet; what it
 * does write plainly is its model, in its own `settings.json`, and its
 * screen.
 *
 * The screen is read on the host and only the verdict crosses: a dozen
 * bottom rows of eleven agy panes every few seconds would be more bytes than
 * the rest of the chat mode together. The rules are herdr's for the same CLI
 * (`src/detect/manifests/antigravity.toml`, Apache-2.0, evidence from
 * Antigravity 1.1.28 to 1.2.17), widened from two footer rows to four
 * because a configured status line adds a row under the footer:
 *
 * - waiting: `navigate` with `tab amend` (permission) or `esc skip` (question)
 *   in the footer, the folder-trust dialog, or the old `requesting
 *   permission for:` dialog;
 * - working: `esc to cancel` in the footer, or a braille spinner starting a
 *   row near the bottom;
 * - idle otherwise.
 */
object AgyProvider : AgentProvider {
    override val kind = "agy"

    override fun recognizes(command: String) = command == "agy" || command == "antigravity"

    override fun sessionsScript() = "m=\$(grep -o -E '\"model\": *\"[^\"]*\"' \"\$HOME/.gemini/antigravity-cli/settings.json\" 2>/dev/null | head -n 1); " +
        "[ -n \"\$m\" ] || m='\"model\":null'; " +
        "tmux list-panes -a -F '#{pane_id} #{session_name}:#{window_id} #{pane_current_command}' | " +
        "while read -r id where cmd; do [ \"\$cmd\" = agy ] || continue; " +
        "s=\$(tmux capture-pane -p -J -t \"\$id\" | awk 'NF' | tail -n 12); " +
        "low=\$(printf '%s\\n' \"\$s\" | tr '[:upper:]' '[:lower:]'); foot=\$(printf '%s\\n' \"\$low\" | tail -n 4); st=idle; " +
        "if printf '%s' \"\$foot\" | grep -qF navigate && { printf '%s' \"\$foot\" | grep -qF 'tab amend' || printf '%s' \"\$foot\" | grep -qF 'esc skip'; }; then st=waiting; " +
        "elif printf '%s' \"\$low\" | grep -qF 'do you trust the contents of this project?'; then st=waiting; " +
        "elif printf '%s' \"\$low\" | grep -qF 'requesting permission for:'; then st=waiting; " +
        "elif printf '%s' \"\$foot\" | grep -qF 'esc to cancel'; then st=busy; " +
        "elif printf '%s\\n' \"\$s\" | LC_ALL=C grep -q \"^ *\$(printf '\\342')[\$(printf '\\240-\\243')]\"; then st=busy; fi; " +
        "printf '{\"pane\":\"%s\",\"where\":\"%s\",\"status\":\"%s\",%s}\\n' \"\$id\" \"\$where\" \"\$st\" \"\$m\"; done"

    override fun parseSession(line: JSONObject): AgentSession? {
        val pane = line.optString("pane").takeIf { it.matches(Regex("^%\\d+$")) } ?: return null
        val where = line.optString("where")
        return AgentSession(
            kind = kind,
            paneId = pane,
            sessionName = where.substringBeforeLast(':', "").ifEmpty { null },
            windowId = where.substringAfterLast(':', "").takeIf { it.startsWith("@") },
            phase = when (line.optString("status")) {
                "busy" -> AgentPhase.WORKING
                "waiting" -> AgentPhase.NEEDS_YOU
                else -> AgentPhase.IDLE
            },
            model = line.optString("model").takeIf { line.has("model") && !line.isNull("model") && it.isNotBlank() },
        )
    }
}

/** Recognised by their command for now; what each keeps for itself is
 * listed in XueshiQiao/Attache#36. */
object CodexProvider : AgentProvider {
    override val kind = "codex"

    override fun recognizes(command: String) = command == "codex" || command.startsWith("codex-")
}

object GrokProvider : AgentProvider {
    override val kind = "grok"

    // Grok Build's process is named after its build, `grok-1.0.52-mac`.
    override fun recognizes(command: String) = command == "grok" || command.startsWith("grok-")
}

object GeminiProvider : AgentProvider {
    override val kind = "gemini"

    override fun recognizes(command: String) = command == "gemini"
}
