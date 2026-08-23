# Chronicle Data Contract (v1.11)

**v1.11 additions (all additive):** entry `text_enc` E2EE blob + config `e2ee` block; connect payload `v:2` with `tls_fp` + default LAN TLS; persistent per-device pairing tokens (`chronicle pair/unpair/pairs`, `~/.config/chronicle/pairing.json`); `POST /entries/mirror` (phone outbox); `GET /events/stream` (SSE); `/auth/e2ee/{status,unlock,lock}`; auth-failure rate limiting. Passphrase rotation reseals all sealed entries (`chronicle e2ee-setup --rotate`, `POST /auth/e2ee/rotate`) — phone adopts new params automatically on next unlock. LAN browsers authenticate `/events/stream` via single-use tickets from `GET /events/ticket`. **Lock semantics:** locking purges sealed entries from `index/` (search/recall stay fail-closed; unlock + `chronicle index` restores); `chronicle e2ee-setup` refuses to overwrite an enabled block and re-enabling after `--disable` requires the ORIGINAL passphrase (same salt/check — never re-minted). Older clients keep working except that LAN serve is now **https by default** (`--no-tls` escape hatch).

Identical copy must live in both `chronicle-android/` and `chronicle-pc/`. Schema changes require updating **both** copies **and** the matching `contract/*.schema.json` files first. The contract is executable: prose here, JSON Schema under `contract/`.

Identical copy must live in both `chronicle-android/` and `chronicle-pc/`. Schema changes require updating **both** copies **and** the matching `contract/*.schema.json` files first. The contract is executable: prose here, JSON Schema under `contract/`.

## Folder layout

Synced phone ↔ PC via Syncthing. Apps only read/write files; they never sync.

```
Chronicle/
├── _capture/entries/yyyy/MM/<id>.json   # preferred capture (layout_version ≥ 2)
├── _attachments/yyyy/MM/<id>_<n>.jpg|.m4a  # preferred media
├── 40-Journal/YYYY-MM-DD.md             # file-once prose SoT (entry:<id> fences)
├── _system/derived/{daily,weekly,monthly,yearly,topics}/  # regenerable aggregates
├── entries/ / img/ / audio/ / notes/    # legacy — dual-read until migrate completes
├── Upcoming.md                      # pipeline-regenerated rolling deadlines (derived-at-root; UI read-only)
├── 00-Inbox/                        # PARA knowledge (preferred) — unsorted; CLAUDE.md agent guide
├── 10-Work/                         # work; Projects/ People/ Meetings/ Reference/ ResumePoints/; CLAUDE.md
├── 20-Personal/                     # personal; Health/ Family/ Finance/ Home/ Travel/; CLAUDE.md
├── 30-Knowledge/                    # evergreen skills & reference; CLAUDE.md
├── 90-Archive/                      # cold knowledge; CLAUDE.md
├── kb/                              # files + knowledge.json only (notes dual-read cutover done)
│   ├── notes/                       # empty tombstone after cutover-kb (do not write)
│   ├── files/                       # PDFs/docx, read-only reference
│   └── knowledge.json               # structured profile, Mac-owned
├── _system/                         # conventions, preferences, derived stubs (synced)
├── _templates/                      # note templates: note, project, person, meeting, daily, attachment-note
├── brain/                           # Mac-written, phone/SPA read-only, synced
│   ├── graph.json                   # topics/entities/concepts + recent-12mo entry nodes
│   ├── graph-archive/yyyy.json      # older entry nodes, on-demand
│   ├── insights/yyyy/YYYY-MM-DD.json
│   ├── prompts.json
│   ├── tags.json                    # taxonomy: canonical, aliases, hierarchy, counts
│   └── enrich/yyyy-MM.json          # per-entry: auto_tags, summary_line, entities
├── curation/ops/<device>.jsonl      # user graph edits, append-only per device
├── health/yyyy/MM.json              # phone-imported sleep/steps (Health Connect)
├── index/                           # Mac-only, EXCLUDED from sync (sqlite + sqlite-vec)
├── Home.md / CLAUDE.md              # vault landing + agent guide
├── .claude/skills/                  # capture-workflow/{SKILL,attachments,reorganization}.md,
│                                    # retrieval-format/SKILL.md, vault-maintenance/{SKILL,link-repair}.md
└── config.json                      # PC-owned: models, timezone, layout_version
```

