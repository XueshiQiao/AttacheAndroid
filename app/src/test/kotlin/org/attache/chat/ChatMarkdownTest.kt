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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    private val colors = MdColors(Color.Gray, Color.Blue)

    private fun blocks(text: String) = ChatMarkdown.blocks(text, colors)

    private fun paragraph(text: String) = (blocks(text).single() as MdBlock.Paragraph).text

    private fun AnnotatedString.bold() = spanStyles.filter { it.item.fontWeight == FontWeight.SemiBold }.map { text.substring(it.start, it.end) }

    @Test
    fun aFileLinkShowsItsNameAndAWebLinkOpens() {
        val text = paragraph("看 [ChatMarkdown.kt](file:///w/ChatMarkdown.kt#L10) 和 [文档](https://example.com/a)。")
        assertEquals("看 ChatMarkdown.kt 和 文档。", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { it.item as LinkAnnotation.Url }
        assertEquals(listOf("https://example.com/a"), links.map { it.url })
    }

    @Test
    fun aBareAddressOpensWithoutTheFullStopAfterIt() {
        val text = paragraph("在 https://example.com/x?a=1 上，见 https://example.com/y。")
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com/x?a=1", "https://example.com/y"), links)
    }

    @Test
    fun boldNextToChinesePunctuationIsBold() {
        // As Claude and agy write it; plain CommonMark leaves all three as typed.
        assertEquals(listOf("第 2 个已经修了："), paragraph("**第 2 个已经修了：**关闭").bold())
        assertEquals(listOf("标题"), paragraph("**标题**：单位放在括号里").bold())
        assertEquals(listOf("「隐藏」"), paragraph("只能选**「隐藏」**或者不动").bold())
        assertTrue(blocks("**改动：**\n- a").first().let { it is MdBlock.Paragraph && it.text.text == "改动：" })
    }

    @Test
    fun codeIsLeftAsWritten() {
        val text = paragraph("把 `**Quick Access**` 和 `a*：*b` 改掉")
        assertEquals("把 **Quick Access** 和 a*：*b 改掉", text.text)
        assertEquals(emptyList<String>(), text.bold())
        val fenced = blocks("```\n**说明：**很好\n```").single() as MdBlock.Preformatted
        assertEquals("**说明：**很好", fenced.text)
    }

    @Test
    fun aTableKeepsItsSourceAndAListItsShape() {
        val out = blocks("| a | b |\n|---|---|\n| 1 | 2 |\n\n1. 第一\n   - 里面\n2. 第二")
        assertEquals(MdBlock.Preformatted("| a | b |\n|---|---|\n| 1 | 2 |"), out[0])
        assertEquals(listOf("1." to 0, "•" to 1, "2." to 0), out.drop(1).map { (it as MdBlock.Bullet).marker to it.indent })
    }

    @Test
    fun aLineBreakStaysALineBreak() {
        assertEquals("第一行\n第二行", paragraph("第一行\n第二行").text)
    }
}
