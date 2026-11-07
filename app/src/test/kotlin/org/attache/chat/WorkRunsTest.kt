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
import org.junit.Test

class WorkRunsTest {
    private fun u(k: String) = ChatItem.UserText(k, k)
    private fun a(k: String) = ChatItem.AgentText(k, "said $k")
    private fun th(k: String) = ChatItem.Thinking(k, "thought $k")
    private fun tl(k: String, done: Boolean = true, bad: Boolean = false) =
        ChatItem.ToolStep(k, "Bash", k, "", result = if (done) "ok" else null, isError = bad)

    private fun show(rows: List<Any>): String = rows.joinToString(" ") {
        when (it) {
            is MarkedText -> "${it.text.key}{${it.key}${if (it.live) " live" else ""} t${it.thoughts} s${it.steps} f${it.failed}}"
            is LooseRun -> "[${it.key}${if (it.live) " live" else ""} s${it.steps}]"
            is ChatItem -> it.key
            is PendingMessage -> "P${it.id}"
            else -> "?"
        }
    }

    @Test
    fun eachRunHangsOnTheLineBeforeIt() {
        assertEquals(
            "u1 a1{w:a1 t1 s1 f0} a2{w:a2 t0 s1 f1} a3",
            show(WorkRuns.attach(listOf(u("u1"), a("a1"), th("t1"), tl("x1"), a("a2"), tl("x2", bad = true), a("a3")), working = false)),
        )
    }

    @Test
    fun aRunBeforeAnyLineJoinsTheNextLine() {
        assertEquals(
            "u1 a1{run:x0 t0 s2 f0} a2",
            show(WorkRuns.attach(listOf(u("u1"), tl("x0"), a("a1"), tl("x1"), a("a2")), working = false)),
        )
    }

    @Test
    fun theRunStillGoingIsLive() {
        assertEquals(
            "u1 a1{w:a1 live t0 s1 f0}",
            show(WorkRuns.attach(listOf(u("u1"), a("a1"), tl("x1", done = false)), working = true)),
        )
        assertEquals(
            "u1 [run:t1 live s1]",
            show(WorkRuns.attach(listOf(u("u1"), th("t1"), tl("x1", done = false)), working = true)),
        )
    }

    @Test
    fun aMessageOnItsWayMeansNothingIsLive() {
        val p = PendingMessage(id = 7, text = "hi", sentAtMs = 0, anchorKey = "a1")
        assertEquals(
            "u1 a1{w:a1 t0 s1 f0} P7",
            show(WorkRuns.attach(listOf(u("u1"), a("a1"), tl("x1", done = false), p), working = true)),
        )
    }

    @Test
    fun aRunBeforeANoteStandsAlone() {
        assertEquals(
            "u1 [run:x1 s1] n1 a1",
            show(WorkRuns.attach(listOf(u("u1"), tl("x1"), ChatItem.Note("n1", "Interrupted"), a("a1")), working = false)),
        )
    }
}
