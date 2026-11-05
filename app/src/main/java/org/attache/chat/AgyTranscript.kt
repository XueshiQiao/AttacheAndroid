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

/**
 * Reads agy's own export of a conversation,
 * `~/.gemini/antigravity-cli/brain/<conversation>/.system_generated/logs/transcript_full.jsonl`,
 * into chat rows. herdr-web-ui reads the same file (`server/agy.ts`); the
 * shapes below were checked against the 484 such files on this machine,
 * agy 1.4.12, 2026-11-05.
 *
 * One JSON object a line, each a step: `step_index`, `type` (`USER_INPUT`,
 * `PLANNER_RESPONSE`, `GENERIC` for a tool's result, `SYSTEM_MESSAGE`,
 * `CHECKPOINT`), `status` and `content`.
 *
 * - The ledger is appended to, never rewritten. A rewind shows up as a step
 *   whose number is not above the last one; every step numbered from it on
 *   is dropped, as herdr does. The same rule drops a step agy wrote twice,
 *   or wrote out of order after an `INVALID` one.
 * - A tool's results follow the planner response that called it, one
 *   `GENERIC` step per call in call order: 10,262 of 10,272 calls here. The
 *   ledger carries no call id, so they are matched by that order.
 * - Only `DONE` steps are shown, and `ERROR` for a result. A `RUNNING` one
 *   is a command left running in the background and never rewritten.
 */
class AgyTranscript : AgentTranscript {
    private class Step(val index: Int, val line: String)

    private val steps = ArrayList<Step>()
    private val items = ArrayList<ChatItem>()

    /** The tool calls of the latest planner response still waiting for a
     * result, as positions in [items]. */
    private val waiting = ArrayDeque<Int>()

    private var contextTokens: Long? = null

    override val snapshot: List<ChatItem> get() = items.toList()

    // agy writes no model into this file; its settings supply that.
    override val status: AgentStatus?
        get() = contextTokens?.let { AgentStatus(source = "agy", contextTokens = it) }

    override fun accept(line: String) {
        val step = parse(line) ?: return
        val index = step.optInt("step_index", -1)
        if (index < 0) return
        if (steps.isNotEmpty() && index <= steps.last().index) {
            while (steps.isNotEmpty() && steps.last().index >= index) steps.removeAt(steps.size - 1)
            steps += Step(index, line)
            rebuild()
        } else {
            steps += Step(index, line)
            read(step, index)
        }
    }

    override fun prepend(earlier: List<String>) {
        val later = steps.map { it.line }
        steps.clear()
        rebuild()
        (earlier + later).forEach(::accept)
    }

    private fun rebuild() {
        items.clear()
        waiting.clear()
        contextTokens = null
        for (s in steps) parse(s.line)?.let { read(it, s.index) }
    }

    private fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (_: Exception) {
        null
    }

