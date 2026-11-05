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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowerTest {
    @Test
    fun aCharacterSplitAcrossTwoReadsSurvives() {
        val splitter = LineSplitter()
        val bytes = "ATTACHE-OFFSET 0\n{\"t\":\"中文\"}\n".toByteArray()
        // Cut inside the three bytes of 中.
        val cut = bytes.indexOf(0xE4.toByte()) + 1
        val first = splitter.feed(bytes.copyOfRange(0, cut))
        val second = splitter.feed(bytes.copyOfRange(cut, bytes.size))
        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("{\"t\":\"中文\"}"), second)
    }

    @Test
    fun theHeaderSaysWhereTheFirstLineStarts() {
        val splitter = LineSplitter()
        val lines = splitter.feed("ATTACHE-OFFSET 120\n{\"a\":2}\n".toByteArray())
        assertEquals(120L, splitter.startOffset)
        assertEquals(listOf("{\"a\":2}"), lines)
    }

    @Test
    fun anUnfinishedLineWaitsForItsNewline() {
        val splitter = LineSplitter()
        assertEquals(emptyList<String>(), splitter.feed("ATTACHE-OFFSET 0\n{\"a\":".toByteArray()))
        assertEquals(listOf("{\"a\":1}"), splitter.feed("1}\n".toByteArray()))
    }

    @Test
    fun onlySafePathsAreFollowed() {
        assertTrue(TranscriptFollower.isFollowablePath("/Users/joey/.claude/projects/-Users-joey-Code-Attache/abc.jsonl"))
        assertTrue(TranscriptFollower.isFollowablePath("/Users/joey/it's here.jsonl"))
        assertFalse(TranscriptFollower.isFollowablePath("relative.jsonl"))
        assertFalse(TranscriptFollower.isFollowablePath("/etc/passwd"))
        assertFalse(TranscriptFollower.isFollowablePath("/x/a\nb.jsonl"))
    }

    /** Runs the real command through /bin/sh against a real file. */
    private fun runEarlier(file: java.io.File, end: Long, budget: Long): Pair<Long, List<String>> {
        val process = ProcessBuilder("/bin/sh", "-c", TranscriptFollower.earlierCommand(file.absolutePath, end, budget))
            .redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        val header = out.substringBefore('\n')
        val offset = header.removePrefix(TranscriptFollower.HEADER).trim().toLong()
        return offset to out.substringAfter('\n', "").split('\n').filter { it.isNotEmpty() }
    }

    @Test
    fun earlierHistoryIsWholeLinesEndingWhereTheLoadedPartBegins() {
        val lines = listOf("{\"n\":1,\"t\":\"一二三\"}", "{\"n\":2,\"t\":\"中文中文\"}", "{\"n\":3}", "{\"n\":4,\"t\":\"尾\"}")
        val file = java.io.File.createTempFile("attache-earlier", ".jsonl").apply { deleteOnExit() }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        val lineStarts = lines.runningFold(0L) { acc, l -> acc + l.toByteArray().size + 1 }
        val end = lineStarts[3] // the start of line 4, as if lines 4.. were already shown
        // A budget that fits line 3 and not line 2 as well, counted in bytes.
        val (offset, got) = runEarlier(file, end, lines[2].toByteArray().size + 1L + 5)
        assertEquals(lineStarts[2], offset)
        assertEquals(listOf(lines[2]), got)
        // A budget that reaches the top of the file takes every line.
        val (top, all) = runEarlier(file, end, 10_000)
        assertEquals(0L, top)
        assertEquals(lines.take(3), all)
        // Even a budget smaller than one line takes one, so scrolling up
        // always gets somewhere.
        val (one, single) = runEarlier(file, end, 1)
        assertEquals(lineStarts[2], one)
        assertEquals(listOf(lines[2]), single)
    }

    /**
     * The real follow command through /bin/sh: the start is chosen on the
     * host by bytes as sent, a long tool result arrives as a short one,
     * and once the reader goes away neither `tail` nor `awk` outlives it.
     */
    @Test
    fun followShrinksLongResultsAndLeavesNothingRunning() {
        val first = "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"一二三四五六七八九十\"}}"
        val big = "{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":[{\"type\":\"tool_result\"," +
            "\"tool_use_id\":\"toolu_X\",\"content\":[{\"type\":\"image\",\"data\":\"" + "A".repeat(300_000) + "\"}]}]}}"
        val last = "{\"type\":\"assistant\",\"uuid\":\"a3\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"好\"}]}}"
        val file = java.io.File.createTempFile("attache-follow", ".jsonl").apply { deleteOnExit() }
        file.writeText("$first\n$big\n$last\n")
        val bigStart = first.toByteArray().size + 1L
        // A budget that fits the last record and the shrunk one, not the first.
        val budget = last.toByteArray().size + 1L + 300 + 10
        val process = ProcessBuilder("/bin/sh", "-c", TranscriptFollower.command(file.absolutePath, budget)).start()
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        val header = reader.readLine()
        val got = listOf(reader.readLine(), reader.readLine())
        assertEquals("ATTACHE-OFFSET $bigStart", header)
        assertTrue(got[0].contains("\"tool_use_id\":\"toolu_X\"") && got[0].length < 400)
        assertTrue(got[0].contains("\"uuid\":\"u2\""))
        assertEquals(last, got[1])

        // The phone closing the channel: both pipes go away.
        process.inputStream.close()
        process.errorStream.close()
        assertTrue("the follower did not exit", process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(500)
        val left = ProcessBuilder("pgrep", "-f", file.name).start()
        val pids = left.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        left.waitFor()
        assertEquals("processes left running: $pids", "", pids)
    }

    @Test
    fun aQuoteInThePathStaysInsideOneShellWord() {
        val command = TranscriptFollower.command("/Users/joey/it's here.jsonl")
        assertTrue(command.startsWith("P='/Users/joey/it'\\''s here.jsonl';"))
    }
}
