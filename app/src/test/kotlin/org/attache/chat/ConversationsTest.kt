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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.attache.tmux.TmuxControl
import org.connectbot.sshlib.SshSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConversationsTest {
    private val host = UUID.randomUUID()

    /** Transcripts whose `attache-cli follow` is running now. */
    private val following = ConcurrentHashMap.newKeySet<String>()

    /** Every follow command started, in order. */
    private val commands = CopyOnWriteArrayList<String>()

    /**
     * A channel whose `follow` answers as attache-cli does: `hello`, the
     * rows, the status, and more rows while the test adds them to [extra].
     */
    private val extra = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    private fun control(): TmuxControl {
        val self = AtomicReference<TmuxControl>()
        return TmuxControl(
            session = mock(SshSession::class.java),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
            stream = { command, onChunk ->
                commands += command
                val path = Regex("follow claude '([^']+)'").find(command)!!.groupValues[1]
                val before = Regex("--before (\\d+)").find(command)?.groupValues?.get(1)?.toLong()
                following += path
                try {
                    // Earlier history: read from further back, every row again.
                    val start = if (before == null) 100 else 0
                    val rows = buildList {
                        if (before != null) add("""{"type":"user","key":"u0","text":"earlier","queued":false}""")
                        add("""{"type":"user","key":"u1","text":"hello $path","queued":false}""")
                    }
                    onChunk(
                        (
                            """{"t":"hello","protocol":1,"kind":"claude","path":"$path","start":$start,"earlier":${start > 0}}""" + "\n" +
                                """{"t":"items","from":0,"items":[${rows.joinToString(",")}]}""" + "\n" +
                                """{"t":"status","source":"claude","model":"Opus 5.5","context_tokens":10,"limits":[]}""" + "\n"
                            ).toByteArray(),
                    )
                    var sent = 0
                    // attache-cli runs until the channel ends.
                    while (self.get()?.isClosed != true) {
                        val more = extra[path].orEmpty()
                        while (sent < more.size) onChunk((more[sent++] + "\n").toByteArray())
                        delay(10)
                    }
                    true
                } finally {
                    following -= path
                }
            },
        ).also {
            self.set(it)
            it.cliInstalled("/h/.local/bin/attache-cli")
        }
    }

    private fun path(n: Int) = "/h/.claude/projects/w/$n.jsonl"

    private fun open(tmux: TmuxControl, n: Int): LiveConversation = Conversations.get(host, path(n), "claude", Dispatchers.Default).also { it.follow(tmux) }

    private fun live(c: LiveConversation, want: (ConversationView) -> Boolean = { it.state == TranscriptStatus.LIVE }) = runBlocking { withTimeout(5_000) { c.view.first(want) } }

    @Test
    fun aConversationIsKeptAndFollowedAfterItsPageIsGone() {
        val tmux = control()
        val c = open(tmux, 1)
        val view = live(c)
        assertEquals("hello ${path(1)}", (view.items.single() as ChatItem.UserText).text)
        assertEquals(100L, view.historyStart)
        assertEquals("'/h/.local/bin/attache-cli' follow claude '${path(1)}'", commands.single())
        // Opened again: the same one, already read, still followed.
        assertSame(c, Conversations.get(host, path(1), "claude", Dispatchers.Default))
        assertTrue(path(1) in following)
        tmux.close()
    }

    @Test
    fun rowsFromTheFirstThatChangedReplaceTheRestAndTheStatusComesAlong() {
        val tmux = control()
        val c = open(tmux, 30)
        live(c)
        extra.getOrPut(path(30)) { CopyOnWriteArrayList() } +=
            """{"t":"items","from":1,"items":[{"type":"agent","key":"a1","text":"好的","queued":false}]}"""
        val after = live(c) { it.items.size == 2 }
        assertEquals(listOf("u1", "a1"), after.items.map { it.key })
        assertEquals("Opus 5.5", after.status?.model)
        // A rewind cuts the tail off.
        extra[path(30)]!! += """{"t":"items","from":1,"items":[]}"""
        assertEquals(listOf("u1"), live(c) { it.items.size == 1 }.items.map { it.key })
        tmux.close()
    }

    @Test
    fun earlierHistoryIsANewFollowFromBeforeWhereThisOneStarted() {
        val tmux = control()
        val c = open(tmux, 40)
        live(c)
        runBlocking { assertTrue(c.loadEarlier(tmux)) }
        val more = live(c) { it.items.size == 2 }
        assertEquals(listOf("u0", "u1"), more.items.map { it.key })
        assertEquals(0L, more.historyStart)
        assertTrue(commands.last().endsWith("--before 100"))
        tmux.close()
    }

    @Test
    fun theNinthPushesOutTheLeastRecentAndStopsItsFollow() {
        val tmux = control()
        val first = open(tmux, 10).also { live(it) }
        for (n in 11..17) open(tmux, n)
        assertTrue(path(10) in following)
        open(tmux, 18)
        runBlocking { withTimeout(5_000) { while (path(10) in following) delay(10) } }
        assertNotSame(first, Conversations.get(host, path(10), "claude", Dispatchers.Default))
        tmux.close()
    }

    @Test
    fun aClosedConnectionTakesItsConversationsWithIt() {
        val tmux = control()
        val c = open(tmux, 20).also { live(it) }
        tmux.close()
        // The follow ends with the channel, and the conversation forgets itself.
        runBlocking {
            withTimeout(5_000) {
                while (Conversations.get(host, path(20), "claude", Dispatchers.Default) === c) delay(10)
            }
        }
    }
}
