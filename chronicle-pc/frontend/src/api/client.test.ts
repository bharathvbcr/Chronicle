import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, AUTH_HEADER, resetLanAuthTokenCache } from '../api/client'

describe('api client', () => {
  afterEach(() => {
    resetLanAuthTokenCache()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('parses JSON on success', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            v: 1,
            host: '127.0.0.1',
            port: 8765,
            base: 'http://127.0.0.1:8765',
            token: null,
            auth_required: false,
          }),
        }
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ ok: true, chronicle_dir: '/vault' }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)
    const res = await api.health()
    expect(res.chronicle_dir).toBe('/vault')
    expect(fetchMock).toHaveBeenCalledWith(
      '/health',
      expect.objectContaining({
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    )
  })

  it('surfaces error detail from failed responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url === '/connect') {
          return {
            ok: true,
            status: 200,
            json: async () => ({ token: null, auth_required: false }),
          }
        }
        return {
          ok: false,
          status: 400,
          statusText: 'Bad Request',
          json: async () => ({ detail: 'nope' }),
        }
      }),
    )
    await expect(api.health()).rejects.toThrow('nope')
  })

  it('passes AbortSignal and rejects on abort', async () => {
    const ac = new AbortController()
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        return new Promise((_resolve, reject) => {
          const signal = init?.signal
          if (signal?.aborted) {
            reject(new DOMException('Aborted', 'AbortError'))
            return
          }
          signal?.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'))
          })
        })
      }),
    )
    const pending = api.entries.list({ limit: 10 }, { signal: ac.signal })
    ac.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('attaches X-Chronicle-Token on all API requests after GET /connect', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            v: 1,
            host: '127.0.0.1',
            port: 8765,
            base: 'http://127.0.0.1:8765',
            token: 'lan-secret',
            auth_required: true,
          }),
        }
      }
      if (url === '/entries') {
        return {
          ok: true,
          status: 201,
          json: async () => ({ id: 'e1', type: 'log', text: 'hi', tags: [], ts: '' }),
        }
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ ok: true }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.health()
    await api.entries.create({ type: 'log', text: 'hi' })

    expect(fetchMock).toHaveBeenCalledWith('/connect', expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(
      '/health',
      expect.objectContaining({
        headers: expect.objectContaining({ [AUTH_HEADER]: 'lan-secret' }),
      }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      '/entries',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ [AUTH_HEADER]: 'lan-secret' }),
      }),
    )
  })

  it('skips auth header when /connect has no token (localhost)', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            v: 1,
            host: '127.0.0.1',
            port: 8765,
            base: 'http://127.0.0.1:8765',
            token: null,
            auth_required: false,
          }),
        }
      }
      return {
        ok: true,
        status: 201,
        json: async () => ({ id: 'e1', type: 'log', text: 'hi', tags: [], ts: '' }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.entries.create({ type: 'log', text: 'hi' })

    const entryCall = fetchMock.mock.calls.find(([u]) => u === '/entries') as
      | [string, RequestInit?]
      | undefined
    expect(entryCall).toBeTruthy()
    const headers = (entryCall![1]?.headers ?? {}) as Record<string, string>
    expect(headers[AUTH_HEADER]).toBeUndefined()
  })

  it('fetches /connect once to seed token before GET API requests', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            v: 1,
            token: 'lan-secret',
            auth_required: true,
          }),
        }
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ ok: true }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.health()

    expect(fetchMock).toHaveBeenCalledWith('/connect', expect.anything())
    expect(fetchMock).toHaveBeenCalledWith(
      '/health',
      expect.objectContaining({
        headers: expect.objectContaining({ [AUTH_HEADER]: 'lan-secret' }),
      }),
    )
  })

  it('kb.tree passes section as a query param', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({ token: null, auth_required: false }),
        }
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ tree: { path: 'knowledge', type: 'dir', children: [] }, files: [] }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.kb.tree({ section: 'kb' })
    expect(fetchMock).toHaveBeenCalledWith('/kb/tree?section=kb', expect.anything())

    await api.kb.tree()
    expect(fetchMock).toHaveBeenCalledWith('/kb/tree', expect.anything())
  })

  it('journal.amend sends token header on PATCH and returns the amend result', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            v: 1,
            host: '127.0.0.1',
            port: 8765,
            base: 'http://127.0.0.1:8765',
            token: 'lan-secret',
            auth_required: true,
          }),
        }
      }
      expect(init?.method).toBe('PATCH')
      return {
        ok: true,
        status: 200,
        json: async () => ({
          id: '2026-07-09_213045-an',
          path: '40-Journal/2026-07-09.md',
          hash: 'a'.repeat(64),
          prose_edited: true,
        }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    const res = await api.journal.amend('2026-07-09_213045-an', {
      body: 'Edited prose.',
      base_hash: 'b'.repeat(64),
    })
    expect(res.prose_edited).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith(
      '/journal/entries/2026-07-09_213045-an',
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({ [AUTH_HEADER]: 'lan-secret' }),
      }),
    )
  })

  it('journal.amend throws an ApiError carrying the 409 conflict body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url === '/connect') {
          return {
            ok: true,
            status: 200,
            json: async () => ({ token: null, auth_required: false }),
          }
        }
        return {
          ok: false,
          status: 409,
          statusText: 'Conflict',
          json: async () => ({
            detail: {
              detail: 'journal fence hash mismatch',
              on_disk_hash: 'c'.repeat(64),
              filed_content_hash: 'c'.repeat(64),
            },
          }),
        }
      }),
    )
    let caught: unknown
    try {
      await api.journal.amend('2026-07-09_213045-an', { body: 'x', base_hash: 'd'.repeat(64) })
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(ApiError)
    const err = caught as ApiError
    expect(err.status).toBe(409)
    const detail = (err.body as { detail: { on_disk_hash: string } }).detail
    expect(detail.on_disk_hash).toBe('c'.repeat(64))
  })

  it('kb.put sends base_hash on overwrite', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/connect') {
        return {
          ok: true,
          status: 200,
          json: async () => ({ token: null, auth_required: false }),
        }
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({
          path: '10-Work/My Note.md',
          content: '# hi\n',
          content_hash: 'a'.repeat(64),
        }),
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.kb.put('10-Work/My Note.md', {
      content: '# hi\n',
      base_hash: 'b'.repeat(64),
    })
    const putCall = fetchMock.mock.calls.find(([u]) =>
      String(u).startsWith('/kb/notes/'),
    ) as [string, RequestInit?] | undefined
    expect(putCall).toBeTruthy()
    const body = JSON.parse(String(putCall![1]?.body))
    expect(body.base_hash).toBe('b'.repeat(64))
  })

  it('URL-encodes kb note path segments (spaces)', async () => {
    const { encodeKbNotePath, kbApiPath } = await import('./client')
    expect(kbApiPath('10-Work/My Note.md')).toBe('10-Work/My Note.md')
    expect(encodeKbNotePath('10-Work/My Note.md')).toBe('10-Work/My%20Note.md')
    expect(encodeKbNotePath('kb/notes/ResumePoints/Foo Bar.md')).toBe(
      'ResumePoints/Foo%20Bar.md',
    )

    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url === '/connect') {
          return {
            ok: true,
            status: 200,
            json: async () => ({ token: null, auth_required: false }),
          }
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({ path: '10-Work/My Note.md', content: '# hi\n' }),
        }
      }),
    )
    await api.kb.get('10-Work/My Note.md')
    expect(fetch).toHaveBeenCalledWith(
      '/kb/notes/10-Work/My%20Note.md',
      expect.anything(),
    )
  })

  describe('e2ee endpoints (contract v1.11)', () => {
    function stubWithToken(jsonFor: (url: string, init?: RequestInit) => unknown) {
      return vi.fn(async (url: string, init?: RequestInit) => {
        if (url === '/connect') {
          return {
            ok: true,
            status: 200,
            json: async () => ({ token: 'lan-secret', auth_required: true }),
          }
        }
        return { ok: true, status: 200, json: async () => jsonFor(url, init) }
      })
    }

    it('status GETs /auth/e2ee/status with the pairing token', async () => {
      const fetchMock = stubWithToken(() => ({ enabled: false }))
      vi.stubGlobal('fetch', fetchMock)
      const status = await api.e2ee.status()
      expect(status.enabled).toBe(false)
      expect(fetchMock).toHaveBeenCalledWith(
        '/auth/e2ee/status',
        expect.objectContaining({
          headers: expect.objectContaining({ [AUTH_HEADER]: 'lan-secret' }),
        }),
      )
    })

    it('unlock POSTs the passphrase to /auth/e2ee/unlock', async () => {
      const fetchMock = stubWithToken(() => ({
        ok: true,
        enabled: true,
        unlocked: true,
      }))
      vi.stubGlobal('fetch', fetchMock)
      const res = await api.e2ee.unlock('passphrase-123')
      expect(res.unlocked).toBe(true)
      const call = fetchMock.mock.calls.find(([u]) => u === '/auth/e2ee/unlock') as
        | [string, RequestInit]
        | undefined
      expect(call).toBeTruthy()
      expect(call![1].method).toBe('POST')
      expect(JSON.parse(String(call![1].body))).toEqual({
        passphrase: 'passphrase-123',
      })
      expect((call![1].headers as Record<string, string>)[AUTH_HEADER]).toBe(
        'lan-secret',
      )
    })

    it('lock POSTs /auth/e2ee/lock without a body payload', async () => {
      const fetchMock = stubWithToken(() => ({ ok: true, unlocked: false }))
      vi.stubGlobal('fetch', fetchMock)
      const res = await api.e2ee.lock()
      expect(res.unlocked).toBe(false)
      const call = fetchMock.mock.calls.find(([u]) => u === '/auth/e2ee/lock') as
        | [string, RequestInit]
        | undefined
      expect(call![1].method).toBe('POST')
      // No JSON body on lock — the route takes no input.
      expect(call![1].body).toBeUndefined()
    })
  })
})
