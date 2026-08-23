package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFrontmatterStripTest {
    @Test
    fun stripFrontmatter_removesYamlBlock() {
        val raw = "---\ntitle: Week\nweek_start: 2026-07-13\n---\n\n## Themes\n\n- work\n"
        assertEquals("## Themes\n\n- work\n", NoteFrontmatter.stripFrontmatter(raw))
    }

    @Test
    fun stripFrontmatter_passthroughWithoutFm() {
        assertEquals("# Hello", NoteFrontmatter.stripFrontmatter("# Hello"))
    }
}
