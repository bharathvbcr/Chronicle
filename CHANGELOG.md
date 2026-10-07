# Changelog

## v0.0.2 — 2026-10-07

Integrity and data-loss hardening across the Rust pipeline, vault backup, and Android capture. All component versions (Rust CLI, Tauri desktop, frontend, Python pipeline, Android) now report `0.0.2`; Android `versionCode` is `2`.

### Security

- **Rust pipeline never files sealed E2EE entries.** `chronicle process` refuses a vault whose config enables E2EE (the same gate `serve` applies), skips any entry carrying `text_enc` before transcription or captions, and `save_entry` refuses plaintext when `config.json` exists but cannot be read. Previously a sealed entry was filed once as an empty journal fence and its prose never reached the journal.
- **Android captures stay encrypted in every process.** The share sheet, quick-capture worker and LAN outbox now read E2EE state from persisted prefs through one decision (`E2eeManager.sealForCapture`) instead of an in-memory flag only set by `MainActivity`, so a cold process no longer writes or mirrors captures in the clear. Locked captures are parked, never written; an unreadable state writes nothing.
- E2EE enable/unlock run off the main thread (PBKDF2 on `Dispatchers.Default`); Settings shows progress while deriving.

### Fixes

- **Vault backup fails loud.** Any unreadable file or directory, archive error, or walk-depth cap is now an error naming the path, and the zip is written to a temp file and renamed only on success. Previously a partial backup reported success.
- **One backup exclusion list.** Rust and Python both read `contract/backup-exclusions.json`; Rust no longer drops every dotfile, so `.stignore` and `.obsidian/` are backed up. `check-contract-sync.sh` compares every contract file.
- **Quick captures are durable.** A capture that exhausts its retries is parked in `PendingCaptureQueue` with a notification instead of being discarded, and parked captures are filed read → write → remove whenever the vault is open.
- `atomicAppendLine` aborts when `phone.jsonl` exists but cannot be read, instead of rewriting the append-only curation log as a single line.

### Docs

- Single-owner runtime architecture and transition plan (Rust as production owner, Python as test oracle).
- OPERATIONS covers backup exclusions, fail-loud behaviour and the restore drill.

### Known limits

- Python installed from a wheel does not ship the exclusion file and fails loud; the editable install resolves it.
- The desktop app still reads `chronicle-pc/frontend/dist/` from the checkout at runtime.
- Builds are unsigned and not notarized.
