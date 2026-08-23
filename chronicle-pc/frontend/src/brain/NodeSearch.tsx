import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { GraphNode } from '../api/types'
import { IconSearch } from '../components/icons'
import { filterNodesByQuery } from './graphUtils'

function highlightMatch(text: string, query: string): ReactNode {
    const q = query.trim()
    if (!q || !text) return text
    const idx = text.toLowerCase().indexOf(q.toLowerCase())
    if (idx < 0) return text
    return (
        <>
            {text.slice(0, idx)}
            <mark className="search-mark">{text.slice(idx, idx + q.length)}</mark>
            {text.slice(idx + q.length)}
        </>
    )
}

interface NodeSearchProps {
    nodes: GraphNode[]
    onPick: (node: GraphNode) => void
}

/** Canvas-overlaid combobox: find a graph node by label/id and jump to it. */
export function NodeSearch({ nodes, onPick }: NodeSearchProps) {
    const [query, setQuery] = useState('')
    const [open, setOpen] = useState(false)
    const [active, setActive] = useState(0)
    const inputRef = useRef<HTMLInputElement>(null)
    const hits = useMemo(() => filterNodesByQuery(nodes, query, 8), [nodes, query])

    useEffect(() => setActive(0), [query])

    function pick(node: GraphNode) {
        onPick(node)
        setQuery('')
        setOpen(false)
        inputRef.current?.blur()
    }

    return (
        <div className="node-search">
            <span className="node-search-icon" aria-hidden="true">
                <IconSearch />
            </span>
            <input
                ref={inputRef}
                className="node-search-input"
                placeholder="Find node…"
                value={query}
                onChange={(e) => {
                    setQuery(e.target.value)
                    setOpen(true)
                }}
                onFocus={() => setOpen(true)}
                onBlur={() => window.setTimeout(() => setOpen(false), 120)}
                onKeyDown={(e) => {
                    if (e.key === 'ArrowDown') {
                        e.preventDefault()
                        setActive((i) => Math.min(Math.max(hits.length - 1, 0), i + 1))
                    } else if (e.key === 'ArrowUp') {
                        e.preventDefault()
                        setActive((i) => Math.max(0, i - 1))
                    } else if (e.key === 'Enter' && hits[active]) {
                        e.preventDefault()
                        pick(hits[active])
                    } else if (e.key === 'Escape') {
                        e.stopPropagation()
                        setQuery('')
                        setOpen(false)
                        inputRef.current?.blur()
                    }
                }}
                role="combobox"
                aria-expanded={open && query.trim().length > 0}
                aria-label="Find a node"
            />
            {open && query.trim() ? (
                <ul className="node-search-list glass" role="listbox" aria-label="Matching nodes">
                    {hits.map((n, i) => (
                        <li key={n.id}>
                            <button
                                type="button"
                                role="option"
                                aria-selected={i === active}
                                className={i === active ? 'active' : undefined}
                                onMouseDown={(e) => {
                                    e.preventDefault()
                                    pick(n)
                                }}
                                onMouseEnter={() => setActive(i)}
                            >
                                <span className={`node-search-dot kind-${String(n.kind)}`} aria-hidden="true" />
                                <span className="node-search-label">
                                    {highlightMatch(n.label || n.id, query)}
                                </span>
                                <span className="node-search-kind muted">{n.kind}</span>
                            </button>
                        </li>
                    ))}
                    {hits.length === 0 ? <li className="node-search-empty muted">No matches</li> : null}
                </ul>
            ) : null}
        </div>
    )
}