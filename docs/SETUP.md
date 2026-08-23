# Chronicle setup (zero → working second brain)

Step-by-step for a new Mac + Android install. Folder layout and schemas are defined in [`CONTRACT.md`](../chronicle-pc/CONTRACT.md) (identical copy in `chronicle-android/`). Do not change schemas here.

Chronicle is **local-first**: Syncthing syncs the vault; embeddings stay on local Ollama. Optional cloud chat (Mac Grok/Vertex; Android Grok BYOK) requires explicit consent — API keys never live in the vault.

## 1. Create the Chronicle data folder (Mac)

Pick a path Syncthing will share, for example:

```bash
mkdir -p ~/Chronicle
```

You can leave it empty. The Android app and PC pipeline create `_capture/`, `_attachments/`, `40-Journal/`, PARA areas, `brain/`, `curation/`, `index/`, and `config.json` as needed (legacy `entries/` / `img/` / `audio/` / `notes/` still dual-read until migrate).

This folder is **not** the same as `KnowledgeBase/` in this git repo. See [KnowledgeBase vs Chronicle vault](#knowledgebase--vault-relationship) below.

## 2. Syncthing (phone ↔ PC)

1. Install Syncthing on Mac and Android.
2. Share the same folder both ways (Mac `~/Chronicle` ↔ phone path of your choice).
3. Add ignore patterns (required by the contract):

```
index/
*.tmp
.DS_Store
.stfolder
```

`index/` is Mac-only search state and must not sync. Apps never talk to Syncthing; they only read/write files.

**Config / consent hygiene:** API keys and consent overrides belong in `~/.config/chronicle/secrets.json` (never in the vault). Synced `config.json` can still flip `llm.provider`, `cloud_consent`, or `ollama.base_url` from a compromised peer — treat the Syncthing share as trusted, or ignore/review those fields after peer recovery. Ollama URLs must stay private/loopback; Grok is pinned to `https://api.x.ai` only.

## 3. Android: install and pick the folder

Build or install the debug APK:

```bash
cd chronicle-android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch:

1. Tap through welcome.
2. Pick the **Syncthing-synced Chronicle folder** (Storage Access Framework).
3. Grant persistent access when prompted.

Capture text, photos, or voice notes. On `layout_version: 2` they land under `_capture/entries/` + `_attachments/` and sync to the Mac. (Legacy APKs wrote `entries/` / `img/` / `audio/` — dual-read until migrate.)

Nav: **Capture / Timeline / Notes / Brain / Portfolio**. Brain merges graph + recall (LAN when Mac serve is up). Optional LLM: Nano | Ollama LAN | Grok BYOK (EncryptedSharedPreferences; consent required for cloud).

**JDK for builds:** use **JDK 21** (`brew install openjdk@21`, set `JAVA_HOME`). See [`chronicle-android/README.md`](../chronicle-android/README.md).

## 4. PC pipeline: venv, env, config

```bash
cd chronicle-pc
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"

export CHRONICLE_DIR=~/Chronicle   # same folder Syncthing shares
```

On first `chronicle process` (or `ensure_config`), the pipeline writes `config.json` inside the Chronicle folder. Edit it (or copy from the example):

```bash
cp config.json.example "$CHRONICLE_DIR/config.json"
# then edit timezone, models (vault_mirror is deprecated — open Chronicle root in Obsidian)
```

Example (new installs should use `layout_version: 2`):

```json
{
  "version": 1,
  "layout_version": 2,
  "timezone": "Asia/Kolkata",
  "models": {
    "llm": "maxwell1500/ornith-35b:Q4_K_M",
    "embed": "nomic-embed-text",
    "vision": "llama3.2-vision:11b"
  },
  "ollama": {
    "base_url": "http://localhost:11434",
    "num_ctx": 32768,
    "temperature": null
  }
}
```

| Field | Meaning |
|-------|---------|
| `layout_version` | **`2`** = file-once (`_capture/`, `_attachments/`, `40-Journal/`). `process`/`serve` refuse other values. |
| `timezone` | Fallback only when an entry timestamp is malformed. Day attribution normally uses the entry’s own offset. |
| `models` | Ollama model names for LLM / embed / vision (embed stays local even if chat uses cloud). |
| `vault_mirror` | **Deprecated.** Do not set for new installs. Open the Chronicle vault root in Obsidian instead. |

`config.json` is PC-owned; the phone never reads it. Optional cloud LLM keys live in `~/.config/chronicle/secrets.json` (never in the vault) — see `chronicle-pc/README.md`.

## 5. Ollama (+ optional whisper)

```bash
ollama pull maxwell1500/ornith-35b:Q4_K_M
ollama pull llama3.2-vision:11b
ollama pull nomic-embed-text
```

Ornith is text-only; vision stays on `llama3.2-vision:11b`. The pipeline strips `<think>` reasoning blocks and uses temp `0.6` / top-p `0.95` / top-k `20` with large per-task `num_ctx`.

Optional audio transcription: install [whisper.cpp](https://github.com/ggerganov/whisper.cpp), put `whisper-cli` on `PATH`, and set:

```bash
export WHISPER_MODEL=~/whisper.cpp/models/ggml-base.en.bin
```

Without whisper, `chronicle process` skips transcription, still files available text into `40-Journal/`, marks entries processed, and logs a warning. Without Ollama, the pipeline degrades to text-only / heuristic enrich (see PC README).

**BYOK cloud (optional):** Mac `llm.provider` = `grok` or `vertex` with `cloud_consent`; Android Settings → Grok with consent. Journal/KB text may leave the device when cloud is on — opt in explicitly.

## 6. Process (or watch)

```bash
export CHRONICLE_DIR=~/Chronicle
source chronicle-pc/.venv/bin/activate

chronicle process    # one shot: transcribe / vision / file-once journal / brain
# or
chronicle watch      # debounced loop on capture + media + curation
```

Useful later:

```bash
chronicle brain      # rebuild graph / insights / tags from current state
chronicle doctor     # integrity, orphans, sync-conflicts, stuck unfiled (JSON fixable; MD report-only)
chronicle rebuild    # regenerate derived chrome / brain / index (never whole-file regen 40-Journal)
chronicle backup     # zip everything except rebuildable index/
chronicle import-legacy /path/to/old/journal
chronicle migrate-kb # KnowledgeBase → vault kb/ + graph (if not already migrated)
chronicle migrate-v2 # dry-run PARA knowledge copy (use --apply --i-have-backup after backup)
chronicle cutover-kb # dry-run: quarantine/move leftover kb/notes → PARA (use --apply --i-have-backup)
chronicle migrate-journal-v2  # dry-run file-once cutover → layout_version 2
chronicle rebuild-markdown-index  # regenerate `_system/index.md` agent shortlist
```

**Backup before migrate:** Always run `chronicle backup` before any vault layout migrate. Put the zip **outside** the Syncthing-shared Chronicle folder (default writes next to the vault as a sibling — fine if that parent is not the share; otherwise pass an explicit path like `~/Backups/chronicle-….zip`). Restoring from a backup inside the share risks re-syncing stale files.

### Try the demo vault

A pre-seeded vault is at `<chronicle-repo>/demo-vault` (synthetic journal fixtures: 41 notes, 22 nodes / 20 links -- demo persona, no real data).

**Important:** `demo-vault` ships at **`layout_version: 1`**. Current CLI requires **`2`**. Work on a **copy**:

```bash
cp -R <chronicle-repo>/demo-vault ~/Chronicle-demo-copy
export CHRONICLE_DIR=~/Chronicle-demo-copy
chronicle backup ~/Backups/demo-vault-pre-migrate.zip
chronicle migrate-journal-v2 --apply --i-have-backup
# Start Chronicle.command → http://127.0.0.1:8765/
# Or point Android SAF at the migrated copy (if synced to phone)
```

PARA chrome/skills are already seeded and knowledge cutover is applied in-repo (`kb/notes/` empty tombstone; leftovers under `90-Archive/_legacy-kb/`). On a personal vault copy, run `chronicle cutover-kb --apply --i-have-backup` after backup if leftovers remain (see cutover runbook below).

## 7. SPA, serve, and Tauri (Mac UI)

The Mac UI talks to the vault over **`chronicle serve` REST** (not File System Access). Build the SPA so `/` serves the React app; legacy dashboard remains at `/legacy`.

```bash
# React SPA
cd chronicle-pc/frontend
npm install
npm run build   # → frontend/dist/

# Optional: Tauri desktop shell
cd chronicle-pc/desktop
npm install
npm run tauri:build
# binary: desktop/src-tauri/target/release/chronicle
# app:    desktop/src-tauri/target/release/bundle/macos/Chronicle.app
```

Double-click **`chronicle-pc/Start Chronicle.command`**. That:

1. Prefers the Tauri binary when built; otherwise runs `chronicle serve` on the LAN (`0.0.0.0:8765`) and opens the browser
2. Primes `~/Library/Application Support/Chronicle/pc_root` so a copied **`/Applications/Chronicle.app`** can locate `chronicle-pc/.venv` on Finder/Dock open (cwd is often `/`)
3. Serves the SPA at `/` (Ask / Resume / Recall / Journal / Knowledge / Timeline)

Then open `http://127.0.0.1:8765/` (or the Tauri window). Use **Timeline** for pending captures + filed journal; **Knowledge/Notes** for PARA markdown; **Brain** for graph + recall. Do **not** start KnowledgeBase `brain_server` (deleted — see [`KnowledgeBase/ARCHIVED.md`](../KnowledgeBase/ARCHIVED.md)).

**Note:** Opening `/Applications/Chronicle.app` from Finder works after a primed support file, the `$HOME/Code/Chronicle/chronicle-pc` candidate path, or one run of `Start Chronicle.command`. Prefer that `.command` (or the in-tree binary) over a bare Dock icon until the support file exists.

### Notes sections (SPA)

The Mac SPA **Vault** tab splits second-brain content into three sub-areas (see [`CONTRACT.md`](../chronicle-pc/CONTRACT.md) § Notes sections):

| Tab | Vault paths | Editable in SPA |
|-----|-------------|-----------------|
| **Knowledge Base** | `30-Knowledge/` | Yes — create, edit, move, archive |
| **Notes** | `00-Inbox/`, `10-Work/`, `20-Personal/`, `90-Archive/` | Yes — same lifecycle as Knowledge Base |
| **Journal** | `40-Journal/` entry fences + `_system/derived/` + `Upcoming.md` | Fence-body amends only (`PATCH /journal/entries/{id}`); derived chrome read-only |

Creates default to `30-Knowledge/` (Knowledge Base) or `00-Inbox/` (Notes). Prefer **Archive** (`POST /kb/archive`) over hard delete. Templates live in vault `_templates/`; hub rows surface `Home.md` (opens Notes), area `MOC-*.md`, and a link to **Upcoming** (derived deadlines). Move/archive rewrites vault wikilinks (`link_repair`) and appends `_system/changelog.md`.

### Knowledge cutover runbook (legacy `kb/notes/` → PARA) — complete

CONTRACT **v1.10** retires dual-read: PARA is the only knowledge candidate set. Legacy `kb/notes/**` paths return **410** from `/kb/*` with a cutover hint. Do this once per vault that still has leftover notes under `kb/notes/`:

1. **Backup first** — `chronicle backup` and store the zip **outside** the Syncthing share.
2. **Optional copy into PARA** — if notes exist only under `kb/notes/` (no PARA peers yet), run `chronicle migrate-v2 --apply --i-have-backup` (copy, does not delete).
3. **Hard cutover** — `chronicle cutover-kb --apply --i-have-backup`:
   - Same-suffix PARA peer → quarantine under `90-Archive/_legacy-kb/`
   - No peer → move into the classified PARA area (default `30-Knowledge/`)
   - Rewrite `brain/graph.json` `doc` fields that still point at `kb/notes/…`
   - Leave an empty `kb/notes/` tombstone (`kb/files/` + `kb/knowledge.json` stay)
4. **Verify** — `chronicle doctor` should be clean of leftover `kb/notes` / dual-read copies. Co-release Android APK + PC CLI/SPA with CONTRACT v1.10 (both `CONTRACT.md` copies must stay identical — `scripts/check-contract-sync.sh`).
5. **Agent chrome (optional)** — `chronicle init-vault-structure --refresh-skills` to overwrite seed skill/CLAUDE bodies that still mention dual-read. Rebuild the agent shortlist with `chronicle rebuild-markdown-index` (or `chronicle index --write-markdown`, or `POST /vault/rebuild-index`).

Normal use should never write under `kb/notes/` after cutover.

| Shortcut | Action |
|----------|--------|
| `n` | Quick entry (PC-originated `-pc` ids) |
| `/` | Search overlay |

Brain curation appends to `curation/ops/pc.jsonl`.

`CHRONICLE_DIR` defaults to `~/Chronicle` if set in the environment, else that folder if it exists, else `demo-vault` in this repo. Override with `export CHRONICLE_DIR=…` before launching. Point at a **migrated** vault (`layout_version: 2`) for serve/process.

## 8. Phone Recall / Ask / Resume (LAN + QR)

Chronicle `serve` is the single LAN gateway (LAN bind is the **default**). Ask/Resume/Recall run natively against the vault + journal index — no separate KnowledgeBase server.

**Threat model:** LAN bind exposes the vault API on your local network. Pairing uses a secret in the QR. Clients must send `X-Chronicle-Token` on vault API requests (including GETs). `GET /connect` returns the token only to loopback (Mac SPA); phones get the secret by scanning the QR. Use `--no-lan` on untrusted networks (cafés, guest Wi‑Fi).

Manual equivalent:

```bash
cd chronicle-pc
source .venv/bin/activate
export CHRONICLE_DIR=~/Chronicle
chronicle serve
```

- Terminal prints an ASCII QR (base URL + token **in the QR only** — not printed as JSON).
- SPA **Settings / Connect phone** (loopback) shows the same QR via `GET /connect/qr.svg`.
- On Android: Settings → scan Mac QR (stores base URL + pairing token for all serve calls).

Use `chronicle serve --no-lan` for a localhost-only bind without auth.

**Concurrency:** Phone SAF and Mac process/serve can still race through Syncthing. Local `vault_process_lock` is best-effort on one machine — not a distributed lock.

---

## KnowledgeBase / vault relationship

| Path | Role |
|------|------|
| **Chronicle folder** (Syncthing) | Live second brain: `_capture` + `40-Journal` + PARA knowledge + `brain/`. Phone + Mac SPA + pipeline use this. |
| **PARA areas** | Only knowledge candidate set: `00-Inbox/`, `10-Work/` (incl. ResumePoints), `20-Personal/`, `30-Knowledge/`, `90-Archive/`. |
| **`KnowledgeBase/` in this repo** | **Archived** older product. See [`KnowledgeBase/ARCHIVED.md`](../KnowledgeBase/ARCHIVED.md). One-time migrate with `chronicle migrate-kb` (already done for `demo-vault`). |
| **`vault_mirror`** | **Deprecated.** Open Chronicle root in Obsidian; do not mirror derived notes into a second vault. |
| **`portfolio/ResumePoints/`** | Tombstone → edit `10-Work/ResumePoints/`. |

### Obsidian (open vault root)

1. Open the **Chronicle Syncthing folder** as the Obsidian vault (not a mirrored copy).
2. Exclude or ignore machine dirs: `index/`, `brain/`, `_capture/`, `_attachments/`, `entries/`, `img/`, `audio/`, `_staging/`, plus `*.tmp` / `.DS_Store`.
3. Edit PARA knowledge notes and `40-Journal/` prose fences only — do not write under `kb/notes/` (empty tombstone after cutover).
4. Phone capture is the **Android app + Syncthing** — not Obsidian mobile.

`config.json` `vault_mirror` is deprecated. If still set, mirroring is skipped unless `CHRONICLE_ALLOW_VAULT_MIRROR=1`. Avoid relying on it.

### How journal / notes appear

After process (file-once) / rollup / topics:

```
40-Journal/YYYY-MM-DD.md     # prose SoT (entry:<id> fences) — never whole-file regen
_system/derived/             # regenerable aggregates (daily chrome, weekly, …)
_capture/entries/…           # structured SoT (mood/tags/type/media)
_attachments/…               # media
```

Legacy `notes/daily|weekly|…` may still exist until migrate; prefer `40-Journal/` + `_system/derived/`. Knowledge notes (hand-authored) live under PARA areas only.

### How to “add notes”

1. **Phone** — Chronicle Capture screen (text / photo / voice), or Notes for PARA / knowledge MD.
2. **Mac SPA** — Timeline composer (`n`) for entries; Knowledge/Notes for PARA markdown; edit filed prose in `40-Journal/` fences only.
3. Let the Mac pipeline file entries into `40-Journal/` and write derived chrome under `_system/derived/`.

Curation (pins, merges, links) belongs in Brain / `curation/ops/`, not in hand-edited derived bodies.

---

## Day-to-day loop

1. Capture on phone (Android) → Syncthing → Mac.
2. `chronicle process` or leave `chronicle watch` running (or `POST /process` from the SPA).
3. Filed journal + brain sync back → phone Timeline / Brain.
4. Mac: Start Chronicle → Timeline / Knowledge / Brain over serve.
5. Periodically: `chronicle doctor`, `chronicle backup` (keep backups outside the Syncthing share; always backup before migrate).

---

## Wired PC → phone catch-up (optional)

When Syncthing is unavailable, [`scripts/pc-to-phone-sync.sh`](../scripts/pc-to-phone-sync.sh) merge-pushes the Mac vault over USB (ADB). **Syncthing remains day-to-day sync** — pause it on both sides during the push to avoid races.

```bash
# USB debugging on; pause Syncthing if active
./scripts/pc-to-phone-sync.sh
CHRONICLE_PHONE_DIR=/sdcard/Chronicle ./scripts/pc-to-phone-sync.sh
```

What it does: fail-fast vault checks → `chronicle process` → merge-extract Mac vault onto the phone (never deletes phone-only files) → opens `Start Chronicle.command` → `./gradlew :app:installDebug` + starts the app.

**Phone path resolution (in order):** `--phone-dir` / `CHRONICLE_PHONE_DIR` → best-effort decode of Android `vault_uri` prefs → probe common paths (`/sdcard/Chronicle`, Syncthing variants). Prefs auto-discover needs a **prior debug install + folder pick** in the app; `installDebug` at the end of this script does **not** create `vault_uri` in the same run. Work profile / non-`primary:` SAF folders require an explicit path.

**Flags:** `--phone-dir`, `--skip-process`, `--skip-launch`, `--skip-android`, `--skip-push`, `--dry-run`.

---

## Troubleshooting

| Symptom | What to try |
|---------|-------------|
| `layout_version` error on process/serve | Vault is still `1`. Copy vault → `chronicle backup` → `migrate-journal-v2 --apply --i-have-backup`. Co-release APK. |
| Settings says Connected but Timeline unchanged | LAN “Connected” only means the phone can reach Mac `serve` for Recall/Ask — not that the vault synced. Journal updates need Syncthing plus Mac `chronicle process` (or `watch`); wait for files, then reopen the app or pull to refresh. |
| Brain / Timeline stale on phone | Run `chronicle process` (or `watch`) on Mac; wait for Syncthing; confirm `brain/graph.json` exists and is recent. |
| Syncthing not moving files | Check both sides are Connected; confirm ignore list didn’t exclude `_capture/` or `brain/`; resolve `.sync-conflict-*` with `chronicle doctor`. |
| Ollama errors / empty enrich | `ollama list`; pull Ornith + embed + vision; ensure `ollama serve` is running. |
| No audio text | Install whisper.cpp + set `WHISPER_MODEL`; otherwise process skips transcription by design. |
| Android build fails (Java) | Use **JDK 21**; set `JAVA_HOME` to that JDK before `./gradlew`. |
| Android can’t write / blank vault | SAF permission lost — reopen app, re-pick the Chronicle folder so the URI is persisted again. |
| SPA blank at `/` | `cd chronicle-pc/frontend && npm run build`; restart `chronicle serve`. Legacy UI at `/legacy`. |
| Search / Recall empty | `chronicle index` then Start Chronicle (or `chronicle serve`); pull embed + LLM models for full answers. |
| Phone can’t reach Mac | Same Wi‑Fi; open via Start Chronicle.command; scan QR in Settings; allow macOS firewall for Python/Tauri if prompted. |
| Ask/Resume empty or error | Run `chronicle index` (or `process`/`watch`); ensure Ollama is up; confirm vault has PARA knowledge (after cutover, not `kb/notes/`). |
| Leftover `kb/notes/` / 410 on old paths | `chronicle backup` → `chronicle cutover-kb --apply --i-have-backup`; co-release CONTRACT v1.10 clients. |
| Stuck unfiled entries | `doctor` lists `processed && !filed`; re-run `chronicle process`. Amend gate skips blocks when MD hash ≠ `filed_content_hash` (human edit preserved). |

More PC-specific notes: [`chronicle-pc/README.md`](../chronicle-pc/README.md). Android build details: [`chronicle-android/README.md`](../chronicle-android/README.md).
