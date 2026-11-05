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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Markdown agents actually write: paragraphs, headings, lists, fenced
 * code, tables, inline code and bold. Not a full CommonMark renderer; a
 * construct it does not know is shown as the text it is, which is still
 * readable, rather than dropped.
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    data class Bullet(val marker: String, val text: String, val indent: Int) : MdBlock

    /** Code and tables both keep their columns, so both are monospace. */
    data class Preformatted(val text: String) : MdBlock

    data object Rule : MdBlock
}

internal object ChatMarkdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val BULLET = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")

    fun blocks(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += MdBlock.Preformatted(code.toString())
                }

                trimmed.startsWith("|") -> {
                    flush()
                    val table = StringBuilder(line)
                    while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith("|")) {
                        i++
                        table.append('\n').append(lines[i])
                    }
                    out += MdBlock.Preformatted(table.toString())
                }

                RULE.matches(trimmed) -> {
                    flush()
                    out += MdBlock.Rule
                }

                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.matchEntire(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }

                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.matchEntire(line)!!
                    val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                    out += MdBlock.Bullet(marker, m.groupValues[3], m.groupValues[1].length / 2)
                }

                line.isBlank() -> flush()

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
            i++
        }
        flush()
        return out
    }

    /** `code` and **bold**; anything unbalanced is left as typed. */
    fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        append(text.substring(i))
                        return@buildAnnotatedString
                    }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }

                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        append(text.substring(i))
                        return@buildAnnotatedString
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { ChatMarkdown.blocks(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    // Chinese sets dense: a taller line, and paragraphs held apart by more
    // than the line gap, or a reply reads as one long block.
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { i, block ->
                val gap = when {
                    i == 0 -> 0.dp
                    block is MdBlock.Heading -> 20.dp
                    block is MdBlock.Bullet && blocks[i - 1] is MdBlock.Bullet -> 4.dp
                    block is MdBlock.Rule || blocks[i - 1] is MdBlock.Rule -> 12.dp
                    else -> 14.dp
                }
                if (gap > 0.dp) Spacer(Modifier.height(gap))
                when (block) {
                    is MdBlock.Paragraph -> Text(ChatMarkdown.inline(block.text, codeBackground), style = body)

                    is MdBlock.Heading -> Text(
                        ChatMarkdown.inline(block.text, codeBackground),
                        style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                        Text(block.marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                        Text(ChatMarkdown.inline(block.text, codeBackground), style = body)
                    }

                    is MdBlock.Preformatted -> CodeBlock(block.text)

                    MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
internal fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}
