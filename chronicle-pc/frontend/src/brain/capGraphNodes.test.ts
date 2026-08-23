import { describe, expect, it } from 'vitest'
import type { BrainGraph } from '../api/types'
import { capGraphNodes } from './capGraphNodes'

function makeGraph(n: number): BrainGraph {
  const nodes = Array.from({ length: n }, (_, i) => ({
    id: `n${i}`,
    kind: i % 2 === 0 ? 'topic' : 'entry',
    label: `Node ${i}`,
    weight: i,
    pinned: i === 0,
    ts: `2026-01-${String((i % 28) + 1).padStart(2, '0')}T12:00:00Z`,
  }))
  const edges = Array.from({ length: Math.max(0, n - 1) }, (_, i) => ({
    from: `n${i}`,
    to: `n${i + 1}`,
    rel: 'related',
  }))
  // Hub: connect n0 to many for degree score
  for (let i = 2; i < Math.min(n, 20); i++) {
    edges.push({ from: 'n0', to: `n${i}`, rel: 'related' })
  }
  return { version: 1, nodes, edges }
}

describe('capGraphNodes', () => {
  it('returns all nodes when under the cap', () => {
    const graph = makeGraph(10)
    const capped = capGraphNodes(graph, 500)
    expect(capped.capped).toBe(false)
    expect(capped.nodes).toHaveLength(10)
    expect(capped.totalNodes).toBe(10)
  })

  it('caps to limit and keeps pinned nodes', () => {
    const graph = makeGraph(50)
    const capped = capGraphNodes(graph, 15)
    expect(capped.capped).toBe(true)
    expect(capped.nodes).toHaveLength(15)
    expect(capped.totalNodes).toBe(50)
    expect(capped.nodes.some((n) => n.id === 'n0' && n.pinned)).toBe(true)
    const ids = new Set(capped.nodes.map((n) => n.id))
    for (const e of capped.edges) {
      expect(ids.has(e.from)).toBe(true)
      expect(ids.has(e.to)).toBe(true)
    }
  })

  it('filters hidden nodes before capping', () => {
    const graph = makeGraph(5)
    graph.nodes[1]!.hidden = true
    const capped = capGraphNodes(graph, 500)
    expect(capped.totalNodes).toBe(4)
    expect(capped.nodes.find((n) => n.id === 'n1')).toBeUndefined()
  })
})
