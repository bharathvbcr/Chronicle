import { useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Entry, EntryType } from '../api/types'
import { isEntryLocked } from '../lib/e2ee'
import { useFocusTrap } from '../hooks/useFocusTrap'
import { MOOD_FACE_DESCRIPTIONS, MOOD_FACES } from '../lib/moodFaces'
import { AppDialog } from './AppDialog'

const TYPES: EntryType[] = ['log', 'idea', 'dream', 'reflection']

interface EntryModalProps {
  open: boolean
  entry?: Entry | null
  onClose: () => void
  onSaved: (entry: Entry) => void
  onDeleted?: (id: string) => void
}

export function EntryModal({ open, entry, onClose, onSaved, onDeleted }: EntryModalProps) {
  const [type, setType] = useState<EntryType>('log')
  const [text, setText] = useState('')
  const [tags, setTags] = useState('')
  const [mood, setMood] = useState<number | ''>('')
  const [busy, setBusy] = useState(false)
  const saveInFlight = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [confirmClose, setConfirmClose] = useState(false)
  const textRef = useRef<HTMLTextAreaElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const baseline = useRef({ type: 'log' as EntryType, text: '', tags: '', mood: '' as number | '' })
  useFocusTrap(open && !confirmDelete && !confirmClose, panelRef)
  const editing = Boolean(entry)
  const e2eeLocked = entry ? isEntryLocked(entry) : false
  // Processed entries are frozen by the pipeline; encrypted entries are
  // read-only until the vault is unlocked (server enforces both).
  const locked = Boolean(entry?.processed) || e2eeLocked

  useEffect(() => {
    if (!open) return
    setError(null)
    setConfirmDelete(false)
    setConfirmClose(false)
    if (entry) {
      const next = {
        type: entry.type,
        text: entry.text,
        tags: (entry.tags || []).join(', '),
        mood: (entry.mood ?? '') as number | '',
      }
      setType(next.type)
      setText(next.text)
      setTags(next.tags)
      setMood(next.mood)
      baseline.current = next
    } else {
      const next = { type: 'log' as EntryType, text: '', tags: '', mood: '' as number | '' }
      setType(next.type)
      setText(next.text)
      setTags(next.tags)
      setMood(next.mood)
      baseline.current = next
    }
    requestAnimationFrame(() => textRef.current?.focus())
  }, [open, entry])

  const dirty = useMemo(() => {
    if (locked) return false
    return (
      type !== baseline.current.type ||
      text !== baseline.current.text ||
      tags !== baseline.current.tags ||
      mood !== baseline.current.mood
    )
  }, [type, text, tags, mood, locked])

  function requestClose() {
    if (dirty && !busy) setConfirmClose(true)
    else onClose()
  }

  async function save() {
    // savingRef guards same-tick re-entry (e.g. two Cmd+Enter keydowns before
    // React re-renders); busy covers renders in between.
    if (locked || busy || saveInFlight.current) return
    saveInFlight.current = true
    setBusy(true)
    setError(null)
    const tagList = tags
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean)
    const moodVal = mood === '' ? null : Number(mood)
    try {
      let saved: Entry
      if (entry) {
        saved = await api.entries.patch(entry.id, {
          type,
          text,
          tags: tagList,
          mood: moodVal,
        })
      } else {
        saved = await api.entries.create({
          type,
          text,
          tags: tagList,
          mood: moodVal,
        })
      }
      onSaved(saved)
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.status === 423) {
        setError('Vault locked — unlock in Settings → Encryption, then save again.')
      } else {
        setError(e instanceof Error ? e.message : String(e))
      }
    } finally {
      saveInFlight.current = false
      setBusy(false)
    }
  }

  async function remove() {
    if (!entry || locked) return
    setBusy(true)
    try {
      await api.entries.remove(entry.id)
      onDeleted?.(entry.id)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
      setConfirmDelete(false)
    }
  }

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !confirmDelete && !confirmClose) {
        e.preventDefault()
        requestClose()
        return
      }
      if (!locked && (e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault()
        void save()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- save/requestClose close over latest form state
  }, [open, locked, busy, type, text, tags, mood, entry, confirmDelete, confirmClose, dirty])

  if (!open) return null

  return (
    <>
      <div
        className="overlay open"
        role="presentation"
        onClick={(e) => {
          if (e.target === e.currentTarget) requestClose()
        }}
      >
        <div
          ref={panelRef}
          className="overlay-panel glass entry-modal entry-modal-enter"
          role="dialog"
          aria-modal="true"
          aria-label={editing ? 'Edit entry' : 'New entry'}
        >
          <h2 className="serif" style={{ margin: '0 0 0.75rem', fontSize: '1.2rem' }}>
            {editing ? 'Entry' : 'Add from Mac'}
          </h2>
          {entry ? (
            <p className="muted" style={{ fontSize: '0.75rem', marginTop: 0 }}>
              {entry.id}
              {entry.processed ? ' · processed (read-only)' : ''}
              {e2eeLocked ? ' · 🔒 encrypted (locked — unlock in Settings)' : ''}
            </p>
          ) : null}
          {e2eeLocked ? (
            <p
              className="muted"
              style={{ fontSize: '0.8rem', margin: '0 0 0.75rem' }}
            >
              This entry's text is sealed. Unlock the vault to read or edit it;
              fields stay editable-safe below once unlocked.
            </p>
          ) : null}
          <div className="entry-form">
            <div className="full">
              <span className="field-label">Type</span>
              <div className="segmented" role="group" aria-label="Entry type">
                {TYPES.map((t) => (
                  <button
                    key={t}
                    type="button"
                    className={`segment${type === t ? ' active' : ''} type-${t}`}
                    aria-pressed={type === t}
                    disabled={locked}
                    onClick={() => setType(t)}
                  >
                    {t}
                  </button>
                ))}
              </div>
            </div>
            <div className="full">
              <span className="field-label">Mood</span>
              <div className="mood-faces" role="group" aria-label="Mood 1 to 5">
                {MOOD_FACES.map((face, i) => {
                  const value = i + 1
                  const selected = mood === value
                  return (
                    <button
                      key={face}
                      type="button"
                      className={`mood-face${selected ? ' active' : ''}`}
                      aria-pressed={selected}
                      aria-label={MOOD_FACE_DESCRIPTIONS[i]}
                      disabled={locked}
                      onClick={() => setMood(selected ? '' : value)}
                    >
                      {face}
                    </button>
                  )
                })}
              </div>
            </div>
            <label className="full">
              Text
              <textarea
                ref={textRef}
                className="field"
                rows={8}
                value={text}
                disabled={locked}
                onChange={(e) => setText(e.target.value)}
                placeholder="What happened, what you noticed…"
              />
            </label>
            <label className="full">
              Tags (comma-separated)
              <input
                className="field"
                value={tags}
                disabled={locked}
                onChange={(e) => setTags(e.target.value)}
              />
            </label>
          </div>
          {error ? (
            <p style={{ color: 'var(--danger)', fontSize: '0.85rem' }}>{error}</p>
          ) : null}
          <div className="entry-actions">
            {editing && !locked ? (
              <button
                type="button"
                className="btn danger"
                disabled={busy}
                onClick={() => setConfirmDelete(true)}
              >
                Delete
              </button>
            ) : (
              <span className="muted" style={{ fontSize: 'var(--type-tiny)' }}>
                {!locked ? '⌘/Ctrl+Enter to save' : ''}
              </span>
            )}
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button type="button" className="btn" onClick={requestClose}>
                Close
              </button>
              {!locked ? (
                <button type="button" className="btn primary" disabled={busy} onClick={() => void save()}>
                  {busy ? 'Saving…' : 'Save'}
                </button>
              ) : null}
            </div>
          </div>
        </div>
      </div>
      <AppDialog
        open={confirmDelete}
        title="Delete entry?"
        message={entry ? `Delete entry ${entry.id}? This cannot be undone.` : undefined}
        confirmLabel="Delete"
        danger
        onCancel={() => setConfirmDelete(false)}
        onConfirm={() => void remove()}
      />
      <AppDialog
        open={confirmClose}
        title="Discard changes?"
        message="You have unsaved edits. Close without saving?"
        confirmLabel="Discard"
        danger
        onCancel={() => setConfirmClose(false)}
        onConfirm={() => {
          setConfirmClose(false)
          onClose()
        }}
      />
    </>
  )
}
