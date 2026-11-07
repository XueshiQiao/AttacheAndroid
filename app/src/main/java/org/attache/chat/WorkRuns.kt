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
 * A line of the agent's with the run of thinking and tools that came with
 * it: drawn as the line with a small badge after its last word, the run
 * opening beneath it (design E in the desktop repo's
 * `docs/android-chat-fold-designs.html`).
 */
internal data class MarkedText(
    val text: ChatItem.AgentText,
    val run: List<ChatItem>,
    val key: String,
    /** The run is still going. */
    val live: Boolean,
) {
    val steps get() = run.count { it is ChatItem.ToolStep }
    val failed get() = run.count { it is ChatItem.ToolStep && it.isError }
    val thoughts get() = run.count { it is ChatItem.Thinking }

    /** The step still out, while the run goes on. */
    val running get() = if (live) run.lastOrNull { it is ChatItem.ToolStep && it.result == null } as ChatItem.ToolStep? else null
}

/** A run with no line of the agent's to hang on: its own small row. */
internal data class LooseRun(val run: List<ChatItem>, val key: String, val live: Boolean) {
    val steps get() = run.count { it is ChatItem.ToolStep }
    val failed get() = run.count { it is ChatItem.ToolStep && it.isError }
    val thoughts get() = run.count { it is ChatItem.Thinking }
    val running get() = if (live) run.lastOrNull { it is ChatItem.ToolStep && it.result == null } as ChatItem.ToolStep? else null
}

internal object WorkRuns {
    /**
     * Hangs each run of thinking and tool steps on the agent's line just
     * before it — what it said is most often what it then did. A run before
     * anything was said in a turn waits for the turn's next line and joins
     * that line's own run; one with no line after it stands alone. Thinking
     * and tools are told from the agent's lines by the transcript's own block
     * types, so nothing is guessed. Keys only grow at the end of a turn, so
     * an opened run stays open as the turn goes on.
     *
     * [working]: the agent is at work, so the last run is live — unless a
     * message is on its way, which is what it works on then.
     */
    fun attach(rows: List<Any>, working: Boolean): List<Any> {
        val out = ArrayList<Any>(rows.size)
        var stretch = ArrayList<ChatItem>()
        var lead: Pair<List<ChatItem>, String>? = null
        fun closeRun() {
            if (stretch.isEmpty()) return
            val last = out.lastOrNull()
            if (last is MarkedText && lead == null) {
                out[out.size - 1] = last.copy(run = last.run + stretch)
            } else if (last is ChatItem.AgentText && lead == null) {
                out[out.size - 1] = MarkedText(last, stretch, "w:${last.key}", live = false)
            } else {
                val waiting = lead
                lead = if (waiting == null) stretch to "run:${stretch.first().key}" else (waiting.first + stretch) to waiting.second
            }
            stretch = ArrayList()
        }
        fun releaseLead() {
            lead?.let { (run, key) -> out.add(LooseRun(run, key, live = false)) }
            lead = null
        }
        for (row in rows) {
            when (row) {
                is ChatItem.Thinking, is ChatItem.ToolStep -> stretch.add(row as ChatItem)
                is ChatItem.AgentText -> {
                    closeRun()
                    val waiting = lead
                    if (waiting != null) {
                        out.add(MarkedText(row, waiting.first, waiting.second, live = false))
                        lead = null
                    } else {
                        out.add(row)
                    }
                }
                else -> {
                    closeRun()
                    releaseLead()
                    out.add(row)
                }
            }
        }
        val tailGoing = stretch.isNotEmpty()
        closeRun()
        releaseLead()
        val live = working && rows.none { it is PendingMessage }
        if (live) {
            val i = out.indexOfLast { it is MarkedText || it is LooseRun }
            // The run still going, or the last run holding a step still out
            // when the agent spoke meanwhile; nothing after it but its line.
            if (i >= 0 && out.drop(i + 1).none { it is ChatItem.AgentText || it is ChatItem.UserText }) {
                when (val item = out[i]) {
                    is MarkedText -> if (tailGoing || item.run.any { it is ChatItem.ToolStep && it.result == null }) out[i] = item.copy(live = true)
                    is LooseRun -> if (tailGoing || item.run.any { it is ChatItem.ToolStep && it.result == null }) out[i] = item.copy(live = true)
                }
            }
        }
        return out
    }
}
