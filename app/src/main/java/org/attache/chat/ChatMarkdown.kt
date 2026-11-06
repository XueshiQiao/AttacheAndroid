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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdText

/** One block of a reply, its text already styled. [indent] counts the
 * list levels it sits in. */
internal sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString, val indent: Int = 0) : MdBlock

    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock

    data class Bullet(val marker: String, val text: AnnotatedString, val indent: Int) : MdBlock

    /** Code and tables both keep their columns, so both are monospace. */
    data class Preformatted(val text: String, val indent: Int = 0) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data object Rule : MdBlock
}

/** The colours inline text is styled with. */
internal data class MdColors(val codeBackground: Color, val link: Color)

/**
 * The agents' Markdown, parsed by commonmark-java (CommonMark plus GitHub's
 * tables and strikethrough) and drawn in the chat's own styles.
 *
 * It used to be parsed by hand, which knew paragraphs, headings, lists,
 * code, tables, inline code and bold, and showed everything else as typed:
 * agy's replies are mostly `[name](file:///…)` links, and every one of
 * them came out as its source.
 *
 * Links open in the browser when they are web links. A `file://` link names
 * a file on the host, which the phone cannot open, so it is coloured as a
 * link and does nothing.
 */
internal object ChatMarkdown {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    private val BARE_URL = Regex("https?://[^\\s<>()\\[\\]{}\"'`，。；：、！？（）「」【】]+[^\\s<>()\\[\\]{}\"'`，。；：、！？（）「」【】.,;:!?]")
    private val OPENS = setOf("http", "https", "mailto")

