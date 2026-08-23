import type {
  BrainGraph,
  ConnectInfo,
  CurationOp,
  E2eeStatus,
  EntriesPage,
  PairedDevice,
  Entry,
  EntryType,
  HealthResponse,
  InsightBundle,
  JournalAmendResult,
  JournalDay,
  JournalEntryBody,
  KbTreeResponse,
  ModelsState,
  NoteContent,
  NotesSection,
  RecallResponse,
  Scope,
  SearchHit,
  StreamTicket,
} from './types'

/** Thrown by request() on a non-2xx response; carries status + parsed body for callers that need to branch on it (e.g. 409 journal amend conflicts). */
export class ApiError extends Error {
  status: number
  body: unknown
  constructor(message: string, status: number, body: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

const BASE = ''

/** Must match chronicle serve `TOKEN_HEADER` when LAN auth is enabled. */
export const AUTH_HEADER = 'X-Chronicle-Token'

const PARA_PREFIX =
  /^(00-Inbox|10-Work|20-Personal|30-Knowledge|90-Archive)(\/|$)/

/** Normalize SPA note path for `/kb/notes/{path}` (PARA full path or legacy relative). */
export function kbApiPath(path: string): string {
  const p = path.replace(/^\/+/, '')
  if (PARA_PREFIX.test(p)) return p
  return p.replace(/^kb\/notes\//, '')
}

/** URL-encode each path segment for `/kb/notes/{path}` (spaces, unicode, etc.). */
export function encodeKbNotePath(path: string): string {
  return kbApiPath(path)
    .split('/')
    .map((seg) => encodeURIComponent(seg))
    .join('/')
}

export type RequestOpts = {
  signal?: AbortSignal
}

/** Cached pairing token from GET /connect; `undefined` = not fetched yet. */
let lanToken: string | null | undefined
let lanTokenPromise: Promise<string | null> | null = null

/** Test helper — clears the lazy LAN token cache. */
export function resetLanAuthTokenCache(): void {
  lanToken = undefined
  lanTokenPromise = null
}

function isConnectPath(path: string): boolean {
  return path === '/connect' || path.startsWith('/connect?')
}

async function fetchLanToken(signal?: AbortSignal): Promise<string | null> {
  const res = await fetch(`${BASE}/connect`, { signal })
  if (!res.ok) return null
  const body = (await res.json()) as ConnectInfo
  const token = typeof body.token === 'string' && body.token.length > 0 ? body.token : null
  return token
}

/**
 * Lazily load the serve pairing token (GET /connect is auth-exempt).
 * Returns null when loopback /connect omits the token or auth is off.
 * Attach the header on all API requests when a token is available.
 */
export async function ensureLanAuthToken(signal?: AbortSignal): Promise<string | null> {
  if (lanToken !== undefined) return lanToken
  if (!lanTokenPromise) {
    lanTokenPromise = fetchLanToken(signal)
      .then((token) => {
        lanToken = token
        return token
      })
      .catch((err: unknown) => {
        // Propagate abort so callers can cancel; other failures → no token.
        if (err instanceof DOMException && err.name === 'AbortError') throw err
        if (err && typeof err === 'object' && 'name' in err && (err as { name: string }).name === 'AbortError') {
          throw err
        }
        lanToken = null
        return null
      })
      .finally(() => {
        lanTokenPromise = null
      })
  }
  return lanTokenPromise
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const authHeaders: Record<string, string> = {}
  // Avoid recursing into /connect while seeding the token cache.
  if (!isConnectPath(path)) {
    const token = await ensureLanAuthToken(
      init?.signal instanceof AbortSignal ? init.signal : undefined,
    )
    if (token) authHeaders[AUTH_HEADER] = token
  }

  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init?.headers,
      ...authHeaders,
    },
  })
  if (!res.ok) {
    let parsedBody: unknown = null
    let detail: unknown = res.statusText
    try {
      parsedBody = await res.json()
      const d = (parsedBody as { detail?: unknown })?.detail
      detail = d ?? parsedBody
    } catch {
      /* ignore */
    }
    throw new ApiError(
      typeof detail === 'string' ? detail : JSON.stringify(detail),
      res.status,
      parsedBody,
    )
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

function withSignal(init: RequestInit | undefined, opts?: RequestOpts): RequestInit | undefined {
  if (!opts?.signal) return init
  return { ...init, signal: opts.signal }
}

export const api = {
  health: (opts?: RequestOpts) => request<HealthResponse>('/health', withSignal(undefined, opts)),

  connect: (opts?: RequestOpts) => request<ConnectInfo>('/connect', withSignal(undefined, opts)),

  models: {
    get: (opts?: RequestOpts) => request<ModelsState>('/models', withSignal(undefined, opts)),
    set: (body: Partial<ModelsState>, opts?: RequestOpts) =>
      request<ModelsState>(
        '/models',
        withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
      ),
  },

  entries: {
    list: (
      params?: {
        limit?: number
        offset?: number
        type?: EntryType
        processed?: boolean
        /** Inclusive YYYY-MM-DD (entry_day / offset wall-date). */
        from?: string
        /** Inclusive YYYY-MM-DD (entry_day / offset wall-date). */
        to?: string
      },
      opts?: RequestOpts,
    ) => {
      const q = new URLSearchParams()
      if (params?.limit != null) q.set('limit', String(params.limit))
      if (params?.offset != null) q.set('offset', String(params.offset))
      if (params?.type) q.set('type', params.type)
      if (params?.processed != null) q.set('processed', String(params.processed))
      if (params?.from) q.set('from', params.from)
      if (params?.to) q.set('to', params.to)
      const qs = q.toString()
      return request<EntriesPage>(`/entries${qs ? `?${qs}` : ''}`, withSignal(undefined, opts))
    },
    get: (id: string, opts?: RequestOpts) =>
      request<Entry>(`/entries/${encodeURIComponent(id)}`, withSignal(undefined, opts)),
    create: (
      body: {
        type: EntryType
        text: string
        tags?: string[]
        mood?: number | null
        ts?: string
        id?: string
      },
      opts?: RequestOpts,
    ) =>
      request<Entry>(
        '/entries',
        withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
      ),
    patch: (
      id: string,
      body: Partial<{
        type: EntryType
        text: string
        tags: string[]
        mood: number | null
      }>,
      opts?: RequestOpts,
    ) =>
      request<Entry>(
        `/entries/${encodeURIComponent(id)}`,
        withSignal({ method: 'PATCH', body: JSON.stringify(body) }, opts),
      ),
    remove: (id: string, opts?: RequestOpts) =>
      request<{ ok: boolean }>(
        `/entries/${encodeURIComponent(id)}`,
        withSignal({ method: 'DELETE' }, opts),
      ),
  },

  kb: {
    tree: (params?: { section?: NotesSection }, opts?: RequestOpts) => {
      const q = params?.section ? `?section=${params.section}` : ''
      return request<KbTreeResponse>(`/kb/tree${q}`, withSignal(undefined, opts))
    },
    get: (path: string, opts?: RequestOpts) => {
      const rel = encodeKbNotePath(path)
      return request<NoteContent>(`/kb/notes/${rel}`, withSignal(undefined, opts))
    },
    put: (
      path: string,
      body: {
        content: string
        title?: string
        tags?: string[]
        section?: NotesSection
        /** Required on overwrite; omit on create. */
        base_hash?: string
      },
      opts?: RequestOpts,
    ) => {
      const rel = encodeKbNotePath(path)
      return request<NoteContent>(
        `/kb/notes/${rel}`,
        withSignal({ method: 'PUT', body: JSON.stringify(body) }, opts),
      )
    },
    create: (
      path: string,
      body: { content: string; title?: string; tags?: string[]; section?: NotesSection },
      opts?: RequestOpts,
    ) => {
      const rel = encodeKbNotePath(path)
      return request<NoteContent>(
        `/kb/notes/${rel}`,
        withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
      )
    },
    remove: (path: string, opts?: RequestOpts) => {
      const rel = encodeKbNotePath(path)
      return request<{ ok: boolean; deleted?: string; deleted_all?: string[] }>(
        `/kb/notes/${rel}`,
        withSignal({ method: 'DELETE' }, opts),
      )
    },
    move: (
      body: { from_path: string; to_path: string },
      opts?: RequestOpts,
    ) =>
      request<{ ok: boolean; from_path: string; to_path: string; quarantined?: string[] }>(
        '/kb/move',
        withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
      ),
    archive: (body: { path: string }, opts?: RequestOpts) =>
      request<{ ok: boolean; from_path: string; to_path: string; quarantined?: string[] }>(
        '/kb/archive',
        withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
      ),
    templates: (opts?: RequestOpts) =>
      request<{ files: { name: string; path: string; content: string }[] }>(
        '/kb/templates',
        withSignal(undefined, opts),
      ),
  },

  notes: {
    list: (opts?: RequestOpts) =>
      request<{ files: { path: string; name: string }[] }>('/notes', withSignal(undefined, opts)),
    get: (path: string, opts?: RequestOpts) => {
      const rel = path.replace(/^notes\//, '')
      return request<NoteContent>(`/notes/${rel}`, withSignal(undefined, opts))
    },
  },

  journal: {
    days: (opts?: RequestOpts) =>
      request<{ days: JournalDay[] }>('/journal/days', withSignal(undefined, opts)),
    entry: (id: string, opts?: RequestOpts) =>
      request<JournalEntryBody>(
        `/journal/entries/${encodeURIComponent(id)}`,
        withSignal(undefined, opts),
      ),
    amend: (id: string, body: { body: string; base_hash: string }, opts?: RequestOpts) =>
      request<JournalAmendResult>(
        `/journal/entries/${encodeURIComponent(id)}`,
        withSignal({ method: 'PATCH', body: JSON.stringify(body) }, opts),
      ),
    acceptDisk: (id: string, opts?: RequestOpts) =>
      request<JournalAmendResult & { accepted_disk?: boolean }>(
        `/journal/entries/${encodeURIComponent(id)}/accept-disk`,
        withSignal({ method: 'POST' }, opts),
      ),
  },

  brain: {
    graph: (opts?: RequestOpts) =>
      request<BrainGraph>('/brain/graph', withSignal(undefined, opts)),
    insights: (params?: { date?: string; limit?: number }, opts?: RequestOpts) => {
      const q = new URLSearchParams()
      if (params?.date) q.set('date', params.date)
      if (params?.limit != null) q.set('limit', String(params.limit))
      const qs = q.toString()
      return request<InsightBundle>(
        `/brain/insights${qs ? `?${qs}` : ''}`,
        withSignal(undefined, opts),
      )
    },
  },

  curation: {
    post: (op: CurationOp, opts?: RequestOpts) =>
      request<{ ok: boolean; op: CurationOp }>(
        '/curation/ops',
        withSignal({ method: 'POST', body: JSON.stringify(op) }, opts),
      ),
  },

  recall: (
    body: {
      message: string
      history?: { role: string; content: string }[]
      scope?: Scope
      node_ids?: string[]
    },
    opts?: RequestOpts,
  ) =>
    request<RecallResponse>(
      '/recall',
      withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
    ),

  search: (body: { query: string; top_k?: number; scope?: Scope }, opts?: RequestOpts) =>
    request<{ query: string; hits: SearchHit[]; ollama: boolean }>(
      '/search',
      withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
    ),

  process: (opts?: { run_brain?: boolean; dry_run?: boolean }, req?: RequestOpts) => {
    const q = new URLSearchParams()
    q.set('run_brain', String(opts?.run_brain ?? true))
    q.set('dry_run', String(opts?.dry_run ?? false))
    return request<Record<string, unknown>>(
      `/process?${q}`,
      withSignal({ method: 'POST' }, req),
    )
  },

  enrichKb: (opts?: RequestOpts) =>
    request<Record<string, unknown>>('/enrich/kb', withSignal({ method: 'POST' }, opts)),

  /** E2EE unlock lifecycle (contract v1.11). Key stays server-side in memory. */
  e2ee: {
    status: (opts?: RequestOpts) =>
      request<E2eeStatus>('/auth/e2ee/status', withSignal(undefined, opts)),
    unlock: (passphrase: string, opts?: RequestOpts) =>
      request<E2eeStatus & { ok: boolean }>(
        '/auth/e2ee/unlock',
        withSignal({ method: 'POST', body: JSON.stringify({ passphrase }) }, opts),
      ),
    lock: (opts?: RequestOpts) =>
      request<{ ok: boolean; unlocked: boolean }>(
        '/auth/e2ee/lock',
        withSignal({ method: 'POST' }, opts),
      ),
    rotate: (
      body: { old_passphrase: string; new_passphrase: string },
      opts?: RequestOpts,
    ) =>
      request<{ ok: boolean; resealed: number; skipped_corrupt: number }>(
        '/auth/e2ee/rotate',
        withSignal({ method: 'POST', body: JSON.stringify(body) }, opts),
      ),
  },

  /** Single-use SSE tickets (EventSource cannot send headers). */
  events: {
    ticket: (opts?: RequestOpts) =>
      request<StreamTicket>('/events/ticket', withSignal(undefined, opts)),
  },

  /** Paired-device management (loopback-only on the serve). */
  devices: {
    list: (opts?: RequestOpts) =>
      request<{ devices: PairedDevice[] }>('/auth/devices', withSignal(undefined, opts)),
    revoke: (name: string, opts?: RequestOpts) =>
      request<{ ok: boolean; revoked: string }>(
        `/auth/devices/${encodeURIComponent(name)}`,
        withSignal({ method: 'DELETE' }, opts),
      ),
  },
}
