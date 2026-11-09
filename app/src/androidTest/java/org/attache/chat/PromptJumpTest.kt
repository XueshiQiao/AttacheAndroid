package org.attache.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up and down step through the person's own messages, each put at the top of
 * the view; down past the last one goes to the bottom (the owner,
 * 2026-11-09). Reads where each message lands after a tap.
 */
@RunWith(AndroidJUnit4::class)
class PromptJumpTest {
    @get:Rule
    val rule = createComposeRule()

    private fun conversation(): AgentChatUiState {
        val items = ArrayList<ChatItem>()
        // Replies longer than a screen, so every message can reach the top.
        for (t in 0 until 6) {
            items += ChatItem.UserText("u$t", "Question $t")
            items += ChatItem.AgentText("a$t", (0 until 40).joinToString("\n\n") { "Paragraph $it of reply $t." })
        }
        return AgentChatUiState(connection = ChatConnection.READY, items = items, transcript = TranscriptStatus.LIVE)
    }

    private fun listTop() = rule.onNode(hasScrollToNodeAction()).getUnclippedBoundsInRoot().top.value

    /** How far below the top of the list a message's text sits. */
    private fun fromTop(text: String) =
        rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value - listTop()

    private fun tap(description: String) {
        rule.onNodeWithContentDescription(description).performClick()
        rule.waitForIdle()
    }

    @Test
    fun upAndDownStepThroughPromptsThenReachTheBottom() {
        rule.setContent { MaterialTheme { Conversation(conversation(), FollowState()) {} } }
        rule.waitForIdle()

        tap("Previous prompt")
        val atTop = fromTop("Question 5")
        println("PromptJump Question 5 at $atTop dp")
        assertTrue("Question 5 is not at the top: $atTop dp", atTop in 0f..60f)

        tap("Previous prompt")
        assertEquals("Question 4 did not land where Question 5 did", atTop, fromTop("Question 4"), 2f)

        // Far away: jumped to rather than slid, landing the same way.
        tap("Previous prompt")
        tap("Previous prompt")
        tap("Previous prompt")
        assertEquals("Question 1 did not land at the top", atTop, fromTop("Question 1"), 2f)

        tap("Next prompt")
        assertEquals("Question 2 did not land at the top", atTop, fromTop("Question 2"), 2f)

        tap("Next prompt")
        tap("Next prompt")
        tap("Next prompt")
        assertEquals("Question 5 did not land at the top", atTop, fromTop("Question 5"), 2f)

        // In the last turn already: down goes to the bottom, and once there
        // the down button is gone because the page follows again.
        tap("Next prompt")
        assertTrue(
            "the down button is still shown at the bottom",
            rule.onAllNodesWithContentDescription("Next prompt").fetchSemanticsNodes().isEmpty(),
        )
        rule.onNodeWithText("Paragraph 39 of reply 5.", substring = true, useUnmergedTree = true).assertExists()
    }
}
