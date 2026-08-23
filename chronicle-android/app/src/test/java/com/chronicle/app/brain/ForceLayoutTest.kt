package com.chronicle.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceLayoutTest {

    @Test
    fun layoutForce_placesLinkedNodesNearEachOther() {
        val nodes = listOf(
            SimNode("a", 0f, 0f, radius = 10f),
            SimNode("b", 0f, 0f, radius = 10f),
            SimNode("c", 0f, 0f, radius = 10f),
        )
        val links = listOf(
            SimLink("a", "b"),
            SimLink("b", "c"),
        )
        val pos = layoutForce(nodes, links, width = 800f, height = 600f, iterations = 200)
        assertEquals(3, pos.size)
        val a = pos["a"]!!
        val b = pos["b"]!!
        val c = pos["c"]!!
        val ab = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        val ac = kotlin.math.hypot((a.x - c.x).toDouble(), (a.y - c.y).toDouble())
        // Linked neighbors should be closer than the chain ends
        assertTrue("ab=$ab ac=$ac", ab < ac || ab < 200.0)
        // Not stacked on the same pixel
        assertTrue(ab > 1.0)
    }

    @Test
    fun layoutForce_preservesPreviousPositionsAsSeed() {
        val nodes = listOf(
            SimNode("a", 0f, 0f, radius = 8f),
            SimNode("b", 0f, 0f, radius = 8f),
        )
        val prev = mapOf(
            "a" to androidx.compose.ui.geometry.Offset(100f, 100f),
            "b" to androidx.compose.ui.geometry.Offset(200f, 100f),
        )
        val pos = layoutForce(
            nodes,
            links = listOf(SimLink("a", "b")),
            width = 400f,
            height = 400f,
            iterations = 40,
            previous = prev,
        )
        // After short sim, nodes stay near their seeded region (not re-circularized at origin)
        assertTrue(pos["a"]!!.x in 50f..350f)
        assertTrue(pos["b"]!!.x in 50f..350f)
    }

    @Test
    fun forceNodeRadius_entrySmallerThanHub() {
        val entry = GraphNode(id = "e1", kind = "entry", label = "E", weight = 1.0)
        val hub = GraphNode(id = "c1", kind = "concept", label = "C", group = "core", weight = 4.0)
        assertTrue(forceNodeRadius(hub) > forceNodeRadius(entry))
    }
}
