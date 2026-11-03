package org.attache.tmux

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.HiltComponentActivity
import org.connectbot.sshlib.SshSession
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * Floating glass chrome (docs/chrome-unify scheme D). Set ATTACHE_SHOTS_DIR to
 * also dump PNG renders for visual comparison with mockups.html.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttacheFloatingChromeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private fun control(): TmuxControl {
        val control = TmuxControl(
            session = mock(SshSession::class.java),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEnded = {},
            exec = { null },
        )
        val stateField = TmuxControl::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(control) as MutableStateFlow<TmuxState>
        val daily = listOf("maintenance", "video-shotcraft", "anydrag->homebrew", "grok-1.0.67-mac")
            .mapIndexed { i, n -> TmuxWindow("@$i", i + 1, n, i == 0) }
        val dev = listOf("QDuo", "AnyDrag", "Attache", "HyperCapslock", "PastePawX", "Flowy")
            .mapIndexed { i, n -> TmuxWindow("@${i + 10}", i + 1, n, false) }
        stateFlow.value = TmuxState(
            sessionId = "\$0",
            sessions = listOf(
                TmuxSession("\$0", "Daily", true, daily),
                TmuxSession("\$1", "Dev", false, dev),
            ),
            windows = daily,
        )
        return control
    }

    private fun render(isDark: Boolean, isLandscape: Boolean, open: Boolean, onState: (Boolean) -> Unit = {}) {
        val control = control()
        val hosts = listOf(
            AttacheHostEntry(UUID(0, 1), "tmux Daily", "xueshis-macbook-pro.taildff16", true, true),
            AttacheHostEntry(UUID(0, 2), "tmux Dev", "192.168.8.88 · 点击连接", false, false),
        )
        composeTestRule.setContent {
            ConnectBotTheme {
                var sidebarOpen by remember { mutableStateOf(open) }
                val palette = attacheGlassPalette(isDark)
                Box(Modifier.fillMaxSize().background(palette.paper)) {
                    Column(Modifier.padding(start = if (isLandscape && sidebarOpen) 272.dp else 12.dp, top = 74.dp)) {
                        repeat(12) {
                            Text(
                                "▸ Thought for 4s · terminal line $it",
                                color = palette.ink,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    AttacheFloatingChrome(
                        control = control,
                        isDark = isDark,
                        isLandscape = isLandscape,
                        barVisible = true,
                        sidebarOpen = sidebarOpen,
                        onBack = {},
                        onToggleSidebar = {
                            sidebarOpen = !sidebarOpen
                            onState(sidebarOpen)
                        },
                        onCloseSidebar = {
                            sidebarOpen = false
                            onState(false)
                        },
                        onBarHeightChanged = {},
                        actions = {
                            CapsuleActionButton(onClick = {}) { Text("A-", color = palette.ink) }
                            CapsuleActionButton(onClick = {}) { Text("A+", color = palette.ink) }
                            CapsuleActionButton(onClick = {}) { Text("☾", color = palette.ink) }
                            CapsuleActionButton(onClick = {}) { Text("▦", color = palette.ink) }
                            CapsuleActionButton(onClick = {}) { Text("⋮", color = palette.ink) }
                        },
                        sidebar = { m ->
                            AttacheSidebar(
                                control = control,
                                hosts = hosts,
                                onSelectHost = {},
                                isDark = isDark,
                                modifier = m,
                            )
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = System.getenv("ATTACHE_SHOTS_DIR") ?: return
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w1000dp-h625dp-land-mdpi")
    fun landscapeLightOpen_showsPanelAndSingleThemeToggle() {
        render(isDark = false, isLandscape = true, open = true)
        composeTestRule.onNodeWithTag("attacheSidebarPanel").assertExists()
        composeTestRule.onNodeWithTag("tmuxSidebarNewWindow").assertExists()
        composeTestRule.onNodeWithTag("tmuxSidebarThemeToggle").assertDoesNotExist()
        shot("land-light-open")
    }

    @Test
    @Config(qualifiers = "w1000dp-h625dp-land-mdpi")
    fun landscapeDarkOpen_render() {
        render(isDark = true, isLandscape = true, open = true)
        shot("land-dark-open")
    }

    @Test
    @Config(qualifiers = "w1000dp-h625dp-land-mdpi")
    fun landscapeClosed_toggleOpensPanel() {
        var state = false
        render(isDark = false, isLandscape = true, open = false) { state = it }
        composeTestRule.onNodeWithTag("attacheSidebarPanel").assertDoesNotExist()
        shot("land-light-closed")
        composeTestRule.onNodeWithTag("attacheSidebarToggle").performClick()
        composeTestRule.waitForIdle()
        assertTrue(state)
        composeTestRule.onNodeWithTag("attacheSidebarPanel").assertExists()
    }

    @Test
    @Config(qualifiers = "w625dp-h1000dp-port-mdpi")
    fun portraitOpen_scrimClosesDrawer() {
        var state = true
        render(isDark = false, isLandscape = false, open = true) { state = it }
        shot("port-light-open")
        composeTestRule.onNodeWithTag("attacheDrawerScrim").performClick()
        composeTestRule.waitForIdle()
        assertFalse(state)
    }
}
