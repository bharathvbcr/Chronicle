package com.chronicle.app.brain

/** Default max nodes rendered on the mind map (Mac ForceGraph parity). */
const val DEFAULT_NODE_CAP = 500

data class CappedGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val totalNodes: Int,
    val capped: Boolean,
)

/**
 * Score by degree (primary) then recency (ts), keep pinned, cap rendered nodes.
 * Mirrors chronicle-pc/frontend/src/brain/capGraphNodes.ts.
 */
fun capGraphNodes(
    graph: BrainGraph,
    limit: Int = DEFAULT_NODE_CAP,
): CappedGraph {
    val all = graph.nodes.filter { !it.hidden }
    val totalNodes = all.size
    if (totalNodes <= limit) {
        val ids = all.map { it.id }.toSet()
        val edges = graph.edges.filter { it.from in ids && it.to in ids }
        return CappedGraph(all, edges, totalNodes, capped = false)
    }

    val degree = mutableMapOf<String, Int>()
    for (e in graph.edges) {
        degree[e.from] = (degree[e.from] ?: 0) + 1
        degree[e.to] = (degree[e.to] ?: 0) + 1
    }

    fun score(n: GraphNode): Double {
        val deg = degree[n.id] ?: 0
        val ts = n.ts?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        val recency = if (ts > 0) ts / 1e13 else 0.0
        return deg * 1000.0 + recency + (if (n.pinned) 1e6 else 0.0) + n.weight
    }

    val pinned = all.filter { it.pinned }
    val rest = all.filter { !it.pinned }.sortedByDescending { score(it) }
    val kept = (pinned + rest).take(limit)
    val ids = kept.map { it.id }.toSet()
    val edges = graph.edges.filter { it.from in ids && it.to in ids }
    return CappedGraph(kept, edges, totalNodes, capped = true)
}
