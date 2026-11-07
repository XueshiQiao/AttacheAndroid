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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.attache.tmux.TmuxControl
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.MainActivity
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Why the phone tells the person about an agent. */
enum class AgentAlert { FINISHED, NEEDS_YOU }

/**
 * What one reading of a host's agents does to the notifications: the phases
 * to compare the next reading against, what to post and which panes'
 * notifications are no longer true.
 */
data class AgentAlertStep(
    val phases: Map<String, AgentPhase>,
    val post: List<Pair<AgentPane, AgentAlert>>,
    val clear: Set<String>,
)

object AgentAlertRule {
    /**
     * [before] is null on the first reading of a host since the app started:
     * every agent that is Done then was Done before anyone was watching, and
     * telling the person about all of them at once would bury the one that
     * matters. Readings after a reconnect compare against the last one before
     * it, so a turn that ended while the phone was offline is still told.
     *
     * A pane attache-cli could not read ([unknown]) keeps its last phase: a
     * session file caught mid-rewrite would otherwise turn Working into Idle
     * and back into Done with nothing to compare against.
     */
    fun step(before: Map<String, AgentPhase>?, now: List<AgentPane>, unknown: Set<String>): AgentAlertStep {
        val phases = HashMap<String, AgentPhase>()
        val post = ArrayList<Pair<AgentPane, AgentAlert>>()
        val clear = HashSet<String>()
        for (agent in now) {
            val prev = before?.get(agent.paneId)
            if (agent.paneId in unknown) {
                prev?.let { phases[agent.paneId] = it }
                continue
            }
            phases[agent.paneId] = agent.phase
            when (agent.phase) {
                AgentPhase.NEEDS_YOU -> if (before != null && prev != AgentPhase.NEEDS_YOU) post += agent to AgentAlert.NEEDS_YOU

                AgentPhase.DONE -> if (prev == AgentPhase.WORKING || prev == AgentPhase.NEEDS_YOU) post += agent to AgentAlert.FINISHED

                // Working again, or seen on some device: whatever was said is over.
                AgentPhase.WORKING, AgentPhase.IDLE -> clear += agent.paneId
            }
        }
        before?.keys?.filterTo(clear) { pane -> now.none { it.paneId == pane } }
        return AgentAlertStep(phases, post, clear)
    }
}

/**
 * The phone's notifications for agents: posted by [AgentWatcher] from the
 * service, so they come while the app is in the background, and opened into
 * that agent's chat page.
 */
object AgentNotifications {
    const val EXTRA_HOST = "org.attache.chat.agentHost"
    const val EXTRA_PANE = "org.attache.chat.agentPane"
    private const val CHANNEL = "agent_turns"

    /** The chat page in front of the person, if any; its agent is never
     * notified about, since the page marks it seen itself. */
    @Volatile private var onScreen: Pair<UUID, String>? = null

    fun setOnScreen(context: Context, host: UUID, pane: String, shown: Boolean) {
        if (shown) {
            onScreen = host to pane
            clear(context, host, pane)
        } else if (onScreen == host to pane) {
            onScreen = null
        }
    }

    fun post(context: Context, host: UUID, hostName: String, agent: AgentPane, alert: AgentAlert) {
        if (onScreen == host to agent.paneId) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannel(context)
        val who = agentName(agent.kind)
        val tag = tag(host, agent.paneId)
        val open = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_HOST, host.toString())
                putExtra(EXTRA_PANE, agent.paneId)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(
                when (alert) {
                    AgentAlert.FINISHED -> "$who finished"
                    AgentAlert.NEEDS_YOU -> "$who needs you"
                },
            )
            .setContentText(agent.title)
            .setSubText(listOf(hostName, agent.sessionName).filter { it.isNotEmpty() }.distinct().joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { agent.sinceEpochSec?.let { setWhen(it * 1000).setShowWhen(true) } }
            .build()
        try {
            manager.notify(tag, 0, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "agents: notification refused")
        }
    }

    fun clear(context: Context, host: UUID, pane: String) {
        NotificationManagerCompat.from(context).cancel(tag(host, pane), 0)
    }

    private fun tag(host: UUID, pane: String) = "agent:$host:$pane"

    private fun agentName(kind: String) = when (kind) {
        "claude" -> "Claude"
        "agy" -> "Antigravity"
        "codex" -> "Codex"
        "grok" -> "Grok"
        "gemini" -> "Gemini"
        else -> "The agent"
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Default sound and vibration: the point is to be told while doing
        // something else. The person can quiet it in the system's settings.
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Agents", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent finished its turn or is waiting for you"
            },
        )
    }
}

/**
 * Watches every connected host's agents from the service, whichever screen
 * is up or none: `attache-cli agents --watch` over an exec channel of its
 * own, as the agent list does, for as long as the host's `tmux -C` channel
 * is up. A turn that ends while the connection is down is told on the first
 * reading after it comes back.
 */
class AgentWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bridges: StateFlow<List<TerminalBridge>>,
) {
    private val watching = HashMap<UUID, Pair<TerminalBridge, Job>>()

    /** The last reading per host, kept across reconnects; see [AgentAlertRule.step]. */
    private val phases = ConcurrentHashMap<UUID, Map<String, AgentPhase>>()

    fun start() {
        scope.launch {
            bridges.collect { list ->
                val live = list.associateBy { it.host.id }
                watching.entries.removeAll { (host, held) ->
                    val stale = live[host] !== held.first
                    if (stale) held.second.cancel()
                    stale
                }
                for ((host, bridge) in live) {
                    if (host in watching) continue
                    watching[host] = bridge to scope.launch {
                        bridge.tmuxControl.collectLatest { tmux -> if (tmux != null) watch(host, bridge.host.nickname, tmux) }
                    }
                }
            }
        }
    }

    private suspend fun watch(host: UUID, hostName: String, tmux: TmuxControl) {
        while (true) {
            val cli = tmux.cli()
            if (cli != null) {
                val lines = JsonLines()
                try {
                    tmux.follow(AttacheCli.watchCommand(cli)) { chunk ->
                        for (line in lines.feed(chunk)) {
                            val event = try {
                                JSONObject(line)
                            } catch (_: Exception) {
                                continue
                            }
                            if (event.optString("t") != "agents") continue
                            onAgents(host, hostName, event)
                        }
                    }
                } catch (e: CancellationException) {
                    // A channel that closed under the watch surfaces as a
                    // cancellation too; the bridge then publishes the next one.
                    currentCoroutineContext().ensureActive()
                    return
                } catch (e: Exception) {
                    Timber.w(e, "agents: watch failed on %s", hostName)
                }
            }
            if (tmux.isClosed) return
            delay(RETRY_MS)
        }
    }

    private fun onAgents(host: UUID, hostName: String, event: JSONObject) {
        val agents = AttacheCli.agentsEvent(event)
        AgentDirectory.put(host, agents)
        val rows = event.optJSONArray("agents")
        val unknown = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            rows?.optJSONObject(i)?.takeIf { it.optString("state") == "unknown" }?.optString("pane")
        }.toSet()
        val step = AgentAlertRule.step(phases[host], agents, unknown)
        phases[host] = step.phases
        step.clear.forEach { AgentNotifications.clear(context, host, it) }
        step.post.forEach { (agent, alert) -> AgentNotifications.post(context, host, hostName, agent, alert) }
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
