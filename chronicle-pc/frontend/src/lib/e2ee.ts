import type { Entry } from '../api/types'

/**
 * True when an entry carries ciphertext but no readable text — the vault is
 * E2EE-locked (contract v1.11). Mirrors Android Entry.isLockedText().
 */
export function isEntryLocked(entry: Pick<Entry, 'text' | 'text_enc'>): boolean {
  return Boolean(entry.text_enc) && !entry.text.trim()
}
