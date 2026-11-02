package org.attache.tmux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.HiltComponentActivity
import org.connectbot.sshlib.SshSession
import org.connectbot.ui.screens.console.CLEAR_INPUT_SEQUENCE
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import java.util.UUID

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AttacheAccessoryRowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun accessoryRow_displaysImageButtonAndTriggersOnPickImage() {
        val session = mock(SshSession::class.java)
        val control = TmuxControl(
            session = session,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
        )
        var interactions = 0
        var textInputClicked = false
        var imagePicked = false
        var cleared = false

        composeTestRule.setContent {
            ConnectBotTheme {
                AttacheAccessoryRow(
                    control = control,
                    onTextInput = { textInputClicked = true },
                    onPaste = {},
                    onSendSlashCommand = {},
                    slashMenuOpen = false,
                    onOpenSlashMenu = {},
                    onDismissSlashMenu = {},
                    tmuxMenuOpen = false,
                    onOpenTmuxMenu = {},
                    onDismissTmuxMenu = {},
                    onInteraction = { interactions++ },
                    onPickImage = { imagePicked = true },
                    onClearInput = { cleared = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("accessoryPreviousWindow").assertDoesNotExist()
        composeTestRule.onNodeWithTag("accessoryNextWindow").assertDoesNotExist()
        composeTestRule.onNodeWithTag("accessoryTextInput").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("accessoryImage").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("accessoryClear").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle {
            assertEquals(3, interactions)
            assertTrue(textInputClicked)
            assertTrue(imagePicked)
            assertTrue(cleared)
        }
    }

    @Test
    fun tmuxControl_uploadImageDelegatesToCallback() = runBlocking {
        val session = mock(SshSession::class.java)
        var uploadedBytes: ByteArray? = null
        var uploadedExt: String? = null
        var reportedProgress: Pair<Long, Long>? = null
        val control = TmuxControl(
            session = session,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
            upload = { bytes, ext, onProgress ->
                uploadedBytes = bytes
                uploadedExt = ext
                onProgress(bytes.size.toLong(), bytes.size.toLong())
                "/tmp/attache-img-1234-abcd.$ext"
            },
        )

        val payload = byteArrayOf(1, 2, 3, 4)
        val path = control.uploadImage(payload, "png") { done, total ->
            reportedProgress = done to total
        }

        assertEquals("/tmp/attache-img-1234-abcd.png", path)
        assertTrue(payload.contentEquals(uploadedBytes))
        assertEquals("png", uploadedExt)
        assertEquals(4L to 4L, reportedProgress)
    }

    @Test
    fun clearInputSequence_clearsSoftWrappedAndMultilineBuffersBothDirections() {
        assertEquals("\u0005" + "\u0015".repeat(64) + "\u000b".repeat(64), CLEAR_INPUT_SEQUENCE)
    }

    @Test
    fun attacheSidebar_displaysHostsAndHandlesSelection() {
        val session = mock(SshSession::class.java)
        val control = TmuxControl(
            session = session,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
        )
        val hostId = UUID(0, 42)
        var selectedHost: UUID? = null
        var dismissed = false
        var themeToggled = false

        composeTestRule.setContent {
            ConnectBotTheme {
                AttacheSidebar(
                    control = control,
                    hosts = listOf(
                        AttacheHostEntry(
                            id = hostId,
                            name = "tmux Dev",
                            subtitle = "100.88.1.2 · 已连接",
                            connected = true,
                            current = true,
                        ),
                    ),
                    onSelectHost = { selectedHost = it },
                    onDismiss = { dismissed = true },
                    isDark = false,
                    onToggleTheme = { themeToggled = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("attacheSidebar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tmuxSidebarThemeToggle").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("attacheHost-$hostId").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle {
            assertTrue(themeToggled)
            assertEquals(hostId, selectedHost)
            assertTrue(dismissed)
        }
    }
}
