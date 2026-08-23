import { useEffect, useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react'
import { drag } from 'd3-drag'
import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  type Simulation,
} from 'd3-force'
import { select } from 'd3-selection'
import { zoom, zoomIdentity, type ZoomBehavior, type ZoomTransform } from 'd3-zoom'
import type { BrainGraph, GraphEdge, GraphGroup, GraphNode } from '../api/types'
import { capGraphNodes, DEFAULT_NODE_CAP } from './capGraphNodes'
import { buildAdjacency, withNeighbors } from './graphUtils'

export interface SimNode extends GraphNode {
  x?: number
  y?: number
  vx?: number
  vy?: number
  fx?: number | null
  fy?: number | null
  index?: number
}

type SimLink = {
  source: string | SimNode
  target: string | SimNode
  from: string
  to: string
  rel: string
}

export interface ForceGraphHandle {
  zoomIn: () => void
  zoomOut: () => void
  fit: () => void
  centerOn: (id: string) => void
}

interface ForceGraphProps {
  graph: BrainGraph
  selectedIds: string[]
  highlightIds: string[]
  focusId?: string | null
  onSelect: (node: GraphNode, additive: boolean) => void
  onBackgroundClick?: () => void
  panRequest?: { id: string; nonce: number } | null
  isolatedGroup?: string | null
  onToggleGroup?: (key: string) => void
  nodeCap?: number
  ref?: Ref<ForceGraphHandle>
}

function kindColor(kind: string): string {
  const map: Record<string, string> = {
    topic: 'var(--mm-node-topic)',
    concept: 'var(--mm-node-concept)',
    entry: 'var(--mm-node-entry)',
    person: 'var(--mm-node-person)',
    place: 'var(--mm-node-place)',
    project: 'var(--mm-node-project)',
  }
  return map[kind] || 'var(--accent)'
}

/** Prefer category (group) color; fall back to kind tokens. */
function nodeColor(n: GraphNode, groups?: Record<string, GraphGroup>): string {
  const key = n.group?.trim()
  if (key && groups?.[key]?.color) return groups[key].color
  return kindColor(String(n.kind))
}

function radius(n: GraphNode): number {
  const w = n.weight ?? 1
  const isHub = n.kind !== 'entry' && Boolean(n.group)
  const base = n.kind === 'entry' ? 5 : isHub ? 9 : 7
  return base + Math.min(8, Math.sqrt(Math.max(w, 1)) * 1.4) + (n.pinned ? 2 : 0)
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s
}

/** Labels fade out below this zoom level (selected/highlighted/hub nodes keep theirs). */
const LABEL_MIN_K = 0.55

