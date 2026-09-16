# Chronicle: one vault across Mac and Android

Chronicle combines quick Android capture with a Mac processing and retrieval system. Syncthing moves a shared folder containing structured captures, Markdown journals, knowledge notes, and generated Brain data. The engineering problem is making those representations cooperate without treating a generated summary or graph as the user's original writing.

## System design

The Mac application embeds a Rust server inside Tauri and renders a shared React/TypeScript UI. Rust also exposes a standalone CLI. Python retains an alternative FastAPI runtime, file watching, and migration tools. Android uses Kotlin, Compose, and the Storage Access Framework.

[Architecture and source owners](../docs/ARCHITECTURE.md) · [Command matrix](../chronicle-pc/README.md#command-availability)

## Engineering decisions

| Decision | Purpose | Evidence |
| --- | --- | --- |
| Split structured entry metadata from filed prose | Retain capture fields while making journals editable as Markdown | [Shared contract](../chronicle-pc/CONTRACT.md), [journal.rs](../chronicle-pc/server/src/journal.rs) |
| Hash-checked journal amends | Detect stale edits before overwriting a changed block | [journal.rs](../chronicle-pc/server/src/journal.rs), journal API implementation |
| One shared file/wire specification | Keep Kotlin, Rust, and Python implementations aligned | Contract/schema copies and [sync check](../scripts/check-contract-sync.sh) |
| Per-device curation logs with generated graph | Separate user graph intent from derived state | `curation/ops/` and `brain/graph.json` in the contract |
| Local search and model adapters | Keep default retrieval local while allowing explicit provider choices | [provider.rs](../chronicle-pc/server/src/provider.rs), [index_store.rs](../chronicle-pc/server/src/index_store.rs) |
| Persistent pairing and pinned LAN transport | Give phone API access a revocable identity and certificate pin | [serve.rs](../chronicle-pc/server/src/serve.rs), contract v1.11 |

Local locks and atomic writes reduce local failure modes; they do not eliminate cross-device synchronization races. Generated graphs are not append-only databases, and archiving older nodes does not establish constant memory usage.

## Portfolio summary

Built a local-first journal and knowledge system across Mac and Android, with an embedded Rust desktop server, a shared React UI, Kotlin/SAF capture, Python migration tooling, and a Syncthing-shared Markdown vault. Designed file-once journal filing and hash-checked amendments, local search and graph-assisted recall, and paired LAN access with credentials kept outside the sync folder.

## Evidence boundaries

**Verified by source inspection:** runtime ownership, the command split, actual vault paths, and the linked implementation mechanisms. **Unverified by this case study:** performance benchmarks, guaranteed loss-free sync, end-to-end installation, signing/notarization, real-device behavior, full Rust/Python parity, and live provider calls.

No synthetic terminal transcript, latency figure, or claim of perfect privacy is used as a measurement. Optional cloud use requires consent; optional entry-text encryption is not whole-vault encryption. See [development qualification](../docs/DEVELOPMENT.md#evidence-and-release-boundaries).
