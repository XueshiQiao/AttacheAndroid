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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What attache-cli prints, read the way the chat mode reads it. Shapes as
 * attache-cli 0.2.0 prints them (XueshiQiao/Attache cli/README.md). */
@RunWith(AndroidJUnit4::class)
class AttacheCliTest {
    private fun agent(fill: JSONObject.() -> Unit): String = JSONObject().apply {
        put("pane", "%145")
        put("session", "Dev")
        put("window", "@4")
        put("window_name", "attache")
        put("path", "/Users/j/Code/Attache")
        put("command", "2.1.311")
        put("title", "✳ Herdr 手机端通信与显示")
        put("kind", "claude")
        put("state", "working")
        put("since", 1793930260)
        put("conversation", "c94cb7d8")
        put("transcript", "/Users/j/.claude/projects/-Users-j-Code-Attache/c94cb7d8.jsonl")
        put("model", JSONObject.NULL)
        fill()
    }.toString()

    @Test
    fun anAgentReadsAsTheListDrawsIt() {
        val a = AttacheCli.parseAgents(listOf(agent {})).single()
        assertEquals("%145", a.paneId)
        assertEquals("Dev", a.sessionName)
        assertEquals("@4", a.windowId)
        assertEquals(AgentPhase.WORKING, a.phase)
        assertEquals(1793930260L, a.sinceEpochSec)
        // Claude's marker glyph taken off the title it gives the task.
        assertEquals("Herdr 手机端通信与显示", a.title)
        assertEquals("c94cb7d8", a.conversation)
        assertTrue(a.transcriptPath!!.endsWith("c94cb7d8.jsonl"))
        assertNull(a.model)
    }

    @Test
    fun eachStateHasItsPhaseAndUnknownIsIdle() {
        fun phase(state: String) = AttacheCli.parseAgents(listOf(agent { put("state", state) })).single().phase
        assertEquals(AgentPhase.NEEDS_YOU, phase("needs_you"))
        assertEquals(AgentPhase.DONE, phase("done"))
        assertEquals(AgentPhase.IDLE, phase("idle"))
        assertEquals(AgentPhase.IDLE, phase("unknown"))
    }

    @Test
    fun aTitleThatSaysNothingAboutTheWorkGivesWayToTheWindow() {
        fun title(raw: String, command: String = "agy") = AttacheCli.parseAgents(
            listOf(
                agent {
                    put("title", raw)
                    put("command", command)
                },
            ),
        ).single().title
        assertEquals("attache", title("agy --dangerously-skip-permissions"))
        assertEquals("attache", title("joey@Xueshis-MacBook-Pro:~/Code/Attache"))
        assertEquals("attache", title("Claude Code", command = "2.1.311"))
        assertEquals("attache", title("Xueshis-MacBook-Pro.local"))
    }

    @Test
    fun theListSortsByUrgencyThenMostRecent() {
        val rows = AttacheCli.parseAgents(
            listOf(
                agent {
                    put("pane", "%1")
                    put("state", "idle")
                    put("since", 9)
                },
                agent {
                    put("pane", "%2")
                    put("state", "needs_you")
                    put("since", 1)
                },
                agent {
                    put("pane", "%3")
                    put("state", "working")
                    put("since", 5)
                },
                agent {
                    put("pane", "%4")
                    put("state", "working")
                    put("since", 7)
                },
                "not json",
                agent { put("pane", "nope") },
            ),
        )
        assertEquals(listOf("%2", "%4", "%3", "%1"), rows.map { it.paneId })
    }

