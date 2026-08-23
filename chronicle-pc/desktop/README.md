# Chronicle desktop (Tauri 2)

Thin native shell around `chronicle serve` + the React SPA. The SPA remains
usable in a plain browser on LAN; this app is a wrapper, not a fork.

## Prerequisites

- Node 20+ and npm
- Rust (rustup) + Xcode Command Line Tools (macOS)
- Chronicle PC venv with the CLI installed:

```bash
cd chronicle-pc
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

- Vault at `~/Chronicle` or `CHRONICLE_DIR`, or the repo `demo-vault/`
- Optional: build the SPA so serve can host it (`cd frontend && npm run build`)

## Dev

```bash
cd chronicle-pc/desktop
npm install
npm run tauri:dev
```

On launch the shell:

1. Resolves the vault (`CHRONICLE_DIR` → `~/Chronicle` → `demo-vault`)
2. Finds `chronicle` (`.venv/bin/chronicle`, then `PATH`)
3. Reuses a healthy serve if `index/serve.json` / `/health` already respond
4. Otherwise spawns `chronicle serve --lan --port 8765` and waits on `/health`
5. Navigates the webview to `http://127.0.0.1:<port>/` (actual port from `serve.json`)

## Release build

```bash
cd chronicle-pc/desktop
npm install
npm run tauri:build
```

App bundle (macOS):

`src-tauri/target/release/bundle/macos/Chronicle.app`

Binary:

`src-tauri/target/release/chronicle`

`Start Chronicle.command` prefers the built **binary** when present (so
`CHRONICLE_PC_ROOT` reaches the process), then the `.app` bundle, otherwise
falls back to the browser launcher (`start_dashboard.sh`).

## Native features

- **Menu-bar tray**: Show Chronicle, Quick Capture, Quit
- **Quick Capture**: small window that `POST`s to `/entries` on the local API
- **Dock badge**: unprocessed entry count (`GET /entries?processed=false`)
- **Window state**: size/position restored via `tauri-plugin-window-state`

## Sidecar notes

- Owned serve processes are killed when the app quits
- If serve was already running, the shell attaches without owning it
- Port preference: `index/serve.json` → `8765` → scan `8765..8814`
- CLI discovery: `CHRONICLE_PC_ROOT` → walk from binary → persisted
  `~/Library/Application Support/Chronicle/pc_root` → cwd →
  `$HOME/Code/Chronicle/chronicle-pc` → `.venv/bin/chronicle` / `PATH`
- Vault discovery: `CHRONICLE_DIR` → `~/Chronicle` → repo `demo-vault/`
- `Start Chronicle.command` sets `CHRONICLE_PC_ROOT`, writes the support
  `pc_root` file, and launches the binary so Finder/`/Applications` opens can
  find the venv even when the `.app` bundle is elsewhere
