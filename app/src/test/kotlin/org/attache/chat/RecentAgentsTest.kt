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
import java.util.UUID

class RecentAgentsTest {
    private fun agent(pane: String) = AgentPane(pane, "Dev", "@1", "w", "/", "claude", AgentPhase.DONE, null, "t$pane")

    @Test
    fun aVisitMovesToTheFrontOnce() {
        val host = UUID.randomUUID()
        RecentAgents.visit(host, "%1", 1)
        RecentAgents.visit(host, "%2", 2)
        RecentAgents.visit(host, "%1", 3)
        assertEquals(listOf("%1", "%2"), RecentAgents.visits.value[host]!!.map { it.paneId })
    }

    @Test
    fun hostsAreKeptApart() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        RecentAgents.visit(a, "%1", 1)
        RecentAgents.visit(b, "%9", 1)
        assertEquals(listOf("%1"), RecentAgents.visits.value[a]!!.map { it.paneId })
    }

    @Test
    fun theOfferLeavesOutTheCurrentOneAndAgentsThatAreGone() {
        val visits = listOf(AgentVisit("%3", 30), AgentVisit("%2", 20), AgentVisit("%7", 15), AgentVisit("%1", 10))
        val offered = RecentAgents.offer(visits, listOf(agent("%1"), agent("%2"), agent("%3")), current = "%3")
        assertEquals(listOf("%2" to 20L, "%1" to 10L), offered.map { it.first.paneId to it.second })
    }

    @Test
    fun atMostFiveAreOffered() {
        val visits = (1..8).map { AgentVisit("%$it", it.toLong()) }
        val offered = RecentAgents.offer(visits, (1..8).map { agent("%$it") }, current = "%0")
        assertEquals(5, offered.size)
    }
}
