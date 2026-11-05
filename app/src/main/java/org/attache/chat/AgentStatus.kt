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

import org.json.JSONObject

/** What an agent says about itself through its status line. */
data class AgentStatus(
    /** `claude`, `agy` or `grok`: whose payload this is. */
    val source: String,
    val model: String? = null,
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

/**
 * Reads the JSON an agent hands its status line, stored in the pane option
 * `@agent_stat`.
 *
 * One reader for three agents, because three agents chose one shape: Claude
 * Code's, which Antigravity (`agy`) and Grok Build both follow. The fields
 * differ only where a table can say so, measured on this machine on
 * 2026-11-05 from a live payload of each (Grok's from its own documentation,
 * `~/.grok/docs/user-guide/25-status-line.md`):
 *
 * | | Claude Code | agy | Grok Build |
 * |---|---|---|---|
 * | model | `model.display_name` | same | same |
 * | effort | `effort.level` | `model.effort` | `effort.level` |
 * | context | `context_window.used_percentage` | same, fractional | same |
 * | marks it as | neither below | `product: antigravity` | `schema_version` |
 *
 * The mark matters: a pane keeps its `@agent_stat` after the agent that
 * wrote it exits, so a pane now running agy was seen still carrying Claude's
 * payload. [parse] says whose payload it is, and [forAgent] refuses one that
 * is not the running agent's.
 */
object AgentStatuses {
    fun parse(raw: String): AgentStatus? {
        val root = try {
            JSONObject(raw.trim())
        } catch (_: Exception) {
            return null
        }
        val source = when {
            root.optString("product") == "antigravity" -> "agy"
            root.has("schema_version") -> "grok"
            root.has("model") || root.has("context_window") -> "claude"
            else -> return null
        }
        val model = root.optJSONObject("model")
        val context = root.optJSONObject("context_window")
        val cost = root.optJSONObject("cost")
        val cache = root.optJSONObject("prompt_cache")
        return AgentStatus(
            source = source,
            model = model?.str("display_name") ?: model?.str("id"),
            effort = root.optJSONObject("effort")?.str("level") ?: model?.str("effort"),
            contextPercent = context?.num("used_percentage")?.let { Math.round(it).toInt().coerceIn(0, 100) },
            contextWindow = context?.num("context_window_size")?.toLong(),
            contextTokens = context?.let(::contextTokens),
            costUsd = cost?.num("total_cost_usd"),
            linesAdded = cost?.num("total_lines_added")?.toInt(),
            linesRemoved = cost?.num("total_lines_removed")?.toInt(),
            thinking = root.optJSONObject("thinking")?.bool("enabled"),
            fastMode = root.bool("fast_mode"),
            cacheWarm = cache?.bool("warm"),
            cacheExpiresAt = cache?.num("expires_at")?.toLong(),
            cacheHitRatio = cache?.num("hit_ratio"),
            limits = limits(root),
            version = root.str("version"),
        )
    }

    /** The status, if it is the running agent's; null for one a previous
     * agent in the pane left behind. */
    fun forAgent(status: AgentStatus?, agentKind: String): AgentStatus? = status?.takeIf { it.source == agentKind || (agentKind.isEmpty() && it.source == "claude") }

    /** What sits in the window now: Grok names it, the others give the last
     * call's input, which is what was sent and so what the window holds. */
    private fun contextTokens(context: JSONObject): Long? {
        context.num("context_tokens")?.let { return it.toLong() }
        val usage = context.optJSONObject("current_usage") ?: return null
        val sum = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens").sumOf { usage.num(it) ?: 0.0 }
        return sum.toLong().takeIf { it > 0 }
    }

    private fun limits(root: JSONObject): List<AgentStatus.Limit> {
        val out = ArrayList<AgentStatus.Limit>()
        root.optJSONObject("rate_limits")?.let { rl ->
            for ((key, label) in listOf("five_hour" to "5-hour", "seven_day" to "7-day")) {
                val w = rl.optJSONObject(key) ?: continue
                val used = w.num("used_percentage") ?: continue
                out += AgentStatus.Limit(label, Math.round(used).toInt(), w.num("resets_at")?.toLong())
            }
        }
        // agy reports what is left, per model family, as a fraction.
        root.optJSONObject("quota")?.let { q ->
            for (key in q.keys()) {
                val w = q.optJSONObject(key) ?: continue
                if (w.optBoolean("disabled")) continue
                val left = w.num("remaining_fraction") ?: continue
                val resetIn = w.num("reset_in_seconds")?.toLong()
                val label = key.replace("-5h", " 5-hour").replace("-weekly", " weekly")
                out += AgentStatus.Limit(label, Math.round((1 - left) * 100).toInt(), resetIn?.takeIf { it > 0 }?.let { System.currentTimeMillis() / 1000 + it })
            }
        }
        return out
    }

    private fun JSONObject.str(key: String): String? = optString(key, "").takeIf { has(key) && !isNull(key) && it.isNotBlank() }

    private fun JSONObject.num(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
}