**layout_version:** `config.json` field. Phase 4 file-once layout is **`2`**. `chronicle process` / `chronicle serve` **refuse** a mismatched version with a loud error (no silent path black hole). Upgrade with `chronicle backup` then `chronicle migrate-journal-v2 --apply --i-have-backup`. Co-release APK + CLI. Structure additions (area sub-folders, nested `CLAUDE.md`, templates, `Upcoming.md`, skill supporting files) are **additive within layout_version 2**; `chronicle init-vault-structure` seeds them idempotently (create-only, never overwrites).

**Knowledge:** PARA areas only (`00-Inbox/`, `10-Work/`, `20-Personal/`, `30-Knowledge/`, `90-Archive/`). Legacy `kb/notes/` dual-read is **done** — run `chronicle backup` then `chronicle cutover-kb --apply --i-have-backup` on any vault that still has leftover notes under `kb/notes/`. ResumePoints live under `10-Work/ResumePoints/`. Phone creates default to `00-Inbox/`. `chronicle doctor` warns on leftover `kb/notes/` files.

**Notes sections (UI):** Both apps present three sub-areas over the same folders. **Notes** = `00-Inbox/`, `10-Work/`, `20-Personal/`, `90-Archive/` (editable). **Knowledge Base** = `30-Knowledge/` (editable). **Journal** = `40-Journal/` + `_system/derived/` (fence-body amends only via serve `PATCH /journal/entries/{id}`; otherwise read-only). Creates default: Notes → `00-Inbox/`, Knowledge Base → `30-Knowledge/`. Creates targeting an explicit `section` are rejected (400) if the path falls outside that section's areas. Chrome/scaffold basenames (`CLAUDE.md`, `.gitkeep`, `README.md`) seeded by `chronicle init-vault-structure` are never listed as notes in either app (PC: `path_map.CHROME_BASENAMES`; Android: `KnowledgePathMap.CHROME_BASENAMES`) — MOCs (`MOC-*.md`) are kept.

**Journal (Phase 4 file-once):** Prefer `_capture/entries/`, `_attachments/`, `40-Journal/`. Legacy `entries/`, `img/`, `audio/`, `notes/` remain dual-read until migrate. Aggregates live under `_system/derived/` — never rewrite human journal body.

`kb/files/` is read-only reference material. `kb/knowledge.json` is Mac-owned.

**KnowledgeBase retired:** the old `KnowledgeBase/` product (brain_server, KnowledgeBrain.html) is archived. Live content lives in the vault (PARA + `kb/files` + `kb/knowledge.json` + `brain/`). Historical `Docs/`, `ReadMe/`, `brain.json` remain as `migrate-kb` input only. Repo `portfolio/ResumePoints/` is a tombstone → use `10-Work/ResumePoints/`.

**Obsidian:** Open the Chronicle vault **root** (not a mirrored subfolder). Exclude machine dirs: `index/`, `brain/`, `_capture/`, `_attachments/`, `entries/`, `img/`, `audio/`, `_staging/`. Config field `vault_mirror` is **deprecated**; leave it unset.

### Journal file-once state machine (frozen)

