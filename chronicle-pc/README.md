# Chronicle PC

Local-first second-brain pipeline and dashboard for the shared Chronicle folder. Phone captures; this Mac pipeline thinks (Ollama by default + whisper.cpp; optional Grok/Vertex chat with consent); Syncthing moves files. Embeddings stay on local Ollama (`nomic-embed-text` @ 768). No Chronicle accounts or product telemetry — API keys never sync via Syncthing.

**Start from zero?** Use the workspace guide: [`../docs/SETUP.md`](../docs/SETUP.md) and [`../README.md`](../README.md).

## Native Rust server (default runtime)

`server/` is the Rust port of the Python pipeline — same REST contract, journal fences, RAG, brain, and CLI. It powers the Tauri app **in-process** (no venv needed) and ships its own `chronicle` binary:

```bash
cd chronicle-pc/server
cargo build --release
./target/release/chronicle serve            # LAN + QR pairing, port 8765+
./target/release/chronicle process|brain|index|backup|export|doctor|rollup|topics
```

- sqlite-vec is linked in; `CHRONICLE_DISABLE_VEC=1` forces JSON-cosine mode.
- Legacy one-shot tools (`migrate*`, `import-*`, `cutover-kb`) remain **Python-only** — run them via `.venv/bin/chronicle`; the native CLI prints this pointer and exits 2.
- Parity harness: `../scripts/parity.sh` boots both engines over one vault and diffs normalized responses.

## Setup

```bash
cd chronicle-pc
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"

# Optional: sqlite-vec for ANN search (otherwise embeddings are JSON + cosine in Python)
# pip install -e ".[vec]"
```

### Chronicle folder

Point the CLI at your Syncthing-synced folder (same path the phone uses):

```bash
export CHRONICLE_DIR=/path/to/Chronicle
# or pass --chronicle-dir on every command
```

`config.json` lives **inside** that folder (PC-owned; created on first run). Copy the sample and edit:

```bash
cp config.json.example "$CHRONICLE_DIR/config.json"
```

```json
{
  "version": 1,
  "layout_version": 2,
  "timezone": "Asia/Kolkata",
  "models": {
    "llm": "llama3.1:8b",
    "embed": "nomic-embed-text",
    "vision": "llama3.2-vision:11b"
  }
}
```

