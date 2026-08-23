package com.chronicle.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapGraphNodesTest {

    @Test
    fun capGraphNodes_keepsPinnedAndHighDegree() {
        val nodes = (1..20).map { i ->
            GraphNode(
                id = "n$i",
                kind = "concept",
                label = "N$i",
                pinned = i == 1,
                weight = 1.0,
            )
        }
        val edges = (2..20).map { i ->
            GraphEdge(from = "n1", to = "n$i", rel = "related")
        } + listOf(GraphEdge(from = "n2", to = "n3", rel = "related"))
        val graph = BrainGraph(generated = "t", nodes = nodes, edges = edges)
        val capped = capGraphNodes(graph, limit = 5)
        assertTrue(capped.capped)
        assertEquals(5, capped.nodes.size)
        assertTrue(capped.nodes.any { it.id == "n1" && it.pinned })
        assertEquals(20, capped.totalNodes)
    }

    @Test
    fun capGraphNodes_noopUnderLimit() {
        val nodes = listOf(
            GraphNode(id = "a", kind = "concept", label = "A"),
            GraphNode(id = "b", kind = "concept", label = "B"),
        )
        val graph = BrainGraph(
            generated = "t",
            nodes = nodes,
            edges = listOf(GraphEdge(from = "a", to = "b", rel = "manual")),
        )
        val capped = capGraphNodes(graph, limit = 10)
        assertFalse(capped.capped)
        assertEquals(2, capped.nodes.size)
        assertEquals(1, capped.edges.size)
    }
}
