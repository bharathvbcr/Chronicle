import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { E2eeStatus } from '../api/types'
import { notifyVaultChanged } from '../lib/vaultBus'

interface E2eeSettingsProps {
  /** Called after a successful unlock/lock so views can refetch. */
  onChanged?: () => void
}

/**
 * Encryption card for Settings — parity with Android Settings → Encryption.
 * Passphrase never leaves this machine: unlock derives + verifies server-side
 * against the check blob; the key stays in serve memory until locked.
 */
export function E2eeSettings({ onChanged }: E2eeSettingsProps) {
  const [status, setStatus] = useState<E2eeStatus | null>(null)
  const [passphrase, setPassphrase] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [rotating, setRotating] = useState(false)
  const [oldPass, setOldPass] = useState('')
  const [newPass, setNewPass] = useState('')
  const [newPass2, setNewPass2] = useState('')

  const loadStatus = useCallback(async () => {
    try {
      setStatus(await api.e2ee.status())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    void loadStatus()
    // Poll so CLI-side lock/unlock (chronicle lock) doesn't leave a stale card.
    const timer = window.setInterval(() => {
      if (!busy) void loadStatus()
    }, 30_000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- busy is a guard, not a trigger
  }, [loadStatus])

  async function unlock() {
    if (!passphrase || busy) return
    setBusy(true)
    setError(null)
    try {
      setStatus(await api.e2ee.unlock(passphrase))
      setPassphrase('')
      setNote('Unlocked — encrypted entries are readable again')
      onChanged?.()
      notifyVaultChanged('e2ee')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function rotate() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const stats = await api.e2ee.rotate({ old_passphrase: oldPass, new_passphrase: newPass })
      setRotating(false)
      setOldPass('')
      setNewPass('')
      setNewPass2('')
      setNote(`Passphrase rotated — ${stats.resealed} entr${stats.resealed === 1 ? 'y' : 'ies'} resealed`)
      onChanged?.()
      notifyVaultChanged('e2ee')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function lock() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await api.e2ee.lock()
      setNote('Locked — encrypted entries are hidden until unlocked')
      onChanged?.()
      notifyVaultChanged('e2ee')
      await loadStatus()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (!status) {
    return <p className="muted">{error ?? 'Checking encryption status…'}</p>
  }

  if (!status.enabled) {
    return (
      <>
        <p className="muted">
          Off. Run <code>chronicle e2ee-setup</code> to encrypt entry text with a
          passphrase, then unlock here or on the phone with the same one.
        </p>
        {error ? <p style={{ color: 'var(--danger)' }}>{error}</p> : null}
      </>
    )
  }

  const iterations = status.kdf?.iter
  return (
    <>
      <dl className="settings-dl">
        <div>
          <dt>Vault</dt>
          <dd>{status.unlocked ? 'unlocked' : 'locked'}</dd>
        </div>
        {iterations != null ? (
          <div>
            <dt>KDF</dt>
            <dd>PBKDF2 · {Number(iterations).toLocaleString('en-US')} iterations</dd>
          </div>
        ) : null}
      </dl>

      {status.unlocked ? (
        <>
          <button type="button" className="btn" disabled={busy} onClick={() => void lock()}>
            {busy ? 'Locking…' : 'Lock vault'}
          </button>
          <div className="e2ee-rotate">
            <p className="muted" style={{ margin: '0.75rem 0 0.25rem' }}>
              Rotate passphrase — reseals every encrypted entry under fresh key
              material. All entries must be readable (they are, while unlocked).
            </p>
            {!rotating ? (
              <button type="button" className="btn" onClick={() => setRotating(true)}>
                Rotate passphrase…
              </button>
            ) : (
              <form
                className="e2ee-unlock-row"
                onSubmit={(e) => {
                  e.preventDefault()
                  void rotate()
                }}
              >
                <input
                  className="field"
                  type="password"
                  autoComplete="current-password"
                  placeholder="Current passphrase"
                  aria-label="Current passphrase"
                  value={oldPass}
                  onChange={(e) => setOldPass(e.target.value)}
                />
                <input
                  className="field"
                  type="password"
                  autoComplete="new-password"
                  placeholder="New passphrase"
                  aria-label="New passphrase"
                  value={newPass}
                  onChange={(e) => setNewPass(e.target.value)}
                />
                <input
                  className="field"
                  type="password"
                  autoComplete="new-password"
                  placeholder="Repeat new passphrase"
                  aria-label="Repeat new passphrase"
                  value={newPass2}
                  onChange={(e) => setNewPass2(e.target.value)}
                />
                <button
                  type="submit"
                  className="btn primary"
                  disabled={busy || !oldPass || !newPass || newPass !== newPass2}
                >
                  {busy ? 'Rotating…' : 'Rotate'}
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    setRotating(false)
                    setOldPass('')
                    setNewPass('')
                    setNewPass2('')
                  }}
                >
                  Cancel
                </button>
              </form>
            )}
          </div>
        </>
      ) : (
        <form
          className="e2ee-unlock-row"
          onSubmit={(e) => {
            e.preventDefault()
            void unlock()
          }}
        >
          <input
            className="field"
            type="password"
            autoComplete="current-password"
            placeholder="Vault passphrase"
            aria-label="Vault passphrase"
            value={passphrase}
            onChange={(e) => setPassphrase(e.target.value)}
          />
          <button type="submit" className="btn primary" disabled={busy || !passphrase}>
            {busy ? 'Unlocking…' : 'Unlock'}
          </button>
        </form>
      )}

      {note ? (
        <p role="status" className="muted">
          {note}
        </p>
      ) : null}
      {error ? (
        <p role="alert" style={{ color: 'var(--danger)' }}>
          {error}
        </p>
      ) : null}
    </>
  )
}