| Rule | Spec |
|------|------|
| States | `captured` → `processed` (file-ready) → `filed` |
| File-ready | `(no audio) OR (text non-empty)`; captions best-effort |
| Atomicity | Write MD block → set `filed=true` + `filed_content_hash` + `filed_path` |
| Stuck | `processed && !filed` = doctor retry queue |
| Phone lock | Edit/delete only `processed=false` (filing does not reopen JSON) |
| **Prose SoT** | Body inside `<!-- entry:<id> -->` … `<!-- /entry:<id> -->` in `40-Journal/YYYY-MM-DD.md` |
| **Structured SoT** | `mood`/`tags`/`type`/`ts`/`images`/`audio` in JSON **forever** |
| JSON `text` after filed | Frozen provenance; RAG prefers MD prose |
| Amend gate | Pipeline rewrites block **iff** on-disk hash == `filed_content_hash` **and** entry not `prose_edited` |
| **UI amend** | serve `PATCH /journal/entries/{id}`: requires on-disk hash == `filed_content_hash` == client `base_hash` → rewrite fence body, set `filed_content_hash` to new body hash, set `prose_edited=true`. Mismatch → HTTP 409. UI never writes journal MD directly (phone included — LAN serve only) |
| Insert | Missing fence → append; **never** whole-file regen of journal |
| Daily chrome | Aggregates in `_system/derived/`, not over human journal body |
| JSON location | In-place `filed=true`; **no** move to `archived/` in v1 |
| Media | Embeds with `_attachments/...`; migrate rewrites JSON and MD |
| Brain | Keep `entry:{id}`; optional `doc` → journal path after filed |
| Rebuild | Insert missing + amend untouched only |
| MD conflicts | Doctor detect; never auto-delete |

### Sync ignore list (part of this contract)

Ignore from Syncthing: `index/`, `*.tmp`, `.DS_Store`, `.stfolder`.

### Atomic writes

Both platforms: write to a temp file in the **same directory**, then rename into place (Android: SAF `renameDocument`). Never leave partial JSON as the final path.

---

## Mac data path (serve, not File System Access)

On Mac, the **primary** UI is the React SPA (served by FastAPI at `/`, or wrapped in Tauri). It talks to the vault **only** through `chronicle serve` REST. The browser File System Access API path (`dashboard/dashboard.html` at `/legacy`) is legacy fallback, not the default write path.

Phone still uses SAF for local vault IO + optional LAN calls to the same serve API.

---

## REST API (`chronicle serve`, default `:8765`)

