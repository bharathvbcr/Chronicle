package com.chronicle.app.ui.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditHelpersTest {

    @Test
    fun wrapSelection_wrapsSelectedText() {
        val value = TextFieldValue("hello world", TextRange(0, 5))
        val result = insertBold(value)
        assertEquals("**hello** world", result.text)
        assertEquals(TextRange(2, 7), result.selection)
    }

    @Test
    fun wrapSelection_insertsPlaceholderWhenEmpty() {
        val value = TextFieldValue("ab", TextRange(1, 1))
        val result = insertItalic(value)
        assertEquals("a*italic*b", result.text)
        assertEquals(TextRange(2, 8), result.selection)
    }

    @Test
    fun insertBulletList_prefixesCurrentLine() {
        val value = TextFieldValue("one\ntwo", TextRange(5, 5))
        val result = insertBulletList(value)
        assertEquals("one\n- two", result.text)
    }

    @Test
    fun insertBulletList_togglesOff() {
        val value = TextFieldValue("- item", TextRange(2, 2))
        val result = insertBulletList(value)
        assertEquals("item", result.text)
    }

    @Test
    fun insertNumberedList_prefixesBlock() {
        val value = TextFieldValue("a\nb", TextRange(0, 3))
        val result = insertNumberedList(value)
        assertEquals("1. a\n1. b", result.text)
    }

    @Test
    fun insertLink_selectsUrl() {
        val value = TextFieldValue("docs", TextRange(0, 4))
        val result = insertLink(value, "https://example.com")
        assertEquals("[docs](https://example.com)", result.text)
        assertEquals(TextRange(7, 26), result.selection)
    }
}
