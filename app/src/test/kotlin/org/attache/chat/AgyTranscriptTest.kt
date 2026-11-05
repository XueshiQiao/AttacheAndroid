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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Step shapes from agy 1.4.12's exports on this machine, 2026-11-05. */
@RunWith(AndroidJUnit4::class)
class AgyTranscriptTest {
    private fun step(index: Int, type: String, status: String = "DONE", fill: JSONObject.() -> Unit = {}): String = JSONObject().apply {
        put("step_index", index)
        put("source", if (type == "USER_INPUT") "USER_EXPLICIT" else "MODEL")
        put("type", type)
        put("status", status)
        put("created_at", "2026-11-05T14:53:57Z")
        fill()
    }.toString()

    private fun user(index: Int, text: String) = step(index, "USER_INPUT") {
        put("content", "<USER_REQUEST>\n$text\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nThe current local time is: 2026-11-05T22:53:57+08:00.\n</ADDITIONAL_METADATA>")
    }

    private fun command(index: Int, line: String, summary: String) = step(index, "PLANNER_RESPONSE") {
        put("input_tokens", 2873)
        put("cache_read_tokens", 101439)
        put("output_tokens", 120)
        put("content", "")
        put(
            "tool_calls",
            org.json.JSONArray().put(
                JSONObject().put("name", "run_command").put(
                    "args",
                    JSONObject().put("CommandLine", line).put("WaitMsBeforeAsync", 10000).put("toolSummary", summary),
                ),
            ),
        )
    }

    private fun output(index: Int, code: Int, printed: String) = step(index, "GENERIC") {
        put("content", "Created At: 2026-10-25T15:08:19+08:00\nCompleted At: 2026-10-25T15:08:19+08:00\nCommand exited with code $code.\n\nCommand output:\n$printed")
    }

    private fun answer(index: Int, text: String) = step(index, "PLANNER_RESPONSE") {
        put("input_tokens", 3000)
        put("cache_read_tokens", 104313)
        put("content", text)
    }

    @Test
    fun aTurnReadsAsPromptCommandAndAnswer() {
        val t = AgyTranscript()
        listOf(
            user(0, "现在查询的频率是多少呀？"),
            step(1, "SYSTEM_MESSAGE") { put("content", "The following is a <SYSTEM_MESSAGE> not actually sent by the user.") },
            command(2, "ls -la", "List the folder"),
            output(3, 0, "total 448\n.git"),
            answer(4, "每 **三秒** 一次。"),
        ).forEach(t::accept)
        val items = t.snapshot
        assertEquals(3, items.size)
        assertEquals("现在查询的频率是多少呀？", (items[0] as ChatItem.UserText).text)
        val run = items[1] as ChatItem.ToolStep
        assertEquals("Bash", run.name)
        assertEquals("List the folder", run.summary)
        assertEquals("ls -la", run.detail)
        assertEquals("total 448\n.git", run.result)
        assertEquals(false, run.isError)
        assertEquals("每 **三秒** 一次。", (items[2] as ChatItem.AgentText).text)
        assertEquals(107_313L, t.status?.contextTokens)
        assertNull(t.status?.model)
    }

    @Test
    fun resultsLandOnTheirCallsInOrderAndAFailedCommandIsAnError() {
        val t = AgyTranscript()
        t.accept(
            step(0, "PLANNER_RESPONSE") {
                put("content", "")
                put(
                    "tool_calls",
                    org.json.JSONArray()
                        .put(JSONObject().put("name", "run_command").put("args", JSONObject().put("CommandLine", "false")))
                        .put(JSONObject().put("name", "edit_file").put("args", JSONObject().put("Path", "/w/a.txt").put("Target", "old").put("Replacement", "new"))),
                )
            },
        )
        t.accept(output(1, 1, "boom"))
        t.accept(step(2, "GENERIC") { put("content", "Created At: x\nCompleted At: y\nMade 1 replacement in /w/a.txt starting at line 15 (+1/-1 lines).") })
        val (run, edit) = t.snapshot.map { it as ChatItem.ToolStep }
        assertEquals("boom", run.result)
        assertTrue(run.isError)
        assertEquals("Edit", edit.name)
        assertEquals("a.txt", edit.summary)
        assertEquals(listOf(DiffLine('-', "old"), DiffLine('+', "new")), edit.diff)
        assertTrue(edit.result!!.startsWith("Made 1 replacement"))
    }

