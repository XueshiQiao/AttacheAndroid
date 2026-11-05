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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Screens follow Claude Code 2.1's permission dialog as `capture-pane -p -J` prints it. */
class ApprovalPromptsTest {
    private val rule = "─".repeat(60)
    private val dash = "╌".repeat(60)

    private fun bash(command: String, vararg options: String) = listOf(
        "⏺ Creating the branch",
        "",
        rule,
        " Bash command",
        " Tip: auto mode handles these prompts for you — choose \"switch to auto mode\" below",
        " Push the fix branch",
        dash,
        " $command",
        dash,
        " Do you want to proceed?",
    ) + options.mapIndexed { i, o -> (if (i == 0) " ❯ " else "   ") + "${i + 1}. $o" } + listOf(
        "",
        " Esc to cancel · Tab to amend",
        "",
        "",
    )

    @Test
    fun readsABashPermissionMenu() {
        val prompt = ApprovalPrompts.detect(bash("touch /tmp/x.txt", "Yes", "Yes, and always allow access to /tmp from this project", "No"))!!
        assertEquals("Do you want to proceed?", prompt.question)
        assertEquals(listOf("Bash command", "Push the fix branch", "touch /tmp/x.txt"), prompt.subject)
        assertEquals(listOf(1, 2, 3), prompt.options.map { it.number })
        assertNull(prompt.options[0].risk)
        assertNotNull(prompt.options[1].risk)
        assertNull(prompt.options[2].risk)
    }

    @Test
    fun aRiskyCommandMakesEveryYesAskTwice() {
        val prompt = ApprovalPrompts.detect(bash("git push origin fix/rail", "Yes", "No"))!!
        assertEquals("pushes to a remote", prompt.options[0].risk)
        assertNull(prompt.options[1].risk)
    }

    @Test
    fun aWrappedOptionIsFoldedIntoItsLabel() {
        val screen = listOf(
            rule,
            " Edit file",
            " note.txt",
            " Do you want to make this edit to note.txt?",
            " ❯ 1. Yes",
            "   2. Yes, and switch to accept edits (auto-approve file edits) for this session",
            "      (shift+tab)",
            "   3. No",
            "",
            " Esc to cancel · Tab to amend",
        )
        val prompt = ApprovalPrompts.detect(screen)!!
        assertEquals(3, prompt.options.size)
        assertEquals("Yes, and switch to accept edits (auto-approve file edits) for this session (shift+tab)", prompt.options[1].label)
        assertNotNull(prompt.options[1].risk)
    }

    @Test
    fun noFooterNoMenu() {
        val screen = bash("ls", "Yes", "No").dropLastWhile { it.isBlank() }.dropLast(1)
        assertNull(ApprovalPrompts.detect(screen))
    }

    @Test
    fun aNumberedListInTheConversationIsNotAMenu() {
        val screen = listOf(
            "Here is the plan:",
            "1. Fix the parser",
            "2. Add a test",
            "",
            "❯ ",
            " ? for shortcuts",
        )
        assertNull(ApprovalPrompts.detect(screen))
    }

    @Test
    fun theMultiQuestionFormIsLeftToTheTerminal() {
        val screen = listOf(
            "Which fruits do I like?",
            "  1. [✔] Apple",
            "  2. [ ] Banana",
            rule,
            "❯ 3. Chat about this",
            "",
            "Enter to select · ↑/↓ to navigate · Esc to cancel",
        )
        assertNull(ApprovalPrompts.detect(screen))
    }

    @Test
    fun gappedNumbersAreNotAMenu() {
        val screen = listOf(" Do you want to proceed?", " ❯ 1. Yes", "   3. No", "", " Esc to cancel")
        assertNull(ApprovalPrompts.detect(screen))
    }
}
