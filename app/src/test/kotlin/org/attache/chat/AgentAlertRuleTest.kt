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

class AgentAlertRuleTest {
    private fun agent(pane: String, phase: AgentPhase) = AgentPane(pane, "Dev", "@1", "w", "/", "claude", phase, null, "t$pane")

    private fun posted(step: AgentAlertStep) = step.post.map { (a, alert) -> a.paneId to alert }

    @Test
    fun theFirstReadingTellsNothing() {
        val step = AgentAlertRule.step(null, listOf(agent("%1", AgentPhase.DONE), agent("%2", AgentPhase.NEEDS_YOU)), emptySet())
        assertEquals(emptyList<Pair<String, AgentAlert>>(), posted(step))
        assertEquals(mapOf("%1" to AgentPhase.DONE, "%2" to AgentPhase.NEEDS_YOU), step.phases)
    }

    @Test
    fun aTurnThatEndsIsTold() {
        val step = AgentAlertRule.step(mapOf("%1" to AgentPhase.WORKING), listOf(agent("%1", AgentPhase.DONE)), emptySet())
        assertEquals(listOf("%1" to AgentAlert.FINISHED), posted(step))
    }

    @Test
    fun waitingIsToldOnceAndDoneAfterItToo() {
        val waiting = AgentAlertRule.step(mapOf("%1" to AgentPhase.WORKING), listOf(agent("%1", AgentPhase.NEEDS_YOU)), emptySet())
        assertEquals(listOf("%1" to AgentAlert.NEEDS_YOU), posted(waiting))
        val still = AgentAlertRule.step(waiting.phases, listOf(agent("%1", AgentPhase.NEEDS_YOU)), emptySet())
        assertEquals(emptyList<Pair<String, AgentAlert>>(), posted(still))
        val done = AgentAlertRule.step(still.phases, listOf(agent("%1", AgentPhase.DONE)), emptySet())
        assertEquals(listOf("%1" to AgentAlert.FINISHED), posted(done))
    }

    @Test
    fun doneThatWasAlreadyDoneIsNotToldAgain() {
        val step = AgentAlertRule.step(mapOf("%1" to AgentPhase.DONE), listOf(agent("%1", AgentPhase.DONE)), emptySet())
        assertEquals(emptyList<Pair<String, AgentAlert>>(), posted(step))
        assertEquals(emptySet<String>(), step.clear)
    }

    @Test
    fun seenWorkingOrGoneClearsTheNotification() {
        val step = AgentAlertRule.step(
            mapOf("%1" to AgentPhase.DONE, "%2" to AgentPhase.NEEDS_YOU, "%3" to AgentPhase.DONE),
            listOf(agent("%1", AgentPhase.IDLE), agent("%2", AgentPhase.WORKING)),
            emptySet(),
        )
        assertEquals(setOf("%1", "%2", "%3"), step.clear)
    }

    @Test
    fun anUnreadablePaneKeepsItsPhaseSoTheEndIsStillTold() {
        val blip = AgentAlertRule.step(mapOf("%1" to AgentPhase.WORKING), listOf(agent("%1", AgentPhase.IDLE)), setOf("%1"))
        assertEquals(mapOf("%1" to AgentPhase.WORKING), blip.phases)
        assertEquals(emptySet<String>(), blip.clear)
        val done = AgentAlertRule.step(blip.phases, listOf(agent("%1", AgentPhase.DONE)), emptySet())
        assertEquals(listOf("%1" to AgentAlert.FINISHED), posted(done))
    }

    @Test
    fun aNewPaneThatWaitsIsTold() {
        val step = AgentAlertRule.step(mapOf("%1" to AgentPhase.IDLE), listOf(agent("%1", AgentPhase.IDLE), agent("%2", AgentPhase.NEEDS_YOU)), emptySet())
        assertEquals(listOf("%2" to AgentAlert.NEEDS_YOU), posted(step))
    }
}
