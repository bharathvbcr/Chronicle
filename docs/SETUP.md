# Set up Chronicle

Build the Mac app or run the shared UI in a browser, then add Android and folder sync. Commands below run from the **Chronicle repository root** unless marked otherwise. Keep the data vault separate from the source checkout.

## Choose a runtime

| Route | What runs | What you need |
| --- | --- | --- |
| Native browser | Rust CLI serving the React UI | Rust toolchain, built frontend |
| Desktop | Tauri with an embedded Rust server | macOS build tools, Rust, Node/npm, built frontend and retained checkout |
| Python tools | FastAPI server, file watcher, migration/maintenance CLI | Python 3.11+ and the project's virtual environment |

The current Tauri configuration targets macOS app and DMG bundles. A build artifact is not evidence of signing or notarization. The desktop still resolves `chronicle-pc/` and frontend assets on disk; retain the checkout. See [desktop details](../chronicle-pc/desktop/README.md).

For the frontend, use a Node version accepted by the locked Vite package (check `node_modules/vite/package.json` after install); Node 22.12+ on the 22.x line satisfies the current Vite 8 requirement. Android uses JDK 21 and the project's Gradle wrapper; SDK versions come from its [build configuration](../chronicle-android/app/build.gradle.kts).

## 1. Prepare the Mac vault and UI

For a **new** vault:

```bash
mkdir -p ~/Chronicle
export CHRONICLE_DIR="$HOME/Chronicle"
(cd chronicle-pc/frontend && npm ci && npm run build)
(cd chronicle-pc/server && cargo build --release)
./chronicle-pc/server/target/release/chronicle serve --no-lan
```

Open the loopback URL reported by the server; the preferred port is 8765, with fallback ports if occupied. A new vault gets default configuration. Existing configurations must declare the actual layout version. Review the vault's timezone and model choices against [config.json.example](../chronicle-pc/config.json.example); **do not overwrite an existing config** with the example.

The Rust API serves `frontend/dist/` at `/` when available. The old single-file dashboard remains at `/legacy`. If the modern UI is missing, confirm that the frontend build succeeded and that `CHRONICLE_PC_ROOT` resolves to this checkout's `chronicle-pc/`.

## 2. Optional desktop shell

Stop the browser-route server before launching the desktop against the same vault.

```bash
(cd chronicle-pc/desktop && npm ci && npm run tauri:build)
export CHRONICLE_DIR="$HOME/Chronicle"
bash "chronicle-pc/Start Chronicle.command"
```

The launcher prefers a built Tauri binary. The app boots the Rust server in-process; it does not launch a Python sidecar. The shell's build does not build the shared React application for you: keep the frontend build from step 1.

For a browser without Tauri, `bash chronicle-pc/start_dashboard.sh --browser` selects a native server binary first, then the Python fallback. That wrapper also uses system `python3` for discovery; direct native CLI use avoids the wrapper's Python dependency.

## 3. Add local models

