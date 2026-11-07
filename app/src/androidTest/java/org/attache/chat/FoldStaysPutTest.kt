package org.attache.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A line's run opened and shut must leave the line where it was tapped, in
 * every frame, not only at the end (the owner: the page "jumped" and "felt
 * like redrawing"). Reads the line's top frame by frame after each tap.
 */
@RunWith(AndroidJUnit4::class)
class FoldStaysPutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun conversation(): AgentChatUiState {
        val items = ArrayList<ChatItem>()
        for (t in 0 until 20) {
            items += ChatItem.UserText("u$t", "Question $t")
            items += ChatItem.AgentText("a$t", "Let me look at item $t first, and then change what needs changing.")
            items += ChatItem.Thinking("th$t", "Thinking about $t")
            for (k in 0 until 4) items += ChatItem.ToolStep("x$t-$k", "Bash", "step $k of turn $t", "detail", result = "ok", isError = k == 2)
            items += ChatItem.AgentText("b$t", "Done with item $t.")
        }
        return AgentChatUiState(connection = ChatConnection.READY, items = items, transcript = TranscriptStatus.LIVE)
    }

    private fun top(key: String): Float = try {
        rule.onNodeWithTag("line:w:$key", useUnmergedTree = true).getUnclippedBoundsInRoot().top.value
    } catch (e: AssertionError) {
        -1f
    }

    private fun textTop(text: String): Float = try {
        rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value
    } catch (e: AssertionError) {
        -1f
    }

    /** Taps the badge and returns the line's top in each of the next frames. */
    private fun tapAndTrack(key: String): List<Float> {
        // Apart by more than a double tap's window, as a person's two taps
        // are: closer, the text around the badge takes them for a double tap.
        rule.mainClock.advanceTimeBy(600)
        val tops = ArrayList<Float>()
        tops += top(key)
        rule.onNodeWithTag("fold:w:$key", useUnmergedTree = true).performClick()
        repeat(12) {
            rule.mainClock.advanceTimeByFrame()
            tops += top(key)
        }
        return tops
    }

    private fun stepsShown(turn: Int) =
        rule.onAllNodesWithText("step 0 of turn $turn", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun check(what: String, tops: List<Float>) {
        val worst = tops.maxOf { kotlin.math.abs(it - tops.first()) }
        println("FoldStaysPut $what: ${tops.joinToString { "%.1f".format(it) }}")
        assertTrue("$what moved by $worst dp in some frame: $tops", worst <= 1f)
    }

    @Test
    fun atTheBottomTheLineStaysPutOpenAndShut() {
        rule.setContent { MaterialTheme { Conversation(conversation(), FollowState()) {} } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        check("bottom open", tapAndTrack("a19"))
        assertTrue("bottom: the steps did not open", stepsShown(19))
        check("bottom shut", tapAndTrack("a19"))
        assertTrue("bottom: the steps did not shut", !stepsShown(19))
        check("bottom open again", tapAndTrack("a19"))
        check("bottom shut again", tapAndTrack("a19"))
    }

    @Test
    fun midPageTheLineStaysPutOpenAndShut() {
        rule.setContent { MaterialTheme { Conversation(conversation(), FollowState()) {} } }
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("line:w:a15"))
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        check("middle open", tapAndTrack("a15"))
        assertTrue("middle: the steps did not open", stepsShown(15))
        check("middle shut", tapAndTrack("a15"))
        assertTrue("middle: the steps did not shut", !stepsShown(15))
        check("middle open again", tapAndTrack("a15"))
        check("middle shut again", tapAndTrack("a15"))
    }
}
