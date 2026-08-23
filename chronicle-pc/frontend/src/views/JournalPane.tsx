import { useCallback, useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ApiError, api } from '../api/client'
import { onVaultChanged } from '../lib/vaultBus'
import type { JournalAmendConflict, JournalDay, JournalEntryBody, NoteContent } from '../api/types'
import { AppDialog } from '../components/AppDialog'
import { NotesSectionTabs } from '../components/NotesSectionTabs'
import { StatusPane } from '../components/StatusPane'
import { parseJournalAmendConflict } from '../notes/notesRouting'
import { markdownLinkComponents } from '../notes/safeMarkdownLink'
import './NotesPanes.css'

interface JournalPaneProps {
  initialPath?: string | null
  onInitialPathConsumed?: () => void
}

type DerivedItem = { path: string; label: string }

/** Browse 40-Journal/ days; amend a single entry's fence body via serve PATCH (hash-gated). */
export function JournalPane({ initialPath = null, onInitialPathConsumed }: JournalPaneProps) {
  const [days, setDays] = useState<JournalDay[]>([])
  const [selectedDate, setSelectedDate] = useState<string | null>(null)
  const [entries, setEntries] = useState<Record<string, JournalEntryBody>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [editingId, setEditingId] = useState<string | null>(null)
  const [savingId, setSavingId] = useState<string | null>(null)
  const [upcoming, setUpcoming] = useState<string | null>(null)
  const [derived, setDerived] = useState<DerivedItem[]>([])
  const [derivedNote, setDerivedNote] = useState<NoteContent | null>(null)
  const [loading, setLoading] = useState(true)
  const [entriesLoading, setEntriesLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<{ id: string; body: JournalAmendConflict } | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const dirty = editingId != null && drafts[editingId] !== undefined &&
    drafts[editingId] !== entries[editingId]?.body

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    try {
      const res = await api.journal.days({ signal })
      if (signal?.aborted) return
      setDays(res.days)
      setError(null)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    api.notes
      .list({ signal: ac.signal })
      .then((res) => {
        if (ac.signal.aborted) return
        const items: DerivedItem[] = (res.files || [])
          .filter(
            (f) =>
              f.path === 'Upcoming.md' ||
              f.path.startsWith('_system/derived/') ||
              (f.path.startsWith('notes/') && f.path.endsWith('.md')),
          )
          .map((f) => ({
            path: f.path,
            label:
              f.path === 'Upcoming.md'
                ? 'Upcoming.md (derived)'
                : `${f.name || f.path} (derived)`,
          }))
        setDerived(items)
        const upcomingItem = items.find((x) => x.path === 'Upcoming.md')
        if (upcomingItem) {
          return api.notes.get('Upcoming.md', { signal: ac.signal }).then((n) => {
            if (!ac.signal.aborted) setUpcoming(n.content)
          })
        }
        setUpcoming(null)
      })
      .catch(() => {
        if (!ac.signal.aborted) {
          setUpcoming(null)
          setDerived([])
        }
      })
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


  const openDay = useCallback(async (day: JournalDay, signal?: AbortSignal) => {
    setEntriesLoading(true)
    setError(null)
    setEditingId(null)
    setDerivedNote(null)
    try {
      const fetched = await Promise.all(
        day.entry_ids.map((id) => api.journal.entry(id, { signal })),
      )
      if (signal?.aborted) return
      const byId: Record<string, JournalEntryBody> = {}
      for (const e of fetched) byId[e.id] = e
      setEntries(byId)
      setSelectedDate(day.date)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (!signal?.aborted) setEntriesLoading(false)
    }
  }, [])

  const openDerived = useCallback(async (path: string, signal?: AbortSignal) => {
    setEntriesLoading(true)
    setError(null)
    setEditingId(null)
    setSelectedDate(null)
    setEntries({})
    try {
      const note = await api.notes.get(path, { signal })
      if (signal?.aborted) return
      setDerivedNote(note)
      if (!derived.some((d) => d.path === path)) {
        setDerived((d) => [...d, { path, label: `${path} (derived)` }])
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (!signal?.aborted) setEntriesLoading(false)
    }
  }, [derived])

  useEffect(() => {
    if (!initialPath) return
    const m = /^40-Journal\/(\d{4}-\d{2}-\d{2})\.md$/.exec(initialPath)
    if (m) {
      if (!days.length) return
      const day = days.find((d) => d.date === m[1])
      if (day) void openDay(day)
      else setError(`No journal day ${m[1]} (process on Mac to file entries).`)
      onInitialPathConsumed?.()
      return
    }
    if (
      initialPath === 'Upcoming.md' ||
      initialPath.startsWith('_system/derived/') ||
      initialPath.startsWith('notes/')
    ) {
      void openDerived(initialPath)
      onInitialPathConsumed?.()
    }
  }, [initialPath, days, openDay, openDerived, onInitialPathConsumed])

  function startEdit(entry: JournalEntryBody) {
    setDrafts((d) => ({ ...d, [entry.id]: entry.body }))
    setEditingId(entry.id)
  }

  async function saveEdit(entry: JournalEntryBody) {
    const draft = drafts[entry.id]
    if (draft === undefined) return
    setSavingId(entry.id)
    setError(null)
    try {
      const res = await api.journal.amend(entry.id, { body: draft, base_hash: entry.body_hash })
      setEntries((prev) => ({
        ...prev,
        [entry.id]: { ...entry, body: draft, body_hash: res.hash, filed_content_hash: res.hash, editable: true },
      }))
      setEditingId(null)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const parsed = parseJournalAmendConflict(e.body)
        if (parsed) {
          setConflict({ id: entry.id, body: parsed })
        } else {
          setError(e.message)
        }
      } else {
        setError(e instanceof Error ? e.message : String(e))
      }
    } finally {
      setSavingId(null)
    }
  }

  async function resolveConflict() {
    if (!conflict) return
    const id = conflict.id
    setConflict(null)
    try {
      const fresh = await api.journal.entry(id)
      setEntries((prev) => ({ ...prev, [id]: fresh }))
      // Keep the user's draft in the editor so they can compare and manually re-apply.
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function acceptDisk(entry: JournalEntryBody) {
    setSavingId(entry.id)
    setError(null)
    try {
      const res = await api.journal.acceptDisk(entry.id)
      setEntries((prev) => ({
        ...prev,
        [entry.id]: {
          ...entry,
          body_hash: res.hash,
          filed_content_hash: res.hash,
          editable: true,
        },
      }))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSavingId(null)
    }
  }

  return (
    <div className="notes">
      <aside className="notes-nav glass">
        <div className="notes-nav-head">
          <NotesSectionTabs dirty={dirty} />
        </div>
        <div className="notes-nav-head">
          <span className="muted" style={{ fontSize: '0.8rem' }}>
            Journal · 40-Journal fences
          </span>
        </div>
        <StatusPane
          loading={loading}
          error={error && !days.length && !derived.length ? error : null}
          empty={!loading && days.length === 0 && derived.length === 0}
          emptyMessage="No journal days yet. Process on Mac to file entries."
          onRetry={() => void load()}
          className="pad"
        >
          <ul className="file-list">
            {days.map((d) => (
              <li key={d.date}>
                <button
                  type="button"
                  className={selectedDate === d.date ? 'active' : undefined}
                  onClick={() => void openDay(d)}
                >
                  {d.date}
                </button>
              </li>
            ))}
          </ul>
          {derived.length ? (
            <>
              <div className="muted" style={{ fontSize: '0.75rem', padding: '0.5rem' }}>
                Derived · read-only
              </div>
              <ul className="file-list">
                {derived.map((d) => (
                  <li key={d.path}>
                    <button
                      type="button"
                      className={derivedNote?.path === d.path ? 'active' : undefined}
                      onClick={() => void openDerived(d.path)}
                    >
                      {d.label}
                    </button>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </StatusPane>
      </aside>
      <section className="notes-body glass">
        {upcoming && !derivedNote && !selectedDate ? (
          <div className="journal-upcoming md-preview">
            <p className="muted" style={{ fontSize: '0.75rem', padding: '0.5rem 1rem 0' }}>
              Upcoming.md · derived
            </p>
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownLinkComponents()}>
              {upcoming}
            </ReactMarkdown>
          </div>
        ) : null}
        {entriesLoading ? (
          <StatusPane loading className="pad" />
        ) : derivedNote ? (
          <>
            <header className="notes-body-head">
              <h2 className="serif">{derivedNote.path}</h2>
              <span className="muted" style={{ fontSize: '0.75rem' }}>
                derived · read-only
              </span>
            </header>
            <div className="md-preview">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownLinkComponents()}>
                {derivedNote.content}
              </ReactMarkdown>
            </div>
          </>
        ) : !selectedDate ? (
          <StatusPane
            empty
            emptyMessage={
              error
                ? error
                : days.length
                  ? 'Select a day to amend fences, or open a derived note.'
                  : 'No journal days yet. Process on Mac to file entries.'
            }
            className="pad"
          />
        ) : (
          <>
            <header className="notes-body-head">
              <h2 className="serif">{selectedDate}</h2>
              {error ? (
                <p style={{ color: 'var(--danger)' }} role="alert">
                  {error}
                </p>
              ) : null}
            </header>
            {Object.values(entries).length === 0 ? (
              <StatusPane empty emptyMessage="No fenced entries on this day." className="pad" />
            ) : (
              Object.values(entries).map((entry) => (
                <article key={entry.id} className="journal-entry-card">
                  <header>
                    <strong style={{ fontSize: '0.8rem' }}>{entry.id}</strong>
                    <div className="spacer" />
                    {!entry.editable ? (
                      <>
                        <span className="muted" style={{ fontSize: '0.75rem' }}>
                          edited outside · read-only
                        </span>
                        <button
                          type="button"
                          className="btn"
                          disabled={savingId === entry.id}
                          onClick={() => void acceptDisk(entry)}
                        >
                          Accept disk
                        </button>
                      </>
                    ) : editingId === entry.id ? (
                      <>
                        <button
                          type="button"
                          className="btn"
                          onClick={() => setEditingId(null)}
                          disabled={savingId === entry.id}
                        >
                          Cancel
                        </button>
                        <button
                          type="button"
                          className="btn primary"
                          onClick={() => void saveEdit(entry)}
                          disabled={savingId === entry.id}
                        >
                          Save
                        </button>
                      </>
                    ) : (
                      <button type="button" className="btn" onClick={() => startEdit(entry)}>
                        Edit
                      </button>
                    )}
                  </header>
                  <div className="body">
                    {editingId === entry.id ? (
                      <textarea
                        className="md-editor field"
                        style={{ minHeight: '8rem' }}
                        value={drafts[entry.id] ?? entry.body}
                        onChange={(e) => setDrafts((d) => ({ ...d, [entry.id]: e.target.value }))}
                      />
                    ) : (
                      <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownLinkComponents()}>
                        {entry.body}
                      </ReactMarkdown>
                    )}
                  </div>
                </article>
              ))
            )}
          </>
        )}
      </section>

      <AppDialog
        open={Boolean(conflict)}
        title="Entry edited elsewhere"
        message={
          conflict
            ? `This entry was edited outside the app since you loaded it.\n\non_disk: ${(conflict.body.on_disk_hash || '').slice(0, 12)}…\nfiled: ${(conflict.body.filed_content_hash || '').slice(0, 12)}…\n\nRefresh to load the latest text — your draft stays in the editor.`
            : ''
        }
        confirmLabel="Refresh"
        onCancel={() => setConflict(null)}
        onConfirm={() => void resolveConflict()}
      />
    </div>
  )
}
