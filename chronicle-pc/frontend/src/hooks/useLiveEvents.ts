import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { notifyVaultChanged } from '../lib/vaultBus'

export type LiveEventsState = 'connecting' | 'live' | 'offline'

/**
 * Subscribe to the serve SSE stream (GET /events/stream, contract v1.11).
 *
 * EventSource cannot send custom headers, so every connection first fetches
 * a single-use short-TTL ticket via the header-authenticated API and appends
 * it as ?ticket=. Works identically on loopback (Mac) and LAN sessions.
 *
 * `vault` events mean "something changed on disk" — coalesced and forwarded
 * to the vault bus so App can refetch visible data. Reconnects use capped
 * backoff; connection state feeds the AppShell live dot.
 */
export function useLiveEvents(): LiveEventsState {
  const [state, setState] = useState<LiveEventsState>('connecting')
  const debounceRef = useRef<number | null>(null)
  const reconnectRef = useRef<number | null>(null)
  const failuresRef = useRef(0)

  useEffect(() => {
    let disposed = false
    let source: EventSource | null = null

    const setStatus = (next: LiveEventsState) => setState(next)

    const scheduleNotify = () => {
      if (debounceRef.current !== null) window.clearTimeout(debounceRef.current)
      debounceRef.current = window.setTimeout(() => {
        debounceRef.current = null
        notifyVaultChanged('sse')
      }, 750)
    }

    const scheduleReconnect = () => {
      if (disposed) return
      const delay = Math.min(1000 * 2 ** failuresRef.current, 30_000)
      failuresRef.current += 1
      // Three-plus consecutive strikes → report offline (red dot) instead of
      // pretending to connect forever.
      setStatus(failuresRef.current > 2 ? 'offline' : 'connecting')
      reconnectRef.current = window.setTimeout(connect, delay)
    }

    const connect = async () => {
      if (disposed) return
      try {
        // Header-authenticated; single-use; 30 s TTL.
        const { ticket } = await api.events.ticket()
        if (disposed) return
        source = new EventSource(`/events/stream?ticket=${encodeURIComponent(ticket)}`)
        source.addEventListener('vault', scheduleNotify)
        source.addEventListener('bye', () => {
          // Server rotates long-lived streams — reconnect with a fresh ticket.
          source?.close()
          source = null
          scheduleReconnect()
        })
        source.onopen = () => {
          failuresRef.current = 0
          setStatus('live')
        }
        source.onerror = () => {
          if (disposed) return
          source?.close()
          source = null
          // Browser would auto-retry the SAME consumed ticket — pointless.
          // We drive reconnection with a fresh ticket + backoff instead.
          scheduleReconnect()
        }
      } catch {
        if (!disposed) scheduleReconnect()
      }
    }

    void connect()

    return () => {
      disposed = true
      if (debounceRef.current !== null) window.clearTimeout(debounceRef.current)
      if (reconnectRef.current !== null) window.clearTimeout(reconnectRef.current)
      source?.close()
    }
  }, [])

  return state
}
