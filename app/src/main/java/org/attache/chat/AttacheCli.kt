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

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxCommandError
import org.attache.tmux.TmuxControl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * The chat mode's way to the host: attache-cli (XueshiQiao/Attache `cli/`),
 * which the phone installs on connect ([TmuxControl.cli]). Everything about
 * agents, which pane runs which, what they are doing, their conversations,
 * comes from it, read from what the agents write for themselves; nothing the
 * desktop app installed is read any more.
 *
 * Short commands go through the tmux control channel that is already open
 * (`run-shell`), one round trip where a fresh SSH exec channel costs three,
 * about 1.5 s on the owner's relayed 5G link. `follow` is long-lived and gets
 * an exec channel of its own.
 */
object AttacheCli {
    /**
     * Runs `attache-cli <args>` and returns what it printed; null when
     * attache-cli is not there or tmux refused.
     *
     * **What `run-shell` prints is not its reply.** Measured on tmux 3.6a over
     * a control client: the reply block arrives empty and the output follows
     * it as bare lines, which a client pairing replies with blocks never sees.
     * So the output goes into a tmux buffer, and `show-buffer`, which tmux runs
     * only after the script has finished, reads it as an ordinary reply. Three
     * lines, not one: a line of three commands gets three reply blocks.
     */
    suspend fun lines(tmux: TmuxControl, args: String): List<String>? = tmux.cli()?.let { linesVia(tmux::query, it, args) }

