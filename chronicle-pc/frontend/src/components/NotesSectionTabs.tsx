import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { AppDialog } from './AppDialog'

const SECTIONS = [
  { to: '/vault/kb', label: 'Knowledge Base' },
  { to: '/vault/notes', label: 'Notes' },
  { to: '/vault/journal', label: 'Journal' },
]

/** Sub-tab bar shared by the three Notes sections (Knowledge Base / Notes / Journal). */
export function NotesSectionTabs({ dirty = false }: { dirty?: boolean }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [pending, setPending] = useState<string | null>(null)

  function go(to: string) {
    if (to === location.pathname) return
    if (dirty) {
      setPending(to)
      return
    }
    navigate(to)
  }

  return (
    <>
      <div className="source-tabs" role="tablist" aria-label="Notes section">
        {SECTIONS.map((s) => (
          <button
            key={s.to}
            type="button"
            role="tab"
            aria-selected={location.pathname === s.to}
            className={location.pathname === s.to ? 'active' : undefined}
            onClick={() => go(s.to)}
          >
            {s.label}
          </button>
        ))}
      </div>
      <AppDialog
        open={Boolean(pending)}
        title="Discard changes?"
        message="You have unsaved edits. Switch Notes section without saving?"
        confirmLabel="Discard"
        danger
        onCancel={() => setPending(null)}
        onConfirm={() => {
          const to = pending
          setPending(null)
          if (to) navigate(to)
        }}
      />
    </>
  )
}
