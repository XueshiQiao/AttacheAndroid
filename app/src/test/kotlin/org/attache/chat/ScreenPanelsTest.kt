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
import org.junit.Assert.assertNull
import org.junit.Test

/** Screens captured from Claude Code 2.1 on this machine, 2026-11-05. */
class ScreenPanelsTest {
    private val rule = "─".repeat(100)

    private val idle = listOf(
        "⏺ Rendering the full film now.",
        "",
        rule,
        "❯ Try \"fix typecheck errors\"",
        rule,
        "  …/cmdtest  |  Opus 5.5                     20:09",
        "  ⏵⏵ auto mode on (shift+tab to cycle) · ← 1 agent",
        "",
    )

    private val usagePanel = listOf(
        "   Total cost:            \$0.0000",
        "   Current session",
        "   ██████████▌                                        21% used",
        "   Resets 9pm (Asia/Singapore)",
        "   Current week (all models)",
        "   █▌                                                 3% used",
        "   Subagents               % of usage                                                             ↓",
        "",
        "",
    )

    @Test
    fun anOrdinaryScreenHasNoPanel() {
        assertNull(ScreenPanels.detect(idle))
    }

    /** A named session (`/rename`) writes its name into the rule above the
     * box. Captured 2026-11-06 right after `/clear`, which is the moment the
     * screen is watched — before this case the box went unrecognised and the
     * empty prompt was shown as a panel. */
    @Test
    fun aBoxUnderANamedRuleHasNoPanel() {
        val named = listOf(
            "",
            "─".repeat(80) + " Herdr 手机端通信与显示 ─",
            "❯ ",
            rule,
            "  ~/Code/Attache  main  |  Opus 5.5  |  5h ███  used 15%",
            "  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← 1 agent",
        )
        assertNull(ScreenPanels.detect(named))
    }

    @Test
    fun aWorkingScreenHasNoPanel() {
        val working = listOf("✻ Jitterbugging… (13s · ↓ 649 tokens · esc to interrupt)", "") + idle.drop(2)
        assertNull(ScreenPanels.detect(working))
    }

    @Test
    fun theUsagePanelIsOne() {
        val panel = ScreenPanels.detect(usagePanel)!!
        assertEquals("   Total cost:            \$0.0000", panel.first())
        assertEquals(7, panel.size)
    }

    @Test
    fun aPickerNamingItsKeysIsOneEvenWithTheBoxDrawn() {
        val picker = idle.dropLast(1) + listOf(
            " Select model",
            " ❯ 1. Opus 5.5 (default)",
            "   2. Sonnet 5.5",
            "",
            " Enter to confirm · Esc to exit",
        )
        assertEquals(" Enter to confirm · Esc to exit", ScreenPanels.detect(picker)!!.last())
    }
}