export function ForceGraph({
  graph,
  selectedIds,
  highlightIds,
  focusId,
  onSelect,
  onBackgroundClick,
  panRequest = null,
  isolatedGroup = null,
  onToggleGroup,
  nodeCap = DEFAULT_NODE_CAP,
  ref,
}: ForceGraphProps) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const gRootRef = useRef<SVGGElement | null>(null)
  const gEdgesRef = useRef<SVGGElement | null>(null)
  const gNodesRef = useRef<SVGGElement | null>(null)
  const simRef = useRef<Simulation<SimNode, SimLink> | null>(null)
  const zoomRef = useRef<ZoomBehavior<SVGSVGElement, unknown> | null>(null)
  const transformRef = useRef<ZoomTransform>(zoomIdentity)
  const nodesRef = useRef<SimNode[]>([])
  const posCacheRef = useRef(new Map<string, { x: number; y: number }>())
  const draggingRef = useRef(false)
  const hoverRef = useRef<string | null>(null)
  const tooltipRef = useRef<string | null>(null)
  const paintDynRef = useRef<() => void>(() => { })
  const selectedRef = useRef(selectedIds)
  const highlightRef = useRef(highlightIds)
  const focusRef = useRef(focusId)
  const onSelectRef = useRef(onSelect)
  const onBgRef = useRef(onBackgroundClick)
  const [size, setSize] = useState({ w: 800, h: 600 })
  const [tooltip, setTooltip] = useState<{ id: string; x: number; y: number } | null>(null)

  selectedRef.current = selectedIds
  highlightRef.current = highlightIds
  focusRef.current = focusId
  onSelectRef.current = onSelect
  onBgRef.current = onBackgroundClick

  const capped = useMemo(() => capGraphNodes(graph, nodeCap), [graph, nodeCap])
  const groups = graph.groups

  const visible = useMemo(() => {
    if (!isolatedGroup) return capped
    const nodes = capped.nodes.filter((n) => n.group === isolatedGroup)
    const ids = new Set(nodes.map((n) => n.id))
    return {
      nodes,
      edges: capped.edges.filter((e) => ids.has(e.from) && ids.has(e.to)),
      totalNodes: capped.totalNodes,
      capped: capped.capped,
    }
  }, [capped, isolatedGroup])

  const visibleById = useMemo(
    () => new Map(visible.nodes.map((n) => [n.id, n])),
    [visible.nodes],
  )

  const legend = useMemo(() => {
    if (!groups) return []
    const counts = new Map<string, number>()
    for (const n of capped.nodes) {
      if (n.group) counts.set(n.group, (counts.get(n.group) ?? 0) + 1)
    }
    return Object.entries(groups)
      .filter(([key]) => counts.has(key))
      .map(([key, def]) => ({ key, label: def.label || key, color: def.color, count: counts.get(key) ?? 0 }))
  }, [groups, capped.nodes])

  const api = useMemo<ForceGraphHandle>(
    () => ({
      zoomIn: () => {
        const s = svgRef.current
        const z = zoomRef.current
        if (s && z) select(s).call(z.scaleBy, 1.35)
      },
      zoomOut: () => {
        const s = svgRef.current
        const z = zoomRef.current
        if (s && z) select(s).call(z.scaleBy, 1 / 1.35)
      },
      fit: () => {
        const wrap = wrapRef.current
        const s = svgRef.current
        const z = zoomRef.current
        const nodes = nodesRef.current
        if (!wrap || !s || !z || nodes.length === 0) return
        const xs = nodes.map((n) => n.x ?? 0)
        const ys = nodes.map((n) => n.y ?? 0)
        const minX = Math.min(...xs)
        const maxX = Math.max(...xs)
        const minY = Math.min(...ys)
        const maxY = Math.max(...ys)
        const w = wrap.clientWidth
        const h = wrap.clientHeight
        const pad = 70
        const bw = Math.max(maxX - minX, 1)
        const bh = Math.max(maxY - minY, 1)
        const k = Math.min(Math.max(Math.min((w - pad * 2) / bw, (h - pad * 2) / bh, 2), 0.25), 4)
        const cx = (minX + maxX) / 2
        const cy = (minY + maxY) / 2
        select(s).call(z.transform, zoomIdentity.translate(w / 2, h / 2).scale(k).translate(-cx, -cy))
      },
      centerOn: (id) => {
        const wrap = wrapRef.current
        const s = svgRef.current
        const z = zoomRef.current
        const node = nodesRef.current.find((n) => n.id === id)
        if (!wrap || !s || !z || !node || node.x == null || node.y == null) return
        const w = wrap.clientWidth
        const h = wrap.clientHeight
        const k = Math.max(transformRef.current.k, 0.9)
        select(s).call(z.transform, zoomIdentity.translate(w / 2, h / 2).scale(k).translate(-node.x, -node.y))
      },
    }),
    [],
  )
  useImperativeHandle(ref, () => api, [api])

  // Mount: SVG scaffolding, zoom, resize observer — once
  useEffect(() => {
    const wrap = wrapRef.current
    const svgEl = svgRef.current
    if (!wrap || !svgEl) return

    const svg = select(svgEl)
    svg.selectAll('*').remove()

    const width = wrap.clientWidth || 800
    const height = wrap.clientHeight || 600
    setSize({ w: width, h: height })
    svg.attr('viewBox', `0 0 ${width} ${height}`)

    const gRoot = svg.append('g').attr('class', 'mm-root')
    const gEdges = gRoot.append('g').attr('class', 'mm-edges')
    const gNodes = gRoot.append('g').attr('class', 'mm-nodes')
    gRootRef.current = gRoot.node()
    gEdgesRef.current = gEdges.node()
    gNodesRef.current = gNodes.node()

    const z = zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.25, 4])
      .on('zoom', (event) => {
        transformRef.current = event.transform
        gRoot.attr('transform', event.transform.toString())
        paintDynRef.current()
        const tipId = tooltipRef.current
        if (tipId) {
          const node = nodesRef.current.find((n) => n.id === tipId)
          if (node?.x != null && node.y != null) {
            const [sx, sy] = event.transform.apply([node.x, node.y] as [number, number])
            setTooltip({ id: tipId, x: sx, y: sy })
          }
        }
      })
    zoomRef.current = z
    svg.call(z)
    svg.on('click', (event) => {
      if (event.target === svgEl) onBgRef.current?.()
    })

    const sim = forceSimulation<SimNode, SimLink>([])
      .force('charge', forceManyBody().strength(-180))
      .force('center', forceCenter(width / 2, height / 2))
      .force('collide', forceCollide<SimNode>().radius((d) => radius(d) + 8))
    simRef.current = sim

    const ro = new ResizeObserver(() => {
      setSize({ w: wrap.clientWidth, h: wrap.clientHeight })
    })
    ro.observe(wrap)

    return () => {
      ro.disconnect()
      sim.stop()
      simRef.current = null
      zoomRef.current = null
      gRootRef.current = null
      gEdgesRef.current = null
      gNodesRef.current = null
    }
  }, [])

  // Resize: update viewport + center force with a gentle reheat (keeps node positions stable)
  useEffect(() => {
    const svgEl = svgRef.current
    const sim = simRef.current
    if (!svgEl || !sim) return
    select(svgEl).attr('viewBox', `0 0 ${size.w} ${size.h}`)
    sim.force('center', forceCenter(size.w / 2, size.h / 2))
    sim.alpha(0.12).restart()
  }, [size])

  // Diff-join graph data into existing simulation / DOM (runs only when data changes)
  useEffect(() => {
    const svgEl = svgRef.current
    const gEdgesEl = gEdgesRef.current
    const gNodesEl = gNodesRef.current
    const sim = simRef.current
    if (!svgEl || !gEdgesEl || !gNodesEl || !sim) return

    const width = wrapRef.current?.clientWidth || 800
    const height = wrapRef.current?.clientHeight || 600
    const prev = new Map(nodesRef.current.map((n) => [n.id, n]))
    const posCache = posCacheRef.current
    const nodes: SimNode[] = visible.nodes.map((n) => {
      const old = prev.get(n.id)
      const cached = posCache.get(n.id)
      return {
        ...n,
        x: old?.x ?? cached?.x ?? width / 2 + (Math.random() - 0.5) * 80,
        y: old?.y ?? cached?.y ?? height / 2 + (Math.random() - 0.5) * 80,
        vx: old?.vx,
        vy: old?.vy,
      }
    })
    nodesRef.current = nodes

    const links: SimLink[] = visible.edges.map((e) => ({
      ...e,
      source: e.from,
      target: e.to,
    }))
    const adjacency = buildAdjacency(visible.edges)

    const gEdges = select(gEdgesEl)
    const gNodes = select(gNodesEl)

    const linkSel = gEdges
      .selectAll<SVGLineElement, GraphEdge>('line')
      .data(visible.edges, (d) => `${d.from}->${d.to}:${d.rel}`)
      .join(
        (enter) =>
          enter
            .append('line')
            .attr('stroke', 'var(--mm-edge)')
            .attr('stroke-width', 1.2)
            .attr('stroke-opacity', 0.85),
        (update) => update,
        (exit) => exit.remove(),
      )

    const nodeSel = gNodes
      .selectAll<SVGGElement, SimNode>('g.node')
      .data(nodes, (d) => d.id)
      .join(
        (enter) => {
          const g = enter.append('g').attr('class', 'node').style('cursor', 'pointer')
          g.append('circle').attr('class', 'pulse').attr('fill', 'var(--mm-pulse)').attr('opacity', 0)
          g.append('circle').attr('class', 'core')
          g.append('text')
            .attr('class', 'label')
            .attr('text-anchor', 'middle')
            .attr('dy', '0.35em')
            .attr('fill', 'var(--mm-label)')
            .style('font-size', '11px')
            .style('font-family', 'var(--font-sans)')
            .style('pointer-events', 'none')
            .style('paint-order', 'stroke')
            .style('stroke', 'var(--mm-label-stroke)')
            .style('stroke-width', '3px')
          return g
        },
        (update) => update,
        (exit) => exit.remove(),
      )

    // Static visual attrs — only when data changes, never per tick
    nodeSel.each(function (d) {
      const g = select(this)
      const r = radius(d)
      g.select<SVGCircleElement>('circle.core')
        .attr('r', r)
        .attr('fill', nodeColor(d, groups))
        .attr('opacity', d.pinned || d.group ? 1 : 0.92)
      g.select<SVGCircleElement>('circle.pulse').attr('r', r)
      g.select<SVGTextElement>('text.label')
        .attr('y', r + 12)
        .text(truncate(d.label || d.id, 22))
    })

    function setHover(id: string | null) {
      hoverRef.current = id
      tooltipRef.current = id
      if (!id) {
        setTooltip(null)
      } else {
        const node = nodesRef.current.find((n) => n.id === id)
        if (node?.x == null || node.y == null) {
          setTooltip(null)
        } else {
          const [sx, sy] = transformRef.current.apply([node.x, node.y] as [number, number])
          setTooltip({ id, x: sx, y: sy })
        }
      }
      paintDynRef.current()
    }

    nodeSel
      .on('click', (event, d) => {
        event.stopPropagation()
        onSelectRef.current(d, event.metaKey || event.shiftKey || event.ctrlKey)
      })
      .on('mouseover', (_event, d) => {
        if (!draggingRef.current) setHover(d.id)
      })
      .on('mouseout', () => setHover(null))
      .call(
        drag<SVGGElement, SimNode>()
          .on('start', (event, d) => {
            draggingRef.current = true
            setHover(null)
            if (!event.active) sim.alphaTarget(0.25).restart()
            d.fx = d.x
            d.fy = d.y
          })
          .on('drag', (event, d) => {
            d.fx = event.x
            d.fy = event.y
          })
          .on('end', (event, d) => {
            draggingRef.current = false
            if (!event.active) sim.alphaTarget(0)
            d.fx = null
            d.fy = null
          }),
      )

    function paintDynamic() {
      const selected = new Set(selectedRef.current)
      const highlights = new Set(highlightRef.current)
      const focus = focusRef.current
      const hover = hoverRef.current
      const k = transformRef.current.k

      let focusSet: Set<string> | null = null
      if (hover) focusSet = withNeighbors([hover], adjacency)
      else if (selected.size) focusSet = withNeighbors(selected, adjacency)

      nodeSel.each(function (d) {
        const g = select(this)
        const isSel = selected.has(d.id)
        const isHi = highlights.has(d.id)
        const isFocus = focus === d.id
        const isHover = hover === d.id
        const isHub = d.kind !== 'entry' && Boolean(d.group)
        g.select<SVGCircleElement>('circle.core')
          .attr('stroke', isSel || isFocus || isHover ? 'var(--mm-highlight)' : 'var(--glass-edge)')
          .attr('stroke-width', isSel || isFocus ? 2.5 : isHover ? 2 : 1)
        g.select<SVGCircleElement>('circle.pulse')
          .attr('opacity', isHi ? 0.55 : 0)
          .style('animation', isHi ? 'pulse-ring 1.4s ease-out infinite' : 'none')
        g.select<SVGTextElement>('text.label')
          .attr('font-weight', isSel || isFocus || isHover ? '600' : '400')
          .attr(
            'display',
            k < LABEL_MIN_K && !isSel && !isHi && !isFocus && !isHover && !isHub ? 'none' : null,
          )
        g.attr('opacity', focusSet && !focusSet.has(d.id) ? 0.15 : 1)
      })

      linkSel
        .attr('stroke', (d) =>
          selected.has(d.from) ||
            selected.has(d.to) ||
            highlights.has(d.from) ||
            highlights.has(d.to) ||
            hover === d.from ||
            hover === d.to
            ? 'var(--mm-edge-strong)'
            : 'var(--mm-edge)',
        )
        .attr('stroke-width', (d) => (selected.has(d.from) || selected.has(d.to) ? 2 : 1.2))
        .attr('stroke-opacity', (d) =>
          focusSet && !(focusSet.has(d.from) && focusSet.has(d.to)) ? 0.06 : 0.85,
        )
    }
    paintDynRef.current = paintDynamic

    sim.nodes(nodes)
    sim.force(
      'link',
      forceLink<SimNode, SimLink>(links)
        .id((d) => d.id)
        .distance(70)
        .strength(0.45),
    )
    sim.on('tick', () => {
      const byId = new Map(nodes.map((n) => [n.id, n]))
      linkSel
        .attr('x1', (d) => byId.get(d.from)?.x ?? 0)
        .attr('y1', (d) => byId.get(d.from)?.y ?? 0)
        .attr('x2', (d) => byId.get(d.to)?.x ?? 0)
        .attr('y2', (d) => byId.get(d.to)?.y ?? 0)
      nodeSel.attr('transform', (d) => `translate(${d.x ?? 0},${d.y ?? 0})`)
      for (const n of nodes) {
        if (n.x != null && n.y != null) posCache.set(n.id, { x: n.x, y: n.y })
      }
    })
    sim.alpha(0.4).restart()
    paintDynamic()
  }, [visible, groups])

  // Repaint dynamic state when selection/highlight/focus changes (no data re-join)
  useEffect(() => {
    paintDynRef.current()
  }, [selectedIds, highlightIds, focusId])

  // Pan-to-node requests (nonce makes repeat requests re-fire)
  useEffect(() => {
    if (!panRequest) return
    api.centerOn(panRequest.id)
  }, [panRequest, api])

  const tooltipNode = tooltip ? (visibleById.get(tooltip.id) ?? null) : null
  const tooltipGroup = tooltipNode?.group ? groups?.[tooltipNode.group] : undefined

  return (
    <div className="force-graph" ref={wrapRef}>
      {capped.capped ? (
        <div className="graph-cap-banner muted" role="status">
          Showing top {visible.nodes.length} of {capped.totalNodes} nodes
        </div>
      ) : null}
      {legend.length > 0 ? (
        <ul className="graph-group-legend" aria-label="Categories">
          {legend.map((g) => (
            <li key={g.key}>
              <button
                type="button"
                className={`graph-group-btn${isolatedGroup === g.key ? ' active' : ''}`}
                onClick={() => onToggleGroup?.(g.key)}
                aria-pressed={isolatedGroup === g.key}
                title={isolatedGroup === g.key ? 'Show all categories' : `Isolate ${g.label}`}
              >
                <span className="graph-group-swatch" style={{ background: g.color }} />
                <span className="graph-group-label">{g.label}</span>
                <span className="graph-group-count muted">{g.count}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <svg ref={svgRef} className="force-graph-svg" role="img" aria-label="Brain graph" />
      {tooltipNode && tooltip ? (
        <div
          className="graph-tooltip"
          style={{
            left: Math.max(8, Math.min(tooltip.x + 14, size.w - 252)),
            top: Math.max(8, Math.min(tooltip.y + 14, size.h - 130)),
          }}
          role="tooltip"
        >
          <p className="graph-tooltip-kind muted">
            {tooltipNode.kind}
            {tooltipGroup ? ` · ${tooltipGroup.label}` : ''}
          </p>
          <p className="graph-tooltip-label">{tooltipNode.label || tooltipNode.id}</p>
          {tooltipNode.annotation ? (
            <p className="graph-tooltip-note muted">{tooltipNode.annotation}</p>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}