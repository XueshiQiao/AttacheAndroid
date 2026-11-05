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

import org.json.JSONArray
import org.json.JSONObject

/** One row of the chat screen. */
sealed interface ChatItem {
    val key: String

    data class UserText(override val key: String, val text: String, val queued: Boolean = false) : ChatItem

    data class AgentText(override val key: String, val text: String) : ChatItem

    data class Thinking(override val key: String, val text: String) : ChatItem

    /** A tool call, with its result once one has come back. */
    data class ToolStep(
        override val key: String,
        val name: String,
        val summary: String,
        val detail: String,
        val result: String? = null,
        val isError: Boolean = false,
        /** For a file change, what changed, line by line. */
        val diff: List<DiffLine> = emptyList(),
    ) : ChatItem

    /** What a slash command or a `!` shell command printed. */
    data class CommandOutput(
        override val key: String,
        val text: String,
        val isError: Boolean = false,
        /** As printed, colour codes and all: `/context` draws its chart in
         * colours, and without them every square looks the same. */
        val raw: String = text,
    ) : ChatItem

    data class Note(override val key: String, val text: String) : ChatItem
}

/** One line of a file change: [kind] is `+`, `-`, a space for context, or
 * `…` where unchanged lines were left out. */
data class DiffLine(val kind: Char, val text: String)

/**
 * Reads a Claude Code transcript (`~/.claude/projects/…/<session>.jsonl`)
 * into chat rows, one record at a time.
 *
 * The rules are the desktop app's `ClaudeTranscript.swift`, which were
 * written from a census of real transcripts, and they keep its bias: dropping
 * something the person said is silent and much worse than showing a line of
 * noise, so every exclusion is a named record kind, never a pattern.
 *
 * - A message typed while the agent works is `type: attachment`,
 *   `attachment.type == queued_command`, and only `origin.kind == human`
 *   separates it from the harness's own notifications.
 * - The same message is also written as `queue-operation`, two to four
 *   times. Those are ignored in favour of the attachment.
 * - Most `type: user` records are tool results, not the person.
 */
class ClaudeTranscript : AgentTranscript {
    private val items = ArrayList<ChatItem>()

    /** Where each tool call sits in [items], so its result lands on it. */
    private val stepIndex = HashMap<String, Int>()

    /** Every line taken so far, so earlier history can go in front of it.
     * A tool call's result can be in a later line than the call, so the
     * whole conversation is read again in order rather than patched. */
    private val lines = ArrayList<String>()

    override val snapshot: List<ChatItem> get() = items.toList()

    // The latest reply's own account of itself. Sidechain records are
    // skipped before here, so a subagent's model never speaks for the
    // session, and Claude Code's `<synthetic>` stand-ins carry no real one.
    private var lastModel: String? = null
    private var lastEffort: String? = null
    private var lastContextTokens: Long? = null

    override val status: AgentStatus?
        get() = lastModel?.let { model ->
            AgentStatus(source = "claude", model = model, effort = lastEffort, contextTokens = lastContextTokens)
        }

    override fun accept(line: String) {
        lines += line
        read(line)
    }

    override fun prepend(earlier: List<String>) {
        val all = earlier + lines
        lines.clear()
        items.clear()
        stepIndex.clear()
        lastModel = null
        lastEffort = null
        lastContextTokens = null
        all.forEach(::accept)
    }

    private fun read(line: String) {
        val root = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        // A subagent's conversation belongs to whatever spawned it.
        if (root.optBoolean("isSidechain")) return
        val uuid = root.optString("uuid").ifEmpty { "line-${items.size}" }
        when (root.optString("type")) {
            "attachment" -> interjection(root, uuid)

            "user" -> user(root, uuid)

            "assistant" -> assistant(root, uuid)

            // A slash command and what it printed, as Claude Code 2.1 writes
            // them: measured in this machine's transcripts on 2026-11-05,
            // `/context` arrived as two `system` records with subtype
            // `local_command` and the text at the top level, where older
            // sessions had the same envelopes in `user` records.
            "system" -> if (root.optString("subtype") == "local_command") said(root.optString("content"), uuid)

            // `queue-operation`, `system`, `file-history-snapshot`, `mode`,
            // `last-prompt`, `ai-title`, the `api-request*` bookkeeping:
            // nothing anybody said.
            else -> Unit
        }
    }

    private fun interjection(root: JSONObject, uuid: String) {
        val attachment = root.optJSONObject("attachment") ?: return
        if (attachment.optString("type") != "queued_command") return
        if (attachment.optJSONObject("origin")?.optString("kind") != "human") return
        val prompt = attachment.optString("prompt").trim()
        if (prompt.isNotEmpty()) items += ChatItem.UserText(uuid, prompt, queued = true)
    }

