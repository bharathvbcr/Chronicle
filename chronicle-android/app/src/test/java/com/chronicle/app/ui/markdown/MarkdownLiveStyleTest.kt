package com.chronicle.app.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiveStyleTest {

    private val colors = MarkdownLiveColors(
        accent = Color.Blue,
        marker = Color.Gray,
        codeBackground = Color.LightGray,
        quote = Color.DarkGray,
    )

    private fun spans(text: String): List<AnnotatedString.Range<SpanStyle>> =
        buildMarkdownSpanStyles(text, colors)

    @Test
    fun plainText_producesNoSpans() {
        assertTrue(spans("just a plain sentence").isEmpty())
        assertTrue(spans("").isEmpty())
    }

    @Test
    fun bold_stylesContentAndDimsMarkers() {
        val text = "say **hello** now"
        val result = spans(text)
        val content = result.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("hello", text.substring(content.start, content.end))
        val markers = result.filter { it.item.color == colors.marker }
        assertEquals(listOf("**", "**"), markers.map { text.substring(it.start, it.end) })
    }

    @Test
    fun italic_stylesContent() {
        val text = "an *italic* word"
        val content = spans(text).single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("italic", text.substring(content.start, content.end))
    }

    @Test
    fun italic_doesNotMatchInsideBold() {
        val result = spans("**bold**")
        assertTrue(result.none { it.item.fontStyle == FontStyle.Italic })
        assertTrue(result.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun strikethrough_stylesContent() {
        val text = "a ~~gone~~ word"
        val content = spans(text).single { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals("gone", text.substring(content.start, content.end))
    }

    @Test
    fun inlineCode_usesMonospaceAndSuppressesInnerSyntax() {
        val text = "run `**not bold**` ok"
        val result = spans(text)
        val code = result.single { it.item.background == colors.codeBackground }
        assertEquals("**not bold**", text.substring(code.start, code.end))
        assertTrue(result.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun heading_scalesByLevel() {
        val h1 = spans("# Title").single { it.item.fontWeight == FontWeight.Bold }
        assertEquals(26.sp, h1.item.fontSize)
        val h2 = spans("## Title").single { it.item.fontWeight == FontWeight.Bold }
        assertEquals(22.sp, h2.item.fontSize)
    }

    @Test
    fun heading_marksHashPrefix() {
        val text = "# Title"
        val marker = spans(text).single { it.item.color == colors.marker }
        assertEquals("# ", text.substring(marker.start, marker.end))
    }

    @Test
    fun quote_stylesLine() {
        val text = "> wise words"
        val quote = spans(text).single { it.item.color == colors.quote }
        assertEquals("wise words", text.substring(quote.start, quote.end))
    }

    @Test
    fun listMarker_highlightsBulletOnly() {
        val text = "- item one"
        val bullet = spans(text).single()
        assertEquals("- ", text.substring(bullet.start, bullet.end))
        assertEquals(colors.accent, bullet.item.color)
    }

    @Test
    fun numberedList_highlightsNumber() {
        val text = "1. first"
        val marker = spans(text).single()
        assertEquals("1. ", text.substring(marker.start, marker.end))
    }

    @Test
    fun link_stylesLabelAndDimsSyntax() {
        val text = "see [docs](https://x.com) here"
        val result = spans(text)
        val label = result.single { it.item.textDecoration == TextDecoration.Underline }
        assertEquals("docs", text.substring(label.start, label.end))
        val markers = result.filter { it.item.color == colors.marker }
        assertEquals(listOf("[", "](https://x.com)"), markers.map { text.substring(it.start, it.end) })
    }

    @Test
    fun emptyLinkLabel_doesNotCrash() {
        val result = spans("[](https://x.com)")
        assertTrue(result.none { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun multiline_offsetsAreRelativeToWholeText() {
        val text = "first\n**bold**\n- item"
        val result = spans(text)
        val bold = result.single {
            it.item.fontWeight == FontWeight.Bold && it.item.color == Color.Unspecified
        }
        assertEquals("bold", text.substring(bold.start, bold.end))
        val bullet = result.single { it.item.color == colors.accent }
        assertEquals("- ", text.substring(bullet.start, bullet.end))
    }

    @Test
    fun allSpans_stayWithinTextBounds() {
        val samples = listOf(
            "**unclosed",
            "*",
            "`",
            "# ",
            "> ",
            "[]()",
            "***",
            "~~~~",
            "text **bold** *ital* `code` [l](u) ~~s~~ end",
        )
        for (sample in samples) {
            for (range in spans(sample)) {
                assertTrue("start in bounds for '$sample'", range.start >= 0)
                assertTrue("end in bounds for '$sample'", range.end <= sample.length)
                assertTrue("ordered for '$sample'", range.start <= range.end)
            }
        }
    }
}