`layout_version: 2` is required for `process` / `serve` (file-once paths). Leave `vault_mirror` unset (deprecated). Open the Chronicle folder root in Obsidian; exclude `index/`, `brain/`, `_capture/`, `_attachments/`, `entries/`, `img/`, `audio/`, `_staging/`. See [Obsidian](#obsidian-open-vault-root).

### Ollama models

```bash
ollama pull llama3.1:8b
ollama pull llama3.2-vision:11b
ollama pull nomic-embed-text
```

### Optional cloud LLM (Mac only)

Default provider is Ollama. To use Grok or Vertex for chat/vision:

1. In Settings (or `config.json` → `llm.provider`), pick `grok` or `vertex` and set **cloud_consent** (and **vision_cloud_consent** if images may leave the machine). Consent means journal/KB text may leave this machine.
2. Keep API keys **off the vault** — Syncthing must never sync secrets:

```bash
mkdir -p ~/.config/chronicle
cat > ~/.config/chronicle/secrets.json <<'EOF'
{
  "grok_api_key": "xai-...",
  "cloud_consent": true,
  "vision_cloud_consent": false
}
EOF
# or: export GROK_API_KEY=xai-...
# Vertex: gcloud auth application-default login + llm.vertex.project (or GOOGLE_CLOUD_PROJECT)
```

Cloud recall/ask use stricter context caps and rate limits. Embeddings never go to the cloud in this pass.

### whisper.cpp (optional)

Install [whisper.cpp](https://github.com/ggerganov/whisper.cpp), put `whisper-cli` on `PATH`, and set:

```bash
export WHISPER_MODEL=~/whisper.cpp/models/ggml-base.en.bin
```

If the binary or model is missing, `chronicle process` **skips transcription**, still files available text into `40-Journal/`, leaves audio entries with empty text **unprocessed** (so they retry), and logs a clear warning.

## Daily usage

```bash
chronicle process              # transcribe / vision / file-once 40-Journal / flip processed / brain
chronicle watch                # debounced loop over _capture + media + curation (legacy paths dual-read)
chronicle rollup               # weekly / monthly / yearly under _system/derived/
chronicle index                # sqlite search index (excludes from Syncthing)
chronicle index --write-markdown  # also rebuild `_system/index.md` agent shortlist
chronicle rebuild-markdown-index  # regenerate `_system/index.md` only
chronicle topics               # topic notes + dreams.md symbol clustering
chronicle brain                # graph / insights / tags
chronicle serve                # LAN gateway + QR on :8765 (default); native /ask+/resume+/recall
chronicle serve --no-lan       # localhost-only bind (no pairing token)
chronicle serve --no-tls       # downgrade LAN to cleartext http (default is pinned https)
chronicle pair <device>        # print QR with a new persistent device token (~/.config/chronicle/pairing.json)
chronicle unpair <device>      # revoke a paired device immediately
chronicle pairs                # list paired devices
chronicle e2ee-setup           # enable entry-text encryption (passphrase; phone uses the same one)
chronicle unlock               # verify passphrase for pipeline runs (CHRONICLE_E2EE_PASSPHRASE or prompt)
chronicle migrate-kb           # one-time: archive KnowledgeBase → vault kb/
chronicle migrate-v2           # dry-run: copy kb/notes → PARA (use --apply --i-have-backup)
chronicle cutover-kb           # dry-run: quarantine/move leftover kb/notes (use --apply --i-have-backup)
chronicle migrate-journal-v2   # file-once path cutover (layout_version 2; require backup)
chronicle init-vault-structure --refresh-skills  # overwrite seed skill/CLAUDE dual-read text
chronicle doctor               # report-only: integrity, stuck unfiled, fence/hash issues
chronicle doctor --fix         # apply JSON sync-conflict repairs + ops compact (MD never auto-merged)
chronicle rebuild              # regenerate derived chrome / brain / index (amend gate on 40-Journal)
```

**layout_version:** Current builds require `layout_version: 2` (file-once: `_capture/`, `_attachments/`, `40-Journal/`). Repo `demo-vault/` is still `1` — **not serve-ready** as-is. **Copy** it, then:

```bash
chronicle backup /path/outside/syncthing/demo-backup.zip
chronicle migrate-journal-v2 --apply --i-have-backup --chronicle-dir /path/to/demo-copy
```

`chronicle init-vault-structure` seeds PARA chrome create-only (never overwrites user edits). Use `--refresh-skills` after knowledge cutover to refresh seed skill/CLAUDE bodies. Prefer tests that migrate a copy rather than treating unmigrated `demo-vault` as the live fixture.

Knowledge dual-read is **done** (CONTRACT v1.10): PARA-only candidates; leftover `kb/notes/` → `chronicle cutover-kb --apply --i-have-backup`. Move/archive runs `link_repair` + `_system/changelog.md`. Co-release the Android APK with this CLI so phone writes `_capture/entries` + `_attachments`.

**Mac open path:** double-click **`Start Chronicle.command`** (or run `./start_dashboard.sh`). That runs `chronicle serve` (LAN + QR, native Ask/Resume) and opens the **React SPA** at `http://127.0.0.1:8765/` when `frontend/dist/` is built (otherwise falls back to the legacy dashboard). Prefer the SPA over File System Access; the old single-file UI remains at `/legacy`.

### Phone LAN (Recall + Ask/Resume)

Preferred: **`Start Chronicle.command`** (LAN serve with native Ask/Resume — no separate `brain_server`).

Manual equivalent:

```bash
chronicle serve
```

Scan the terminal or Connect QR in Android Settings. Payload:

```json
{"v":1,"base":"http://<lan-ip>:8765","token":"<pairing-secret>"}
```

LAN mode (default) binds `0.0.0.0` and serves **https** with a persistent self-signed cert whose fingerprint (`tls_fp`) ships in the QR so the phone pins it (`--no-tls` downgrades). Auth is default-deny on header `X-Chronicle-Token`: tokens are **persistent per device** (`chronicle pair/unpair/pairs`, stored at `~/.config/chronicle/pairing.json`, mode 0600), so the phone survives serve restarts without re-scanning; revocation is immediate and failed attempts are rate-limited per IP. `/connect` returns tokens only to loopback. Optional mDNS discovery: `pip install -e ".[mdns]"`. E2EE for entry text is opt-in via `chronicle e2ee-setup` — see CONTRACT v1.11. Treat LAN as a trusted network — use `chronicle serve --no-lan` on untrusted ones. The old KnowledgeBase `brain_server` is archived — see [`../KnowledgeBase/ARCHIVED.md`](../KnowledgeBase/ARCHIVED.md).

### Adding content

- Prefer **entries** via SPA / Android app → `_capture/entries/` (pipeline files prose into `40-Journal/`).
- Knowledge markdown: PARA only (`00-Inbox/`, `10-Work/`, …); legacy `kb/notes/` returns 410 — run `cutover-kb` if leftovers remain.
- Edit filed **prose** only inside `40-Journal/` fences; structured fields stay in JSON. Never whole-file regen journal; never hand-edit `_system/derived/` or `brain/` as SoT.
- Graph intent: Brain curation → `curation/ops/pc.jsonl`.

## Obsidian (open vault root)

Open the **Chronicle Syncthing folder** as your Obsidian vault. Exclude machine dirs: `index/`, `brain/`, `_capture/`, `_attachments/`, `entries/`, `img/`, `audio/`, `_staging/`.

`vault_mirror` in `config.json` is **deprecated**. Phone capture = Android app + Syncthing, not Obsidian mobile.

## Graceful degradation

| Missing | Behavior |
|---------|----------|
| Ollama | Journal from text only; heuristic enrich/tags/graph; no vision; index without embeddings (keyword search); Recall returns citations-only |
| whisper.cpp | Skip audio transcription; entry with audio + empty text stays `processed=false` for retry |
| sqlite-vec | Index uses SQLite + JSON embedding blobs + Python cosine (documented default fallback) |

## Backup & restore

```bash
chronicle backup                       # dated zip next to Chronicle/; excludes index/
chronicle backup /path/to/out.zip
chronicle backup /path/to/out.zip --force   # overwrite existing zip
# Restore: unzip → set CHRONICLE_DIR → chronicle rebuild
```

Always backup **outside** the Syncthing share before `migrate-v2` / `migrate-journal-v2`.

## Export / migrate / legacy

```bash
chronicle export --format chronosflow [out.json]
chronicle migrate
chronicle import-legacy /path/to/old/journal   # flat entries/*.json → sharded layout
chronicle import-knowledgebase                 # KnowledgeBase/brain.json → curation ops (MindMap)
# default --source resolves via CHRONICLE_KB_SOURCE or workspace KnowledgeBase/brain.json
# chronicle import-knowledgebase --source /other/brain.json --apply
```

## Contract

See [`CONTRACT.md`](CONTRACT.md) and `contract/*.schema.json`. Keep in sync with `chronicle-android/`. Do not change schemas without updating both repos. Hard rules: local-first vault, optional BYOK providers, secrets off-vault, journal SoT split.

## Troubleshooting

- **`layout_version` refused** — vault still `1`; migrate-journal-v2 on a backed-up copy.
- **Brain missing / stale on phone** — run `chronicle process` (or `watch`); wait for Syncthing; confirm ignore list did not exclude `brain/`.
- **Stuck unfiled** — `doctor` lists `processed && !filed`; re-run `process`. Amend gate skips MD blocks when hash ≠ `filed_content_hash`.
- **Ollama missing models** — `ollama list` / `ollama pull …`; ensure the daemon is running.
- **`.sync-conflict-*` files** — `chronicle doctor --fix` repairs JSON only; MD is report-only. Never deletes user data.
- **Orphan media** — `doctor` reports only; never auto-deletes.
- **SPA not loading** — build `frontend/` (`npm run build`); without `frontend/dist/`, `/` serves legacy `dashboard/dashboard.html`.
- **Recall empty** — run `chronicle index` then `chronicle serve` (or `Start Chronicle.command`); pull Ollama models if you want LLM answers.
- **Phone LAN 401** — rescan the QR so Android stores the pairing token; all vault API calls (including GETs) need `X-Chronicle-Token` when LAN-bound.

Full setup + KnowledgeBase relationship: [`../docs/SETUP.md`](../docs/SETUP.md).

## Tests & lint

```bash
pytest
ruff check pipeline tests
```

Tests use fixtures and do **not** require a live Ollama or whisper binary.