    @Test
    fun aSpinnerLeavesNoLinesInACommandsOutput() {
        val t = AgyTranscript()
        t.accept(command(0, "gh run watch", "Watch"))
        t.accept(output(1, 0, "\n\n\u28fe\n\n\u28fd\n\n\n\u28fb\n\nrun 123 completed\n\u28ff done"))
        assertEquals("run 123 completed\n\u28ff done", (t.snapshot[0] as ChatItem.ToolStep).result)
    }

    @Test
    fun aRewindDropsEveryStepFromWhereItRestarts() {
        // As in a real export: step 583 written again after 593.
        val t = AgyTranscript()
        listOf(
            user(583, "第一次问"),
            answer(584, "第一次答"),
            command(592, "ls", "List"),
            output(593, 0, "x"),
            user(583, "改过的问题"),
            answer(584, "新的回答"),
        ).forEach(t::accept)
        assertEquals(listOf("改过的问题", "新的回答"), t.snapshot.map { (it as? ChatItem.UserText)?.text ?: (it as ChatItem.AgentText).text })
    }

    @Test
    fun aStepWrittenTwiceOrAfterAnInvalidOneIsShownOnce() {
        val t = AgyTranscript()
        listOf(
            command(9, "ls", "List"),
            step(11, "GENERIC", "INVALID") { put("content", "Created At: x") },
            output(10, 0, "one"),
            answer(12, "done"),
            answer(12, "done"),
        ).forEach(t::accept)
        assertEquals(2, t.snapshot.size)
        assertEquals("one", (t.snapshot[0] as ChatItem.ToolStep).result)
    }

    @Test
    fun aRunningStepIsNotShownAndAnImageIsNamed() {
        val t = AgyTranscript()
        t.accept(
            step(0, "USER_INPUT") {
                put("content", "<USER_REQUEST>\n看这张图\n</USER_REQUEST>")
                put("media", org.json.JSONArray().put(JSONObject().put("mime_type", "image/png").put("uri", "/h/.gemini/antigravity-cli/brain/x/.user_uploaded/uploaded_media_0.png")))
            },
        )
        t.accept(command(1, "sleep 100", "Wait"))
        t.accept(step(2, "GENERIC", "RUNNING") { put("content", "Created At: x\nCommand is running") })
        assertEquals("看这张图\n[1 image]", (t.snapshot[0] as ChatItem.UserText).text)
        assertNull((t.snapshot[1] as ChatItem.ToolStep).result)
    }

    @Test
    fun earlierHistoryGoesInFront() {
        val t = AgyTranscript()
        t.accept(output(3, 0, "late"))
        t.accept(answer(4, "end"))
        t.prepend(listOf(user(1, "q"), command(2, "ls", "List")))
        assertEquals(3, t.snapshot.size)
        assertEquals("late", (t.snapshot[1] as ChatItem.ToolStep).result)
    }

    @Test
    fun theProviderNamesThePanesConversation() {
        val s = HostScripts.parse(
            listOf(
                "@@attache-agent agy",
                """{"pane":"%110","where":"Dev:@71","status":"busy","conversation":"37eabe5b-ec94-4711-9ffb-c87b830d58bb","home":"/Users/joey","model": "Gemini 4 Argon (Medium)"}""",
                """{"pane":"%16","where":"Dev:@11","status":"idle","conversation":"","home":"/Users/joey","model":null}""",
                """{"pane":"%17","where":"Dev:@12","status":"idle","conversation":"../../etc","home":"/Users/joey","model":null}""",
            ),
        )
        assertEquals(
            "/Users/joey/.gemini/antigravity-cli/brain/37eabe5b-ec94-4711-9ffb-c87b830d58bb/.system_generated/logs/transcript_full.jsonl",
            s[0].transcriptPath,
        )
        assertTrue(TranscriptFollower.isFollowablePath(s[0].transcriptPath!!))
        assertNull(s[1].transcriptPath)
        assertNull(s[2].transcriptPath)
    }
}
