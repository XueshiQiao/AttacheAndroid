package org.attache.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.connectbot.HiltComponentActivity
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.sshlib.SshSession
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.ui.screens.console.CLEAR_INPUT_SEQUENCE
import org.connectbot.ui.screens.console.isNonTmuxTerminalScrollable
import org.connectbot.ui.screens.console.shouldDismissKeyboardOnDownwardSwipe
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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

    @Test
    fun downwardSwipeKeyboardDismissal_respectsInlineInputAndScrollability() {
        assertTrue(
            shouldDismissKeyboardOnDownwardSwipe(
                imeVisible = true,
                showSoftwareKeyboard = false,
                inlineInputActive = false,
            ),
        )
        assertTrue(
            shouldDismissKeyboardOnDownwardSwipe(
                imeVisible = false,
                showSoftwareKeyboard = true,
                inlineInputActive = false,
            ),
        )
        assertFalse(
            shouldDismissKeyboardOnDownwardSwipe(
                imeVisible = true,
                showSoftwareKeyboard = true,
                inlineInputActive = true,
            ),
        )
        assertFalse(
            shouldDismissKeyboardOnDownwardSwipe(
                imeVisible = false,
                showSoftwareKeyboard = false,
                inlineInputActive = false,
            ),
        )

        val bridge = mock(TerminalBridge::class.java)
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        `when`(bridge.terminalEmulator).thenReturn(emulator)
        `when`(bridge.host).thenReturn(Host(id = UUID(0, 1), nickname = "scrollable", scrollbackLines = 140))
        assertTrue(isNonTmuxTerminalScrollable(bridge, pgUpDnGestureEnabled = false))

        `when`(bridge.host).thenReturn(Host(id = UUID(0, 1), nickname = "no-scrollback", scrollbackLines = 0))
        assertFalse(isNonTmuxTerminalScrollable(bridge, pgUpDnGestureEnabled = false))
        assertTrue(isNonTmuxTerminalScrollable(bridge, pgUpDnGestureEnabled = true))
    }

    @Test
    fun tmuxTouch_downwardSwipeDismissesKeyboardOnce_upwardSwipeDoesNot() {
        val session = mock(SshSession::class.java)
        val control = TmuxControl(
            session = session,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
        )
        val stateField = TmuxControl::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(control) as MutableStateFlow<TmuxState>
        stateFlow.value = TmuxState(
            sessionId = "\$0",
            panes = listOf(TmuxPane(id = "%0", left = 0, top = 0, width = 80, height = 24, active = true, inMode = false)),
        )

        var dismissCount = 0
        composeTestRule.setContent {
            ConnectBotTheme {
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .tmuxTouch(
                            control = control,
                            selectionActive = { false },
                            dimensions = { TerminalDimensions(rows = 24, columns = 80) },
                            onDismissKeyboard = { dismissCount++ },
                        )
                        .testTag("tmuxTouchBox"),
                )
            }
        }

        // Upward swipe (finger moving up, delta.y < 0) should NOT dismiss keyboard
        composeTestRule.onNodeWithTag("tmuxTouchBox").performTouchInput {
            swipe(start = Offset(150f, 220f), end = Offset(150f, 40f), durationMillis = 150)
        }
        composeTestRule.runOnIdle {
            assertEquals(0, dismissCount)
        }

        // Downward swipe (finger moving down towards keyboard, delta.y > 0) SHOULD dismiss keyboard once
        composeTestRule.onNodeWithTag("tmuxTouchBox").performTouchInput {
            swipe(start = Offset(150f, 40f), end = Offset(150f, 220f), durationMillis = 150)
        }
        composeTestRule.runOnIdle {
            assertEquals(1, dismissCount)
        }
    }
}