    private fun user(root: JSONObject, uuid: String) {
        if (root.optBoolean("isCompactSummary")) {
            items += ChatItem.Note(uuid, "Earlier conversation was compacted")
            return
        }
        // Skill preambles and the rest of what the harness writes in the
        // user's name; Claude Code marks them itself.
        if (root.optBoolean("isMeta")) return
        val message = root.optJSONObject("message") ?: return
        val text = when (val content = message.opt("content")) {
            is String -> content

            is JSONArray -> {
                if (attachResults(content)) return
                textOf(content)
            }

            else -> return
        }
        said(text, uuid)
    }

    /** A body written in the person's name: what they typed, a command, or
     * what a command printed. */
    private fun said(text: String, uuid: String) {
        var body = text.trim()
        if (body.isEmpty() || body.startsWith("[Image:")) return
        if (body.startsWith(RELAYED_FROM_ANOTHER_AGENT)) return
        // What a slash command or a `!` command printed. Claude Code writes it
        // right after the command, and for `/usage`, `/context` or `/model`
        // it is the whole answer: counted across 150 transcripts here, every
        // one of those carried its result this way.
        commandOutput(body, uuid)?.let {
            if (it.text.isNotEmpty() && it.text != "(no content)") items += it
            return
        }
        if (MACHINE_ENVELOPES.any(body::startsWith)) return
        if (body.startsWith("<")) {
            val command = tag("command-name", body)
            val shell = tag("bash-input", body)
            body = when {
                command.isNotEmpty() -> tag("command-args", body).let { if (it.isEmpty()) command else "$command $it" }
                shell.isNotEmpty() -> "!$shell"
                body.startsWith("<bash-input>") && body.contains("</bash-input>") -> return
                else -> body
            }
        }
        // `[Request interrupted by user]` survives on purpose: the person
        // stopping the agent is a real turn, often the most important one.
        items += if (body.startsWith("[Request interrupted")) {
            ChatItem.Note(uuid, "Interrupted")
        } else {
            ChatItem.UserText(uuid, body)
        }
    }