    fun blocks(text: String, colors: MdColors): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val prepared = CjkEmphasis.prepare(text)
        children(parser.parse(prepared), 0, out, colors, prepared.lines())
        return out
    }

    private fun children(parent: Node, indent: Int, out: MutableList<MdBlock>, colors: MdColors, lines: List<String>) {
        var node = parent.firstChild
        while (node != null) {
            block(node, indent, out, colors, lines)
            node = node.next
        }
    }

    private fun block(node: Node, indent: Int, out: MutableList<MdBlock>, colors: MdColors, lines: List<String>) {
        when (node) {
            is Paragraph -> out += MdBlock.Paragraph(inline(node, colors), indent)

            is Heading -> out += MdBlock.Heading(node.level, inline(node, colors))

            is BulletList, is OrderedList -> {
                var number = (node as? OrderedList)?.markerStartNumber ?: 1
                val delimiter = (node as? OrderedList)?.markerDelimiter ?: "."
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        val marker = if (node is OrderedList) "${number++}$delimiter" else "•"
                        val first = item.firstChild
                        // The item's first paragraph sits beside its marker;
                        // whatever follows it is indented under it.
                        if (first is Paragraph) {
                            out += MdBlock.Bullet(marker, inline(first, colors), indent)
                        } else {
                            out += MdBlock.Bullet(marker, AnnotatedString(""), indent)
                            if (first != null) block(first, indent + 1, out, colors, lines)
                        }
                        var rest = first?.next
                        while (rest != null) {
                            block(rest, indent + 1, out, colors, lines)
                            rest = rest.next
                        }
                    }
                    item = item.next
                }
            }

            is FencedCodeBlock -> out += MdBlock.Preformatted(node.literal.trimEnd('\n'), indent)

            is IndentedCodeBlock -> out += MdBlock.Preformatted(node.literal.trimEnd('\n'), indent)

            is ThematicBreak -> out += MdBlock.Rule

            is BlockQuote -> out += MdBlock.Quote(ArrayList<MdBlock>().also { children(node, 0, it, colors, lines) })

            // As written: its columns are lined up in the source, and a
            // phone is too narrow for a laid-out table anyway.
            is TableBlock -> out += MdBlock.Preformatted(source(node, lines), indent)

            is HtmlBlock -> out += MdBlock.Paragraph(AnnotatedString(node.literal.trim()), indent)

            else -> children(node, indent, out, colors, lines)
        }
    }

    private fun source(node: Node, lines: List<String>): String = node.sourceSpans.joinToString("\n") { span ->
        lines.getOrNull(span.lineIndex)?.let { line ->
            line.substring(span.columnIndex.coerceAtMost(line.length), (span.columnIndex + span.length).coerceAtMost(line.length))
        }.orEmpty()
    }

    /** A block's inline content: code, emphasis, strikethrough and links. */
    fun inline(parent: Node, colors: MdColors): AnnotatedString = buildAnnotatedString {
        fun visit(node: Node) {
            var child = node.firstChild
            while (child != null) {
                when (child) {
                    is MdText -> linkify(child.literal.replace(CjkEmphasis.MARK, ""), colors)

                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                        append(child.literal)
                    }

                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { visit(child) }

                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { visit(child) }

                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { visit(child) }

                    is Link -> link(child.destination, colors) { visit(child) }

                    // The phone does not fetch it; its description says what it was.
                    is Image -> {
                        append("[")
                        visit(child)
                        append("]")
                    }

                    // Agents break lines on purpose; a space in Chinese text
                    // would be a gap that was never there.
                    is SoftLineBreak, is HardLineBreak -> append('\n')

                    is HtmlInline -> append(child.literal)

                    else -> visit(child)
                }
                child = child.next
            }
        }
        visit(parent)
    }

    private fun AnnotatedString.Builder.link(destination: String, colors: MdColors, content: AnnotatedString.Builder.() -> Unit) {
        val style = SpanStyle(color = colors.link)
        if (destination.substringBefore(':', "").lowercase() in OPENS) {
            withLink(LinkAnnotation.Url(destination, TextLinkStyles(style.copy(textDecoration = TextDecoration.Underline)))) { content() }
        } else {
            withStyle(style) { content() }
        }
    }

    /** Bare web addresses in running text, which agents write as often as
     * links. */
    private fun AnnotatedString.Builder.linkify(text: String, colors: MdColors) {
        var at = 0
        for (m in BARE_URL.findAll(text)) {
            append(text.substring(at, m.range.first))
            link(m.value, colors) { append(m.value) }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
}

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = MdColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary)
    val blocks = remember(text, colors) { ChatMarkdown.blocks(text, colors) }
    SelectionContainer(modifier) { MarkdownBlocks(blocks) }
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>) {
    // Chinese sets dense: a taller line, and paragraphs held apart by more
    // than the line gap, or a reply reads as one long block.
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
    Column {
        blocks.forEachIndexed { i, block ->
            val previous = blocks.getOrNull(i - 1)
            val gap = when {
                previous == null -> 0.dp

                block is MdBlock.Heading -> 20.dp

                block is MdBlock.Bullet && previous is MdBlock.Bullet -> 4.dp

                // What follows a list item's first line belongs to it.
                block.indentOf() > 0 && previous.indentOf() >= 0 && (previous is MdBlock.Bullet || previous.indentOf() > 0) -> 6.dp

                block is MdBlock.Rule || previous is MdBlock.Rule -> 12.dp

                else -> 14.dp
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            when (block) {
                is MdBlock.Paragraph -> Text(block.text, style = body, modifier = Modifier.padding(start = under(block.indent)))

                is MdBlock.Heading -> Text(
                    block.text,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                    Text(block.text, style = body)
                }

                is MdBlock.Preformatted -> CodeBlock(block.text, Modifier.padding(start = under(block.indent)))

                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .padding(vertical = 2.dp),
                    ) {
                        Surface(color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight()) {}
                    }
                    Spacer(Modifier.width(10.dp))
                    MarkdownBlocks(block.blocks)
                }

                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** How far in a block sits that continues a list item: past the item's
 * marker. */
private fun under(indent: Int) = if (indent == 0) 0.dp else ((indent - 1) * 16 + 22).dp

private fun MdBlock.indentOf(): Int = when (this) {
    is MdBlock.Paragraph -> indent
    is MdBlock.Bullet -> indent
    is MdBlock.Preformatted -> indent
    else -> 0
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

/**
 * CommonMark decides whether `**` opens or closes by what is on either
 * side, with rules written for text that puts spaces between words. A
 * closing `**` right after punctuation must be followed by a space or more
 * punctuation, so `**第 2 个已经修了：**关闭` stays as typed: measured on
 * this machine's replies, 182 of 6,590 (25 Claude, 157 agy), every one this
 * shape. The hand-written parser before ignored the rule and got them right.
 *
 * The usual remedy: a zero-width space between the punctuation and the
 * `*`s, so the run sits next to an ordinary character. Only where a CJK
 * letter or full-width punctuation is involved, never inside code, and the
 * mark is taken out again before anything is drawn.
 */
internal object CjkEmphasis {
    const val MARK = "\u200B"

    fun prepare(text: String): String {
        if (!text.contains('*')) return text
        val out = StringBuilder(text.length + 16)
        var fenced = false
        text.split('\n').forEachIndexed { n, line ->
            if (n > 0) out.append('\n')
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced
                out.append(line)
            } else if (fenced) {
                out.append(line)
            } else {
                line(line, out)
            }
        }
        return out.toString()
    }

    private fun line(line: String, out: StringBuilder) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '`') {
                // An inline code span, up to the same run of backticks.
                var run = 1
                while (i + run < line.length && line[i + run] == '`') run++
                val close = line.indexOf("`".repeat(run), i + run)
                val end = if (close < 0) i + run else close + run
                out.append(line, i, end)
                i = end
                continue
            }
            if (c != '*') {
                out.append(c)
                i++
                continue
            }
            var end = i
            while (end < line.length && line[end] == '*') end++
            val before = if (i > 0) line[i - 1] else null
            val after = if (end < line.length) line[end] else null
            if (before != null && after != null && punctuation(before) && ordinary(after) && (cjk(before) || cjk(after))) out.append(MARK)
            out.append(line, i, end)
            if (before != null && after != null && punctuation(after) && ordinary(before) && (cjk(before) || cjk(after))) out.append(MARK)
            i = end
        }
    }

    private fun ordinary(c: Char) = !c.isWhitespace() && !punctuation(c)

    /** CommonMark's: Unicode punctuation and symbols. */
    private fun punctuation(c: Char) = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true

        else -> false
    }

    private fun cjk(c: Char): Boolean {
        if (c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF') return true
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
            -> true

            else -> false
        }
    }
}
