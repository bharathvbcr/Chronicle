import type { BrainGraph, GraphEdge, GraphNode } from '../api/types'

export const DEFAULT_NODE_CAP = 500

export interface CappedGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  totalNodes: number
  capped: boolean
}

/** Score by degree (primary) then recency (ts), keep pinned, cap rendered nodes. */
export function capGraphNodes(
  graph: BrainGraph,
  limit: number = DEFAULT_NODE_CAP,
): CappedGraph {
  const all = (graph.nodes || []).filter((n) => !n.hidden)
  const totalNodes = all.length
  if (totalNodes <= limit) {
    const ids = new Set(all.map((n) => n.id))
    const edges = (graph.edges || []).filter((e) => ids.has(e.from) && ids.has(e.to))
    return { nodes: all, edges, totalNodes, capped: false }
  }

  const degree = new Map<string, number>()
  for (const e of graph.edges || []) {
    degree.set(e.from, (degree.get(e.from) || 0) + 1)
    degree.set(e.to, (degree.get(e.to) || 0) + 1)
  }

  const score = (n: GraphNode): number => {
    const deg = degree.get(n.id) || 0
    const ts = n.ts ? Date.parse(n.ts) : 0
    const recency = Number.isFinite(ts) ? ts / 1e13 : 0
    return deg * 1000 + recency + (n.pinned ? 1e6 : 0) + (n.weight ?? 0)
  }

  const pinned = all.filter((n) => n.pinned)
  const rest = all
    .filter((n) => !n.pinned)
    .sort((a, b) => score(b) - score(a))
  const kept = [...pinned, ...rest].slice(0, limit)
  const ids = new Set(kept.map((n) => n.id))
  const edges = (graph.edges || []).filter((e) => ids.has(e.from) && ids.has(e.to))
  return { nodes: kept, edges, totalNodes, capped: true }
}
