import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client'
import { onVaultChanged } from '../lib/vaultBus'
import type { BrainGraph, Citation, Entry, GraphNode } from '../api/types'
import { ForceGraph, type ForceGraphHandle } from '../brain/ForceGraph'
import { NodeInspector } from '../brain/NodeInspector'
import { NodeSearch } from '../brain/NodeSearch'
import { RecallChat } from '../brain/RecallChat'
import { AppDialog } from '../components/AppDialog'
import { IconFit, IconX, IconZoomIn, IconZoomOut } from '../components/icons'
import { StatusPane } from '../components/StatusPane'
import { citationPathForOpen } from '../notes/notesRouting'
import './BrainView.css'

interface BrainViewProps {
  onOpenEntry: (entry: Entry) => void
  onOpenNote: (path: string) => void
}

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

export function BrainView({ onOpenEntry, onOpenNote }: BrainViewProps) {
  const [graph, setGraph] = useState<BrainGraph>({ version: 1, nodes: [], edges: [] })
  const [reloadKey, setReloadKey] = useState(0)
  const [insights, setInsights] = useState<Record<string, unknown>[]>([])
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [highlightIds, setHighlightIds] = useState<string[]>([])
  const [panRequest, setPanRequest] = useState<{ id: string; nonce: number } | null>(null)
  const [isolatedGroup, setIsolatedGroup] = useState<string | null>(null)
  const [sideTab, setSideTab] = useState<'chat' | 'inspector'>('chat')
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(true)
  const [building, setBuilding] = useState(false)
  const [pinPrompt, setPinPrompt] = useState<{
    answer: string
    seedIds: string[]
    defaultLabel: string
  } | null>(null)
  const graphRef = useRef<ForceGraphHandle>(null)

  /** Reload graph + insights. Resolves true when the graph has nodes. */
  const load = useCallback(async (signal?: AbortSignal): Promise<boolean> => {
    setError(null)
    setLoading(true)
    try {
      const [g, ins] = await Promise.all([
        api.brain.graph({ signal }),
        api.brain.insights({ limit: 30 }, { signal }),
      ])
      if (signal?.aborted) return false
      setGraph(g)
      setInsights(ins.insights || [])
      return (g.nodes || []).length > 0
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return false
      setError(e instanceof Error ? e.message : String(e))
      return false
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    return () => ac.abort()
  }, [load, reloadKey])

  // Live updates (v1.11): refetch when the SSE stream or an E2EE flip signals
  // a vault change. 'manual' (own edits) is excluded to avoid double-loads.
  useEffect(
    () =>
      onVaultChanged((reason) => {
        if (reason === 'sse' || reason === 'e2ee') setReloadKey((k) => k + 1)
      }),
    [],
  )

  const byId = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph.nodes])
  const seedNodes = selectedIds.map((id) => byId.get(id)).filter(Boolean) as GraphNode[]
  const focusNode = selectedIds.length ? byId.get(selectedIds[selectedIds.length - 1]) || null : null

  const requestPan = useCallback((id: string) => {
    setPanRequest({ id, nonce: Date.now() })
  }, [])

  const selectNode = useCallback(
    (id: string) => {
      setSelectedIds([id])
      requestPan(id)
    },
    [requestPan],
  )

  const clearSelection = useCallback(() => {
    setSelectedIds([])
    setHighlightIds([])
  }, [])

  // Jump to the Inspector tab whenever a node gains focus
  const lastFocusRef = useRef<string | null>(null)
  useEffect(() => {
    const id = focusNode?.id ?? null
    if (id && lastFocusRef.current !== id) setSideTab('inspector')
    lastFocusRef.current = id
  }, [focusNode])

  // Esc clears selection + highlights (unless typing or an overlay is open)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || isTypingTarget(e.target)) return
      if (document.querySelector('.overlay.open')) return
      clearSelection()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [clearSelection])

  function onSelect(node: GraphNode, additive: boolean) {
    setSelectedIds((prev) => {
      if (additive) {
        return prev.includes(node.id) ? prev.filter((id) => id !== node.id) : [...prev, node.id]
      }
      return [node.id]
    })
  }

  async function openCitation(citation: Citation) {
    const nodeId = citation.node_ids?.[0]
    if (nodeId) {
      setHighlightIds((h) => [...new Set([...h, ...citation.node_ids])])
      setSelectedIds([nodeId])
      requestPan(nodeId)
    }

    if (citation.kind === 'entry') {
      try {
        const entry = await api.entries.get(citation.id)
        onOpenEntry(entry)
      } catch {
        setStatus(`Could not open entry ${citation.id}`)
      }
      return
    }

    const path = citation.path || citation.id
    const normalized = citationPathForOpen(path, citation.kind)
    if (normalized) onOpenNote(normalized)
    else setStatus(`Cannot open ${path}`)
  }

  async function pinAsConcept(label: string, seedIds: string[]) {
    if (!label.trim()) return
    const slug = label
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .slice(0, 48)
    const id = `concept:${slug || Date.now()}`
    setStatus('Pinning concept…')
    try {
      await api.curation.post({ op: 'create_concept', id, label: label.trim() })
      for (const seed of seedIds.slice(0, 8)) {
        await api.curation.post({
          op: 'link',
          from: id,
          to: seed,
          rel: 'related',
        })
      }
      setGraph((g) => ({
        ...g,
        nodes: [...g.nodes, { id, kind: 'concept', label: label.trim(), pinned: true }],
        edges: [
          ...g.edges,
          ...seedIds.slice(0, 8).map((to) => ({ from: id, to, rel: 'related' })),
        ],
      }))
      setSelectedIds([id])
      setHighlightIds([id, ...seedIds])
      requestPan(id)
      setStatus(`Pinned ${id}`)
      void load()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setStatus('')
    }
  }

  async function openEntryId(entryId: string) {
    try {
      const entry = await api.entries.get(entryId)
      onOpenEntry(entry)
    } catch (e) {
      setStatus(e instanceof Error ? e.message : String(e))
    }
  }

  /** Trigger a server-side brain build, then poll until the graph appears. */
  async function buildGraph() {
    if (building) return
    setBuilding(true)
    setStatus('')
    try {
      await api.process({ run_brain: true })
      for (let i = 0; i < 8; i++) {
        await new Promise((r) => window.setTimeout(r, 4000))
        if (await load()) return
      }
      setStatus('Build is still running — refresh in a bit.')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBuilding(false)
    }
  }

  const emptyGraph = !loading && !error && graph.nodes.length === 0
  const busy = loading || building

  return (
    <div className="brain">
      <div className="brain-canvas glass">
        {status ? <div className="brain-banner">{status}</div> : null}
        {busy || emptyGraph || error ? (
          <div className="brain-status">
            <StatusPane
              loading={busy}
              loadingMessage={building ? 'Building graph — this can take a minute…' : 'Loading…'}
              error={error}
              empty={emptyGraph && !building}
              emptyMessage="No graph yet — build it from your journal and knowledge base."
              emptyAction={{ label: 'Build graph', onClick: () => void buildGraph() }}
              onRetry={() => void load()}
              className="pad"
            />
          </div>
        ) : (
          <>
            <ForceGraph
              ref={graphRef}
              graph={graph}
              selectedIds={selectedIds}
              highlightIds={highlightIds}
              focusId={focusNode?.id}
              onSelect={onSelect}
              onBackgroundClick={clearSelection}
              panRequest={panRequest}
              isolatedGroup={isolatedGroup}
              onToggleGroup={(key) => setIsolatedGroup((g) => (g === key ? null : key))}
            />
            <div className="brain-toolbar">
              <NodeSearch
                nodes={graph.nodes.filter((n) => !n.hidden)}
                onPick={(n) => selectNode(n.id)}
              />
              <div className="brain-toolbar-row">
                <button
                  type="button"
                  className="btn ghost brain-tool"
                  onClick={() => graphRef.current?.zoomOut()}
                  title="Zoom out"
                  aria-label="Zoom out"
                >
                  <IconZoomOut />
                </button>
                <button
                  type="button"
                  className="btn ghost brain-tool"
                  onClick={() => graphRef.current?.zoomIn()}
                  title="Zoom in"
                  aria-label="Zoom in"
                >
                  <IconZoomIn />
                </button>
                <button
                  type="button"
                  className="btn ghost brain-tool"
                  onClick={() => graphRef.current?.fit()}
                  title="Fit graph to view"
                  aria-label="Fit graph to view"
                >
                  <IconFit />
                </button>
                {selectedIds.length > 0 ? (
                  <button
                    type="button"
                    className="btn ghost brain-tool"
                    onClick={clearSelection}
                    title="Clear selection (Esc)"
                    aria-label="Clear selection"
                  >
                    <IconX />
                  </button>
                ) : null}
              </div>
              <span className="brain-stats muted">
                {graph.nodes.length} nodes · {graph.edges.length} edges
              </span>
            </div>
          </>
        )}
        <div className="brain-hint muted">
          Click to seed recall · ⌘/Shift-click multi-select · drag nodes · scroll zoom ·{' '}
          <kbd>Esc</kbd> clear
        </div>
      </div>

      <div className="brain-side">
        <div className="brain-tabs glass" role="tablist" aria-label="Brain panel">
          <button
            type="button"
            role="tab"
            aria-selected={sideTab === 'chat'}
            className={`brain-tab${sideTab === 'chat' ? ' active' : ''}`}
            onClick={() => setSideTab('chat')}
          >
            Recall
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={sideTab === 'inspector'}
            className={`brain-tab${sideTab === 'inspector' ? ' active' : ''}`}
            onClick={() => setSideTab('inspector')}
          >
            Inspector
            {focusNode ? <span className="brain-tab-dot" aria-hidden="true" /> : null}
          </button>
        </div>
        <div className="brain-side-panes">
          <div
            role="tabpanel"
            aria-label="Recall"
            className={`brain-pane${sideTab === 'chat' ? '' : ' hidden'}`}
          >
            <RecallChat
              seedNodes={seedNodes}
              onRemoveSeed={(id) => setSelectedIds((prev) => prev.filter((x) => x !== id))}
              onCitationClick={(c) => void openCitation(c)}
              onHighlight={setHighlightIds}
              onPinConcept={(answer, seeds) =>
                setPinPrompt({
                  answer,
                  seedIds: seeds,
                  defaultLabel: answer.slice(0, 48).replace(/\s+/g, ' ').trim(),
                })
              }
            />
          </div>
          <div
            role="tabpanel"
            aria-label="Inspector"
            className={`brain-pane${sideTab === 'inspector' ? '' : ' hidden'}`}
          >
            <NodeInspector
              node={focusNode}
              graph={graph}
              insights={insights}
              onOpenEntry={(id) => void openEntryId(id)}
              onOpenNote={onOpenNote}
              onSelectNode={selectNode}
              onGraphChange={() => void load()}
              onClose={clearSelection}
            />
          </div>
        </div>
      </div>

      <AppDialog
        open={Boolean(pinPrompt)}
        title="Pin as concept"
        message="Label for the new concept node"
        confirmLabel="Pin"
        prompt={{ defaultValue: pinPrompt?.defaultLabel ?? '', placeholder: 'Concept label' }}
        onCancel={() => setPinPrompt(null)}
        onConfirm={(label) => {
          const pending = pinPrompt
          setPinPrompt(null)
          if (pending && label?.trim()) {
            void pinAsConcept(label, pending.seedIds)
          }
        }}
      />
    </div>
  )
}