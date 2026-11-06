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
