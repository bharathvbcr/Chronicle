# Operate and maintain Chronicle

Commands run from the repository root with `CHRONICLE_DIR` set to the intended data vault. The examples use explicit executable paths to distinguish native and Python commands.

## Daily loop

```bash
export CHRONICLE_DIR="$HOME/Chronicle"
./chronicle-pc/server/target/release/chronicle process
./chronicle-pc/server/target/release/chronicle doctor
```

Capture → sync files to Mac → process → sync journals and Brain back. To watch for file changes, use the **Python** CLI after [installing it](SETUP.md#python-tools):

```bash
./chronicle-pc/.venv/bin/chronicle watch
```

Do not assume that starting the native server starts the Python file watcher. Use the desktop's processing controls or run `process` explicitly when testing a new setup.

## Search and generated content

```bash
./chronicle-pc/server/target/release/chronicle index
./chronicle-pc/server/target/release/chronicle brain
./chronicle-pc/server/target/release/chronicle rollup
./chronicle-pc/server/target/release/chronicle topics
./chronicle-pc/server/target/release/chronicle rebuild-markdown-index
```

`index/` is rebuildable and Mac-only. `brain/graph.json` and `_system/derived/` are generated output. Do not treat a generated graph as the source of user edits: curation belongs in the per-device operation log.

Open the **vault root** in Obsidian. Hide machine/capture paths from note navigation as appropriate, including `index/`, `brain/`, `_capture/`, `_attachments/`, and legacy `entries/`, `img/`, `audio/`, `_staging/`. Do not remove those files just to hide them. Keep journal fences intact; use the UI's hash-checked amendment path for conflict-aware edits.

## Backup and recovery

```bash
mkdir -p "$HOME/Backups"
./chronicle-pc/server/target/release/chronicle backup "$HOME/Backups/chronicle-before-change.zip"
```

Choose a new backup filename; existing files require an explicit overwrite option. Store backups **outside the Syncthing folder**. The backup excludes the rebuildable `index/`.

For recovery, stop writers, extract the archive to a separate folder, point `CHRONICLE_DIR` there, and run `doctor`. Check the recovered entries, Markdown, and attachments before putting it back into sync. Rebuild the index and other derived content as needed. A successful archive command does not by itself prove a complete restore.

`doctor` without `--fix` is report-only. Review its output before applying repairs. Markdown conflicts and orphan media need human review; do not equate repair of entry JSON conflicts with automatic recovery of all user content.

## Migration and legacy content

Use the Python tools, not the native CLI, for migration:

| Tool | Purpose |
| --- | --- |
| `migrate-journal-v2` | File-once journal layout conversion |
| `migrate-v2` / `migrate-kb-para` | Knowledge migration into the PARA areas |
| `cutover-kb` | Retire remaining legacy `kb/notes/` content |
| `migrate-kb` | Historical KnowledgeBase import workflow |
| `import-legacy`, `import-knowledgebase` | Legacy input adapters |

Run the relevant command's `--help`, make an external backup, inspect its dry-run output, then use `--apply --i-have-backup` where required. The [demo migration recipe](SETUP.md#try-the-demo-or-migrate-an-existing-vault) works on a copy. Do not use old clients to write a converted vault.

## Pairing and entry-text encryption

Use Settings on the running server to connect the phone. Python also provides these management commands:

```bash
./chronicle-pc/.venv/bin/chronicle pairs
./chronicle-pc/.venv/bin/chronicle pair phone
./chronicle-pc/.venv/bin/chronicle unpair phone
```

`pair --show-token` reveals the secret; keep it out of logs and shared screenshots. Native CLI dispatch does not expose `pair`, `unpair`, or `pairs`, even though the native server supports pairing APIs.

Python `e2ee-setup`, `unlock`, and `lock` are also distinct from unlocking a running server. A CLI unlock verifies the passphrase in its own process; the server has its own unlock endpoint/state. Follow the [contract](../chronicle-pc/CONTRACT.md) for setup and rotation. Back up before changing encryption configuration, and do not mistake capture-text encryption for encryption of every vault file.

## Optional wired catch-up

Pause Syncthing on both devices, connect Android over ADB, and inspect the script first:

```bash
bash scripts/pc-to-phone-sync.sh --dry-run
```

The script can process the Mac vault, merge-push it to the phone, launch the desktop, and build/install Android. These are broader effects than file copy. Use `--phone-dir` or `CHRONICLE_PHONE_DIR` for the correct phone path. Automatic SAF preference discovery requires a previous debug install and folder selection; it cannot select the folder on first install. See the script's `--help` for skip flags.

## Background services

The checked-in [install-launchd.sh](../chronicle-pc/scripts/install-launchd.sh) is **machine-specific**: it hardcodes `/Users/bharath/`, runs serve on port 8000 with `--no-lan`, and writes `~/Library/Logs/chronicle-serve.log` and `chronicle-watch.log` for that account. It is not a portable first-install command. Inspect/adapt its program paths, vault path, port, and log directories before using it. Use the Python executable for `watch`. Do not run a second unmanaged server against the same vault while diagnosing services.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Layout refused | Inspect actual config; back up and migrate version 1. Do not just change the version field. |
| Desktop cannot find PC root | Retain the source checkout; start through `Start Chronicle.command`, which sets `CHRONICLE_PC_ROOT`. |
| Old or missing browser UI | Build `chronicle-pc/frontend`; check asset discovery and the reported server URL. |
| Phone says connected, but notes are stale | API reachability is separate from Syncthing. Confirm both file sync and Mac processing. |
| Phone cannot save | Re-pick the vault folder to restore SAF permission. |
| Audio stays unprocessed | Check `whisper-cli`, `WHISPER_MODEL`, and transcription output. Empty transcripts are retried. |
| Recall lacks answers | Check search indexing and the configured model/provider. Missing AI is not a successful answer. |
| Pairing fails after network change | Use the current server QR; check LAN reachability and the certificate pin. |
| Markdown amendment conflict | Review disk and UI versions; preserve the edited block and use explicit conflict resolution. |
| Sync conflict or orphan file | Run report-only `doctor`; review before repairing. Local locks do not prevent all cross-device races. |
| Native CLI says unknown command | Check the [command matrix](../chronicle-pc/README.md#command-availability); watcher and management tools may be Python-only. |

When reporting a problem, include runtime (native/Python/desktop), command, layout version, and the error message. Exclude credentials, tokens, passphrases, and private journal text.
