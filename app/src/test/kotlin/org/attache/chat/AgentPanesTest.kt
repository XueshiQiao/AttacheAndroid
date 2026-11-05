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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rows are shaped like `list-panes -a` on the owner's machine, 2026-11-05. */
class AgentPanesTest {
    private fun row(vararg f: String) = f.joinToString("\u0001")

    private fun parseOne(line: String): AgentPane? = AgentPanes.parse(listOf(line)).singleOrNull()

    @Test
    fun claudePaneTakesItsTitleFromTheTask() {
        val agent = parseOne(
            row("%145", "Dev", "@4", "Attaché", "/Users/joey/Code/Attache", "claude", "working", "1793876884", "claude", "✳ Herdr 手机端通信与显示"),
        )!!
        assertEquals("claude", agent.kind)
        assertEquals(AgentPhase.WORKING, agent.phase)
        assertEquals(1793876884L, agent.sinceEpochSec)
        assertEquals("Herdr 手机端通信与显示", agent.title)
    }

    @Test
    fun needsInputIsTheNeedsYouPhase() {
        val agent = parseOne(row("%1", "Dev", "@1", "w", "/", "claude", "needs-input", "1", "claude", "✳ x"))!!
        assertEquals(AgentPhase.NEEDS_YOU, agent.phase)
    }

    @Test
    fun aShellPaneIsNotAnAgentEvenWithLeftoverOptions() {
        // `@agent_state` outlives a ^C exit.
        assertNull(parseOne(row("%28", "Dev", "@18", "HyperCapslock", "/", "claude", "working", "1", "zsh", "joey@Mac:~/x")))
        assertNull(parseOne(row("%15", "Dev", "@10", "DroidPaw", "/", "", "", "", "zsh", "joey@Mac:~/x")))
    }

    @Test
    fun aStaleOptionFromAnotherAgentIsIgnored() {
        val agent = parseOne(
            row("%17", "Dev", "@11", "VideoDuo", "/", "claude", "working", "1792030958", "grok-1.0.52-mac", "grok"),
        )!!
        assertEquals("grok", agent.kind)
        assertEquals(AgentPhase.IDLE, agent.phase)
        assertNull(agent.sinceEpochSec)
    }

    @Test
    fun anAgentWithNoHookStillAppearsAsIdle() {
        val agent = parseOne(
            row("%73", "Dev", "@55", "8Ball", "/", "", "", "", "agy", "agy --dangerously-skip-permissions"),
        )!!
        assertEquals("agy", agent.kind)
        assertEquals(AgentPhase.IDLE, agent.phase)
        // The title is its own command line, so the window name stands in.
        assertEquals("8Ball", agent.title)
    }

    @Test
    fun claudeNamedAfterItsVersionIsStillClaude() {
        val agent = parseOne(row("%2", "Dev", "@2", "w", "/", "", "", "", "2.1.220", "Xueshis-MacBook-Pro.local"))!!
        assertEquals("claude", agent.kind)
        assertEquals("w", agent.title)
    }

    @Test
    fun rowsSortByUrgencyThenMostRecent() {
        val agents = AgentPanes.parse(
            listOf(
                row("%1", "s", "@1", "a", "/", "claude", "done", "300", "claude", "a"),
                row("%2", "s", "@2", "b", "/", "claude", "needs-input", "100", "claude", "b"),
                row("%3", "s", "@3", "c", "/", "claude", "working", "200", "claude", "c"),
                row("%4", "s", "@4", "d", "/", "claude", "done", "400", "claude", "d"),
            ),
        )
        assertEquals(listOf("%2", "%3", "%4", "%1"), agents.map { it.paneId })
    }

    @Test
    fun malformedRowsAreDropped() {
        assertEquals(emptyList<AgentPane>(), AgentPanes.parse(listOf("", "garbage", row("x", "s", "@1"))))
    }
}
