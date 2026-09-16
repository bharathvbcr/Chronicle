# Chronicle

**Capture a moment. Connect your notes. Find your way back.**

Chronicle is a local-first journal and second brain for Mac and Android. Android captures text, photos, and voice into a folder you choose. Syncthing moves the files between devices. The Mac files entries into Markdown journals and builds search, summaries, and a knowledge graph.

[Website](https://chronicle.vbcr.dev/) · [Setup guide](docs/SETUP.md) · [Documentation](docs/README.md) · [Architecture](docs/ARCHITECTURE.md)

## What you can do

- Capture on Android and browse files already synced to the phone while offline.
- Review Timeline, edit knowledge notes, and explore Brain in the desktop app or browser.
- Open the same vault in Obsidian. Knowledge lives in Markdown; journal prose is filed into identifiable entry fences.
- Use local Ollama models for text, vision, and embeddings, plus optional whisper.cpp transcription.
- Pair Android to the Mac for LAN recall and other API features. Pairing does not replace folder sync.

Optional cloud chat and vision require consent. API credentials stay outside the synced vault. Optional entry-text encryption is **not whole-vault encryption**; see [data and network boundaries](docs/ARCHITECTURE.md#data-and-network-boundaries).

## Start here

The current distribution path is a **source build**. The Tauri app uses an **embedded Rust server**. Python is available for the alternative server, watcher, and migration tools; it is not the desktop app's sidecar.

From this repository's root, with Rust, the macOS build tools, and a Node version compatible with the checked-in frontend installed:

```bash
# Build the shared application UI.
(cd chronicle-pc/frontend && npm ci && npm run build)

# Build the native CLI and initialize a new, separate data folder.
(cd chronicle-pc/server && cargo build --release)
mkdir -p ~/Chronicle
export CHRONICLE_DIR="$HOME/Chronicle"
./chronicle-pc/server/target/release/chronicle serve --no-lan
```

Open the local URL reported by the server. Stop it before starting another server for the same vault. For Android, models, the desktop shell, pairing, or an existing vault, follow the [complete setup guide](docs/SETUP.md).

**Existing data:** current processing and serving require `layout_version: 2`. The checked-in `demo-vault/` is version 1. [Copy, back up, and migrate it](docs/SETUP.md#try-the-demo-or-migrate-an-existing-vault); do not merely change its version field.

## One folder, clear ownership

| Content | Location | Owner |
| --- | --- | --- |
| Captures and structured metadata | `_capture/entries/` | Android / Mac entry APIs; pipeline updates processing state |
| Photos and voice recordings | `_attachments/` | Capture clients |
| Filed journal prose | `40-Journal/` | Pipeline filing; hash-checked amends preserve edit conflicts |
| Knowledge | `00-Inbox/`, `10-Work/`, `20-Personal/`, `30-Knowledge/`, `90-Archive/` | User-authored Markdown |
| Graph and summaries | `brain/`, `_system/derived/` | Mac-generated; synced for phone reading |
| Search database | `index/` | Mac-only; exclude from Syncthing |

The repo's archived `KnowledgeBase/` is not the live data folder. `kb/notes/` is retired; `vault_mirror` is deprecated. [Architecture and ownership](docs/ARCHITECTURE.md) explains the current layout.

## Repository guide

| Path | Purpose |
| --- | --- |
| [chronicle-android/](chronicle-android/README.md) | Kotlin / Compose app, SAF storage, phone integration |
| [chronicle-pc/server/](chronicle-pc/README.md) | Rust server and native CLI |
| [chronicle-pc/frontend/](chronicle-pc/frontend/README.md) | Shared React / TypeScript UI |
| [chronicle-pc/desktop/](chronicle-pc/desktop/README.md) | Tauri desktop shell and embedded server lifecycle |
| [chronicle-pc/pipeline/](chronicle-pc/README.md#python-tools) | Python CLI, FastAPI alternative, watcher, migrations |
| [docs/](docs/README.md) | Setup, architecture, operations, development |
| [portfolio/CHRONICLE_CASE_STUDY.md](portfolio/CHRONICLE_CASE_STUDY.md) | Engineering case study with evidence boundaries |

## Development and compatibility

Both apps carry the same [data contract](chronicle-pc/CONTRACT.md) and JSON schemas. Contract version **1.11** and vault layout version **2** are separate version numbers. Run `bash scripts/check-contract-sync.sh` after shared contract changes.

See [development checks](docs/DEVELOPMENT.md) for the actual commands, source owners, and limits of local verification. Source inspection and tests do not establish a signed release, physical-device behavior, or successful cloud-provider calls.