[Install Ollama](https://docs.ollama.com/quickstart) and use its [CLI](https://docs.ollama.com/cli) to pull the model names configured in your vault. Current source defaults are:

```bash
ollama pull maxwell1500/ornith-35b:Q4_K_M
ollama pull nomic-embed-text
ollama pull llama3.2-vision:11b
```

These are configuration defaults, not a hardware recommendation or a guarantee that the registry tag is available. The 35B text and 11B vision models require substantial local resources. Choose models appropriate for your machine and update `models.llm`, `models.embed`, and `models.vision` together with your setup. Changing embedding models requires rebuilding the search index.

Optional transcription uses [whisper.cpp](https://github.com/ggml-org/whisper.cpp): make `whisper-cli` available and set `WHISPER_MODEL` to your downloaded model file before starting Chronicle.

If audio has no text and transcription cannot produce any, processing leaves that entry unprocessed so it can retry. Existing text can still be filed. AI enrichment and answers depend on available providers; saving an entry does not prove its AI processing succeeded.

## 4. Sync the folder and install Android

Configure [Syncthing](https://docs.syncthing.net/intro/getting-started.html) on both devices to share the **data vault**, not this repository. Required ignore patterns from the contract:

```text
index/
*.tmp
.DS_Store
.stfolder
```

Keep `brain/`, `_capture/`, `_attachments/`, `40-Journal/`, and the knowledge areas in sync. Credentials belong outside this folder. Chronicle itself does not control the Syncthing application.

With Android SDK and JDK 21 configured:

```bash
(cd chronicle-android && ./gradlew :app:assembleDebug)
adb install -r chronicle-android/app/build/outputs/apk/debug/app-debug.apk
```

Open Chronicle on Android and select the Syncthing folder using the system folder picker. Allow persistent access. Capture a text entry, wait for the file to reach the Mac, then process it:

```bash
./chronicle-pc/server/target/release/chronicle process
```

Wait for the filed journal and graph to sync back. Confirm that the entry appears on both devices. This is the first end-to-end check; a green build alone does not establish it.

## 5. Pair Android for Mac-backed features

Start the native CLI with `serve` (LAN is the default), or use the desktop app. Use Settings / Connect on the Mac and scan the QR in Android Settings. Store the displayed base URL, token, and certificate pin through the app's pairing flow; do not copy tokens into documentation or shared files.

The native server exposes loopback HTTP for the local UI and pinned HTTPS on the LAN address. If it cannot prepare LAN TLS, it reports an error. The Python server has different transport details; follow its reported URL. Use `serve --no-lan` for a local-only browser session.

Pairing uses **persistent per-device tokens**. File sync remains a separate path: paired API access, an outbox mirror, or an SSE event does not mean every vault file is synchronized. [Operations](OPERATIONS.md) covers pairing management and troubleshooting.

## Python tools

Install these for `watch`, migration, or the alternative FastAPI runtime:

```bash
(cd chronicle-pc && python3 -m venv .venv)
./chronicle-pc/.venv/bin/python -m pip install -e './chronicle-pc[dev]'
./chronicle-pc/.venv/bin/chronicle --help
```

Use the explicit binary path when following Python-only commands. Both runtimes install a command named `chronicle`; a bare command may select the wrong one. [PC reference](../chronicle-pc/README.md) lists the differences.

## Try the demo or migrate an existing vault

The checked-in demo is `layout_version: 1`. Work on a copy and make a backup outside the sync share before migration. After installing the Python tools:

```bash
cp -R demo-vault "$HOME/Chronicle-demo-copy"
export CHRONICLE_DIR="$HOME/Chronicle-demo-copy"
mkdir -p "$HOME/Backups"
./chronicle-pc/.venv/bin/chronicle backup "$HOME/Backups/chronicle-demo-before-migration.zip"
./chronicle-pc/.venv/bin/chronicle migrate-journal-v2 --dry-run
./chronicle-pc/.venv/bin/chronicle migrate-journal-v2 --apply --i-have-backup
./chronicle-pc/.venv/bin/chronicle cutover-kb --dry-run
```

Review cutover output. If legacy `kb/notes/` remain, apply `cutover-kb --apply --i-have-backup` to the backed-up copy. Run `doctor`, then start the server. Never upgrade an existing vault by changing `layout_version` alone. Use matching Android and PC clients.

## Optional cloud models and encryption

Ollama is the default. Grok is available through the native and Python runtimes. Vertex is supported through Python; the native provider factory currently rejects it despite an adapter file being present. Android offers Grok. Configure the provider and grant cloud consent explicitly. Images need separate vision consent. On Mac, credentials and local consent overrides live under `~/.config/chronicle/`; on Android use the app's protected settings. A local JSON secrets file is not an OS keychain. Do not put credentials in `config.json` or the Syncthing share.

Optional `text_enc` encryption protects entry text in the capture format. It does **not** establish encryption for Markdown journals, media, or all derived state. See [architecture boundaries](ARCHITECTURE.md#data-and-network-boundaries) before enabling it. This setup guide does not alter authentication, encryption, or provider consent.

Next: [day-to-day operations](OPERATIONS.md), [troubleshooting](OPERATIONS.md#troubleshooting), or [development](DEVELOPMENT.md).
