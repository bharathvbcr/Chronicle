import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PairedDevice } from '../api/types'

/**
 * Paired phones card — parity with `chronicle pairs` / `chronicle unpair`.
 * Revocation is immediate: the running serve hot-reloads pairing.json.
 */
export function PairedDevices() {
  const [devices, setDevices] = useState<PairedDevice[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const res = await api.devices.list()
      setDevices(res.devices)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function revoke(name: string) {
    setBusy(name)
    setError(null)
    try {
      await api.devices.revoke(name)
      setConfirming(null)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }

  if (error && !devices) {
    return (
      <p role="status" className="muted">
        {error}
      </p>
    )
  }
  if (!devices) return <p className="muted">Loading paired devices…</p>
  if (devices.length === 0) {
    return <p className="muted">No devices paired. Run `chronicle pair &lt;name&gt;`.</p>
  }

  return (
    <>
      <ul className="device-list">
        {devices.map((d) => (
          <li key={d.name} className="device-row">
            <span className="device-name">{d.name}</span>
            {d.created ? (
              <span className="muted device-created">
                paired {new Date(d.created).toLocaleDateString()}
              </span>
            ) : null}
            {confirming === d.name ? (
              <span className="device-confirm">
                <button
                  type="button"
                  className="btn danger"
                  disabled={busy !== null}
                  onClick={() => void revoke(d.name)}
                >
                  {busy === d.name ? 'Revoking…' : 'Revoke'}
                </button>
                <button type="button" className="btn" onClick={() => setConfirming(null)}>
                  Cancel
                </button>
              </span>
            ) : (
              <button
                type="button"
                className="btn"
                disabled={busy !== null}
                onClick={() => setConfirming(d.name)}
              >
                Revoke…
              </button>
            )}
          </li>
        ))}
      </ul>
      {error ? (
        <p role="alert" style={{ color: 'var(--danger)' }}>
          {error}
        </p>
      ) : null}
    </>
  )
}
