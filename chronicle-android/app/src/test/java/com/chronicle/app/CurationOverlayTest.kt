package com.chronicle.app

import com.chronicle.app.brain.BrainGraph
import com.chronicle.app.brain.CurationOp
import com.chronicle.app.brain.GraphEdge
import com.chronicle.app.brain.GraphNode
import com.chronicle.app.brain.applyCurationOverlay
import com.chronicle.app.brain.parseGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationOverlayTest {

    private val base = BrainGraph(
        generated = "2026-07-09T12:00:00Z",
        nodes = listOf(
            GraphNode(id = "topic:gym", kind = "topic", label = "gym", weight = 2.0),
            GraphNode(id = "topic:fitness", kind = "topic", label = "fitness", weight = 3.0),
            GraphNode(id = "entry:2026-07-09_213045-an", kind = "entry", label = "note"),
            GraphNode(id = "concept:startup", kind = "concept", label = "Startup", weight = 1.0),
            GraphNode(id = "project:app", kind = "project", label = "App", weight = 1.0),
        ),
        edges = listOf(
            GraphEdge(from = "entry:2026-07-09_213045-an", to = "topic:gym", rel = "about"),
            GraphEdge(from = "concept:startup", to = "project:app", rel = "related"),
        ),
    )

    @Test
    fun pinRenameCreateAndMerge() {
        val ops = listOf(
            CurationOp(op = "pin", ts = "2026-07-09T12:01:00Z", node = "topic:fitness"),
            CurationOp(op = "rename", ts = "2026-07-09T12:02:00Z", node = "topic:fitness", label = "Fitness"),
            CurationOp(op = "create_concept", ts = "2026-07-09T12:03:00Z", id = "concept:new", label = "New"),
            CurationOp(op = "merge", ts = "2026-07-09T12:04:00Z", from = "topic:gym", into = "topic:fitness"),
            CurationOp(
                op = "link",
                ts = "2026-07-09T12:05:00Z",
                from = "entry:2026-07-09_213045-an",
                to = "concept:new",
                rel = "manual",
            ),
        )
        val out = applyCurationOverlay(base, ops)
        assertFalse(out.nodes.any { it.id == "topic:gym" })
        val fitness = out.nodes.first { it.id == "topic:fitness" }
        assertTrue(fitness.pinned)
        assertEquals("Fitness", fitness.label)
        assertTrue(out.nodes.any { it.id == "concept:new" })
        assertTrue(out.edges.any { it.from == "entry:2026-07-09_213045-an" && it.to == "topic:fitness" })
        assertTrue(out.edges.any { it.to == "concept:new" && it.rel == "manual" })
    }

    @Test
    fun setDocUpdatesNodeDoc() {
        val ops = listOf(
            CurationOp(
                op = "set_doc",
                ts = "2026-07-09T12:10:00Z",
                node = "concept:startup",
                doc = "ResumePoints/Startup.md",
            ),
        )
        val out = applyCurationOverlay(base, ops)
        val node = out.nodes.first { it.id == "concept:startup" }
        assertEquals("ResumePoints/Startup.md", node.doc)
    }

    @Test
    fun deleteConceptRemovesNodeAndEdges() {
        val ops = listOf(
            CurationOp(op = "delete_concept", ts = "2026-07-09T12:11:00Z", node = "concept:startup"),
        )
        val out = applyCurationOverlay(base, ops)
        assertFalse(out.nodes.any { it.id == "concept:startup" })
        assertFalse(out.edges.any { it.from == "concept:startup" || it.to == "concept:startup" })
        assertTrue(out.nodes.any { it.id == "project:app" })
    }

    @Test
    fun deleteConceptIgnoresNonConceptKinds() {
        val ops = listOf(
            CurationOp(op = "delete_concept", ts = "2026-07-09T12:12:00Z", node = "topic:gym"),
        )
        val out = applyCurationOverlay(base, ops)
        assertTrue(out.nodes.any { it.id == "topic:gym" })
    }

    @Test
    fun parseGraphPreservesDocField() {
        val json = """
            {
              "version": 1,
              "generated": "2026-07-09T12:00:00Z",
              "nodes": [
                {
                  "id": "concept:ml",
                  "kind": "concept",
                  "label": "ML",
                  "doc": "Skills/ML.md"
                }
              ],
              "edges": []
            }
        """.trimIndent()
        val graph = parseGraph(json)!!
        assertEquals("Skills/ML.md", graph.nodes.single().doc)
    }

    @Test
    fun parseGraphOmitsMissingDoc() {
        val json = """
            {
              "version": 1,
              "generated": "2026-07-09T12:00:00Z",
              "nodes": [
                { "id": "topic:a", "kind": "topic", "label": "a" }
              ],
              "edges": []
            }
        """.trimIndent()
        val graph = parseGraph(json)!!
        assertNull(graph.nodes.single().doc)
    }
}
