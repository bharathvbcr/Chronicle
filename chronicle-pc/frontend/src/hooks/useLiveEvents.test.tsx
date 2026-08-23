import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLiveEvents } from './useLiveEvents'
import { onVaultChanged, resetVaultBus } from '../lib/vaultBus'
import { resetLanAuthTokenCache } from '../api/client'

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>()
  return {
    ...original,
    api: {
      ...original.api,
      events: { ticket: vi.fn() },
    },
  }
})

import { api } from '../api/client'

const ticketMock = vi.mocked(api.events.ticket)

type Handler = (event?: { data?: string }) => void

class FakeEventSource {
  static instances: FakeEventSource[] = []
  url: string
  closed = false
  createdAt = 0
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  private handlers = new Map<string, Set<Handler>>()

  constructor(url: string) {
    this.url = url
    this.createdAt = Date.now()
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, handler: Handler) {
    const set = this.handlers.get(type) ?? new Set<Handler>()
    set.add(handler)
    this.handlers.set(type, set)
  }

  emit(type: string) {
    for (const handler of this.handlers.get(type) ?? []) handler()
  }

  close() {
    this.closed = true
  }
}

function lastSource(): FakeEventSource {
  const source = FakeEventSource.instances.at(-1)
  if (!source) throw new Error('no EventSource created')
  return source
}

/** Flush the hook's async connect() chain (fetch → ticket → EventSource). */
async function flushConnect() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
  })
}

describe('useLiveEvents (stream-ticket flow)', () => {
  let originalEventSource: typeof EventSource | undefined
  let ticketsIssued: string[]

  beforeEach(() => {
    vi.useFakeTimers()
    originalEventSource = global.EventSource
    FakeEventSource.instances = []
    resetVaultBus()
    resetLanAuthTokenCache()
    ;(global as Record<string, unknown>).EventSource = FakeEventSource

    ticketsIssued = []
    let n = 0
    ticketMock.mockImplementation(async () => {
      const ticket = `t-${n}`
      n += 1
      ticketsIssued.push(ticket)
      return { ticket, expires_in: 30 }
    })
  })

  afterEach(() => {
    if (originalEventSource) {
      ;(global as Record<string, unknown>).EventSource = originalEventSource
    }
    vi.useRealTimers()
    resetVaultBus()
    resetLanAuthTokenCache()
  })

  it('opens /events/stream with a fresh single-use ticket and reports live', async () => {
    const { result } = renderHook(() => useLiveEvents())
    await flushConnect()

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(lastSource().url).toBe('/events/stream?ticket=t-0')
    expect(result.current).toBe('connecting')

    act(() => lastSource().onopen?.())
    expect(result.current).toBe('live')
  })

  it('forwards coalesced vault events to the vault bus', async () => {
    const seen: string[] = []
    onVaultChanged((reason) => seen.push(reason))
    renderHook(() => useLiveEvents())
    await flushConnect()

    act(() => {
      for (let i = 0; i < 5; i += 1) lastSource().emit('vault')
    })
    expect(seen).toEqual([]) // debounced — nothing yet

    await act(async () => {
      await vi.advanceTimersByTimeAsync(800)
    })
    expect(seen).toEqual(['sse']) // five events → one refresh
  })

  it('reconnects after an error using a NEW ticket (old one is consumed)', async () => {
    renderHook(() => useLiveEvents())
    await flushConnect()
    expect(lastSource().url).toBe('/events/stream?ticket=t-0')

    act(() => lastSource().onerror?.())
    expect(lastSource().closed).toBe(true)

    // First retry arrives after ~1s backoff.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100)
      await flushConnect()
    })
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(lastSource().url).toBe('/events/stream?ticket=t-1')
  })

  it('drops to offline after repeated failures and recovers on open', async () => {
    const { result } = renderHook(() => useLiveEvents())
    await flushConnect()

    // Strike 1 and 2 → still "connecting"; strike 3+ → offline.
    for (let round = 1; round <= 3; round += 1) {
      act(() => lastSource().onerror?.())
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000)
        await flushConnect()
      })
      if (round < 3) expect(result.current).toBe('connecting')
    }
    expect(result.current).toBe('offline')

    act(() => lastSource().onopen?.())
    expect(result.current).toBe('live')
  })

  it('treats a server rotation (bye) as reconnect-with-fresh-ticket', async () => {
    renderHook(() => useLiveEvents())
    await flushConnect()
    act(() => lastSource().onopen?.())

    act(() => lastSource().emit('bye'))
    expect(lastSource().closed).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100)
      await flushConnect()
    })
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1].url).not.toBe(
      FakeEventSource.instances[0].url,
    )
  })

  it('closes the stream, cancels pending reconnects and debounces on unmount', async () => {
    const seen: string[] = []
    onVaultChanged((reason) => seen.push(reason))
    const { unmount } = renderHook(() => useLiveEvents())
    await flushConnect()

    act(() => lastSource().emit('vault'))
    unmount()
    expect(lastSource().closed).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(seen).toEqual([]) // pending debounce was cancelled
    expect(FakeEventSource.instances).toHaveLength(1) // no reconnect fired
  })
})
