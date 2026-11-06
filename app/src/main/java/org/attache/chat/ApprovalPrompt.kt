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

/** A Claude Code permission menu, as attache-cli reads it off the bottom of
 * its pane (`screen`); the reader is there, once for every client. */
data class ApprovalPrompt(
    /** What the menu is about: the tool header, its description and the
     * command or path, as Claude drew them. */
    val subject: List<String>,
    val question: String,
    val options: List<Option>,
    /** attache-cli's hash of the rows the menu was read from: an answer
     * names it, and the host presses the digit only while that very menu
     * is on screen. */
    val fingerprint: String,
) {
    data class Option(
        val number: Int,
        val label: String,
        /** Why this answer needs a second tap; null when one tap is enough. */
        val risk: String?,
    )
}
