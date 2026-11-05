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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Payloads cut down from live ones on this machine, 2026-11-05. */
@RunWith(AndroidJUnit4::class)
class AgentStatusesTest {
    private val claude = """{"session_id":"s","effort":{"level":"high"},"model":{"id":"claude-opus-5-5","display_name":"Opus 5.5"},
        "version":"2.1.311","cost":{"total_cost_usd":40.0000389,"total_lines_added":3067,"total_lines_removed":141},
        "context_window":{"total_input_tokens":676914,"context_window_size":1000000,"current_usage":{"input_tokens":2,"output_tokens":8,
        "cache_creation_input_tokens":1621,"cache_read_input_tokens":675291},"used_percentage":68,"remaining_percentage":32},
        "prompt_cache":{"warm":true,"ttl":"1h","expires_at":1793890192,"hit_ratio":0.9936986162533591},"fast_mode":false,
        "thinking":{"enabled":true},"rate_limits":{"five_hour":{"used_percentage":5,"resets_at":1793901600},
        "seven_day":{"used_percentage":4,"resets_at":1794430800}}}"""

    private val agy = """{"session_id":"s","model":{"id":"Gemini 4 Argon (Medium)","display_name":"Gemini 4 Argon (Medium)","effort":"medium"},
        "version":"1.4.12","context_window":{"context_window_size":1048576,"used_percentage":24.004268646240234},"product":"antigravity",
        "quota":{"3p-5h":{"remaining_fraction":1,"disabled":true},"gemini-5h":{"remaining_fraction":0.90346307,"reset_in_seconds":0},
        "gemini-weekly":{"remaining_fraction":0.86459285,"reset_in_seconds":425852}},"agent_state":"idle"}"""

    private val grok = """{"schema_version":1,"version":"1.0.52","model":{"id":"grok-4.7","display_name":"Grok 4.7"},
        "effort":{"level":"medium"},"context_window":{"context_window_size":256000,"context_tokens":64000,"used_percentage":25}}"""

    @Test
    fun claudeCode() {
        val s = AgentStatuses.parse(claude)!!
        assertEquals("claude", s.source)
        assertEquals("Opus 5.5", s.model)
        assertEquals("high", s.effort)
        assertEquals(68, s.contextPercent)
        assertEquals(1_000_000L, s.contextWindow)
        assertEquals(676_914L, s.contextTokens)
        assertEquals(true, s.cacheWarm)
        assertEquals(1793890192L, s.cacheExpiresAt)
        assertEquals(listOf("5-hour" to 5, "7-day" to 4), s.limits.map { it.label to it.usedPercent })
        assertEquals(true, s.thinking)
    }

    @Test
    fun antigravityKeepsEffortUnderTheModelAndReportsWhatIsLeft() {
        val s = AgentStatuses.parse(agy)!!
        assertEquals("agy", s.source)
        assertEquals("Gemini 4 Argon (Medium)", s.model)
        assertEquals("medium", s.effort)
        assertEquals(24, s.contextPercent)
        // The disabled third-party window is left out; what is left becomes what is used.
        assertEquals(listOf("gemini 5-hour" to 10, "gemini weekly" to 14), s.limits.map { it.label to it.usedPercent })
    }

    @Test
    fun grokBuild() {
        val s = AgentStatuses.parse(grok)!!
        assertEquals("grok", s.source)
        assertEquals("Grok 4.7", s.model)
        assertEquals(25, s.contextPercent)
        assertEquals(64_000L, s.contextTokens)
    }

    @Test
    fun aPayloadAPreviousAgentLeftIsRefused() {
        // Seen on pane %62: agy running, Claude's payload still in @agent_stat.
        val left = AgentStatuses.parse(claude)
        assertNull(AgentStatuses.forAgent(left, "agy"))
        assertEquals("claude", AgentStatuses.forAgent(left, "claude")?.source)
    }

    @Test
    fun nothingUsableIsNothing() {
        assertNull(AgentStatuses.parse(""))
        assertNull(AgentStatuses.parse("not json"))
        assertNull(AgentStatuses.parse("""{"session_id":"s"}"""))
    }
}
