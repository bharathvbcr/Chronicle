import { useEffect, useRef, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import type { SearchHit } from '../api/types'
import { useFocusTrap } from '../hooks/useFocusTrap'

const RECENT_KEY = 'chronicle-search-recent'
const MAX_RECENT = 8

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function loadRecent(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

function saveRecent(query: string) {
  const q = query.trim()
  if (!q) return
  const next = [q, ...loadRecent().filter((x) => x !== q)].slice(0, MAX_RECENT)
  localStorage.setItem(RECENT_KEY, JSON.stringify(next))
}

function highlightMatch(text: string, query: string): ReactNode {
  const q = query.trim()
  if (!q || !text) return text
  const lower = text.toLowerCase()
  const needle = q.toLowerCase()
  const idx = lower.indexOf(needle)
  if (idx < 0) return text
  return (
    <>
      {text.slice(0, idx)}
      <mark className="search-mark">{text.slice(idx, idx + q.length)}</mark>
      {text.slice(idx + q.length)}
    </>
  )
}

interface SearchOverlayProps {
  open: boolean
  onClose: () => void
  onActivate: (hit: SearchHit) => void
}

export function SearchOverlay({ open, onClose, onActivate }: SearchOverlayProps) {
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<SearchHit[]>([])
  const [recent, setRecent] = useState<string[]>([])
  const [active, setActive] = useState(0)
  const [busy, setBusy] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  useFocusTrap(open, panelRef)

  useEffect(() => {
    if (open) {
      setQuery('')
      setHits([])
      setActive(0)
      setRecent(loadRecent())
      requestAnimationFrame(() => inputRef.current?.focus())
    }
  }, [open])

  useEffect(() => {
    if (!open || !query.trim()) {
      setHits([])
      setSearchError(null)
      return
    }
    const ac = new AbortController()
    setSearchError(null)
    const t = setTimeout(async () => {
      setBusy(true)
      setSearchError(null)
      try {
        const res = await api.search(
          { query: query.trim(), top_k: 12, scope: 'all' },
          { signal: ac.signal },
        )
        setHits(res.hits)
        setActive(0)
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return
        // Distinguish "nothing found" from "search is broken" — a swallowed
        // error here reads as an empty journal.
        setSearchError(e instanceof Error ? e.message : String(e))
        setHits([])
      } finally {
        if (!ac.signal.aborted) setBusy(false)
      }
    }, 180)
    return () => {
      ac.abort()
      clearTimeout(t)
    }
  }, [query, open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      const typing = isTypingTarget(e.target)
      if (e.key === 'ArrowDown' || (!typing && e.key === 'j')) {
        e.preventDefault()
        setActive((i) => Math.min(Math.max(hits.length - 1, 0), i + 1))
      }
      if (e.key === 'ArrowUp' || (!typing && e.key === 'k')) {
        e.preventDefault()
        setActive((i) => Math.max(0, i - 1))
      }
      if (e.key === 'Enter' && hits[active]) {
        e.preventDefault()
        saveRecent(query)
        onActivate(hits[active])
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, hits, active, onActivate, query])

  if (!open) return null

  const showRecent = !query.trim() && recent.length > 0

  return (
    <div
      className="overlay open"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        ref={panelRef}
        className="overlay-panel glass search-palette"
        role="dialog"
        aria-modal="true"
        aria-label="Search"
      >
        <div className="search-palette-chrome">
          <input
            ref={inputRef}
            className="field search-palette-input"
            placeholder="Search journal & knowledge…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Search query"
          />
          <p className="muted search-palette-hint">
            <kbd>↑</kbd>/<kbd>↓</kbd> or <kbd>j</kbd>/<kbd>k</kbd> · <kbd>Enter</kbd> open ·{' '}
            <kbd>Esc</kbd> close
            {busy ? ' · searching…' : ''}
          </p>
        </div>
        {showRecent ? (
          <div className="search-recent">
            <p className="search-recent-label muted">Recent</p>
            <ul className="search-recent-list">
              {recent.map((q) => (
                <li key={q}>
                  <button type="button" onClick={() => setQuery(q)}>
                    {q}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <ul className="search-hits">
          {hits.map((h, i) => (
            <li key={`${h.kind}-${h.id}-${i}`}>
              <button
                type="button"
                className={i === active ? 'active' : undefined}
                onClick={() => {
                  saveRecent(query)
                  onActivate(h)
                }}
                onMouseEnter={() => setActive(i)}
              >
                <span className="hit-kind">{h.kind}</span>
                <span className="hit-id">{highlightMatch(h.id, query)}</span>
                <span className="hit-snip muted">
                  {highlightMatch((h.text || '').slice(0, 120), query)}
                </span>
              </button>
            </li>
          ))}
          {!busy && query.trim() && searchError ? (
            <li className="muted" style={{ padding: '0.5rem' }}>
              Search failed: {searchError}
            </li>
          ) : null}
          {!busy && query.trim() && !searchError && hits.length === 0 ? (
            <li className="muted" style={{ padding: '0.5rem' }}>
              No matches
            </li>
          ) : null}
        </ul>
      </div>
    </div>
  )
}