| Method | Path | Notes |
|--------|------|--------|
| `GET` | `/health` | Liveness + vault status |
| `GET` | `/connect`, `/connect/qr.svg` | Phone pairing discovery. **`token` / `qr.token` only for loopback clients**; non-loopback omits the secret. Terminal/Settings QR still embeds the token in-memory. |
| `GET`/`POST` | `/models` | Read/update Ollama model config |
| `GET`/`POST`/`PATCH`/`DELETE` | `/entries`, `/entries/{id}` | CRUD; new ids use `-pc` suffix. **E2EE (v1.11):** when the vault is enabled+locked, plaintext writes are refused with **423** (`POST /entries`, `/entries/mirror`), and encrypted-entry `PATCH`/`DELETE` are refused with **423** ("unlock to edit/delete") — deleting unread ciphertext is treated as data destruction. Unlocked vaults auto-seal fresh/edited text. Locking purges sealed entries' documents from the search index (fail-closed search) |
| `GET` | `/events/ticket` | Issue a **single-use stream ticket** (`{ticket, expires_in:30}`, 256-bit, pool capped at 64). Token-required. EventSource cannot send headers — LAN browsers append `?ticket=` to `/events/stream`; replay/expiry → 401 (and never burn the rate limiter). Header-token auth on the stream remains valid (Android) |
| `POST` | `/auth/e2ee/rotate` | `{old_passphrase, new_passphrase}` → verify-all-then-rewrite reseal of every encrypted entry under fresh KDF params; any unreadable blob aborts with nothing written (**403**). Equivalent to `chronicle e2ee-setup --rotate`; phone re-reads params on next unlock |
| `POST` | `/entries/mirror` | Idempotent LAN outbox push of a phone capture: full `{entry}` JSON with an `-an` id. Identical content → `{ok, deduped:true}`; missing → written to `_capture/entries`; differing → **409** (Syncthing stays SoT). E2EE blobs are copied verbatim |
| `GET` | `/events/stream` | SSE vault-change notifications (`event: vault`) + heartbeats; token required |
| `GET`/`POST` | `/auth/e2ee/status`, `/auth/e2ee/unlock`, `/auth/e2ee/lock` | E2EE state, passphrase unlock (in-memory key), lock. Token required |
| `POST` | `/entries/{id}/images`, `/entries/{id}/audio` | Media upload under `_attachments/` (≤50 MB; legacy `img/`/`audio/` dual-read) |
| `GET` | `/kb/tree` | Knowledge note tree (PARA areas only); optional `?section=kb\|notes` filter (`kb` = `30-Knowledge/`, `notes` = `00-Inbox/`/`10-Work/`/`20-Personal/`/`90-Archive/`) |
| `GET`/`PUT`/`POST`/`DELETE` | `/kb/notes/{path}` | Markdown CRUD; `{path}` must be PARA (`10-Work/...`, `30-Knowledge/...`, …). Legacy `kb/notes/…` → **410** with cutover hint. `GET` returns `content_hash` (SHA-256). Overwrite `PUT` requires `base_hash` matching on-disk hash → else **409** `{detail, on_disk_hash}`. Creates ignore `base_hash`. Create body may pass `section` to pick the default area. **Prefer `POST /kb/archive` over `DELETE`.** |
| `POST` | `/kb/move` | Move note (`contract/kb-move.schema.json`); body `{from_path, to_path}` — both PARA; `to_path` must be PARA |
| `POST` | `/kb/archive` | Archive under `90-Archive/<original subpath>/`. Preferred over hard delete |
| `GET` | `/kb/templates` | List `_templates/*.md` (`{files:[{name,path,content}]}`) for create picker |
| `GET` | `/notes`, `/notes/{path}` | Journal (`40-Journal/`), derived (`_system/derived/`), legacy `notes/`, root `Upcoming.md` (read-only). **Not** the Notes tab — that uses `/kb/*` |
| `GET` | `/journal/days` | Day list: `[{date, path, entry_ids}]` from `40-Journal/*.md` fences |
| `GET` | `/journal/entries/{id}` | Fence body + `body_hash` + `filed_content_hash` + `editable` |
| `PATCH` | `/journal/entries/{id}` | Amend fence body via pipeline amend gate (`contract/journal-amend.schema.json`); 409 `{detail, on_disk_hash, filed_content_hash}` on hash mismatch |
| `POST` | `/journal/entries/{id}/accept-disk` | Resync `filed_content_hash` to on-disk fence after external (Obsidian) edit; sets `prose_edited` |
| `GET` | `/brain/graph`, `/brain/insights` | Graph + insights |
| `POST` | `/curation/ops` | Append to `curation/ops/pc.jsonl` |
| `POST` | `/search` | Index search (`top_k` 1–50) |
| `POST` | `/recall` | Body may include `node_ids` (graph-seeded); citations include `node_ids` mapping |
| `POST` | `/ask`, `/resume` | KB ask / resume points (hard failures → HTTP 5xx, body keeps `ok:false`) |
| `POST` | `/process` | Trigger incremental pipeline |
| `GET` | `/` | React SPA (when `frontend/dist/` built) |
| `GET` | `/legacy` | Legacy single-file dashboard |

Ask/Resume/Recall run **natively** against the vault index — no separate `brain_server`.

### LAN pairing auth, TLS, and pairing tokens

When `chronicle serve` binds beyond localhost (default LAN mode → `0.0.0.0`):

- **Auth:** default-deny; header `X-Chronicle-Token` required on all non-exempt paths. Tokens are **persistent per device** (`~/.config/chronicle/pairing.json`, mode 0600): `chronicle pair <name>` prints a QR for a new device; `chronicle unpair <name>` revokes instantly (revocation is authoritative — no parallel legacy token). Failed attempts are rate-limited per client IP (429 after repeated failures). Exempt without token: `OPTIONS`, `/health`, `/`, `/legacy`, `/favicon*`, `/assets/*`, SPA shell routes, and `/connect*` (discovery only).
- **TLS:** LAN serves **https by default** with a self-signed cert at `~/.config/chronicle/tls/` (regenerated if the advertised LAN IP changes). The QR/connect payload carries `tls_fp` — base64 SHA-256 of the cert's SPKI — which clients pin (OkHttp `CertificatePinner`). `--no-tls` downgrades to cleartext http (discouraged).
- **QR payload v2:**

