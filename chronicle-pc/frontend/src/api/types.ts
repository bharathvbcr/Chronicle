/** API types matching Phase 1 REST contract. */

export type EntryType = 'log' | 'idea' | 'dream' | 'reflection'
export type Scope = 'journal' | 'kb' | 'all'
export type ThemePref = 'system' | 'light' | 'dark'

export type NodeKind = 'topic' | 'entry' | 'person' | 'place' | 'project' | 'concept'

export interface Entry {
  id: string
  ts: string
  type: EntryType
  text: string
  tags: string[]
  mood?: number | null
  images?: string[]
  audio?: string[]
  processed?: boolean
  filed?: boolean
  filed_content_hash?: string | null
  filed_path?: string | null
  device?: string
  /** E2EE blob (contract v1.11). Present with empty text while locked. */
  text_enc?: Record<string, string> | null
}

/** GET /auth/e2ee/status payload (non-secret KDF/check material only). */
export interface E2eeStatus {
  enabled: boolean
  unlocked?: boolean
  kdf?: { alg?: string; iter?: number; salt?: string } | null
  check?: { nonce?: string; ct?: string } | null
}

export interface EntriesPage {
  total: number
  offset: number
  limit: number
  entries: Entry[]
}

export interface GraphNode {
  id: string
  kind: NodeKind | string
  label: string
  weight?: number
  pinned?: boolean
  hidden?: boolean
  annotation?: string
  doc?: string
  entry_id?: string
  ts?: string
  /** KB category key (e.g. bio, ai) — colors via BrainGraph.groups */
  group?: string
}

export interface GraphEdge {
  from: string
  to: string
  rel: string
  score?: number
}

export interface GraphGroup {
  label: string
  color: string
}

export interface BrainGraph {
  version: number
  generated?: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** Category defs with colors (from migrate-kb / brain.json) */
  groups?: Record<string, GraphGroup>
}

export interface Citation {
  id: string
  kind: string
  score?: number
  snippet?: string
  path?: string | null
  node_ids: string[]
}

export interface RecallResponse {
  answer: string
  citations: Citation[]
  seed_node_ids: string[]
  degraded: boolean
}

export interface SearchHit {
  id: string
  kind: string
  path?: string
  text?: string
  score?: number
}

export interface KbTreeNode {
  path: string
  name?: string
  type: 'dir' | 'file'
  children?: KbTreeNode[]
}

export interface KbTreeResponse {
  tree: KbTreeNode
  files: string[]
}

export interface NoteContent {
  path: string
  content: string
  /** SHA-256 of on-disk body; send as `base_hash` on PUT overwrite. */
  content_hash?: string
}

export interface KbNoteConflict {
  detail: string
  on_disk_hash?: string
}

export type NotesSection = 'kb' | 'notes'

export interface JournalDay {
  date: string
  path: string
  entry_ids: string[]
}

export interface JournalEntryBody {
  id: string
  date: string
  path: string
  body: string
  body_hash: string
  filed_content_hash: string | null
  editable: boolean
}

export interface JournalAmendResult {
  id: string
  path: string
  hash: string
  prose_edited: true
}

export interface JournalAmendConflict {
  detail: string
  on_disk_hash: string | null
  filed_content_hash: string | null
}

export interface ModelsState {
  llm: string
  embed: string
  vision: string
  base_url: string
  num_ctx: number
  temperature: number | null
  available: string[]
  ollama_ok: boolean
  provider?: 'ollama' | 'grok' | 'vertex'
  provider_ok?: boolean
  provider_error?: string | null
  cloud_consent?: boolean
  vision_cloud_consent?: boolean
  grok_base_url?: string
  grok_model?: string | null
  vertex_project?: string | null
  vertex_location?: string
  vertex_model?: string | null
  embed_note?: string
}

export interface HealthResponse {
  ok: boolean
  chronicle_dir?: string
  ollama?: boolean
  provider?: string
  provider_ok?: boolean
  models?: Record<string, string>
}

export interface ConnectInfo {
  v: number
  host: string
  port: number
  bind_host?: string
  lan_ip?: string | null
  base: string
  kb_proxied?: boolean
  /** Present when serve issued a pairing token (LAN auth). */
  token?: string | null
  auth_required?: boolean
  qr?: { v: number; base: string; token?: string }
}

export interface CurationOp {
  op: string
  ts?: string
  device?: string
  node?: string
  label?: string
  from?: string
  into?: string
  to?: string
  rel?: string
  text?: string
  id?: string
  doc?: string
}

export interface InsightBundle {
  insights: Record<string, unknown>[]
  dates: string[]
}

export interface PairedDevice {
  name: string
  created?: string | null
}

/** GET /events/ticket payload — single-use SSE auth for EventSource. */
export interface StreamTicket {
  ticket: string
  expires_in: number
}