    @Test
    fun rowsReadAsTheChatDrawsThem() {
        val tool = AttacheCli.item(
            JSONObject(
                """{"type":"tool","key":"a-0","name":"Edit","summary":"a.kt","detail":"/x/a.kt","result":null,"error":false,"diff":[["-","val x = 1"],["+","val x = 2"],["…",""]]}""",
            ),
        ) as ChatItem.ToolStep
        assertEquals("Edit", tool.name)
        assertNull(tool.result)
        assertEquals(listOf(DiffLine('-', "val x = 1"), DiffLine('+', "val x = 2"), DiffLine('…', "")), tool.diff)
        assertEquals(
            ChatItem.UserText("q", "顺便跑测试", queued = true),
            AttacheCli.item(JSONObject("""{"type":"user","key":"q","text":"顺便跑测试","queued":true}""")),
        )
        assertEquals(
            ChatItem.CommandOutput("o", "Context", isError = false, raw = "\u001b[1mContext"),
            AttacheCli.item(JSONObject("""{"type":"output","key":"o","text":"Context","error":false,"raw":"\u001b[1mContext"}""")),
        )
        assertEquals(ChatItem.Note("n", "Interrupted"), AttacheCli.item(JSONObject("""{"type":"note","key":"n","text":"Interrupted"}""")))
        assertNull(AttacheCli.item(JSONObject("""{"type":"future-kind","key":"z"}""")))
    }

    @Test
    fun theStatusReadsAsTheHeaderDrawsIt() {
        val s = AttacheCli.status(
            JSONObject(
                """{"t":"status","source":"claude","model":"Opus 5.5","model_id":"claude-opus-5-5","effort":"high","context_tokens":539318,
                   "context_window":1000000,"context_percent":54,"cost_usd":1.25,"lines_added":null,"lines_removed":null,"thinking":null,
                   "fast_mode":false,"cache_warm":true,"cache_expires_at":1793946489,
                   "limits":[{"label":"5-hour","used_percent":3,"resets_at":1800001000}],"version":"2.1.311"}""",
            ),
        )
        assertEquals("Opus 5.5", s.model)
        assertEquals(54, s.contextPercent)
        assertEquals(539318L, s.contextTokens)
        assertEquals(1.25, s.costUsd!!, 0.0)
        assertNull(s.linesAdded)
        assertEquals(false, s.fastMode)
        assertEquals(1793946489L, s.cacheExpiresAt)
        assertEquals(listOf(AgentStatus.Limit("5-hour", 3, 1800001000L)), s.limits)
    }

    @Test
    fun aFollowCommandQuotesWhatItCarriesAndRefusesWhatItShouldNot() {
        assertEquals(
            "'/h/.local/bin/attache-cli' follow claude '/h/it'\\''s/s.jsonl' --before 42",
            AttacheCli.followCommand("/h/.local/bin/attache-cli", "claude", "/h/it's/s.jsonl", 42),
        )
        assertFalse(AttacheCli.isFollowablePath("relative.jsonl"))
        assertFalse(AttacheCli.isFollowablePath("/a\nb.jsonl"))
        assertTrue(runCatching { AttacheCli.followCommand("/c", "claude; rm", "/a.jsonl") }.isFailure)
    }

    @Test
    fun aLineCutInTheMiddleOfACharacterIsReadWhole() {
        val lines = JsonLines()
        val bytes = "{\"t\":\"items\",\"text\":\"中文\"}\n{\"t\":\"status\"}\n".toByteArray()
        val cut = "{\"t\":\"items\",\"text\":\"中".toByteArray().size - 1
        assertEquals(emptyList<String>(), lines.feed(bytes.copyOfRange(0, cut)))
        assertEquals(listOf("{\"t\":\"items\",\"text\":\"中文\"}", "{\"t\":\"status\"}"), lines.feed(bytes.copyOfRange(cut, bytes.size)))
    }

