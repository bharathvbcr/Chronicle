# Chronicle on Mac

The Mac runtime processes the shared vault, serves the application UI, and builds search and Brain. The **Rust server and CLI** (`chronicle-pc/server/`) is designated as the single production owner for all shipped runtime capabilities. The **Python toolchain** (`chronicle-pc/pipeline/`) is transitioning to a reference test oracle and fixture generator under our single-owner architecture plan; see [Architecture](../docs/ARCHITECTURE.md#single-owner-runtime-architecture-and-transition-plan).

[Setup](../docs/SETUP.md) · [Architecture](../docs/ARCHITECTURE.md) · [Operations](../docs/OPERATIONS.md) · [Contract v1.11](CONTRACT.md)

## Native runtime

From this directory:

```bash
(cd frontend && bun install --frozen-lockfile && bun run build)
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

Python 3.11+ is required by `pyproject.toml`. Optional extras are `vec`, `vertex`, and `mdns`; install only the extras required by your chosen workflow. Use explicit binary paths to avoid confusing the two commands named `chronicle`. Under the single-owner transition plan, Python serves as an assertion-backed reference oracle for contract and behavioral parity testing.

## Command availability

Checked against [Rust dispatch](server/src/main.rs) and [Python dispatch](pipeline/chronicle_pipeline/cli.py). This table describes commands during the transition period. Under the [single-owner port plan](../docs/ARCHITECTURE.md#single-owner-runtime-architecture-and-transition-plan), Rust will solely own all production commands.

| Command | Rust CLI | Python CLI | Single-owner target |
| --- | --- | --- | --- |
| `serve`, `process`, `brain`, `index` | Yes | Yes | **Rust** (Python as oracle) |
| `backup`, `export`, `doctor`, `rollup`, `topics` | Yes | Yes | **Rust** (Python as oracle / delete) |
| `rebuild-markdown-index` | Yes | Yes | **Rust** (Python as oracle) |
| `watch` | No | Yes | **Rust** (Phase 5 port: in-process & CLI daemon) |
| `rebuild` | No | Yes | **Rust** (Phase 6 port: native orchestrator) |
| `pair`, `unpair`, `pairs` | No; server APIs available | Yes | **Rust** (Phase 2 port: persistent pairstore CLI) |
| `e2ee-setup`, `unlock`, `lock` | No; server APIs available | Yes | **Rust** (Phase 1 port: native E2EE CLI) |
| `migrate-v2`, `migrate-journal-v2` | Reports Python-only tooling | Yes | **Rust** (Phase 6 port: native layout v2 migration) |
| `cutover-kb`, `import-*`, `migrate-kb` | Reports Python-only tooling | Yes | Retired / historical offline scripts |
| `serve --no-tls` | Not a supported native downgrade | Python option | Deprecated (Rust enforces pinned TLS on LAN) |
| `index --write-markdown` | Use separate `rebuild-markdown-index` | Yes | Unified under Rust `index` / `rebuild-markdown-index` |

The native CLI parser is not a substitute for Python's per-command `--help`. Check supported flags in `server/src/main.rs` before assuming an option is honored. Refer to the [Architecture transition guide](../docs/ARCHITECTURE.md#single-owner-runtime-architecture-and-transition-plan) for the complete duplicated module inventory, port roadmap, and parity harness design.

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
