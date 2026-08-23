package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFrontmatterTest {
    @Test
    fun bumpUpdated_replacesExistingField() {
        val input =
            """
            ---
            title: X
            updated: 2020-01-01
            ---
            body
            """.trimIndent()
        val out = NoteFrontmatter.bumpUpdated(input, "2026-07-12")
        assertTrue(out.contains("updated: 2026-07-12"))
        assertFalse(out.contains("2020-01-01"))
    }

    @Test
    fun bumpUpdated_insertsWhenMissing() {
        val input =
            """
            ---
            title: X
            ---
            body
            """.trimIndent()
        val out = NoteFrontmatter.bumpUpdated(input, "2026-07-12")
        assertTrue(out.contains("updated: 2026-07-12"))
    }

    @Test
    fun bumpUpdated_noOpWithoutFrontmatter() {
        val input = "# No frontmatter\n"
        assertEquals(input, NoteFrontmatter.bumpUpdated(input))
    }

    @Test
    fun applyTemplatePlaceholders_expandsTokens() {
        val template = "# {{title}}\n{{date}}"
        val out = NoteFrontmatter.applyTemplatePlaceholders(template, "Skills", "2026-07-12")
        assertEquals("# Skills\n2026-07-12", out)
    }

    @Test
    fun ensureCreateFrontmatter_fillsDefaults() {
        val out = NoteFrontmatter.ensureCreateFrontmatter(
            "# Hello\n",
            title = "Hello",
            date = "2026-07-12",
        )
        assertTrue(out.contains("title: Hello"))
        assertTrue(out.contains("created: 2026-07-12"))
        assertTrue(out.contains("updated: 2026-07-12"))
        assertTrue(out.contains("type: note"))
        assertTrue(out.contains("tags: []"))
        assertTrue(out.contains("# Hello"))
    }

    @Test
    fun ensureCreateFrontmatter_preservesExisting() {
        val src =
            """
            ---
            title: Keep
            type: project
            created: 2020-01-01
            ---

            body
            """.trimIndent()
        val out = NoteFrontmatter.ensureCreateFrontmatter(src, title = "Ignored", date = "2026-07-12")
        assertTrue(out.contains("title: Keep"))
        assertTrue(out.contains("type: project"))
        assertTrue(out.contains("created: 2020-01-01"))
        assertTrue(out.contains("updated: 2026-07-12"))
    }
}
