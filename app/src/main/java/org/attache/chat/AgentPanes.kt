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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    /** From the agent's own report, when it gave one. */
    val transcriptPath: String? = null,
    val model: String? = null,
    /** The conversation's id: Claude's session, agy's conversation. */
    val conversation: String? = null,
)

/** How the list names a pane: by the task Claude titled it with, or by its
 * window when the title says nothing about the work. */
object AgentPanes {
    // Claude Code prefixes the terminal title with a spinner or a marker glyph.
    private val TITLE_GLYPHS = Regex("^[\\u2800-\\u28FF\\u25D0-\\u25D3\\u2722\\u2731\\u2733\\u273B\\u273D\\u00B7*]+\\s*")

    // A shell's own title, `joey@Xueshis-MacBook-Pro:~/Code/Attache`.
    private val USER_AT_HOST = Regex("^[^\\s@]+@[^\\s:]+:")

    /** Claude Code titles the pane after the task; anything else titles it
     * with the host, `user@host:path` or its own command line, none of which
     * names the work, so the window name stands in. */
    fun cleanTitle(raw: String, command: String, fallback: String): String {
        val t = raw.replace(TITLE_GLYPHS, "").trim()
        val meaningless = t.isEmpty() || t == "Claude Code" || t.endsWith(".local") ||
            USER_AT_HOST.containsMatchIn(t) || t == command || t.startsWith("$command ")
        return if (meaningless) fallback else t
    }
}

/**
 * The agent list each host's list page read last, kept in memory. The list
 * is what a chat page is opened from, so the chat page starts from the row
 * that was tapped, transcript and all, instead of asking every agent again;
 * and its agent switcher shows this list rather than polling every agent on
 * the host, a dozen agy screens read each time, while a conversation is
 * being read.
 */
object AgentDirectory {
    private val byHost = ConcurrentHashMap<UUID, List<AgentPane>>()

    fun put(host: UUID, agents: List<AgentPane>) {
        byHost[host] = agents
    }

    fun get(host: UUID): List<AgentPane> = byHost[host].orEmpty()
}
