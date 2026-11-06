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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConversationsTest {
    private val host = UUID.randomUUID()

    /** Paths whose follow is running now, as `tail -f` would be on the host. */
    private val following = ConcurrentHashMap.newKeySet<String>()

    private fun control(): TmuxControl {
        val self = AtomicReference<TmuxControl>()
        return TmuxControl(
            session = mock(SshSession::class.java),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
            stream = { command, onChunk ->
                val path = Regex("P='([^']+)'").find(command)!!.groupValues[1]
                following += path
                try {
                    onChunk(
                        (
                            "${TranscriptFollower.HEADER}0\n" +
                                """{"type":"user","uuid":"u1","message":{"role":"user","content":"hello $path"}}""" + "\n"
                            ).toByteArray(),
                    )
                    // `tail -f` runs until the channel ends.
                    while (self.get()?.isClosed != true) delay(10)
                    true
                } finally {
                    following -= path
                }
            },
        ).also(self::set)
    }

    private fun path(n: Int) = "/h/.claude/projects/w/$n.jsonl"

    private fun open(tmux: TmuxControl, n: Int): LiveConversation = Conversations.get(host, path(n), "claude", Dispatchers.Default).also { it.follow(tmux) }

    private fun live(c: LiveConversation) = runBlocking { withTimeout(5_000) { c.view.first { it.state == TranscriptStatus.LIVE } } }

    @Test
    fun aConversationIsKeptAndFollowedAfterItsPageIsGone() {
        val tmux = control()
        val c = open(tmux, 1)
        assertEquals("hello ${path(1)}", (live(c).items.single() as ChatItem.UserText).text)
        // Opened again: the same one, already read, still followed.
        assertSame(c, Conversations.get(host, path(1), "claude", Dispatchers.Default))
        assertTrue(path(1) in following)
        tmux.close()
    }

    @Test
    fun theNinthPushesOutTheLeastRecentAndStopsItsFollow() {
        val tmux = control()
        val first = open(tmux, 10).also(::live)
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
        val c = open(tmux, 20).also(::live)
        tmux.close()
        // The follow ends with the channel, and the conversation forgets itself.
        runBlocking {
            withTimeout(5_000) {
                while (Conversations.get(host, path(20), "claude", Dispatchers.Default) === c) delay(10)
            }
        }
    }
}
