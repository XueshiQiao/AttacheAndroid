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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The colour and weight codes a command printed, turned into text styles.
 * Only SGR (`ESC [ … m`) is read: foreground colours in 16, 256 and 24-bit
 * form, bold and italic, and their resets. Every other sequence is dropped,
 * so what is left is the printed text with the look it had in the terminal.
 */
internal object AnsiText {
    private val SEQUENCE = Regex("\u001B\\[([0-9;?]*)([ -/]*[@-~])|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    /**
     * [background] is what the text is drawn on. A colour the command chose
     * for a terminal of the other brightness, black on a dark card or white
     * on a light one, is dropped back to the theme's text colour rather than
     * drawn where it cannot be read.
     */
    fun of(raw: String, background: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
        var color: Color? = null
        var bold = false
        var italic = false
        var at = 0
        for (m in SEQUENCE.findAll(raw)) {
            appendStyled(raw.substring(at, m.range.first), color, bold, italic, background)
            at = m.range.last + 1
            if (m.groupValues[2] != "m") continue
            val codes = m.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < codes.size) {
                when (val c = codes[i]) {
                    0 -> {
                        color = null
                        bold = false
                        italic = false
                    }

                    1 -> bold = true

                    22 -> bold = false

                    3 -> italic = true

                    23 -> italic = false

                    39 -> color = null

                    in 30..37 -> color = basic(c - 30)

                    in 90..97 -> color = basic(c - 90 + 8)

                    38 -> when (codes.getOrNull(i + 1)) {
                        5 -> {
                            color = xterm(codes.getOrNull(i + 2) ?: 0)
                            i += 2
                        }

                        2 -> {
                            color = Color(codes.getOrElse(i + 2) { 0 }, codes.getOrElse(i + 3) { 0 }, codes.getOrElse(i + 4) { 0 })
                            i += 4
                        }
                    }
                }
                i++
            }
        }
        appendStyled(raw.substring(at), color, bold, italic, background)
    }

    private fun AnnotatedString.Builder.appendStyled(text: String, color: Color?, bold: Boolean, italic: Boolean, background: Color) {
        if (text.isEmpty()) return
        val readable = color?.takeIf { background == Color.Unspecified || contrast(it, background) >= MIN_CONTRAST }
        val style = SpanStyle(
            color = readable ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
        )
        pushStyle(style)
        append(text)
        pop()
    }

    /** WCAG's floor for large text; below it the colour goes. */
    private const val MIN_CONTRAST = 3.0

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }

    private val BASIC = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )

    private fun basic(index: Int) = Color(0xFF000000.toInt() or BASIC[index.coerceIn(0, 15)])

    /** The xterm 256-colour palette: 16 basic, a 6×6×6 cube, 24 greys. */
    private fun xterm(index: Int): Color = when (index) {
        in 0..15 -> basic(index)

        in 16..231 -> {
            val i = index - 16
            fun level(c: Int) = if (c == 0) 0 else 55 + 40 * c
            Color(level(i / 36), level(i / 6 % 6), level(i % 6))
        }

        in 232..255 -> (8 + 10 * (index - 232)).let { Color(it, it, it) }

        else -> Color.Unspecified
    }
}
