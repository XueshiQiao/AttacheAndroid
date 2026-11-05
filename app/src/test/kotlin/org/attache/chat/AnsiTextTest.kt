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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lines as `/context` printed them on this machine, 2026-11-05. */
class AnsiTextTest {
    @Test
    fun theTextIsWhatWasPrinted() {
        val text = AnsiText.of("\u001b[1mContext Usage\u001b[22m\n\u001b[38;5;140m⛁ ⛁\u001b[39m  Opus 5.5")
        assertEquals("Context Usage\n⛁ ⛁  Opus 5.5", text.text)
    }

    @Test
    fun coloursAndBoldBecomeStyles() {
        val text = AnsiText.of("\u001b[1mA\u001b[22m\u001b[38;5;140mB\u001b[39mC\u001b[38;2;10;20;30mD")
        val styles = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item }
        assertEquals(FontWeight.Bold, styles["A"]?.fontWeight)
        // 140 in the xterm cube is (3, 2, 4): #af87d7.
        assertEquals(Color(175, 135, 215), styles["B"]?.color)
        assertEquals(Color.Unspecified, styles["C"]?.color)
        assertEquals(Color(10, 20, 30), styles["D"]?.color)
    }

    @Test
    fun greysAndBasicColoursAreInThePalette() {
        val text = AnsiText.of("\u001b[38;5;246mG\u001b[31mR")
        val styles = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item }
        assertEquals(Color(148, 148, 148), styles["G"]?.color)
        assertEquals(Color(0xFFCD3131), styles["R"]?.color)
    }

    @Test
    fun aColourThatCannotBeReadOnTheCardGivesWayToTheThemes() {
        val dark = Color(0xFF2B2D31)
        val text = AnsiText.of("\u001b[30mblack\u001b[38;5;246mgrey", background = dark)
        val styles = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item }
        assertEquals(Color.Unspecified, styles["black"]?.color)
        assertEquals(Color(148, 148, 148), styles["grey"]?.color)
    }
}
