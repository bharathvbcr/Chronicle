/**
 * Tiny pub/sub for "the vault changed" notifications.
 *
 * Producers: the SSE live-events hook (server fingerprint changed) and the
 * E2EE unlock/lock card (decryption state flipped). Consumer: App bumps its
 * refreshKey so visible views refetch. Decoupled so views never need to know
 * where change signals come from.
 */

export type VaultChangeReason = 'sse' | 'e2ee' | 'manual'

type Listener = (reason: VaultChangeReason) => void

const listeners = new Set<Listener>()

export function onVaultChanged(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function notifyVaultChanged(reason: VaultChangeReason = 'manual'): void {
  // Isolated: one broken consumer must not break change delivery for others.
  for (const listener of [...listeners]) {
    try {
      listener(reason)
    } catch {
      /* listener errors are its own problem */
    }
  }
}

/** Test helper. */
export function resetVaultBus(): void {
  listeners.clear()
}
