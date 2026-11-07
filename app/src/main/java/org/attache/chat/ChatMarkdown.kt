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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
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
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdText

/** One block of a reply, its text already styled. [indent] counts the
 * list levels it sits in. */
internal sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString, val indent: Int = 0) : MdBlock

    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock

    data class Bullet(val marker: String, val text: AnnotatedString, val indent: Int) : MdBlock

    /** Code keeps its columns, so it is monospace. */
    data class Preformatted(val text: String, val indent: Int = 0) : MdBlock

    /** Every row has as many cells as [header], one [align] per column. */
    data class Table(
        val header: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
        val align: List<TextAlign>,
        val indent: Int = 0,
    ) : MdBlock

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
        .build()

    private val BARE_URL = Regex("https?://[^\\s<>()\\[\\]{}\"'`，。；：、！？（）「」【】]+[^\\s<>()\\[\\]{}\"'`，。；：、！？（）「」【】.,;:!?]")
    private val OPENS = setOf("http", "https", "mailto")
    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    fun blocks(text: String, colors: MdColors): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val prepared = CjkEmphasis.prepare(text)
        children(parser.parse(prepared), 0, out, colors)
        return out
    }

    private fun children(parent: Node, indent: Int, out: MutableList<MdBlock>, colors: MdColors) {
        var node = parent.firstChild
        while (node != null) {
            block(node, indent, out, colors)
            node = node.next
        }
    }

    private fun block(node: Node, indent: Int, out: MutableList<MdBlock>, colors: MdColors) {
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
                            if (first != null) block(first, indent + 1, out, colors)
                        }
                        var rest = first?.next
                        while (rest != null) {
                            block(rest, indent + 1, out, colors)
                            rest = rest.next
                        }
                    }
                    item = item.next
                }
            }

            is FencedCodeBlock -> out += MdBlock.Preformatted(node.literal.trimEnd('\n'), indent)

            is IndentedCodeBlock -> out += MdBlock.Preformatted(node.literal.trimEnd('\n'), indent)

            is ThematicBreak -> out += MdBlock.Rule

            is BlockQuote -> out += MdBlock.Quote(ArrayList<MdBlock>().also { children(node, 0, it, colors) })

            is TableBlock -> out += table(node, indent, colors)

            is HtmlBlock -> out += MdBlock.Paragraph(AnnotatedString(node.literal.trim()), indent)

            else -> children(node, indent, out, colors)
        }
    }

    /** Laid out rather than shown as typed: the source only lines up when
     * every cell is ASCII, and Claude does not pad Chinese cells, so a
     * table came out as a run of pipes. */
    private fun table(node: TableBlock, indent: Int, colors: MdColors): MdBlock.Table {
        val rows = ArrayList<List<TableCell>>()
        fun collect(parent: Node) {
            var child = parent.firstChild
            while (child != null) {
                if (child is TableRow) {
                    rows += generateSequence(child.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                } else {
                    collect(child)
                }
                child = child.next
            }
        }
        collect(node)
        val header = rows.first()
        val columns = header.size
        fun texts(row: List<TableCell>) = List(columns) { c -> row.getOrNull(c)?.let { inline(it, colors) } ?: AnnotatedString("") }
        val align = header.map {
            when (it.alignment) {
                TableCell.Alignment.CENTER -> TextAlign.Center
                TableCell.Alignment.RIGHT -> TextAlign.End
                else -> TextAlign.Start
            }
        }
        return MdBlock.Table(texts(header), rows.drop(1).map(::texts), align, indent)
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

                    // A cell cannot hold a line break, so agents write
                    // `<br>` in tables to get one.
                    is HtmlInline -> if (BREAK.matches(child.literal)) append('\n') else append(child.literal)

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

/** Something set after the last word of a reply: [widthEm] wide, in the
 * text's own line, as a placeholder the text flows around. */
internal class MdBadge(val widthEm: Float, val heightEm: Float = 1.35f, val content: @Composable () -> Unit)

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier, badge: MdBadge? = null) {
    val colors = MdColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary)
    val blocks = remember(text, colors) { ChatMarkdown.blocks(text, colors) }
    SelectionContainer(modifier) { MarkdownBlocks(blocks, badge) }
}

private const val BADGE = "badge"