```json
{"v":2,"base":"https://<lan-ip>:8765","token":"<url-safe-secret>","tls_fp":"<sha256-b64>"}
```

`GET /connect` and `/connect/qr.svg` omit `token` unless the client is loopback. `index/serve.json` never stores tokens. Localhost-only binds (`--no-lan`) skip auth and TLS.

**mDNS (optional):** serve advertises `_chronicle._tcp.local.` when the optional `zeroconf` package is installed (`pip install -e ".[mdns]"`; TXT carries `fp`=cert fingerprint). Discovery is convenience only — QR pairing remains canonical.


### E2EE (opt-in, field-level)

`chronicle e2ee-setup` derives a key from a passphrase (PBKDF2-HmacSHA256, 600k iterations, random salt) and stores only **non-secret** material in `config.json`: `{"e2ee":{"enabled":true,"kdf":{"alg","iter","salt"},"check":{"nonce","ct"}}}`. The check blob verifies passphrases on both phone and PC; the key itself lives only in process memory (`POST /auth/e2ee/unlock`, or `chronicle unlock` for CLI runs).

When enabled, captures seal `text` into `text_enc = {v, nonce, ct}` (AES-256-GCM) and set `text: ""`. Both apps round-trip unknown keys, so older versions never destroy blobs. Locked behavior is fail-closed: pipeline skips transcription/vision/filing/indexing for locked entries rather than writing plaintext; search/recall exclude them. Filed `40-Journal` prose is intentionally plaintext (Obsidian compatibility) — E2EE protects unfiled captures at rest (lost-phone SAF dir, folder backups) and the capture window.

### Syncthing / concurrency

Phone SAF edits and Mac `serve`/pipeline writes can still race via Syncthing. `vault_process_lock` on journal amend and KB mutators is **best-effort local only** — not a distributed lock. Prefer one writer per note; resolve `.sync-conflict-*` with `chronicle doctor`.

---

## ENTRY SCHEMA v1.4 (text remains CommonMark string; no HTML/AST field)

```json
{
  "version": 1,
  "id": "2026-07-09_213045-an",
  "ts": "2026-07-09T21:30:45+05:30",
  "type": "log",
  "text": "...",
  "tags": ["work", "#plan"],
  "images": ["_attachments/2026/07/2026-07-09_213045-an_1.jpg"],
  "audio": ["_attachments/2026/07/2026-07-09_213045-an_1.m4a"],
  "mood": 4,
  "processed": false,
  "filed": false,
  "filed_content_hash": null,
  "filed_path": null,
  "prose_edited": false
}
```

| Field | Rules |
|-------|--------|
| `version` | Integer schema version; currently `1`. `chronicle migrate` handles bumps. |
| `id` | `yyyy-MM-dd_HHmmss-<dev>` where `<dev>` is `an` (Android) or `pc` (Mac via serve). Same-second same-device collision → append `_2`, `_3`, … Filenames = `<id>.json`. |
| `ts` | ISO-8601 with offset. **Day attribution** uses the local date of this offset (travel-safe), never `config.json` timezone. Config timezone is only a fallback for malformed timestamps. |
| `type` | One of: `log`, `idea`, `dream`, `reflection`. |
| `text` | String; may be empty when `audio` is present (Mac fills via whisper before flipping `processed`). After `filed=true`, frozen provenance — prose SoT is the MD block. |
| `tags` | Array of strings. Conventions (no extra fields): `#plan` = trackable intention; `future:YYYY-MM-DD` = time capsule; `prompt:<id>` = prompt answer. |
| `images` | Relative paths under `_attachments/yyyy/MM/` (preferred) or legacy `img/yyyy/MM/`. JPEG only, ≤2560px long edge. |
| `audio` | Optional. Relative paths under `_attachments/yyyy/MM/` or legacy `audio/yyyy/MM/`. `.m4a` (AAC) only. |
| `mood` | Optional integer 1–5. |
| `processed` | Boolean. Phone/Mac UI may edit or delete **only while `false`**. True when file-ready. |
| `filed` | Optional boolean. True after MD block written to `40-Journal/`. |
| `filed_content_hash` | Optional. SHA-256 of fenced block body (amend gate). |
| `filed_path` | Optional. Vault-relative path e.g. `40-Journal/2026-07-09.md`. |
| `prose_edited` | Optional boolean. True after the fence body was amended by a user via serve `PATCH /journal/entries/{id}`. When true, the pipeline never re-renders this block from JSON (insert-if-missing still applies; doctor reports missing fences distinctly). |

