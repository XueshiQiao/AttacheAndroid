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
 * What a coding agent in a pane is doing, in the order the chat mode's list
 * shows them: the one the person has to act on first.
 */
enum class AgentPhase { NEEDS_YOU, WORKING, DONE, IDLE }

/** One pane that runs a coding agent, as the chat mode's list draws it. */
data class AgentPane(
    val paneId: String,
    val sessionName: String,
    val windowId: String,
    val windowName: String,
    val path: String,
    /** `claude`, `codex`, …, from `@agent_kind`; empty when no hook said. */
    val kind: String,
    val phase: AgentPhase,
    /** When the hook last wrote `@agent_state`, epoch seconds; null if never. */
    val sinceEpochSec: Long?,
    val title: String,
)

/**
 * Reads agents out of `list-panes -a`. Nothing here is authored by the phone:
 * `@agent_state`, `@agent_kind` and `@agent_at` are pane options the desktop
 * app's hook (`attache-agent-hub`) writes from Claude Code's and Codex's own
 * hook events, so the phone, the desktop rail and a plain `tmux attach` all
 * read the same value.
 */
object AgentPanes {
    const val SEP = "\u0001"
    private const val FIELDS = 10

    /** Quoted for tmux's command parser; ids and options only, no user text. */
    val FORMAT =
        "'#{pane_id}$SEP#{session_name}$SEP#{window_id}$SEP#{window_name}$SEP" +
            "#{pane_current_path}$SEP#{@agent_kind}$SEP#{@agent_state}$SEP#{@agent_at}$SEP" +
            "#{pane_current_command}$SEP#{pane_title}'"

    val LIST_COMMAND = "list-panes -a -F $FORMAT"

    private val PANE_ID = Regex("^%\\d+$")
    private val WINDOW_ID = Regex("^@\\d+$")

    // Claude Code's process names itself after its version, so
    // `pane_current_command` reads `2.1.220` rather than `claude`.
    private val VERSION_COMMAND = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "tcsh", "csh", "login")

    /** The agent a running command is, or null. */
    private fun agentOfCommand(command: String): String? = AgentProviders.forCommand(command)?.kind

    // Claude Code prefixes the terminal title with a spinner or a marker glyph.
    private val TITLE_GLYPHS = Regex("^[\\u2800-\\u28FF\\u25D0-\\u25D3\\u2722\\u2731\\u2733\\u273B\\u273D\\u00B7*]+\\s*")

    fun parse(lines: List<String>): List<AgentPane> = lines.mapNotNull(::parseLine)
        .sortedWith(compareBy<AgentPane>({ it.phase.ordinal }, { -(it.sinceEpochSec ?: 0L) }))

    private fun parseLine(line: String): AgentPane? {
        val f = line.split(SEP, limit = FIELDS)
        if (f.size != FIELDS || !PANE_ID.matches(f[0]) || !WINDOW_ID.matches(f[2])) return null
        val optionKind = f[5].trim()
        val command = f[8].trim()
        // `@agent_state` outlives a `^C` exit, so a pane back at its shell
        // prompt still carries `@agent_kind=claude`: the running command is
        // the liveness check, and the option alone is not.
        if (command in SHELLS) return null
        val running = agentOfCommand(command)
        if (running == null && optionKind.isEmpty()) return null
        // Seen on this machine: `@agent_kind=claude @agent_state=working` left
        // on a pane that has since started Grok Build. A known agent other
        // than the option's means the option belongs to the one before.
        val stale = running != null && optionKind.isNotEmpty() && agentOfCommand(optionKind) != null &&
            agentOfCommand(optionKind) != running
        return AgentPane(
            paneId = f[0],
            sessionName = f[1],
            windowId = f[2],
            windowName = f[3],
            path = f[4],
            kind = if (stale || optionKind.isEmpty()) running.orEmpty() else optionKind,
            phase = if (stale) AgentPhase.IDLE else phaseOf(f[6].trim()),
            sinceEpochSec = if (stale) null else f[7].trim().toLongOrNull(),
            title = cleanTitle(f[9], command = command, fallback = f[3]),
        )
    }

    private fun phaseOf(state: String): AgentPhase = when (state) {
        "needs-input" -> AgentPhase.NEEDS_YOU
        "working" -> AgentPhase.WORKING
        "done" -> AgentPhase.DONE
        else -> AgentPhase.IDLE
    }

    // A shell's own title, `joey@Xueshis-MacBook-Pro:~/Code/Attache`.
    private val USER_AT_HOST = Regex("^[^\\s@]+@[^\\s:]+:")

    /** Claude Code titles the pane after the task; anything else titles it
     * with the host, `user@host:path` or its own command line, none of which
     * names the work, so the window name stands in. */
    private fun cleanTitle(raw: String, command: String, fallback: String): String {
        val t = raw.replace(TITLE_GLYPHS, "").trim()
        val meaningless = t.isEmpty() || t == "Claude Code" || t.endsWith(".local") ||
            USER_AT_HOST.containsMatchIn(t) || t == command || t.startsWith("$command ")
        return if (meaningless) fallback else t
    }

    /**
     * Lays what agents report for themselves over what tmux options say.
     * An agent's own word wins: it is what needs nothing installed on the
     * host. The options stay as detail, here to tell a finished turn
     * (`done`) from a session that was never used, which the agents' own
     * files do not separate.
     */
    fun merge(panes: List<AgentPane>, sessions: List<AgentSession>): List<AgentPane> = panes.map { pane ->
        val own = sessions.firstOrNull { s ->
            s.paneId == pane.paneId && s.kind == pane.kind &&
                (s.sessionName == null || s.sessionName == pane.sessionName) &&
                (s.windowId == null || s.windowId == pane.windowId)
        } ?: return@map pane
        val phase = when {
            own.phase == AgentPhase.IDLE && pane.phase == AgentPhase.DONE -> AgentPhase.DONE
            own.phase != null -> own.phase
            else -> pane.phase
        }
        pane.copy(phase = phase, sinceEpochSec = own.sinceEpochSec ?: pane.sinceEpochSec)
    }.sortedWith(compareBy<AgentPane>({ it.phase.ordinal }, { -(it.sinceEpochSec ?: 0L) }))
}
