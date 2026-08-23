import { describe, expect, it } from 'vitest'
import type { GraphEdge, GraphNode } from '../api/types'
import { buildAdjacency, filterNodesByQuery, shortNodeId, withNeighbors } from './graphUtils'

const edges: GraphEdge[] = [
    { from: 'a', to: 'b', rel: 'related' },
    { from: 'a', to: 'c', rel: 'about' },
    { from: 'b', to: 'c', rel: 'related' },
]

describe('buildAdjacency', () => {
    it('maps both directions of every edge', () => {
        const adj = buildAdjacency(edges)
        expect([...(adj.get('a') ?? [])].sort()).toEqual(['b', 'c'])
        expect([...(adj.get('b') ?? [])].sort()).toEqual(['a', 'c'])
        expect([...(adj.get('c') ?? [])].sort()).toEqual(['a', 'b'])
    })

    it('handles empty edge lists', () => {
        expect(buildAdjacency([]).size).toBe(0)
    })
})

describe('withNeighbors', () => {
    it('includes seeds and their direct neighbors', () => {
        const adj = buildAdjacency(edges)
        expect([...withNeighbors(['a'], adj)].sort()).toEqual(['a', 'b', 'c'])
    })

    it('keeps unknown seeds without failing', () => {
        const adj = buildAdjacency(edges)
        expect([...withNeighbors(['zzz'], adj)]).toEqual(['zzz'])
    })
})

describe('filterNodesByQuery', () => {
    const nodes: GraphNode[] = [
        { id: 'concept:alpha', kind: 'concept', label: 'Alpha Project' },
        { id: 'project:beta', kind: 'project', label: 'Beta' },
        { id: 'entry:alpha-2026', kind: 'entry', label: 'Daily log' },
    ]

    it('returns nothing for a blank query', () => {
        expect(filterNodesByQuery(nodes, '   ')).toEqual([])
    })

    it('matches labels case-insensitively and ranks them before id matches', () => {
        const hits = filterNodesByQuery(nodes, 'ALPHA')
        expect(hits.map((n) => n.id)).toEqual(['concept:alpha', 'entry:alpha-2026'])
    })

    it('falls back to id matches', () => {
        const hits = filterNodesByQuery(nodes, 'project:beta')
        expect(hits.map((n) => n.id)).toEqual(['project:beta'])
    })

    it('respects the limit', () => {
        expect(filterNodesByQuery(nodes, 'a', 2)).toHaveLength(2)
    })
})

describe('shortNodeId', () => {
    it('trims scheme prefixes and leaves plain ids alone', () => {
        expect(shortNodeId('concept:foo-bar')).toBe('foo-bar')
        expect(shortNodeId('plain-id')).toBe('plain-id')
    })
})