    /** [lines], given a way to send one tmux command and read its reply. */
    internal suspend fun linesVia(query: suspend (String) -> List<String>, cli: String, args: String): List<String>? {
        // Per call: the phone and the tablet can ask the same server at once.
        val buffer = "attache-cli-" + Random.nextLong().toULong().toString(16)
        val script = "${shell(cli)} $args 2>/dev/null | tmux load-buffer -b $buffer -"
        try {
            query("run-shell " + tmuxQuote(script.replace("#", "##")))
            return try {
                // The buffer ends in a newline, which comes back as an empty line.
                query("show-buffer -b $buffer").filter { it.isNotBlank() }
            } catch (_: TmuxCommandError) {
                // Nothing printed: tmux makes no buffer of nothing.
                emptyList()
            }
        } catch (e: TmuxCommandError) {
            return null
        } finally {
            withContext(NonCancellable) {
                try {
                    query("delete-buffer -b $buffer")
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Runs `attache-cli <args>` for its effect only. */
    suspend fun run(tmux: TmuxControl, args: String): Boolean = tmux.cli()?.let { runVia(tmux::query, it, args) } ?: false

    /** [run], given a way to send one tmux command. */
    internal suspend fun runVia(query: suspend (String) -> List<String>, cli: String, args: String): Boolean = try {
        query("run-shell " + tmuxQuote("${shell(cli)} $args >/dev/null 2>&1".replace("#", "##")))
        true
    } catch (_: TmuxCommandError) {
        false
    }

    /** Every agent, or the one in [pane]; null when attache-cli could not be asked. */
    suspend fun agents(tmux: TmuxControl, pane: String? = null): List<AgentPane>? {
        require(pane == null || PANE.matches(pane)) { "not a pane id" }
        val out = lines(tmux, if (pane == null) "agents" else "agents --pane $pane") ?: return null
        return parseAgents(out)
    }

    /** The person has looked at this conversation: Done becomes Idle. */
    suspend fun seen(tmux: TmuxControl, kind: String, conversation: String) {
        if (ID.matches(kind) && ID.matches(conversation)) run(tmux, "seen $kind $conversation")
    }

    /** Types [text] into [pane], then Enter: one message at a time per pane,
     * whichever device sends it. */
    suspend fun send(tmux: TmuxControl, pane: String, text: String): Boolean {
        require(PANE.matches(pane)) { "not a pane id" }
        val b64 = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
        return run(tmux, "input $pane --text $b64 --enter")
    }

    /** One of the keys a panel or an approval takes. */
    suspend fun key(tmux: TmuxControl, pane: String, key: String): Boolean {
        require(PANE.matches(pane) && KEY.matches(key)) { "not a pane or a key" }
        return run(tmux, "input $pane --key $key")
    }

    /** The shell command that follows a conversation over an exec channel. */
    fun followCommand(cli: String, kind: String, transcript: String, before: Long? = null): String {
        require(ID.matches(kind) && isFollowablePath(transcript)) { "not a kind or a transcript" }
        val tail = before?.let { " --before $it" }.orEmpty()
        return "${shell(cli)} follow $kind ${shell(transcript)}$tail"
    }

    /** A transcript path goes onto a shell command line, so only an absolute
     * `.jsonl` path with nothing a terminal would act on gets that far; it is
     * single-quoted on top of that. */
    fun isFollowablePath(path: String): Boolean = path.startsWith("/") && path.endsWith(".jsonl") &&
        path.length <= 4096 && path.none { it < ' ' || it == '\u007f' }

    fun parseAgents(lines: List<String>): List<AgentPane> = lines.mapNotNull { line ->
        val o = try {
            JSONObject(line)
        } catch (_: Exception) {
            return@mapNotNull null
        }
        val pane = o.optString("pane").takeIf { PANE.matches(it) } ?: return@mapNotNull null
        val windowName = o.optString("window_name")
        AgentPane(
            paneId = pane,
            sessionName = o.optString("session"),
            windowId = o.optString("window"),
            windowName = windowName,
            path = o.optString("path"),
            kind = o.optString("kind"),
            phase = when (o.optString("state")) {
                "working" -> AgentPhase.WORKING
                "needs_you" -> AgentPhase.NEEDS_YOU
                "done" -> AgentPhase.DONE
                else -> AgentPhase.IDLE
            },
            sinceEpochSec = o.optLongOrNull("since"),
            title = AgentPanes.cleanTitle(o.optString("title"), command = o.optString("command"), fallback = windowName),
            transcriptPath = o.optStringOrNull("transcript"),
            model = o.optStringOrNull("model"),
            conversation = o.optStringOrNull("conversation"),
        )
    }.sortedWith(compareBy<AgentPane>({ it.phase.ordinal }, { -(it.sinceEpochSec ?: 0L) }))

    /** One row of a conversation, as `follow` sends it. */
    fun item(o: JSONObject): ChatItem? {
        val key = o.optString("key").ifEmpty { return null }
        return when (o.optString("type")) {
            "user" -> ChatItem.UserText(key, o.optString("text"), queued = o.optBoolean("queued"))

            "agent" -> ChatItem.AgentText(key, o.optString("text"))

            "thinking" -> ChatItem.Thinking(key, o.optString("text"))

            "tool" -> ChatItem.ToolStep(
                key = key,
                name = o.optString("name"),
                summary = o.optString("summary"),
                detail = o.optString("detail"),
                result = o.optStringOrNull("result"),
                isError = o.optBoolean("error"),
                diff = (o.optJSONArray("diff") ?: JSONArray()).let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val pair = a.optJSONArray(i) ?: return@mapNotNull null
                        DiffLine(pair.optString(0).firstOrNull() ?: ' ', pair.optString(1))
                    }
                },
            )

            "output" -> ChatItem.CommandOutput(key, o.optString("text"), isError = o.optBoolean("error"), raw = o.optString("raw"))

            "note" -> ChatItem.Note(key, o.optString("text"))

            else -> null
        }
    }

    /** What the header shows, as `follow` sends it. */
    fun status(o: JSONObject): AgentStatus = AgentStatus(
        source = o.optString("source").ifEmpty { "agent" },
        model = o.optStringOrNull("model"),
        modelId = o.optStringOrNull("model_id"),
        effort = o.optStringOrNull("effort"),
        contextPercent = o.optLongOrNull("context_percent")?.toInt(),
        contextWindow = o.optLongOrNull("context_window"),
        contextTokens = o.optLongOrNull("context_tokens"),
        costUsd = if (o.has("cost_usd") && !o.isNull("cost_usd")) o.optDouble("cost_usd") else null,
        linesAdded = o.optLongOrNull("lines_added")?.toInt(),
        linesRemoved = o.optLongOrNull("lines_removed")?.toInt(),
        thinking = if (o.has("thinking") && !o.isNull("thinking")) o.optBoolean("thinking") else null,
        fastMode = if (o.has("fast_mode") && !o.isNull("fast_mode")) o.optBoolean("fast_mode") else null,
        cacheWarm = if (o.has("cache_warm") && !o.isNull("cache_warm")) o.optBoolean("cache_warm") else null,
        cacheExpiresAt = o.optLongOrNull("cache_expires_at"),
        limits = (o.optJSONArray("limits") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val l = a.optJSONObject(i) ?: return@mapNotNull null
                AgentStatus.Limit(l.optString("label"), l.optInt("used_percent"), l.optLongOrNull("resets_at"))
            }
        },
        version = o.optStringOrNull("version"),
    )

    private val PANE = Regex("^%\\d+$")
    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val KEY = Regex("^(Enter|Escape|Up|Down|Left|Right|Tab|BSpace|[1-8])$")

    /** Single-quoted for sh. */
    private fun shell(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Single-quoted for tmux's command parser. */
    private fun tmuxQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
}
