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

/** What the chat page's header shows about an agent, as attache-cli reads it
 * from the agent's own files and, for Claude, its status line snapshot. */
data class AgentStatus(
    /** `claude`, `agy` or `grok`: whose payload this is. */
    val source: String,
    val model: String? = null,
    /** `claude-opus-5-5`: what the context window is remembered by. */
    val modelId: String? = null,
    val effort: String? = null,
    val contextPercent: Int? = null,
    val contextWindow: Long? = null,
    val contextTokens: Long? = null,
    val costUsd: Double? = null,
    val linesAdded: Int? = null,
    val linesRemoved: Int? = null,
    val thinking: Boolean? = null,
    val fastMode: Boolean? = null,
    val cacheWarm: Boolean? = null,
    /** Epoch seconds the prompt cache goes cold, when known. */
    val cacheExpiresAt: Long? = null,
    val cacheHitRatio: Double? = null,
    val limits: List<Limit> = emptyList(),
    val version: String? = null,
) {
    /** A usage window, as used percent and when it resets (epoch seconds). */
    data class Limit(val label: String, val usedPercent: Int, val resetsAt: Long?)
}
