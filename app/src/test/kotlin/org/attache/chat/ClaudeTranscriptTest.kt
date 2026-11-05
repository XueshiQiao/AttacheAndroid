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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Records are shaped like a real Claude Code 2.1 transcript on this machine. */
@RunWith(AndroidJUnit4::class)
class ClaudeTranscriptTest {
    /** Command output compared by its plain text; the raw form with colour
     * codes is checked by [AnsiTextTest]. */
    private fun parse(vararg lines: String): List<ChatItem> = ClaudeTranscript().apply { lines.forEach(::accept) }.snapshot
        .map { if (it is ChatItem.CommandOutput) it.copy(raw = it.text) else it }

    @Test
    fun promptAndReply() {
        val items = parse(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"修一下滚动 bug"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"text","text":"好的"}]}}""",
        )
        assertEquals(listOf(ChatItem.UserText("u1", "修一下滚动 bug"), ChatItem.AgentText("a1-0", "好的")), items)
    }

    @Test
    fun toolResultLandsOnItsStepAndIsNotAPrompt() {
        val items = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"swift build","description":"Build it"}}]}}""",
            """{"type":"user","uuid":"u2","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"Build complete","is_error":false}]}}""",
        )
        assertEquals(1, items.size)
        val step = items[0] as ChatItem.ToolStep
        assertEquals("Build it", step.summary)
        assertEquals("swift build", step.detail)
        assertEquals("Build complete", step.result)
    }

    @Test
    fun aFailedToolIsMarked() {
        val items = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x/y.swift"}}]}}""",
            """{"type":"user","uuid":"u2","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"no such file"}],"is_error":true}]}}""",
        )
        val step = items.single() as ChatItem.ToolStep
        assertEquals("y.swift", step.summary)
        assertTrue(step.isError)
        assertEquals("no such file", step.result)
    }

    @Test
    fun aQueuedHumanMessageIsKeptAndTheHarnessOnesAreNot() {
        val items = parse(
            """{"type":"attachment","uuid":"q1","attachment":{"type":"queued_command","prompt":"顺便跑测试","origin":{"kind":"human"}}}""",
            """{"type":"attachment","uuid":"q2","attachment":{"type":"queued_command","prompt":"<task-notification>x","origin":{"kind":"task"}}}""",
            """{"type":"queue-operation","operation":"enqueue","content":"顺便跑测试"}""",
        )
        assertEquals(listOf(ChatItem.UserText("q1", "顺便跑测试", queued = true)), items)
    }

    @Test
    fun machineRecordsInTheUsersNameAreDropped() {
        val items = parse(
            """{"type":"user","uuid":"m","isMeta":true,"message":{"content":"skill preamble"}}""",
            """{"type":"user","uuid":"n","message":{"content":"<task-notification>done</task-notification>"}}""",
            """{"type":"user","uuid":"s","isSidechain":true,"message":{"content":"subagent prompt"}}""",
            """{"type":"user","uuid":"i","message":{"content":[{"type":"text","text":"[Image: 1200x800]"}]}}""",
        )
        assertEquals(emptyList<ChatItem>(), items)
    }

    @Test
    fun aQuestionAboutHtmlIsNotMistakenForAnEnvelope() {
        val items = parse("""{"type":"user","uuid":"h","message":{"content":"<div>why does this break?</div>"}}""")
        assertEquals(listOf(ChatItem.UserText("h", "<div>why does this break?</div>")), items)
    }

    @Test
    fun slashAndShellCommandsAreUnwrapped() {
        val items = parse(
            """{"type":"user","uuid":"c","message":{"content":"<command-message>clear</command-message><command-name>/clear</command-name><command-args></command-args>"}}""",
            """{"type":"user","uuid":"b","message":{"content":"<bash-input>git status</bash-input>"}}""",
            """{"type":"user","uuid":"o","message":{"content":"<bash-stdout>On branch main</bash-stdout><bash-stderr></bash-stderr>"}}""",
        )
        assertEquals(
            listOf(ChatItem.UserText("c", "/clear"), ChatItem.UserText("b", "!git status"), ChatItem.CommandOutput("o", "On branch main")),
            items,
        )
    }

    @Test
    fun interruptionIsANote() {
        val items = parse("""{"type":"user","uuid":"x","message":{"content":[{"type":"text","text":"[Request interrupted by user]"}]}}""")
        assertEquals(listOf(ChatItem.Note("x", "Interrupted")), items)
    }

    @Test
    fun redactedThinkingIsNotARow() {
        val items = parse("""{"type":"assistant","uuid":"a","message":{"content":[{"type":"thinking","thinking":"","signature":"abc"}]}}""")
        assertEquals(emptyList<ChatItem>(), items)
    }

    @Test
    fun earlierHistoryGoesInFrontAndAResultFindsACallFromBeforeTheCut() {
        val transcript = ClaudeTranscript()
        // Loaded first: the result, whose call is in the part not yet read.
        transcript.accept("""{"type":"user","uuid":"u2","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""")
        transcript.accept("""{"type":"assistant","uuid":"a2","message":{"content":[{"type":"text","text":"done"}]}}""")
        transcript.prepend(
            listOf(
                """{"type":"user","uuid":"u1","message":{"content":"go"}}""",
                """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            ),
        )
        val items = transcript.snapshot
        assertEquals(listOf("u1", "a1-0", "a2-0"), items.map { it.key })
        assertEquals("ok", (items[1] as ChatItem.ToolStep).result)
    }

    @Test
    fun aSlashCommandsResultIsShown() {
        val items = parse(
            """{"type":"user","uuid":"c","message":{"content":"<command-name>/usage</command-name><command-message>usage</command-message><command-args></command-args>"}}""",
            """{"type":"user","uuid":"r","message":{"content":"<local-command-stdout>Current session: 21% used\nCurrent week: 3% used</local-command-stdout>"}}""",
            """{"type":"user","uuid":"e","message":{"content":"<local-command-stdout>(no content)</local-command-stdout>"}}""",
            """{"type":"user","uuid":"z","message":{"content":"<local-command-stdout></local-command-stdout>"}}""",
        )
        assertEquals(
            listOf(ChatItem.UserText("c", "/usage"), ChatItem.CommandOutput("r", "Current session: 21% used\nCurrent week: 3% used")),
            items,
        )
    }

    /** `/context` as Claude Code 2.1 recorded it on this machine. */
    @Test
    fun aCommandRecordedAsSystemRecordsIsShownToo() {
        val items = parse(
            """{"type":"system","subtype":"local_command","uuid":"s1","isMeta":false,"level":"info","content":"<command-name>/context</command-name>\n            <command-message>context</command-message>\n            <command-args></command-args>"}""",
            """{"type":"system","subtype":"local_command","uuid":"s2","isMeta":false,"level":"info","content":"<local-command-stdout> \u001b[1mContext Usage\u001b[22m\n\u001b[38;5;140m⛁ ⛁\u001b[39m  \u001b[38;5;246m513.9k/1m tokens (51%)\u001b[39m</local-command-stdout>"}""",
            """{"type":"user","uuid":"m","isMeta":true,"message":{"role":"user","content":"## Context Usage\n\n**Tokens:** 513.9k / 1m (51%)"}}""",
            """{"type":"system","subtype":"api_error","uuid":"s3","content":"retrying"}""",
        )
        assertEquals(
            listOf(ChatItem.UserText("s1", "/context"), ChatItem.CommandOutput("s2", "Context Usage\n⛁ ⛁  513.9k/1m tokens (51%)")),
            items,
        )
    }

    @Test
    fun terminalColoursAreTakenOutOfAResult() {
        val items = parse(
            """{"type":"user","uuid":"r","message":{"content":"<local-command-stdout>\u001b[1mContext Usage\u001b[22m\n\u001b[38;5;244m⛀ \u001b[38;5;246m⛁ ⛶\u001b[0m</local-command-stdout>"}}""",
        )
        assertEquals(listOf(ChatItem.CommandOutput("r", "Context Usage\n⛀ ⛁ ⛶")), items)
    }

    @Test
    fun anEditShowsOnlyWhatChangedWithContext() {
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = old.replace("line 5", "line five")
        val diff = LineDiff.of(old, new)
        assertEquals(
            listOf(
                DiffLine('…', ""),
                DiffLine(' ', "line 3"),
                DiffLine(' ', "line 4"),
                DiffLine('-', "line 5"),
                DiffLine('+', "line five"),
                DiffLine(' ', "line 6"),
                DiffLine(' ', "line 7"),
                DiffLine('…', ""),
            ),
            diff,
        )
    }

    @Test
    fun anEditToolCallCarriesItsDiff() {
        val items = parse(
            """{"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":"t","name":"Edit","input":{"file_path":"/x/a.kt","old_string":"val x = 1","new_string":"val x = 2"}}]}}""",
        )
        val step = items.single() as ChatItem.ToolStep
        assertEquals(listOf(DiffLine('-', "val x = 1"), DiffLine('+', "val x = 2")), step.diff)
        assertEquals("a.kt", step.summary)
    }

    @Test
    fun garbageLinesAreSkipped() {
        assertEquals(emptyList<ChatItem>(), parse("not json", "{\"type\":", ""))
    }
}
