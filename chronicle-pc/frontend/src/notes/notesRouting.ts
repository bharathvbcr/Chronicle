/**
 * Which /vault/* sub-route a vault-relative path belongs to.
 *
 * The SPA's Notes feature deliberately does NOT live under /notes — the
 * backend already owns GET /notes and /notes/{path} (see api/notes.py).
 */
export type VaultNotesRoute = '/vault/kb' | '/vault/notes' | '/vault/journal'

/** Vault Notes section key used by KnowledgePane / AppShell tabs. */
export type VaultNotesSection = 'kb' | 'notes' | 'journal'

/** Route for the active KnowledgePane / JournalPane section. */
export function routeForSection(section: VaultNotesSection): VaultNotesRoute {
  if (section === 'kb') return '/vault/kb'
  if (section === 'journal') return '/vault/journal'
  return '/vault/notes'
}

/**
 * Return null for paths that must not open via kb.get (non-derived _system,
 * retired kb/notes). Home.md opens under Notes.
 */
export function notesRouteFor(path: Stringish): VaultNotesRoute | null {
  const p = String(path || '').replace(/^\/+/, '')
  if (!p) return null

  // Retired dual-read tree — not openable; cutover to PARA first
  if (p.startsWith('kb/notes/') || p === 'kb/notes' || p.startsWith('kb/')) {
    return null
  }

  if (p === 'Home.md') {
    return '/vault/notes'
  }
  if (p.startsWith('30-Knowledge/')) {
    return '/vault/kb'
  }
  if (/^(00-Inbox|10-Work|20-Personal|90-Archive)\//.test(p)) {
    return '/vault/notes'
  }
  if (
    p.startsWith('40-Journal/') ||
    p.startsWith('_system/derived/') ||
    p.startsWith('notes/') ||
    p === 'Upcoming.md'
  ) {
    return '/vault/journal'
  }
  // Preferences / other chrome — not a Notes tree target
  if (p.startsWith('_system/')) {
    return null
  }
  return null
}

/** True when [path] belongs to a different Notes tab than [section]. */
export function isCrossSectionPath(path: string, section: VaultNotesSection): boolean {
  const route = notesRouteFor(path)
  if (!route) return false
  return route !== routeForSection(section)
}

type Stringish = string

/** Normalize Brain/search citation paths before routing (PARA-only knowledge). */
export function citationPathForOpen(
  path: string,
  kind?: string,
): string | null {
  const p = path.replace(/^\/+/, '').trim()
  if (!p) return null

  if (
    p.startsWith('40-Journal/') ||
    p.startsWith('_system/derived/') ||
    p === 'Upcoming.md'
  ) {
    return p
  }

  // Retired legacy knowledge paths — do not open
  if (p.startsWith('kb/notes/') || p === 'kb/notes' || (p.startsWith('kb/') && !p.startsWith('kb/files'))) {
    return null
  }

  if (p === 'Home.md') return p

  const isPara =
    /^(00-Inbox|10-Work|20-Personal|30-Knowledge|90-Archive)\//.test(p)
  if (isPara) return p
  if (p.startsWith('notes/')) return p
  if (kind === 'note') return `notes/${p}`
  // Bare kb citations → Knowledge area (not retired kb/notes)
  if (kind === 'kb') {
    if (p.startsWith('ResumePoints/')) return `10-Work/${p}`
    return `30-Knowledge/${p}`
  }
  return p
}

/** Unwrap FastAPI 409 body for journal amend conflicts. */
export function parseJournalAmendConflict(body: unknown): {
  detail: string
  on_disk_hash: string | null
  filed_content_hash: string | null
} | null {
  if (!body || typeof body !== 'object') return null
  const root = body as Record<string, unknown>
  const nested = root.detail
  const obj =
    nested && typeof nested === 'object' && nested !== null && 'on_disk_hash' in nested
      ? (nested as Record<string, unknown>)
      : 'on_disk_hash' in root
        ? root
        : null
  if (!obj) return null
  return {
    detail: typeof obj.detail === 'string' ? obj.detail : 'journal fence hash mismatch',
    on_disk_hash: typeof obj.on_disk_hash === 'string' ? obj.on_disk_hash : null,
    filed_content_hash:
      typeof obj.filed_content_hash === 'string' ? obj.filed_content_hash : null,
  }
}
