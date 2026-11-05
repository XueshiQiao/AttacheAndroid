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
 * What an agent says about one of its running sessions, read from the
 * agent's own files or its own screen on the host. Every field is optional:
 * each agent keeps a different part of this.
 */
data class AgentSession(
    val kind: String,
    /** `%42`. */
    val paneId: String,
    /** When the agent recorded its tmux session and window too, so a pane id
     * from another tmux server is not taken for this one's. */
    val sessionName: String? = null,
    val windowId: String? = null,
    val phase: AgentPhase? = null,
    val sinceEpochSec: Long? = null,
    val transcriptPath: String? = null,
    val model: String? = null,
)

/**
 * One coding agent product, as the chat mode needs to know it. Everything
 * the phone learns about an agent comes through here, so a new agent is a
 * new implementation and nothing else changes.
 *
 * The rule every implementation follows: read what the agent itself writes
 * on the host, its session files, transcripts and screen, and need nothing
 * installed there by anyone else. What the desktop app installs (the hooks'
 * `@agent_state`, the status line's `@agent_stat`) is extra detail when it
 * is present, never a requirement. XueshiQiao/Attache#36.
 */
interface AgentProvider {
    /** `claude`, `agy`, …, the same words `@agent_kind` uses. */
    val kind: String

    /** Whether a pane's `pane_current_command` is this agent. */
    fun recognizes(command: String): Boolean

    /**
     * A shell script, run on the host through `tmux run-shell`, that prints
     * what the agent knows of its live sessions as one JSON object a line;
     * null when it keeps nothing worth reading. It runs inside tmux, so it
     * may call tmux itself; `#` is doubled for it by [HostScripts].
     */
    fun sessionsScript(): String? = null

    fun parseSession(line: JSONObject): AgentSession? = null

    /** A reader for this agent's transcript, or null when its conversation
     * cannot be read (yet). */
    fun transcriptReader(): AgentTranscript? = null
}

/** An agent's conversation, read one transcript line at a time. */
interface AgentTranscript {
    fun accept(line: String)

    fun prepend(earlier: List<String>)

    val snapshot: List<ChatItem>

    /** Model, effort and context as the transcript itself records them. */
    val status: AgentStatus?
}

object AgentProviders {
    val all: List<AgentProvider> = listOf(ClaudeProvider, AgyProvider, CodexProvider, GrokProvider, GeminiProvider)

    fun forCommand(command: String): AgentProvider? = all.firstOrNull { it.recognizes(command) }

    fun forKind(kind: String): AgentProvider? = all.firstOrNull { it.kind == kind }
}

/**
 * Runs every provider's script in one `run-shell` on the control channel
 * that is already open: one round trip, where a fresh SSH exec channel costs
 * three, about 1.5 s on the owner's relayed 5G link.
 */
object HostScripts {
    private const val MARK = "@@attache-agent "

    /** The `run-shell` command, quoted for tmux's parser. `run-shell`
     * expands formats in its argument first, so `#` is doubled to stay `#`. */
    fun sessionsCommand(providers: List<AgentProvider> = AgentProviders.all): String? {
        val parts = providers.mapNotNull { p -> p.sessionsScript()?.let { "printf '%s\\n' '$MARK${p.kind}'; $it" } }
        if (parts.isEmpty()) return null
        val script = parts.joinToString("; ").replace("#", "##")
        return "run-shell " + quote(script)
    }

    fun parse(lines: List<String>, providers: List<AgentProvider> = AgentProviders.all): List<AgentSession> {
        val out = ArrayList<AgentSession>()
        var current: AgentProvider? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith(MARK)) {
                current = providers.firstOrNull { it.kind == line.removePrefix(MARK) }
                continue
            }
            val provider = current ?: continue
            if (!line.startsWith("{")) continue
            val json = try {
                JSONObject(line)
            } catch (_: Exception) {
                continue
            }
            provider.parseSession(json)?.let(out::add)
        }
        return out
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
