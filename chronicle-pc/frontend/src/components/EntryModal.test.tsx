import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { EntryModal } from './EntryModal'
import type { Entry } from '../api/types'

vi.mock('../api/client', () => ({ api: { entries: {}, journal: {}, kb: {} } }))

afterEach(() => cleanup())

const lockedEntry: Entry = {
  id: '2026-08-20_090000-an',
  ts: '2026-08-20T09:00:00+05:30',
  type: 'log',
  text: '',
  tags: [],
  text_enc: { v: '1', nonce: 'bm9uY2U=', ct: 'Y2lwaGVydGV4dA==' },
}

describe('EntryModal locked-entry guards', () => {
  it('shows the encrypted notice and disables editing while locked', () => {
    render(<EntryModal open entry={lockedEntry} onClose={() => {}} onSaved={() => {}} />)

    expect(screen.getByText(/encrypted \(locked/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Text/i)).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
  })

  it('keeps Save/Delete available for plain unlocked entries', () => {
    const plain: Entry = { ...lockedEntry, text: 'readable', text_enc: undefined }
    render(<EntryModal open entry={plain} onClose={() => {}} onSaved={() => {}} />)

    expect(screen.getByLabelText(/Text/i)).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument()
  })
})

describe('EntryModal double-submit guard', () => {
  it('ignores Cmd/Ctrl+Enter while a save is in flight', async () => {
    const user = userEvent.setup()
    let resolveCreate: (v: unknown) => void = () => {}
    const create = vi.fn(
      () =>
        new Promise((res) => {
          resolveCreate = res
        }),
    )
    const { api } = (await import('../api/client')) as {
      api: { entries: { create: unknown; patch: unknown } }
    }
    ;(api.entries as { create: unknown }).create = create

    const mod = await import('./EntryModal')
    // Create-mode modal (no entry) — Cmd+Enter must POST exactly once.
    render(<mod.EntryModal open entry={null} onClose={() => {}} onSaved={() => {}} />)
    const area = screen.getByLabelText(/Text/i)
    await user.type(area, 'a fresh thought')

    // Fire twice in a row; the second must hit the busy guard.
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }))
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }))

    expect(create).toHaveBeenCalledTimes(1)
    resolveCreate({
      id: '2026-08-20_090000-pc',
      ts: '2026-08-20T09:00:00+05:30',
      type: 'log',
      text: 'a fresh thought',
      tags: [],
    })
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
  })
})
