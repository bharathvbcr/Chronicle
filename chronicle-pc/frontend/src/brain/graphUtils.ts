import type { GraphEdge, GraphNode } from '../api/types'

/** Build an undirected adjacency map: node id -> set of neighbor ids. */
export function buildAdjacency(edges: GraphEdge[]): Map<string, Set<string>> {
    const adj = new Map<string, Set<string>>()
    for (const e of edges) {
        let fromSet = adj.get(e.from)
        if (!fromSet) {
            fromSet = new Set()
            adj.set(e.from, fromSet)
        }
        fromSet.add(e.to)

        let toSet = adj.get(e.to)
        if (!toSet) {
            toSet = new Set()
            adj.set(e.to, toSet)
        }
        toSet.add(e.from)
    }
    return adj
}

/** Expand seed ids to include their direct neighbors. Unknown ids are kept. */
export function withNeighbors(
    seeds: Iterable<string>,
    adj: Map<string, Set<string>>,
): Set<string> {
    const out = new Set<string>()
    for (const id of seeds) {
        out.add(id)
        const ns = adj.get(id)
        if (ns) for (const n of ns) out.add(n)
    }
    return out
}

/**
 * Case-insensitive substring search over node label then id.
 * Label matches rank before id-only matches. Returns at most `limit` nodes.
 */
export function filterNodesByQuery(
    nodes: GraphNode[],
    query: string,
    limit = 8,
): GraphNode[] {
    const q = query.trim().toLowerCase()
    if (!q) return []
    const labelHits: GraphNode[] = []
    const idHits: GraphNode[] = []
    for (const n of nodes) {
        if ((n.label || '').toLowerCase().includes(q)) {
            labelHits.push(n)
        } else if (n.id.toLowerCase().includes(q)) {
            idHits.push(n)
        }
    }
    return [...labelHits, ...idHits].slice(0, limit)
}

/** Trim a known scheme prefix (entry:, concept:, …) for compact display. */
export function shortNodeId(id: string): string {
    return id.replace(/^[a-z-]+:/i, '')
}