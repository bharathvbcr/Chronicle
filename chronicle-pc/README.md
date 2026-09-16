# Chronicle on Mac

The Mac runtime processes the shared vault, serves the application UI, and builds search and Brain. The **Rust server** powers the desktop in-process. The **Python CLI** remains the alternative API runtime and the owner of watcher and migration commands.

[Setup](../docs/SETUP.md) · [Architecture](../docs/ARCHITECTURE.md) · [Operations](../docs/OPERATIONS.md) · [Contract v1.11](CONTRACT.md)

## Native runtime

From this directory:

```bash
(cd frontend && npm ci && npm run build)
(cd server && cargo build --release)
export CHRONICLE_DIR="$HOME/Chronicle"
./server/target/release/chronicle serve --no-lan
```

The preferred port is 8765; use the URL the server reports. Omit `--no-lan` to enable the paired LAN service. Rust keeps local UI HTTP on loopback and serves pinned TLS on the LAN address. `frontend/dist/` supplies the modern UI; `/legacy` retains the old dashboard.

For Tauri, build using the [desktop guide](desktop/README.md), then run `bash "Start Chronicle.command"`. The browser wrapper `bash start_dashboard.sh --browser` selects native release/debug binaries before the Python virtual environment. It uses system Python for discovery even when the selected server is Rust.

## Python tools

From this directory:

```bash
python3 -m venv .venv
./.venv/bin/python -m pip install -e '.[dev]'
./.venv/bin/chronicle --help
```

Python 3.11+ is required by `pyproject.toml`. Optional extras are `vec`, `vertex`, and `mdns`; install only the extras required by your chosen workflow. Use explicit binary paths to avoid confusing the two commands named `chronicle`.

## Command availability

Checked against [Rust dispatch](server/src/main.rs) and [Python dispatch](pipeline/chronicle_pipeline/cli.py). This table describes commands, not full behavioral parity.

| Command | Rust CLI | Python CLI |
| --- | --- | --- |
| `serve`, `process`, `brain`, `index` | Yes | Yes |
| `backup`, `export`, `doctor`, `rollup`, `topics` | Yes | Yes |
| `rebuild-markdown-index` | Yes | Yes |
| `watch`, `rebuild`, `init-vault-structure`, `enrich-kb` | No | Yes |
| `pair`, `unpair`, `pairs` | No; server APIs available | Yes |
| `e2ee-setup`, `unlock`, `lock` | No; server APIs available | Yes |
| `migrate*`, `cutover-kb`, `import-*` | Reports Python-only tooling | Yes |
| `serve --no-tls` | Not a supported native downgrade | Python option |
| `index --write-markdown` | Use separate `rebuild-markdown-index` | Yes |

The native CLI parser is not a substitute for Python's per-command `--help`. Check supported flags in `server/src/main.rs` before assuming an option is honored.

## Vault and models

Set `CHRONICLE_DIR` to the Syncthing data folder. `process` and `serve` require `layout_version: 2`; the repo demo is version 1 and needs migration on a backed-up copy.

Defaults come from [config.json.example](config.json.example) and runtime configuration: Ornith 35B for text, `nomic-embed-text` for embeddings, `llama3.2-vision:11b` for vision. They are configurable defaults, not benchmark results or a hardware-sizing recommendation. Optional transcription requires `whisper-cli` and `WHISPER_MODEL`.

Ollama is the default; Grok is wired in both Mac runtimes. Vertex is Python-only: the Rust provider factory currently rejects it; the presence of `server/src/vertex.rs` does not make it available. Cloud use requires consent, with a separate vision consent flag. Credentials belong outside the synced vault. See [setup](../docs/SETUP.md#optional-cloud-models-and-encryption) and the [security boundary](../docs/ARCHITECTURE.md#data-and-network-boundaries).

## Verification

```bash
./.venv/bin/python -m pytest -q
./.venv/bin/ruff check pipeline tests
(cd server && cargo test)
```

[Development](../docs/DEVELOPMENT.md) describes broader checks and parity-harness limitations. Native tests, Python tests, a built frontend, and physical phone/LAN qualification are separate evidence.