Unknown fields must be preserved on load→save (soft-roll). Readers may ignore unknown keys they do not understand; writers must not strip unknown keys.

---

## GRAPH SCHEMA v1

`brain/graph.json` (and archive shards) carry `version`, `generated` (ISO-8601 freshness), `nodes`, `edges`.

**Nodes** — `kind`: `topic` | `entry` | `person` | `place` | `project` | `concept` (`concept` = user-created). Deterministic ids (e.g. `topic:health`, `entry:2026-07-09_213045-an`, `concept:startup-idea`).

Optional node field `doc` — vault-relative path to a knowledge note (PARA, e.g. `"10-Work/ResumePoints/foo.md"`). Index `kind` values (`entry` / `note` / `kb`) are a separate namespace from graph node kinds — do not conflate.

**Edges** — `rel`: `about` | `related` | `continues` | `mentions` | `manual`, plus optional `score`.

`graph.json` holds all topic/entity/concept nodes + edges, but **entry nodes only for the last 12 months + pinned/thread members**. Older entry nodes live in `brain/graph-archive/yyyy.json`, loaded on demand.

---

## CURATION OPS v1

Path: `curation/ops/<device>.jsonl` (`phone` / `pc` / device code). One JSON object per line, append-only per device (conflict-free by construction).

Common shape: `{ "op", "ts", "device", ...args }`.

| op | args |
|----|------|
| `pin` / `unpin` | `{"node": "topic:health"}` |
| `hide` / `unhide` | `{"node": "entry:2026-07-09_213045-an"}` |
| `rename` | `{"node": "topic:ml", "label": "machine learning"}` |
| `merge` | `{"from": "topic:gym", "into": "topic:fitness"}` — feeds `tags.json` aliases |
| `link` / `unlink` | `{"from": "entry:A", "to": "topic:B", "rel": "manual"}` |
| `annotate` | `{"node": "topic:health", "text": "focus area for Q3"}` |
| `create_concept` | `{"id": "concept:startup-idea", "label": "Startup idea"}` |
| `set_doc` | `{"node": "concept:startup-idea", "doc": "30-Knowledge/startup-idea.md"}` |
| `delete_concept` | `{"node": "concept:startup-idea"}` |

Pipeline replays ops (ts order, last-write-wins per node/edge) as the final step of `chronicle brain`. Renderers overlay local not-yet-consumed ops. User intent always beats machine inference. `doctor` compacts superseded ops.

---

## INSIGHT SCHEMA v1

`brain/insights/yyyy/YYYY-MM-DD.json` — per day:

- `version`, `date`, `generated`
- `summary` — short day digest
- `mood_avg` — optional number
- `themes` — string array
- `connections` — notable links (free-form objects or strings)
- `related_entries` — map of entry id → related entry ids (precomputed semantic recall)
- `on_this_day` — embedding-picked entries from ~a month/year back
- `time_capsules` — capsules due that day

---

## TAGS TAXONOMY v1

`brain/tags.json`:

- `version`, `generated`
- `tags` — list of `{ "canonical", "aliases", "parent?", "count" }`
- Normalized canonical tags, embedding-merged aliases, inferred hierarchy (`work/chronicle`), counts
- Phone tag row reads it; ghost auto-tag chips come from `enrich/`

