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
/** What `input` did: sent, or why not. */
data class InputResult(val sent: Boolean, val why: String?)

/** What attache-cli read off a Claude pane; nothing at all on another agent. */
data class ScreenReading(
    /** Claude's input box is on screen. */
    val prompt: Boolean,
    val approval: ApprovalPrompt?,
    val panel: List<String>?,
    /** attache-cli's hash of [panel]: an Enter, the key that chooses, names
     * it and is pressed only on that very panel. */
    val panelFingerprint: String?,
    val activity: AgentActivity?,
    /** A question Claude asked (`AskUserQuestion`). */
    val question: QuestionPrompt? = null,
)

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

    /**
     * Types [text] into [pane], then Enter: one message at a time per pane,
     * whichever device sends it. attache-cli sends it only if Claude's own
     * input box is on screen with nothing over it, read again on the host
     * just before typing.
     */
    suspend fun send(tmux: TmuxControl, pane: String, text: String): InputResult {
        require(PANE.matches(pane)) { "not a pane id" }
        val b64 = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
        return parseInput(lines(tmux, "input $pane --text $b64 --enter"))
    }

    /**
     * Presses [key] in [pane], but only while [condition] holds on the host,
     * checked holding the pane right before the key goes out: what the phone
     * was shown is a request, not a permission. `approval:<fingerprint>` for
     * a digit on that very menu, `panel` for a panel's arrows, Tab and Esc,
     * `panel:<fingerprint>` for those and Enter on that very panel, and
     * `working` for the Esc that stops a turn. A key that lands elsewhere can cost the
     * session its prompt cache (an Esc on the idle prompt is half of the two
     * that open the rewind menu, a Tab turns thinking on or off).
     */
    suspend fun key(tmux: TmuxControl, pane: String, key: String, condition: String): InputResult {
        require(PANE.matches(pane) && KEY.matches(key) && CONDITION.matches(condition)) { "not a pane, a key or a condition" }
        return parseInput(lines(tmux, "input $pane --key $key --if $condition"))
    }

    /**
     * Picks row [row] of the question on screen: an answer's number, or
     * `submit` for a multiple choice's Submit row. attache-cli moves the
     * cursor there with arrows, reads the screen again, and presses Enter
     * only if the cursor arrived on that very question ([fingerprint]).
     */
    suspend fun choose(tmux: TmuxControl, pane: String, row: String, fingerprint: String): InputResult {
        require(PANE.matches(pane) && ROW.matches(row) && FINGERPRINT.matches(fingerprint)) { "not a pane, a row or a fingerprint" }
        return parseInput(lines(tmux, "input $pane --choose $row --if question:$fingerprint"))
    }

    /** What `input` printed; a host that printed nothing (attache-cli
     * missing, or one from before protocol 2) sent nothing either. */
    fun parseInput(lines: List<String>?): InputResult {
        val o = lines?.lastOrNull()?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        } ?: return InputResult(false, "unreachable")
        return InputResult(o.optBoolean("sent"), o.optStringOrNull("why"))
    }

    /** The shell command that keeps this host's agents (or [pane]'s agent
     * and screen) coming over an exec channel. */
    fun watchCommand(cli: String, pane: String? = null): String {
        require(pane == null || PANE.matches(pane)) { "not a pane id" }
        return "${shell(cli)} agents --watch" + (pane?.let { " --pane $it" } ?: "")
    }

    /** The shell command that keeps the host's AI subscription usage
     * coming (`usage --watch`, attache-cli 0.3.0). An older attache-cli
     * prints its usage text and exits, which reads as no usage at all. */
    fun usageWatchCommand(cli: String): String = "${shell(cli)} usage --watch"

    /** The agents in a `watch` event, as [parseAgents] reads `agents`. */
    fun agentsEvent(o: JSONObject): List<AgentPane> {
        val a = o.optJSONArray("agents") ?: JSONArray()
        return parseAgents((0 until a.length()).mapNotNull { a.optJSONObject(it)?.toString() })
    }

    /** What attache-cli read off the pane's screen (`screen`, or a watch's
     * `screen` event). */
    fun screen(o: JSONObject): ScreenReading {
        val approval = o.optJSONObject("approval")?.let { a ->
            val opts = a.optJSONArray("options") ?: JSONArray()
            val subject = a.optJSONArray("subject") ?: JSONArray()
            ApprovalPrompt(
                subject = (0 until subject.length()).map { subject.optString(it) },
                question = a.optString("question"),
                options = (0 until opts.length()).mapNotNull { i ->
                    val opt = opts.optJSONObject(i) ?: return@mapNotNull null
                    ApprovalPrompt.Option(opt.optInt("number"), opt.optString("label"), opt.optStringOrNull("risk"))
                },
                fingerprint = a.optString("fingerprint"),
            ).takeIf { it.options.isNotEmpty() && FINGERPRINT.matches(it.fingerprint) }
        }
        val panel = o.optJSONArray("panel")?.let { p -> (0 until p.length()).map { p.optString(it) } }
        val activity = o.optJSONObject("activity")?.let { AgentActivity(it.optString("verb"), it.optStringOrNull("detail")) }
        val panelFingerprint = o.optStringOrNull("panel_fingerprint")?.takeIf { FINGERPRINT.matches(it) }
        val question = o.optJSONObject("question")?.let(::question)
        return ScreenReading(o.optBoolean("prompt"), approval, panel, panelFingerprint, activity, question)
    }

    /** A `question` in a screen reading; null without a fingerprint or rows,
     * since then nothing on it can be answered. */
    fun question(q: JSONObject): QuestionPrompt? {
        val opts = q.optJSONArray("options") ?: JSONArray()
        val tabs = q.optJSONArray("tabs") ?: JSONArray()
        val answers = q.optJSONArray("answers") ?: JSONArray()
        return QuestionPrompt(
            header = q.optString("header"),
            question = q.optString("question"),
            tabs = (0 until tabs.length()).mapNotNull { i ->
                tabs.optJSONObject(i)?.let { QuestionPrompt.Tab(it.optString("title"), it.optBoolean("answered")) }
            },
            multi = q.optBoolean("multi"),
            review = q.optBoolean("review"),
            answers = (0 until answers.length()).mapNotNull { i ->
                answers.optJSONObject(i)?.let { it.optString("question") to it.optString("answer") }
            },
            options = (0 until opts.length()).mapNotNull { i ->
                val o = opts.optJSONObject(i) ?: return@mapNotNull null
                if (!o.has("number")) return@mapNotNull null
                QuestionPrompt.Option(
                    number = o.optInt("number"),
                    label = o.optString("label"),
                    description = o.optString("description"),
                    typed = o.optBoolean("typed"),
                    checked = o.optBoolean("checked"),
                )
            },
            submitRow = q.optBoolean("submit_row"),
            fingerprint = q.optString("fingerprint"),
        ).takeIf { it.options.isNotEmpty() && FINGERPRINT.matches(it.fingerprint) }
    }

    /** Why attache-cli did not send, in words for a notice. */
    fun refusal(why: String?): String = when (why) {
        "question" -> "Answer the question first"
        "panel" -> "Close the panel first"
        "no_prompt" -> "Claude's input box is not on screen"
        "no_agent" -> "No agent runs in this pane"
        "enter_withheld" -> "Something came up on screen as it was typed, so it was not sent; the text is in the box"
        "panel_changed" -> "The panel changed"
        "question_gone" -> "The question was answered already"
        "question_changed" -> "The question changed"
        "no_such_option" -> "That answer is not on the menu"
        "panel_closed" -> "The panel has closed"
        "typed_answer" -> "That answer needs typing, in the terminal"
        "not_working" -> "It has already stopped"
        "not_claude" -> "Only Claude's screen can be answered from here"
        "busy" -> "Another device is typing into this pane"
        "unreachable" -> "attache-cli did not answer"
        else -> "tmux did not take it"
    }.let { if (why == "enter_withheld") it else "$it, so nothing was sent" }

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
    private val KEY = Regex("^(Enter|Escape|Up|Down|Left|Right|Tab|[1-8])$")
    private val FINGERPRINT = Regex("^[0-9a-f]{16}$")
    private val ROW = Regex("^([1-9][0-9]?|submit)$")
    private val CONDITION = Regex("^(approval:[0-9a-f]{16}|panel(:[0-9a-f]{16})?|working)$")

    /** Single-quoted for sh. */
    private fun shell(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Single-quoted for tmux's command parser. */
    private fun tmuxQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
}
