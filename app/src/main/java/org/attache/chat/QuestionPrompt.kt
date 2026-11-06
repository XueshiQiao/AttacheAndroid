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

/**
 * A question Claude asked with `AskUserQuestion`, as attache-cli reads it
 * off the pane (`screen`): one question or several (as tabs), each picked
 * from a list or, as multiple choice, ticked; and the last tab, where the
 * answers are reviewed and submitted. attache-cli moves the cursor to a row,
 * reads the screen again, and only then presses Enter.
 */
data class QuestionPrompt(
    /** The single question's header chip; empty when there are tabs. */
    val header: String,
    val question: String,
    val tabs: List<Tab>,
    val multi: Boolean,
    /** The last tab of several: the answers given, Submit answers or Cancel. */
    val review: Boolean,
    val answers: List<Pair<String, String>>,
    val options: List<Option>,
    /** A multiple choice's Submit row, which ends that question. */
    val submitRow: Boolean,
    /** The question and its rows, not where the cursor is: a choice names
     * it, and the host presses Enter only on that very question. */
    val fingerprint: String,
) {
    data class Option(
        val number: Int,
        val label: String,
        val description: String,
        /** Answered by typing (`Type something.`, `Chat about this`): the
         * terminal's. */
        val typed: Boolean,
        /** A multiple choice's box is ticked. */
        val checked: Boolean,
    )

    data class Tab(val title: String, val answered: Boolean)
}
