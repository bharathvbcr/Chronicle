# Chronicle

Shared second brain: phone captures, Mac thinks, Syncthing syncs one folder. **Local-first** (Ollama embeds + default chat); optional BYOK cloud LLM (Mac Grok/Vertex; Android Grok) with opt-in consent — keys never in the vault. No Chronicle accounts or product telemetry.

```
Chronicle/                    # this git workspace
├── chronicle-android/        # Capture + Brain + Nano | Ollama LAN | Grok BYOK (SAF + LAN)
├── chronicle-pc/             # Pipeline CLI + React SPA + Tauri (Ollama / optional Grok|Vertex / whisper.cpp)
│   ├── frontend/             # React + Vite SPA (served at /)
│   ├── desktop/              # Tauri shell (prefers Start Chronicle.command)
│   └── pipeline/             # chronicle serve REST + process/watch
├── KnowledgeBase/            # Retired — see KnowledgeBase/ARCHIVED.md
├── demo-vault/               # Sample vault (layout_version 1 until you migrate a copy)
└── docs/SETUP.md             # Full zero → working walkthrough
```

**New here?** Follow the [5-step start](#get-started-in-5-steps) below, or the full guide: [`docs/SETUP.md`](docs/SETUP.md).

## Get started in 5 steps

1. **Create & sync a Chronicle folder** on the Mac (e.g. `~/Chronicle`). Share it with the phone via Syncthing. Ignore: `index/`, `*.tmp`, `.DS_Store`, `.stfolder`.
2. **Android** — install the APK, first-run pick that Syncthing folder (SAF). Capture writes `_capture/entries` + `_attachments` at `layout_version: 2`.
3. **PC** — `cd chronicle-pc && python3 -m venv .venv && source .venv/bin/activate && pip install -e ".[dev]"`, then `export CHRONICLE_DIR=~/Chronicle` and edit `config.json` with `layout_version: 2` (see [`config.json.example`](chronicle-pc/config.json.example); leave `vault_mirror` unset — open Chronicle root in Obsidian).
4. **Models** — pull Ornith + embed + vision (see below). Optional: whisper.cpp + `WHISPER_MODEL`. Optional cloud: secrets in `~/.config/chronicle/secrets.json` + consent.
5. **Run** — build the SPA (`cd frontend && npm install && npm run build`), then double-click `chronicle-pc/Start Chronicle.command` (prefers Tauri binary; else serve + browser) → open `http://127.0.0.1:8765/`.

Day-to-day: capture on phone → sync → process (file-once into `40-Journal/`) → brain syncs back → Timeline / Brain on phone.

**Wired catch-up (optional):** if Syncthing is down, pause it and run `./scripts/pc-to-phone-sync.sh` (or `CHRONICLE_PHONE_DIR=/sdcard/Chronicle ./scripts/pc-to-phone-sync.sh`). Merge-pushes the Mac vault over ADB, runs `chronicle process`, launches PC, and `installDebug`s the Android app. Prefs auto-discover needs a prior debug install + folder pick; Syncthing stays the day-to-day path. Details: [`docs/SETUP.md`](docs/SETUP.md) § Wired PC → phone catch-up.

### Ollama models (Ornith)

```bash
ollama pull maxwell1500/ornith-35b:Q4_K_M
ollama pull nomic-embed-text
ollama pull llama3.2-vision:11b
```

Defaults: LLM Ornith 35B, embed `nomic-embed-text`, vision `llama3.2-vision:11b`. Sampling temp 0.6 / top-p 0.95 / top-k 20; large `num_ctx` for recall/ask.

### SPA + Tauri (optional but recommended)

```bash
# SPA (required for / to serve the React UI; legacy dashboard stays at /legacy)
cd chronicle-pc/frontend && npm install && npm run build

# Tauri desktop shell (Start Chronicle.command prefers the built binary)
cd chronicle-pc/desktop && npm install && npm run tauri:build
```

## KnowledgeBase vs Chronicle vault

| | Chronicle folder (Syncthing) | `KnowledgeBase/` in this repo |
|--|-----------------------------|-------------------------------|
| What it is | Live data: `_capture`, `40-Journal`, PARA knowledge, brain | **Retired** — content migrated to vault; see [`KnowledgeBase/ARCHIVED.md`](KnowledgeBase/ARCHIVED.md) |
| Who writes | Phone, Mac UI via serve, PC pipeline | Historical source only (`Docs/`, `ReadMe/`, `brain.json`) |
| Obsidian | Open Chronicle vault root (exclude machine dirs); `vault_mirror` deprecated | Unrelated |

**Vault mirror is deprecated.** Prefer capturing **entries** (phone or Mac Timeline); edit filed prose in `40-Journal/` fences; do not hand-edit `_system/derived/`.

Knowledge is PARA-only (`00-Inbox/` … `90-Archive/`); ResumePoints at `10-Work/ResumePoints/` (legacy `kb/notes/` dual-read retired in CONTRACT v1.10 — run `chronicle cutover-kb` if leftovers remain). Repo `portfolio/ResumePoints/` is a tombstone.

## Contract (identical in both apps)

- [`chronicle-android/CONTRACT.md`](chronicle-android/CONTRACT.md) ≡ [`chronicle-pc/CONTRACT.md`](chronicle-pc/CONTRACT.md)
- `contract/*.schema.json` — keep byte-identical across both repos
- Do not change schemas without updating both copies

## Docs map

| Doc | Audience |
|-----|----------|
| [`docs/SETUP.md`](docs/SETUP.md) | Full setup, vault relationship, day-to-day, troubleshooting |
| [`chronicle-pc/README.md`](chronicle-pc/README.md) | CLI, Ollama, SPA/Tauri, backup, migrate |
| [`chronicle-pc/frontend/README.md`](chronicle-pc/frontend/README.md) | React SPA build |
| [`chronicle-pc/desktop/README.md`](chronicle-pc/desktop/README.md) | Tauri shell |
| [`chronicle-android/README.md`](chronicle-android/README.md) | Build, SAF, LLM, DevCouncil |
| `CONTRACT.md` (either app) | Folder layout + schemas + REST API |

## Useful CLI

```bash
export CHRONICLE_DIR=/path/to/synced/Chronicle
chronicle process          # transcribe / vision / file-once journal / brain
chronicle watch            # debounced loop
chronicle serve            # LAN gateway + QR; SPA at / when frontend/dist exists
chronicle serve --no-lan   # localhost-only bind
chronicle brain            # refresh graph / insights / tags
chronicle doctor           # integrity, orphans, sync-conflicts, stuck unfiled
chronicle rebuild          # regenerate derived chrome / brain / index
chronicle backup           # zip (excludes rebuildable index/)
chronicle migrate-kb       # one-time: KnowledgeBase → vault kb/ + graph ops
chronicle migrate-v2       # PARA knowledge copy (dry-run; --apply --i-have-backup)
chronicle cutover-kb       # quarantine/move leftover kb/notes → PARA (require backup)
chronicle migrate-journal-v2  # file-once cutover → layout_version 2
chronicle rebuild-markdown-index  # regenerate `_system/index.md` agent shortlist
chronicle import-legacy …  # flat entries → sharded layout
```

Day-to-day Mac UI: double-click **`chronicle-pc/Start Chronicle.command`**. That prefers the Tauri binary when built; otherwise runs `chronicle serve` and opens the browser. The SPA talks to the vault over REST (not File System Access). Scan the QR in Android Settings. KnowledgeBase’s live UI/server is **retired** — see [`KnowledgeBase/ARCHIVED.md`](KnowledgeBase/ARCHIVED.md).

## Try the demo

[`demo-vault/`](demo-vault/) is a ready sample (journal + KnowledgeBase migration into `kb/` / PARA + Brain graph). It ships at **`layout_version: 1`** — copy it, then migrate before `process`/`serve`:

```bash
cp -R demo-vault ~/Chronicle-demo-copy
export CHRONICLE_DIR=~/Chronicle-demo-copy
cd chronicle-pc && source .venv/bin/activate
chronicle backup ~/Backups/demo-pre-migrate.zip
chronicle migrate-journal-v2 --apply --i-have-backup
# If leftover kb/notes/ remain: chronicle cutover-kb --apply --i-have-backup
# Start Chronicle.command → http://127.0.0.1:8765/
```
Cutover runbook: [`docs/SETUP.md`](docs/SETUP.md) § Knowledge cutover.

## Longevity

```bash
chronicle doctor    # integrity, orphans, sync-conflicts, stuck unfiled
chronicle rebuild   # regenerate derived state (amend gate preserves touched 40-Journal blocks)
chronicle backup    # zip everything except rebuildable index/
```
