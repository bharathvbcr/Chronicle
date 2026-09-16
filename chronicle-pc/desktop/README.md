# Chronicle desktop

Tauri 2 shell with an **embedded Rust server** and the shared React application. The desktop boots `chronicle_server::serve` in-process through [embedded.rs](src-tauri/src/embedded.rs); Python is not its sidecar.

## Build and run

From the repository root, with macOS build tools, Rust, and Node/npm installed:

```bash
(cd chronicle-pc/frontend && npm ci && npm run build)
(cd chronicle-pc/desktop && npm ci && npm run tauri:build)
export CHRONICLE_DIR="$HOME/Chronicle"
bash "chronicle-pc/Start Chronicle.command"
```

For development use `npm run tauri:dev` from `chronicle-pc/desktop/`. Its Vite server serves the shell startup/capture pages; the shared React vault UI is a separate frontend build.

Current configured bundles are macOS `app` and `dmg`. The configured minimum system version is 12.0; that is not a physical compatibility test. Outputs are under `src-tauri/target/release/bundle/`, with the executable at `src-tauri/target/release/chronicle`. Signing, notarization, installation, and launch from a relocated bundle need separate verification.

## Startup and ownership

1. Resolve the PC source root using environment, executable/cwd ancestry, persisted support path, and configured fallbacks.
2. Resolve the vault: `CHRONICLE_DIR`, then `~/Chronicle`, then the repo demo fallback.
3. Validate vault layout and prepare the native server, preferring port 8765.
4. Report status to the shell and serve the shared UI from the local URL.
5. Signal the owned in-process server to stop during shutdown/restart.

The **demo fallback is not serve-ready**: it is layout version 1. Use an explicit version-2 vault or migrate a copy first.

The current app still needs the PC checkout and `frontend/dist/` on disk. `Start Chronicle.command` sets `CHRONICLE_PC_ROOT` and prefers a built executable; it writes the persisted support path only when the Python CLI file exists. Therefore, running that launcher once without a venv does not prove a relocated `/Applications` bundle can find the checkout later. Do not describe this as a self-contained installer.

## Native surfaces

[lib.rs](src-tauri/src/lib.rs) wires tray actions, quick capture, window state, and embedded server startup. These use the same vault API as the browser UI. The webview connects over loopback HTTP; Android connects to the server's paired LAN endpoint.

Python is still useful for file watching and migrations. See the [command matrix](../README.md#command-availability), [setup](../../docs/SETUP.md), and [development checks](../../docs/DEVELOPMENT.md).