---

## ENRICH SCHEMA v1

`brain/enrich/yyyy-MM.json` — map of entry id → `{ "auto_tags", "summary_line", "entities" }`.

---

## HEALTH SCHEMA v1

`health/yyyy/MM.json` — monthly map of local date → day record. Phone-imported from Health Connect (or equivalent); **source-of-truth** (imported, not derived). Mac pipeline may read it for future insights but does not own or rewrite it.

```json
{
  "version": 1,
  "days": {
    "2026-07-09": {
      "sleep": {
        "start": "2026-07-08T23:10:00+05:30",
        "end": "2026-07-09T06:50:00+05:30",
        "duration_min": 460,
        "stages": [
          { "stage": "deep", "start": "2026-07-09T01:00:00+05:30", "end": "2026-07-09T01:45:00+05:30" }
        ]
      },
      "steps": 8432,
      "source": "health_connect"
    }
  }
}
```

| Field | Rules |
|-------|--------|
| `version` | Integer schema version; currently `1`. |
| `days` | Map of `YYYY-MM-DD` → day object. Missing days are omitted. |
| `sleep` | Optional. `start` / `end` ISO-8601 with offset; `duration_min` integer minutes; `stages` array of `{ stage, start, end }` (`stage` is a free string, e.g. `awake`, `light`, `deep`, `rem`). |
| `steps` | Optional non-negative integer. |
| `source` | Required string identifying the import origin (e.g. `health_connect`). |

At least one of `sleep` or `steps` should be present on a day object. Merge-on-write: re-imports update individual days without clobbering the rest of the month file.

---

## CONFIG SCHEMA v1

`config.json` is **PC-owned**. The phone never reads it (uses device timezone + SAF-picked folder).

```json
{
  "version": 1,
  "layout_version": 2,
  "models": {
    "llm": "maxwell1500/ornith-35b:Q4_K_M",
    "embed": "nomic-embed-text",
    "vision": "llama3.2-vision:11b"
  },
  "timezone": "Asia/Kolkata",
  "ollama": {
    "base_url": "http://localhost:11434",
    "num_ctx": 32768,
    "temperature": null
  }
}
```

`layout_version` **2** = file-once journal layout (`_capture/`, `_attachments/`, `40-Journal/`). Process/serve refuse other values.

`vault_mirror` (optional string) is **deprecated** — open the Chronicle root in Obsidian instead. If still set, mirroring is **skipped** unless `CHRONICLE_ALLOW_VAULT_MIRROR=1`; doctor still warns.

### Model defaults (Mac / Ollama)

| Role | Default |
|------|---------|
| LLM | `maxwell1500/ornith-35b:Q4_K_M` (text; strips `<think>` blocks) |
| Embed | `nomic-embed-text` |
| Vision | `llama3.2-vision:11b` (Ornith is text-only) |

Sampling defaults: temperature `0.6`, top-p `0.95`, top-k `20`. Per-task `num_ctx` is raised for recall/ask (Ornith long context). Phone on-device AI is Gemini Nano via ML Kit GenAI (suggestion-only).

---

## Write ownership

| Writer | May write |
|--------|-----------|
| Phone | `_capture/entries/`, `_attachments/` (new + edit/delete while `processed=false`; dual-read legacy `entries/`/`img/`/`audio/`); `health/`; PARA knowledge; append `curation/ops/phone.jsonl`; browse `40-Journal/` read-only — journal fence amends **only via LAN serve `PATCH /journal/entries/{id}`**, never direct SAF fence writes |
| Mac UI via serve | Same for PC-originated entries (`-pc` ids); PARA knowledge; append `curation/ops/pc.jsonl`; amend journal fence bodies via `PATCH /journal/entries/{id}` |
| Mac pipeline | `40-Journal/` (file-once blocks), `_system/derived/`, `Upcoming.md`, `brain/`, `index/`, `config.json`, `kb/knowledge.json`, PARA knowledge, `kb/files/`; may **read** `health/`; may modify an entry **only** to (a) flip `processed`, (b) fill `text` on an audio entry whose text is empty, or (c) set `filed` / `filed_content_hash` / `filed_path` / `prose_edited` after writing or amending the MD block |

