package com.chronicle.app.brain

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lightweight force-directed layout mirroring Mac d3-force params:
 * charge -180, link distance 70 / strength 0.45, collide radius+8, center.
 *
 * Runs to cooling (not a live sim) so Compose Canvas stays battery-friendly.
 */
data class SimNode(
    val id: String,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val radius: Float,
    /** When set, node stays pinned at (fx, fy) during simulation (d3-force style). */
    var fx: Float? = null,
    var fy: Float? = null,
)

data class SimLink(
    val source: String,
    val target: String,
)

/** Node radius matching Mac ForceGraph.tsx. */
fun forceNodeRadius(n: GraphNode): Float {
    val w = n.weight.coerceAtLeast(1.0)
    val isHub = n.kind != "entry" && !n.group.isNullOrBlank()
    val base = when {
        n.kind == "entry" -> 5f
        isHub -> 9f
        else -> 7f
    }
    return base + minOf(8.0, sqrt(w) * 1.4).toFloat() + if (n.pinned) 2f else 0f
}

fun layoutForce(
    nodes: List<SimNode>,
    links: List<SimLink>,
    width: Float,
    height: Float,
    chargeStrength: Float = -180f,
    linkDistance: Float = 70f,
    linkStrength: Float = 0.45f,
    collidePadding: Float = 8f,
    iterations: Int = 280,
    alphaDecay: Float = 0.028f,
    velocityDecay: Float = 0.4f,
    previous: Map<String, Offset> = emptyMap(),
    seed: Long = 42L,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    if (nodes.size == 1) {
        val n = nodes.first()
        return mapOf(n.id to Offset(width / 2f, height / 2f))
    }

    val cx = width / 2f
    val cy = height / 2f
    val rng = Random(seed)
    val byId = nodes.associateBy { it.id }

    for (n in nodes) {
        val pinnedX = n.fx
        val pinnedY = n.fy
        if (pinnedX != null && pinnedY != null) {
            n.x = pinnedX
            n.y = pinnedY
        } else {
            val prev = previous[n.id]
            if (prev != null) {
                n.x = prev.x
                n.y = prev.y
            } else {
                n.x = cx + (rng.nextFloat() - 0.5f) * 80f
                n.y = cy + (rng.nextFloat() - 0.5f) * 80f
            }
        }
        n.vx = 0f
        n.vy = 0f
    }

    val resolvedLinks = links.mapNotNull { link ->
        val a = byId[link.source] ?: return@mapNotNull null
        val b = byId[link.target] ?: return@mapNotNull null
        if (a === b) return@mapNotNull null
        a to b
    }

    var alpha = 1f
    repeat(iterations) {
        if (alpha < 0.001f) return@repeat

        // Many-body (pairwise repulsion) — O(n²) fine for ≤500 capped nodes
        val nCount = nodes.size
        for (i in 0 until nCount) {
            val a = nodes[i]
            for (j in i + 1 until nCount) {
                val b = nodes[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                var dist2 = dx * dx + dy * dy
                if (dist2 < 0.01f) {
                    dx = (rng.nextFloat() - 0.5f) * 0.1f
                    dy = (rng.nextFloat() - 0.5f) * 0.1f
                    dist2 = dx * dx + dy * dy
                }
                val dist = sqrt(dist2)
                val force = alpha * chargeStrength / dist2
                val fx = dx / dist * force
                val fy = dy / dist * force
                a.vx -= fx
                a.vy -= fy
                b.vx += fx
                b.vy += fy
            }
        }

        // Links (springs)
        for ((a, b) in resolvedLinks) {
            var dx = b.x - a.x
            var dy = b.y - a.y
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 0.01f) {
                dx = 0.01f
                dy = 0f
                dist = 0.01f
            }
            val force = alpha * linkStrength * (dist - linkDistance) / dist
            val fx = dx * force
            val fy = dy * force
            a.vx += fx
            a.vy += fy
            b.vx -= fx
            b.vy -= fy
        }

        // Collide
        for (i in 0 until nCount) {
            val a = nodes[i]
            for (j in i + 1 until nCount) {
                val b = nodes[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                var dist = sqrt(dx * dx + dy * dy)
                val minDist = a.radius + b.radius + collidePadding
                if (dist < 0.01f) {
                    dx = (rng.nextFloat() - 0.5f)
                    dy = (rng.nextFloat() - 0.5f)
                    dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                }
                if (dist < minDist) {
                    val overlap = (minDist - dist) / dist * 0.5f * alpha
                    val fx = dx * overlap
                    val fy = dy * overlap
                    a.vx -= fx
                    a.vy -= fy
                    b.vx += fx
                    b.vy += fy
                }
            }
        }

        // Center pull + integrate (skip velocity for pinned nodes)
        val centerStrength = 0.05f * alpha
        for (n in nodes) {
            val pinnedX = n.fx
            val pinnedY = n.fy
            if (pinnedX != null && pinnedY != null) {
                n.x = pinnedX
                n.y = pinnedY
                n.vx = 0f
                n.vy = 0f
                continue
            }
            n.vx += (cx - n.x) * centerStrength
            n.vy += (cy - n.y) * centerStrength
            n.vx *= velocityDecay
            n.vy *= velocityDecay
            n.x += n.vx
            n.y += n.vy
        }

        alpha *= (1f - alphaDecay)
    }

    return nodes.associate { it.id to Offset(it.x, it.y) }
}
