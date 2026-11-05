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

class PendingMessagesTest {
    private fun user(key: String, text: String) = ChatItem.UserText(key, text)
    private fun agent(key: String) = ChatItem.AgentText(key, "reply $key")
    private fun pending(id: Long, text: String, anchor: String?) = PendingMessage(id, text, 0L, anchorKey = anchor)

    /** The case from the owner's phone: commands never confirmed, then a
     * message that was. The commands stay where they were sent. */
    @Test
    fun anUnconfirmedMessageStaysWhereItWasSent() {
        val items = listOf(agent("a1"), agent("a2"), user("u3", "later message"), agent("a4"))
        val rows = PendingMessages.interleave(items, listOf(pending(1, "/context", "a2"), pending(2, "/usage", "a2")))
        assertEquals(listOf("a1", "a2", "p1", "p2", "u3", "a4"), rows.map { if (it is PendingMessage) "p${it.id}" else (it as ChatItem).key })
    }

    @Test
    fun aMessageWithNoAnchorGoesAtTheEnd() {
        val rows = PendingMessages.interleave(listOf(agent("a1")), listOf(pending(1, "hi", null), pending(2, "x", "gone")))
        assertEquals(3, rows.size)
        assertTrue(rows[1] is PendingMessage && rows[2] is PendingMessage)
    }

    @Test
    fun onlyAPromptAfterTheAnchorConfirms() {
        val items = listOf(user("u1", "/context"), agent("a2"))
        // A second /context: the first one must not count.
        assertFalse(PendingMessages.confirmed(pending(1, "/context", "a2"), items))
        assertTrue(PendingMessages.confirmed(pending(1, "/context", "a2"), items + user("u3", "/context")))
    }

    @Test
    fun imagesAloneAreConfirmedByTheNextPrompt() {
        val p = PendingMessage(1, "(2 images)", 0L, match = "", anchorKey = "a1")
        assertFalse(PendingMessages.confirmed(p, listOf(agent("a1"))))
        assertTrue(PendingMessages.confirmed(p, listOf(agent("a1"), user("u2", "/tmp/attache-img-1.png"))))
    }

    @Test
    fun consecutiveToolStepsShareOneCardThatKeepsItsKeyAsItGrows() {
        fun step(key: String) = ChatItem.ToolStep(key, "Bash", "", "")
        val first = ToolGroups.group(listOf(agent("a1"), step("t1"), step("t2"), agent("a2"), step("t3")))
        assertEquals(listOf("a1", "group-t1", "a2", "group-t3"), first.map { (it as? ToolGroup)?.key ?: (it as ChatItem).key })
        assertEquals(2, (first[1] as ToolGroup).steps.size)
        val grown = ToolGroups.group(listOf(agent("a1"), step("t1"), step("t2"), step("t9")))
        assertEquals("group-t1", (grown[1] as ToolGroup).key)
        assertEquals(3, (grown[1] as ToolGroup).steps.size)
    }
}