/** [text] with the badge after its last word. */
private fun withBadge(text: AnnotatedString, badge: MdBadge?): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    if (badge == null) return text to emptyMap()
    val joined = buildAnnotatedString {
        append(text)
        append(" ")
        appendInlineContent(BADGE, "[…]")
    }
    val content = InlineTextContent(Placeholder(badge.widthEm.em, badge.heightEm.em, PlaceholderVerticalAlign.TextCenter)) {
        badge.content()
    }
    return joined to mapOf(BADGE to content)
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, badge: MdBadge? = null) {
    // The badge goes after the last word when the reply ends in words; after
    // a code block, a table or a rule it gets a line of its own.
    val inLast = badge != null && (blocks.lastOrNull() is MdBlock.Paragraph || blocks.lastOrNull() is MdBlock.Bullet)
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
            val last = i == blocks.lastIndex && inLast
            when (block) {
                is MdBlock.Paragraph -> {
                    val (shown, inline) = withBadge(block.text, if (last) badge else null)
                    Text(shown, style = body, inlineContent = inline, modifier = Modifier.padding(start = under(block.indent)))
                }

                is MdBlock.Heading -> Text(
                    block.text,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                    val (shown, inline) = withBadge(block.text, if (last) badge else null)
                    Text(shown, style = body, inlineContent = inline)
                }

                is MdBlock.Preformatted -> CodeBlock(block.text, Modifier.padding(start = under(block.indent)))

                is MdBlock.Table -> MarkdownTable(block, Modifier.padding(start = under(block.indent)))

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
        if (badge != null && !inLast) {
            Spacer(Modifier.height(6.dp))
            badge.content()
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
    is MdBlock.Table -> indent
    else -> 0
}

/** How narrow a column may be squeezed before the table scrolls instead:
 * about ten Chinese characters a line. At 72dp a four-column table of
 * sentences fitted every phone by wrapping each cell into a tall sliver,
 * and nothing ever scrolled. */
private val TABLE_COLUMN_FLOOR = 160.dp

/**
 * A table as a grid. Columns take the width their longest cell wants;
 * when that is wider than the screen the wide columns give way and wrap,
 * and once they are down to [TABLE_COLUMN_FLOOR] the table scrolls
 * sideways — which on a phone is most tables of sentences.
 */
@Composable
private fun MarkdownTable(table: MdBlock.Table, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val head = MaterialTheme.colorScheme.surfaceVariant
    val cell = MaterialTheme.colorScheme.surface
    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    val floor = with(LocalDensity.current) { TABLE_COLUMN_FLOOR.roundToPx() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(
                content = {
                    (listOf(table.header) + table.rows).forEachIndexed { r, row ->
                        row.forEachIndexed { c, text ->
                            Text(
                                text,
                                style = style,
                                fontWeight = if (r == 0) FontWeight.SemiBold else null,
                                textAlign = table.align[c],
                                modifier = Modifier
                                    .background(if (r == 0) head else cell)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                },
                // The gaps between cells show this through, as the rules.
                // The outer edge is stroked on top as well: the cells are
                // square, so at a rounded corner they reach past the 1dp
                // gap and the clip cut the frame away with them.
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(line)
                    .border(1.dp, line, RoundedCornerShape(8.dp)),
            ) { measurables, _ ->
                val columns = table.header.size
                val rows = measurables.size / columns
                val gap = 1.dp.roundToPx().coerceAtLeast(1)
                val natural = IntArray(columns) { c -> (0 until rows).maxOf { r -> measurables[r * columns + c].maxIntrinsicWidth(Constraints.Infinity) } }
                val widths = if (available == Constraints.Infinity) natural else TableWidths.fit(natural, available - gap * (columns + 1), floor)
                // Asked first so every cell in a row can be measured at the
                // row's full height, which is what fills its background.
                val heights = IntArray(rows) { r -> (0 until columns).maxOf { c -> measurables[r * columns + c].minIntrinsicHeight(widths[c]) } }
                val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % columns], heights[i / columns])) }
                layout(widths.sum() + gap * (columns + 1), heights.sum() + gap * (rows + 1)) {
                    var y = gap
                    for (r in 0 until rows) {
                        var x = gap
                        for (c in 0 until columns) {
                            placeables[r * columns + c].place(x, y)
                            x += widths[c] + gap
                        }
                        y += heights[r] + gap
                    }
                }
            }
        }
    }
}

internal object TableWidths {
    /**
     * Column widths for [available], from the width each column's longest
     * cell wants. Columns that want no more than an even share keep what
     * they want; the rest split what is left evenly, but none goes below
     * [floor] — past that the sum exceeds [available] and the table scrolls.
     */
    fun fit(natural: IntArray, available: Int, floor: Int): IntArray {
        if (natural.sum() <= available) return natural
        val open = natural.indices.toMutableList()
        var left = available
        while (true) {
            val share = left / open.size
            val settled = open.filter { natural[it] <= share }
            if (settled.isEmpty()) break
            for (c in settled) left -= natural[c]
            open.removeAll(settled)
        }
        val share = left / open.size
        return IntArray(natural.size) { c -> if (c in open) maxOf(share, minOf(floor, natural[c])) else natural[c] }
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
