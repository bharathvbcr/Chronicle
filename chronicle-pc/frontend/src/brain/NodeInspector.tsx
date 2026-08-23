import { useMemo, useState } from 'react'
import { api } from '../api/client'
import type { BrainGraph, CurationOp, GraphNode } from '../api/types'
import { filterNodesByQuery, shortNodeId } from './graphUtils'

interface NodeInspectorProps {
  node: GraphNode | null
  graph: BrainGraph
  insights: Record<string, unknown>[]
  onOpenEntry: (entryId: string) => void
  onOpenNote: (path: string) => void
  onSelectNode: (id: string) => void
  onGraphChange: () => void
  onClose: () => void
}

export function NodeInspector({
  node,
  graph,
  insights,
  onOpenEntry,
  onOpenNote,
  onSelectNode,
  onGraphChange,
  onClose,
}: NodeInspectorProps) {
  const [busy, setBusy] = useState(false)
  const [mergeQuery, setMergeQuery] = useState('')
  const [mergePick, setMergePick] = useState<GraphNode | null>(null)
  const [mergeOpen, setMergeOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const neighbors = useMemo(() => {
    if (!node) return []
    const byId = new Map(graph.nodes.map((n) => [n.id, n]))
    const out: { node: GraphNode; rel: string }[] = []
    for (const e of graph.edges) {
      if (e.from === node.id && byId.has(e.to)) out.push({ node: byId.get(e.to)!, rel: e.rel })
      if (e.to === node.id && byId.has(e.from)) out.push({ node: byId.get(e.from)!, rel: e.rel })
    }
    return out
  }, [node, graph])

  const relatedInsights = useMemo(() => {
    if (!node) return []
    const label = (node.label || '').toLowerCase()
    return insights
      .filter((ins) => {
        const text = JSON.stringify(ins).toLowerCase()
        return label && text.includes(label)
      })
      .slice(0, 5)
  }, [node, insights])

  const mergeCandidates = useMemo(() => {
    if (!node) return []
    return filterNodesByQuery(
      graph.nodes.filter((n) => n.id !== node.id),
      mergeQuery,
      6,
    )
  }, [graph.nodes, mergeQuery, node])

  if (!node) {
    return (
      <aside className="node-inspector glass empty">
        <p className="muted">Select a node to inspect links, insights, and curation.</p>
      </aside>
    )
  }

  async function runOp(op: CurationOp) {
    setBusy(true)
    setError(null)
    try {
      await api.curation.post(op)
      onGraphChange()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const entryId = node.entry_id || (node.id.startsWith('entry:') ? node.id.slice(6) : null)

  const groupDef = node.group ? graph.groups?.[node.group] : undefined

  return (
    <aside className="node-inspector glass">
      <header>
        <div>
          <p className="kind muted">
            <span
              className={`kind-dot kind-${String(node.kind)}`}
              style={groupDef ? { background: groupDef.color } : undefined}
              aria-hidden="true"
            />
            {node.kind} · {neighbors.length} {neighbors.length === 1 ? 'link' : 'links'}
            {groupDef ? (
              <>
                {' · '}
                <span className="group-chip">
                  <span className="group-swatch" style={{ background: groupDef.color }} />
                  {groupDef.label}
                </span>
              </>
            ) : null}
          </p>
          <h3 className="serif">{node.label || node.id}</h3>
          <code className="node-id">{node.id}</code>
        </div>
        <button type="button" className="btn ghost" onClick={onClose} aria-label="Close inspector">
          ×
        </button>
      </header>

      {node.annotation ? <p className="annotation">{node.annotation}</p> : null}

      <div className="curation-row">
        <button
          type="button"
          className="btn"
          disabled={busy}
          title={node.pinned ? 'Remove the pin' : 'Pin to keep this node in the graph'}
          onClick={() => void runOp({ op: node.pinned ? 'unpin' : 'pin', node: node.id })}
        >
          {node.pinned ? 'Unpin' : 'Pin'}
        </button>
        <button
          type="button"
          className="btn"
          disabled={busy}
          title={node.hidden ? 'Show this node again' : 'Hide this node from the graph'}
          onClick={() => void runOp({ op: node.hidden ? 'unhide' : 'hide', node: node.id })}
        >
          {node.hidden ? 'Unhide' : 'Hide'}
        </button>
      </div>

      <div className="merge-row">
        <div className="merge-combo">
          <input
            className="field"
            placeholder="Merge into node…"
            value={mergePick ? mergePick.label || mergePick.id : mergeQuery}
            onChange={(e) => {
              setMergePick(null)
              setMergeQuery(e.target.value)
              setMergeOpen(true)
            }}
            onFocus={() => setMergeOpen(true)}
            onBlur={() => window.setTimeout(() => setMergeOpen(false), 120)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                e.stopPropagation()
                setMergeOpen(false)
              }
            }}
            aria-label="Merge into node"
          />
          {mergeOpen && mergeQuery.trim() && !mergePick ? (
            <ul className="merge-list glass" role="listbox" aria-label="Merge candidates">
              {mergeCandidates.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={false}
                    onMouseDown={(e) => {
                      e.preventDefault()
                      setMergePick(n)
                      setMergeOpen(false)
                    }}
                  >
                    <span className={`node-search-dot kind-${String(n.kind)}`} aria-hidden="true" />
                    <span className="merge-candidate-label">{n.label || n.id}</span>
                    <span className="merge-candidate-id muted">{shortNodeId(n.id)}</span>
                  </button>
                </li>
              ))}
              {mergeCandidates.length === 0 ? (
                <li className="merge-empty muted">No matches</li>
              ) : null}
            </ul>
          ) : null}
        </div>
        <button
          type="button"
          className="btn"
          disabled={busy || !mergePick}
          title="Merge this node into the chosen node"
          onClick={() => {
            const target = mergePick
            setMergePick(null)
            setMergeQuery('')
            if (target) void runOp({ op: 'merge', from: node.id, into: target.id })
          }}
        >
          Merge
        </button>
      </div>

      {error ? <p style={{ color: 'var(--danger)', fontSize: '0.8rem' }}>{error}</p> : null}

      <section>
        <h4>Sources</h4>
        <ul className="link-list">
          {entryId ? (
            <li>
              <button type="button" onClick={() => onOpenEntry(entryId)}>
                Entry {entryId}
              </button>
            </li>
          ) : null}
          {node.doc ? (
            <li>
              <button type="button" onClick={() => onOpenNote(node.doc!)}>
                {node.doc}
              </button>
            </li>
          ) : null}
          {!entryId && !node.doc ? <li className="muted">No linked entry/note</li> : null}
        </ul>
      </section>

      <section>
        <h4>Linked</h4>
        <ul className="link-list">
          {neighbors.map(({ node: n, rel }) => (
            <li key={`${n.id}-${rel}`}>
              <button
                type="button"
                className="neighbor-link"
                onClick={() => onSelectNode(n.id)}
                title={`Select ${n.label || n.id} on the map`}
              >
                <span className="neighbor-rel muted">{rel}</span>
                <span className={`node-search-dot kind-${String(n.kind)}`} aria-hidden="true" />
                {n.label || n.id}
              </button>
            </li>
          ))}
          {neighbors.length === 0 ? <li className="muted">No edges</li> : null}
        </ul>
      </section>

      <section>
        <h4>Insights</h4>
        <ul className="link-list">
          {relatedInsights.map((ins, i) => (
            <li key={i} className="insight-snip">
              {String(
                (ins as { summary?: string }).summary ||
                (ins as { date?: string }).date ||
                JSON.stringify(ins).slice(0, 120),
              )}
            </li>
          ))}
          {relatedInsights.length === 0 ? <li className="muted">No matching insights</li> : null}
        </ul>
      </section>
    </aside>
  )
}