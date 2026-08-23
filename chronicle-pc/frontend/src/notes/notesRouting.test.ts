import { describe, expect, it } from 'vitest'
import {
  citationPathForOpen,
  isCrossSectionPath,
  notesRouteFor,
  parseJournalAmendConflict,
  routeForSection,
} from './notesRouting'

describe('notesRouteFor', () => {
  it('routes PARA, Home, and journal paths', () => {
    expect(notesRouteFor('30-Knowledge/a.md')).toBe('/vault/kb')
    expect(notesRouteFor('00-Inbox/a.md')).toBe('/vault/notes')
    expect(notesRouteFor('10-Work/a.md')).toBe('/vault/notes')
    expect(notesRouteFor('Home.md')).toBe('/vault/notes')
    expect(notesRouteFor('40-Journal/2026-07-09.md')).toBe('/vault/journal')
    expect(notesRouteFor('_system/derived/daily/x.md')).toBe('/vault/journal')
    expect(notesRouteFor('Upcoming.md')).toBe('/vault/journal')
    expect(notesRouteFor('notes/daily/x.md')).toBe('/vault/journal')
  })

  it('refuses retired kb/notes and non-derived _system', () => {
    expect(notesRouteFor('kb/notes/a.md')).toBeNull()
    expect(notesRouteFor('kb/files/x.pdf')).toBeNull()
    expect(notesRouteFor('_system/preferences.md')).toBeNull()
  })

  it('detects cross-section paths', () => {
    expect(routeForSection('kb')).toBe('/vault/kb')
    expect(isCrossSectionPath('00-Inbox/a.md', 'kb')).toBe(true)
    expect(isCrossSectionPath('30-Knowledge/a.md', 'kb')).toBe(false)
    expect(isCrossSectionPath('Home.md', 'notes')).toBe(false)
    expect(isCrossSectionPath('Home.md', 'kb')).toBe(true)
    expect(isCrossSectionPath('40-Journal/2026-07-09.md', 'notes')).toBe(true)
  })
})

describe('citationPathForOpen', () => {
  it('does not double-prefix journal paths', () => {
    expect(citationPathForOpen('40-Journal/2026-07-09.md')).toBe('40-Journal/2026-07-09.md')
    expect(citationPathForOpen('_system/derived/x.md')).toBe('_system/derived/x.md')
    expect(citationPathForOpen('notes/daily/x.md', 'note')).toBe('notes/daily/x.md')
  })

  it('uses PARA-only knowledge citation fallbacks', () => {
    expect(citationPathForOpen('Home.md')).toBe('Home.md')
    expect(citationPathForOpen('daily/x.md', 'note')).toBe('notes/daily/x.md')
    expect(citationPathForOpen('foo.md', 'kb')).toBe('30-Knowledge/foo.md')
    expect(citationPathForOpen('ResumePoints/Foo.md', 'kb')).toBe(
      '10-Work/ResumePoints/Foo.md',
    )
    expect(citationPathForOpen('00-Inbox/foo.md', 'kb')).toBe('00-Inbox/foo.md')
    expect(citationPathForOpen('kb/notes/foo.md', 'kb')).toBeNull()
  })
})

describe('parseJournalAmendConflict', () => {
  it('unwraps FastAPI nested detail', () => {
    const parsed = parseJournalAmendConflict({
      detail: {
        detail: 'journal fence hash mismatch',
        on_disk_hash: 'c'.repeat(64),
        filed_content_hash: 'd'.repeat(64),
      },
    })
    expect(parsed?.on_disk_hash).toBe('c'.repeat(64))
    expect(parsed?.filed_content_hash).toBe('d'.repeat(64))
  })

  it('accepts flat conflict bodies', () => {
    const parsed = parseJournalAmendConflict({
      detail: 'mismatch',
      on_disk_hash: 'a'.repeat(64),
      filed_content_hash: null,
    })
    expect(parsed?.on_disk_hash).toBe('a'.repeat(64))
  })
})
