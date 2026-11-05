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

/** The bottom of a working Claude Code pane, as captured on this machine. */
class AgentActivitiesTest {
    private val rule = "─".repeat(80)

    private fun screen(status: String, vararg above: String) = above.toList() + listOf(
        status,
        "  ⎿  Tip: Run Claude Code locally or remotely using the Claude desktop app · run",
        "     /desktop",
        "",
        rule,
        "❯ ",
        rule,
        "  ~/Code/Attache   main  |  Opus 5.5  ctx 36%",
        "  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← 1 agent",
    )

    @Test
    fun readsTheVerbAndItsDetail() {
        val activity = AgentActivities.detect(screen("✻ Jitterbugging… (13s · ↓ 649 tokens · thought for 7s)"))
        assertEquals(AgentActivity("Jitterbugging…", "13s · ↓ 649 tokens · thought for 7s"), activity)
    }

    @Test
    fun theKeyHintIsDropped() {
        val activity = AgentActivities.detect(screen("✶ Pondering… (2m 4s · ↑ 1.2k tokens · esc to interrupt)"))
        assertEquals(AgentActivity("Pondering…", "2m 4s · ↑ 1.2k tokens"), activity)
    }

    @Test
    fun aStatusWithNoBracketsStillCounts() {
        assertEquals(AgentActivity("Thinking…", null), AgentActivities.detect(screen("· Thinking…")))
    }

    @Test
    fun aBulletInTheAgentsOutputIsNotAStatus() {
        val rows = listOf("* Loading the config…", "", "⏺ Done.") + List(10) { "  output line $it" } +
            listOf("", rule, "❯ ", rule, "  status line")
        assertNull(AgentActivities.detect(rows))
    }

    @Test
    fun anIdlePaneHasNone() {
        assertNull(AgentActivities.detect(screen("⏺ All tests pass.")))
    }
}
