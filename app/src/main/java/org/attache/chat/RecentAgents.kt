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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** One chat page opened on the phone. */
data class AgentVisit(val paneId: String, val atMs: Long)

/**
 * Which agents' chats were opened last, per host, newest first: the chat
 * mode's counterpart of the terminal mode's recent windows.
 *
 * Kept in memory on the phone and nowhere else. Which chat the person looked
 * at is the phone's own business: tmux has no opinion about it, and nothing
 * of it is written to tmux or to the agents' files.
 */
object RecentAgents {
    const val KEPT = 8

    private val _visits = MutableStateFlow<Map<UUID, List<AgentVisit>>>(emptyMap())
    val visits: StateFlow<Map<UUID, List<AgentVisit>>> = _visits.asStateFlow()

    fun visit(host: UUID, paneId: String, nowMs: Long = System.currentTimeMillis()) {
        _visits.update { all ->
            val list = listOf(AgentVisit(paneId, nowMs)) + all[host].orEmpty().filterNot { it.paneId == paneId }
            all + (host to list.take(KEPT))
        }
    }

    /**
     * The agents to offer, newest first: those still running, without the
     * one on screen, joined to their current state so each shows its status.
     */
    fun offer(visits: List<AgentVisit>, agents: List<AgentPane>, current: String, limit: Int = 5): List<Pair<AgentPane, Long>> {
        val byPane = agents.associateBy { it.paneId }
        return visits.asSequence()
            .filter { it.paneId != current }
            .mapNotNull { v -> byPane[v.paneId]?.let { it to v.atMs } }
            .take(limit)
            .toList()
    }
}