- **Source of truth (structured):** `_capture/entries/` (dual-read `entries/`), `_attachments/` (dual-read `img/`/`audio/`), `health/`, `curation/`, `config.json`, PARA knowledge
- **Source of truth (prose):** `40-Journal/` entry fences after filed
- **Derived (rebuildable):** `_system/derived/`, `Upcoming.md` (regenerated from unchecked `- [ ] … 📅 YYYY-MM-DD` tasks in knowledge notes — the source-note checkbox is SoT; direct edits to `Upcoming.md` are overwritten), legacy `notes/`, `brain/`, `index/`
- Phone/Mac UI never write `brain/`, `index/`, `_system/derived/`, `Upcoming.md`, or `kb/knowledge.json`
- `index/` stays Mac-only and unsynced
- **Dual-read cutover (knowledge):** **done** in v1.10 — PARA is the only knowledge candidate set; leftover `kb/notes/**` is quarantined/moved by `chronicle cutover-kb`

---

## Data rules (from audit)

1. **IDs** — `yyyy-MM-dd_HHmmss-<dev>`; globally unique by construction.
2. **Day attribution** — local date of entry `ts` offset; config timezone only for malformed timestamps.
3. **Journal is file-once** — per-block fences; never whole-file regen of `40-Journal/`. Derived aggregates under `_system/derived/`.
4. **Edit race** — any entry with `processed=false` is (re)processed and filed when ready. Syncthing conflict on an entry or knowledge/journal note: **newest write wins, then reprocess** (entries) / report-only for markdown (`doctor`). Never auto-delete MD conflicts.
5. **Deletion** — allowed only while `processed=false`. Pipeline tolerates vanished files mid-run. `doctor` reports orphaned media; never auto-deletes.
6. **Media** — images JPEG ≤2560px; audio `.m4a` AAC; preferred under `_attachments/`.
7. **Backup** — everything except `index/` (rebuildable); restore = unzip + `chronicle rebuild`. Always backup before `migrate-journal-v2 --apply`.
8. **Legacy** — `chronicle import-legacy` converts flat `entries/*.json` to sharded layout. `chronicle migrate-journal-v2` performs Phase 4 path cutover.

---

## Hard rules

- **Local-first vault** — phone ↔ Mac share one Syncthing folder; apps only read/write files. No Chronicle accounts, no product telemetry.
- **Optional LLM providers (user-chosen, BYOK)** — Mac chat/vision: `ollama` (default) | `grok` | `vertex`. Android: on-device Gemini Nano | Ollama LAN (private hosts only) | Grok BYOK. **No Vertex on Android.** Embeddings stay local Ollama (`nomic-embed-text` @ 768); never cloud-embed this pass. Cloud chat/vision requires explicit consent (journal/KB text may leave the device).
- **Secrets never synced** — Mac: `~/.config/chronicle/secrets.json` or env vars; Android: EncryptedSharedPreferences. Never store API keys in the vault or Syncthing share.
- **Optional LAN** — phone may call a **single user-configured Mac base URL** (QR from `chronicle serve`). That gateway serves Recall/search/Ask/Resume and vault CRUD. Empty URL = offline-only.
- **Journal SoT split** — prose = `40-Journal/` `entry:<id>` fences; structured fields (`mood`/`tags`/`type`/`ts`/`media`) stay in entry JSON forever. Never whole-file regen `40-Journal/`; never hand-edit `brain/` or `_system/derived/` as SoT.
- No habit tracking / streak stats
- Never destroy user data; atomic writes (temp + rename)
- Phone/Mac UI never write `brain/`, `index/`, `_system/derived/`, or `kb/knowledge.json`. AI output is suggestion-only until the user accepts.
- Simplest working implementation; ask when ambiguous