    @Test
    fun aScreenIsReadAsAttacheCliPrintsIt() {
        val reading = AttacheCli.screen(
            JSONObject(
                """{"t":"screen","pane":"%1","kind":"claude","prompt":false,"approval":{"subject":["Bash command","touch x"],""" +
                    """"question":"Do you want to proceed?","fingerprint":"0123456789abcdef","options":[""" +
                    """{"number":1,"label":"Yes","risk":null},{"number":2,"label":"Yes, always","risk":"stops asking about this from now on"}]},""" +
                    """"panel":null,"panel_fingerprint":null,"activity":null}""",
            ),
        )
        val approval = reading.approval!!
        assertEquals("0123456789abcdef", approval.fingerprint)
        assertEquals(listOf(1, 2), approval.options.map { it.number })
        assertNull(approval.options[0].risk)
        assertEquals("stops asking about this from now on", approval.options[1].risk)
        assertFalse(reading.prompt)
        // A fingerprint that is not attache-cli's shape is no fingerprint:
        // an answer could not name the menu, so there is no menu to answer.
        assertNull(AttacheCli.screen(JSONObject("""{"approval":{"fingerprint":"x","options":[{"number":1,"label":"Yes"}]}}""")).approval)
        val panel = AttacheCli.screen(JSONObject("""{"prompt":false,"panel":["Usage","Esc to exit"],"panel_fingerprint":"00000000000000ff"}"""))
        assertEquals(listOf("Usage", "Esc to exit"), panel.panel)
        assertEquals("00000000000000ff", panel.panelFingerprint)
    }

    @Test
    fun inputSaysWhetherItWasSent() {
        assertEquals(InputResult(true, null), AttacheCli.parseInput(listOf("""{"sent":true}""")))
        assertEquals(InputResult(false, "panel_closed"), AttacheCli.parseInput(listOf("""{"sent":false,"why":"panel_closed"}""")))
        // Nothing printed (no attache-cli, or one from before protocol 2) sent nothing.
        assertEquals(InputResult(false, "unreachable"), AttacheCli.parseInput(emptyList()))
        assertEquals(InputResult(false, "unreachable"), AttacheCli.parseInput(null))
    }

    @Test
    fun aQuestionIsReadWithItsTabsTicksAndSubmitRow() {
        // The shape attache-cli 0.2.3 prints for a multiple choice, the
        // second of two questions.
        val reading = AttacheCli.screen(
            JSONObject(
                """{"prompt":false,"approval":null,"panel":null,"activity":null,"question":{"header":"",""" +
                    """"question":"Which sides?","tabs":[{"title":"Position","answered":true},{"title":"Sides","answered":false}],""" +
                    """"multi":true,"review":false,"answers":[],"cursor":1,"submit_row":true,"fingerprint":"00000000000000ab",""" +
                    """"options":[{"number":1,"label":"Left","description":"The left edge","typed":false,"checked":true},""" +
                    """{"number":2,"label":"Right","description":"","typed":false,"checked":false},""" +
                    """{"number":3,"label":"Type something.","description":"","typed":true,"checked":false}]}}""",
            ),
        )
        val q = reading.question!!
        assertEquals("Which sides?", q.question)
        assertEquals(listOf(QuestionPrompt.Tab("Position", true), QuestionPrompt.Tab("Sides", false)), q.tabs)
        assertTrue(q.multi && q.submitRow && !q.review)
        assertEquals(listOf(true, false, false), q.options.map { it.checked })
        assertEquals(listOf(false, false, true), q.options.map { it.typed })
        assertEquals("The left edge", q.options[0].description)
    }

    @Test
    fun aQuestionWithoutAFingerprintCannotBeAnswered() {
        val reading = AttacheCli.screen(
            JSONObject("""{"question":{"question":"Q","fingerprint":"XYZ","options":[{"number":1,"label":"A"}]}}"""),
        )
        assertNull(reading.question)
        val review = AttacheCli.screen(
            JSONObject(
                """{"question":{"question":"Review your answers","review":true,"fingerprint":"0123456789abcdef",""" +
                    """"answers":[{"question":"Which sides?","answer":"Left"}],"options":[{"number":1,"label":"Submit answers"},{"number":2,"label":"Cancel"}]}}""",
            ),
        ).question!!
        assertTrue(review.review)
        assertEquals(listOf("Which sides?" to "Left"), review.answers)
    }
}
