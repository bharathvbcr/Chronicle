import { useCallback, useEffect, useMemo, useState } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import type { Entry, SearchHit } from './api/types'
import { api } from './api/client'
import { AppShell } from './components/AppShell'
import { EntryModal } from './components/EntryModal'
import { SearchOverlay } from './components/SearchOverlay'
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts'
import { useLiveEvents } from './hooks/useLiveEvents'
import { citationPathForOpen, notesRouteFor } from './notes/notesRouting'
import { onVaultChanged } from './lib/vaultBus'
import { BrainView } from './views/BrainView'
import { JournalPane } from './views/JournalPane'
import { KnowledgePane } from './views/KnowledgePane'
import { SettingsView } from './views/SettingsView'
import { TimelineView } from './views/TimelineView'
import './components/overlays.css'

export default function App() {
  const navigate = useNavigate()
  const [searchOpen, setSearchOpen] = useState(false)
  const [entryOpen, setEntryOpen] = useState(false)
  const [editing, setEditing] = useState<Entry | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [timelineIds, setTimelineIds] = useState<string[]>([])
  const [refreshKey, setRefreshKey] = useState(0)
  const [notePath, setNotePath] = useState<string | null>(null)
  const [status, setStatus] = useState('')
  const liveState = useLiveEvents()

  // Live updates: SSE vault fingerprints and E2EE unlock/lock both funnel
  // through the bus; any signal refetches the visible timeline (Android parity).
  useEffect(
    () =>
      onVaultChanged((reason) => {
        if (reason === 'sse' || reason === 'e2ee') setRefreshKey((k) => k + 1)
      }),
    [],
  )

  const openNewEntry = useCallback(() => {
    setEditing(null)
    setEntryOpen(true)
  }, [])

  const openEntry = useCallback((entry: Entry) => {
    setEditing(entry)
    setSelectedId(entry.id)
    setEntryOpen(true)
  }, [])

  const openNote = useCallback(
    (path: string) => {
      const route = notesRouteFor(path)
      if (!route) {
        setStatus(`Cannot open ${path} in Notes (not a knowledge/journal path)`)
        return
      }
      setNotePath(path)
      navigate(route)
    },
    [navigate],
  )

  const clearNotePath = useCallback(() => setNotePath(null), [])

  const onActivateHit = useCallback(
    async (hit: SearchHit, signal?: AbortSignal) => {
      setSearchOpen(false)
      if (hit.kind === 'entry') {
        try {
          const entry = await api.entries.get(hit.id, { signal })
          if (signal?.aborted) return
          navigate('/')
          openEntry(entry)
        } catch (e) {
          if (e instanceof DOMException && e.name === 'AbortError') return
          setStatus(e instanceof Error ? e.message : String(e))
        }
        return
      }
      const raw = hit.path || hit.id
      const path = citationPathForOpen(raw, hit.kind)
      if (path) openNote(path)
      else setStatus(`Cannot open ${raw}`)
    },
    [navigate, openEntry, openNote],
  )

  const shortcutHandlers = useMemo(
    () => ({
      onSearch: () => setSearchOpen(true),
      onNewEntry: openNewEntry,
      onEscape: () => {
        if (searchOpen) setSearchOpen(false)
        else if (entryOpen) setEntryOpen(false)
      },
      onTimelineNav: (dir: 1 | -1) => {
        if (!timelineIds.length) return
        const idx = selectedId ? timelineIds.indexOf(selectedId) : -1
        const nextIdx =
          idx < 0 ? (dir > 0 ? 0 : timelineIds.length - 1) : Math.max(0, Math.min(timelineIds.length - 1, idx + dir))
        setSelectedId(timelineIds[nextIdx] ?? null)
      },
    }),
    [openNewEntry, searchOpen, entryOpen, timelineIds, selectedId],
  )

  useKeyboardShortcuts(shortcutHandlers, !searchOpen && !entryOpen)

  return (
    <>
      <Routes>
        <Route
          element={
            <AppShell
              onSearch={() => setSearchOpen(true)}
              onNewEntry={openNewEntry}
              status={status}
              liveState={liveState}
            />
          }
        >
          <Route
            path="/"
            element={
              <TimelineView
                selectedId={selectedId}
                refreshKey={refreshKey}
                onVisibleIds={setTimelineIds}
                onEmptyCreate={openNewEntry}
                onSelect={(e) => {
                  setSelectedId(e.id)
                  openEntry(e)
                }}
              />
            }
          />
          <Route path="/vault" element={<Navigate to="/vault/notes" replace />} />
          <Route
            path="/vault/kb"
            element={
              <KnowledgePane
                section="kb"
                initialPath={notePath}
                onInitialPathConsumed={clearNotePath}
                onOpenNote={openNote}
              />
            }
          />
          <Route
            path="/vault/notes"
            element={
              <KnowledgePane
                section="notes"
                initialPath={notePath}
                onInitialPathConsumed={clearNotePath}
                onOpenNote={openNote}
              />
            }
          />
          <Route
            path="/vault/journal"
            element={<JournalPane initialPath={notePath} onInitialPathConsumed={clearNotePath} />}
          />
          <Route
            path="/brain"
            element={<BrainView onOpenEntry={openEntry} onOpenNote={openNote} />}
          />
          <Route path="/settings" element={<SettingsView />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>

      <SearchOverlay
        open={searchOpen}
        onClose={() => setSearchOpen(false)}
        onActivate={(h) => {
          const ac = new AbortController()
          void onActivateHit(h, ac.signal)
        }}
      />
      <EntryModal
        open={entryOpen}
        entry={editing}
        onClose={() => setEntryOpen(false)}
        onSaved={(entry) => {
          setSelectedId(entry.id)
          setRefreshKey((k) => k + 1)
          setStatus(`Saved ${entry.id}`)
        }}
        onDeleted={(id) => {
          if (selectedId === id) setSelectedId(null)
          setRefreshKey((k) => k + 1)
          setStatus(`Deleted ${id}`)
        }}
      />
    </>
  )
}
