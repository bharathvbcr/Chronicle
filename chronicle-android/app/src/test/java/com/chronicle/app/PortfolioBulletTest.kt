package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioBulletTest {
    @Test
    fun extractStarBullets_pullsListItems() {
        val md = """
            # Bank
            - Designed a Go sidecar that speaks JSON-RPC over stdio to the Rust core.
            * Short
            1. Shipped fourteen backend implementations with per-OS invocation handling.
            plain text
        """.trimIndent()
        val bullets = extractStarBullets(md)
        assertEquals(2, bullets.size)
        assertTrue(bullets[0].startsWith("Designed"))
        assertTrue(bullets[1].startsWith("Shipped"))
    }
}