    /** Tool results come back as user-role records. Each one is folded into
     * the step that asked for it; true when the record held any. */
    private fun attachResults(content: JSONArray): Boolean {
        var any = false
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "tool_result") continue
            any = true
            val index = stepIndex[block.optString("tool_use_id")] ?: continue
            val step = items[index] as? ChatItem.ToolStep ?: continue
            val result = when (val c = block.opt("content")) {
                is String -> c
                is JSONArray -> textOf(c)
                else -> ""
            }
            items[index] = step.copy(result = result.take(RESULT_LIMIT), isError = block.optBoolean("is_error"))
        }
        return any
    }

    private fun assistant(root: JSONObject, uuid: String) {
        val message = root.optJSONObject("message") ?: return
        message.optString("model").takeIf { it.isNotEmpty() && !it.startsWith("<") }?.let { model ->
            lastModel = model
            lastEffort = root.optString("effort").ifEmpty { null } ?: lastEffort
            // What was sent is what the window holds: fresh input, plus what
            // was read from the cache and what was written to it.
            message.optJSONObject("usage")?.let { u ->
                val inWindow = u.optLong("input_tokens") + u.optLong("cache_read_input_tokens") + u.optLong("cache_creation_input_tokens")
                if (inWindow > 0) lastContextTokens = inWindow
            }
        }
        val content = message.optJSONArray("content") ?: return
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            val key = "$uuid-$i"
            when (block.optString("type")) {
                "text" -> block.optString("text").trim().takeIf { it.isNotEmpty() }
                    ?.let { items += ChatItem.AgentText(key, it) }

                // Current Claude Code writes thinking redacted, signature
                // only; an empty one is not worth a row.
                "thinking" -> block.optString("thinking").trim().takeIf { it.isNotEmpty() }
                    ?.let { items += ChatItem.Thinking(key, it) }

                "tool_use" -> {
                    val id = block.optString("id")
                    val step = toolStep(key, block.optString("name"), block.optJSONObject("input") ?: JSONObject())
                    if (id.isNotEmpty()) stepIndex[id] = items.size
                    items += step
                }
            }
        }
    }

    private fun toolStep(key: String, name: String, input: JSONObject): ChatItem.ToolStep {
        fun s(field: String) = input.optString(field)
        val summary = when (name) {
            "Bash" -> s("description").ifEmpty { s("command").lineSequence().first() }
            "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> s("file_path").substringAfterLast('/')
            "Grep", "Glob" -> s("pattern")
            "Task", "Agent" -> s("description")
            "WebFetch" -> s("url")
            "WebSearch" -> s("query")
            "TodoWrite" -> "Updated the plan"
            else -> ""
        }
        val detail = when (name) {
            "Bash" -> s("command")
            "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> s("file_path")
            else -> input.toString(2).take(DETAIL_LIMIT)
        }
        val diff = when (name) {
            "Edit" -> LineDiff.of(s("old_string"), s("new_string"))

            "Write" -> s("content").lines().take(LineDiff.MAX_LINES).map { DiffLine('+', it) }

            "MultiEdit" -> {
                val edits = input.optJSONArray("edits") ?: JSONArray()
                (0 until edits.length()).mapNotNull(edits::optJSONObject).flatMapIndexed { i, e ->
                    val one = LineDiff.of(e.optString("old_string"), e.optString("new_string"))
                    if (i == 0) one else listOf(DiffLine('…', "")) + one
                }
            }

            else -> emptyList()
        }
        return ChatItem.ToolStep(key = key, name = name, summary = summary, detail = detail, diff = diff)
    }

    private fun commandOutput(body: String, uuid: String): ChatItem.CommandOutput? = when {
        body.startsWith("<local-command-stdout>") -> tag("local-command-stdout", body).let {
            ChatItem.CommandOutput(uuid, stripAnsi(it), raw = it.trimEnd())
        }

        body.startsWith("<local-command-stderr>") -> tag("local-command-stderr", body).let {
            ChatItem.CommandOutput(uuid, stripAnsi(it), isError = true, raw = it.trimEnd())
        }

        body.startsWith("<bash-stdout>") || body.startsWith("<bash-stderr>") -> {
            val out = stripAnsi(tag("bash-stdout", body))
            val err = stripAnsi(tag("bash-stderr", body))
            ChatItem.CommandOutput(uuid, listOf(out, err).filter { it.isNotEmpty() }.joinToString("\n"), isError = out.isEmpty() && err.isNotEmpty())
        }

        else -> null
    }

    private fun stripAnsi(text: String): String = text.replace(ANSI, "").trim()

    private fun textOf(blocks: JSONArray): String = buildString {
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }

    private fun tag(name: String, body: String): String {
        val open = body.indexOf("<$name>")
        val close = body.indexOf("</$name>")
        if (open < 0 || close < open) return ""
        return body.substring(open + name.length + 2, close).trim()
    }

    private companion object {
        const val RESULT_LIMIT = 4_000
        const val DETAIL_LIMIT = 4_000
        const val RELAYED_FROM_ANOTHER_AGENT = "Another Claude session sent a message"

        /** Machine-written bodies wearing a user record, by name; counted
         * across 408 transcripts by the desktop app. */
        val MACHINE_ENVELOPES = listOf(
            "<task-notification",
            "<system-reminder",
        )

        /** Colour and cursor sequences, and window-title strings. */
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    }
}

/**
 * A line diff for a file change, as the terminal draws one: changed lines
 * with two lines of context, and the unchanged stretches between left out.
 */
internal object LineDiff {
    /** Past this many lines a side, the change is shown as removed-then-added
     * rather than aligned: the alignment is quadratic in the line count. */
    const val MAX_LINES = 400
    private const val CONTEXT = 2

    fun of(old: String, new: String): List<DiffLine> {
        val a = if (old.isEmpty()) emptyList() else old.lines()
        val b = if (new.isEmpty()) emptyList() else new.lines()
        if (a.size > MAX_LINES || b.size > MAX_LINES) {
            return a.take(MAX_LINES).map { DiffLine('-', it) } + b.take(MAX_LINES).map { DiffLine('+', it) }
        }
        // Longest common subsequence, then walked forwards.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val all = ArrayList<DiffLine>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> {
                    all += DiffLine(' ', a[i])
                    i++
                    j++
                }

                // Removed before added where both fit, as the terminal draws it.
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                    all += DiffLine('-', a[i])
                    i++
                }

                else -> {
                    all += DiffLine('+', b[j])
                    j++
                }
            }
        }
        // Keep each change with its context; fold what lies between.
        val keep = BooleanArray(all.size)
        all.forEachIndexed { k, line ->
            if (line.kind != ' ') for (c in maxOf(0, k - CONTEXT)..minOf(all.size - 1, k + CONTEXT)) keep[c] = true
        }
        val out = ArrayList<DiffLine>()
        var folded = false
        for (k in all.indices) {
            if (keep[k]) {
                out += all[k]
                folded = false
            } else if (!folded) {
                out += DiffLine('…', "")
                folded = true
            }
        }
        return out
    }
}