    private fun read(step: JSONObject, index: Int) {
        val status = step.optString("status")
        val content = step.optString("content")
        val key = "agy-$index"
        when (step.optString("type")) {
            "USER_INPUT" -> {
                if (status != "DONE") return
                waiting.clear()
                val images = step.optJSONArray("media")?.length() ?: 0
                val text = listOfNotNull(
                    userText(content).trim().ifEmpty { null },
                    if (images > 0) "[${if (images == 1) "1 image" else "$images images"}]" else null,
                ).joinToString("\n")
                if (text.isNotEmpty()) items += ChatItem.UserText(key, text)
            }

            "PLANNER_RESPONSE" -> {
                if (status != "DONE") return
                waiting.clear()
                // What was sent is what the window holds: fresh input plus
                // what was read from the cache.
                val inWindow = step.optLong("input_tokens") + step.optLong("cache_read_tokens")
                if (inWindow > 0) contextTokens = inWindow
                step.optString("thinking").trim().takeIf { it.isNotEmpty() }?.let { items += ChatItem.Thinking("$key-t", it) }
                content.trim().takeIf { it.isNotEmpty() }?.let { items += ChatItem.AgentText("$key-a", it) }
                val calls = step.optJSONArray("tool_calls") ?: JSONArray()
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    waiting.addLast(items.size)
                    items += toolStep("$key-$i", call.optString("name"), call.optJSONObject("args") ?: JSONObject())
                }
            }

            "GENERIC" -> {
                if (status != "DONE" && status != "ERROR") return
                val (result, failed) = result(content)
                val error = failed || status == "ERROR"
                val at = waiting.removeFirstOrNull()
                val step0 = at?.let { items[it] as? ChatItem.ToolStep }
                if (at != null && step0 != null) {
                    items[at] = step0.copy(result = result.take(RESULT_LIMIT), isError = error)
                } else if (result.isNotBlank()) {
                    items += ChatItem.ToolStep(key, "Tool output", "", "", result.take(RESULT_LIMIT), error)
                }
            }

            // SYSTEM_MESSAGE is the harness talking to the model, CHECKPOINT
            // its own bookkeeping: nothing anybody said.
            else -> Unit
        }
    }

    /**
     * agy's tools under the names the chat page already draws: a command's
     * output is shown without a tap when the step is called `Bash`, a file
     * change's diff whatever it is called. Anything else keeps agy's name.
     */
    private fun toolStep(key: String, name: String, args: JSONObject): ChatItem.ToolStep {
        fun s(field: String) = args.optString(field)
        val path = s("AbsolutePath").ifEmpty { s("TargetFile") }.ifEmpty { s("Path") }
        val shown = when (name) {
            "run_command" -> "Bash"
            "view_file", "read_file" -> "Read"
            "edit_file", "replace_file_content", "multi_replace_file_content" -> "Edit"
            "write_file", "write_to_file" -> "Write"
            "grep_search" -> "Grep"
            "find_by_name" -> "Glob"
            "list_dir" -> "List"
            "search_web" -> "WebSearch"
            "read_url_content" -> "WebFetch"
            "invoke_subagent" -> "Agent"
            else -> name
        }
        // agy writes its own one-line summary on most calls.
        val summary = s("toolSummary").ifEmpty {
            when (name) {
                "run_command" -> s("CommandLine").lineSequence().first()
                "grep_search" -> s("Query")
                "find_by_name" -> s("Pattern")
                "list_dir" -> s("DirectoryPath").substringAfterLast('/')
                "search_web" -> s("query")
                "read_url_content" -> s("Url")
                "invoke_subagent" -> s("Role")
                else -> path.substringAfterLast('/')
            }
        }
        val detail = when {
            name == "run_command" -> s("CommandLine")
            path.isNotEmpty() -> path
            else -> args.toString(2).take(DETAIL_LIMIT)
        }
        val diff = when (name) {
            "edit_file" -> LineDiff.of(s("Target"), s("Replacement"))

            "replace_file_content" -> LineDiff.of(s("TargetContent"), s("ReplacementContent"))

            "write_file", "write_to_file" -> s("Content").ifEmpty { s("CodeContent") }
                .lines().take(LineDiff.MAX_LINES).map { DiffLine('+', it) }

            else -> emptyList()
        }
        return ChatItem.ToolStep(key = key, name = shown, summary = summary, detail = detail, diff = diff)
    }

    companion object {
        private const val RESULT_LIMIT = 4_000
        private const val DETAIL_LIMIT = 4_000
        private val STAMP = Regex("^(Created|Completed) At: .*$")
        private val EXIT = Regex("^Command exited with code (\\d+)")

        /** A prompt without the wrapper agy puts around it:
         * `<USER_REQUEST>…</USER_REQUEST>` and the metadata after it. */
        fun userText(text: String): String {
            if (!text.startsWith("<USER_REQUEST>")) return text
            val end = text.lastIndexOf("</USER_REQUEST>")
            if (end < 0) return text
            return text.substring("<USER_REQUEST>".length, end).trim('\n')
        }

        /**
         * A tool result without agy's timestamps at the top; for a command,
         * only what it printed, and whether it exited non-zero. As agy
         * writes it: `Command exited with code 0.`, a blank line,
         * `Command output:` and the output.
         */
        fun result(content: String): Pair<String, Boolean> {
            val body = content.lineSequence().dropWhile { STAMP.matches(it) }.joinToString("\n").trim()
            val code = EXIT.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return body to false
            val output = withoutSpinner(body.substringAfter("Command output:\n", ""))
            return output.ifEmpty { body } to (code != 0)
        }

        /**
         * A command that draws a spinner (`gh run watch`, `npm install`)
         * leaves every frame in its output as a line of its own, one braille
         * cell and a blank line each, measured in this machine's exports:
         * hundreds of lines of nothing. Those lines are dropped and the blank
         * runs they leave closed up; a braille character inside real text
         * stays.
         */
        private fun withoutSpinner(output: String): String {
            val kept = output.lines().filterNot { line -> line.isNotBlank() && line.all { it.isWhitespace() || it in '\u2800'..'\u28FF' } }
            return kept.joinToString("\n").replace(BLANK_RUN, "\n\n").trim()
        }

        private val BLANK_RUN = Regex("\n\\s*\n(\\s*\n)+")
    }
}
