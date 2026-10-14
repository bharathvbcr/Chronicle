# Chronicle audit — 2026-10-13

No source, config, or dependency was modified. The only writes are this file and GitPulse board updates. Nothing was moved to done. No `in_progress` card was touched. No card was deleted or merged.

Verification is control-flow reading of the cited lines and their callers. No tests, builds, or scripts were executed. The JDK SIGPIPE exit 141 on `scripts/pc-to-phone-sync.sh` is inferred, not observed.

The harness has no Sonnet / Opus / Fable selector. Wave auditors were Grok subagents in the Sonnet role (max 4 at once). The parent did Opus verification and Fable grouping. Follow that grouping; do not split these cards back into one-finding tasks.

## Index and lane

GitPulse Insights, re-checked immediately before the last filing: one worktree (`/Users/bharath/Code/apps/Chronicle`, `main`), dirty 0, live Claude and Codex sessions 0, collisions 0. Every facet `ok`. No finding is flagged in-flight.

DevMap 0.2.4, store `.devcouncil/codeintel/devmap.sqlite`, generation 140, schema 27, `query_ready`. The CLI `degraded_reason` is partial call extraction (13 HTML/CSS pattern-recovered, 13 files with small ERROR/MISSING regions). MCP status reported `degraded_reason` null; the CLI is the one that was trusted. `liveness_unreachable_unreliable` is true, so `unreachable_files` was ignored. `dead_clusters` empty is not evidence of none. GitPulse codeintel disagrees with the CLI on analyzer freshness: Insights says `analyzer_freshness: false` (“stored extraction payload is obsolete”); the CLI said true. Both are recorded. Kotlin, Rust, TSX, and Python resolution is low. Dead symbols and unwired files are a lower bound.

The index was not rebuilt. `devmap gap-record` was not used. Both would write under the repo, which this audit does not allow. Empty graph answers are unknown.

## Severity × dimension

Counts are verified findings, not cards. One card owns each shared concern. Pre-existing board work that this audit did not re-file is not in the counts.

| Dimension | Critical | High | Medium | Low |
| --- | --- | --- | --- | --- |
| Security | 1 | 12 | 1 | 0 |
| Correctness (Win / macOS / Linux) | 0 | 7 | 4 | 0 |
| Defensive validation at trust boundaries | 0 | 1 | 1 | 0 |
| Async error boundaries | 0 | 1 | 2 | 0 |
| Deterministic testability | 0 | 1 | 0 | 0 |
| Performance and benchmarks | 0 | 0 | 2 | 0 |
| Modular architecture | 0 | 0 | 0 | 0 |
| Strict typing | 0 | 0 | 0 | 0 |

Modular architecture is the existing spike `ft-f1ec93f73d4a0af2069b1bddf1d9c1db` (medium, review). Criteria 1–3 were marked met in source. The port itself is not a finding and has not started. Strict typing had no confirmed defect after verification. The Grok message-shape bug is counted under correctness.

Security critical: passphrase rotation (`py-1`). Security high: Ollama userinfo URL (`rust-1`), sealed-text search (`py-2`), zip backup (`py-5`), pairing-file clobber (`py-6`), PBKDF2 ceiling and remote adopt (`py-7`, `and-1`), Discover persisting an unpaired host (`and-2`), four containment bugs (`rust-3` through `rust-6`), parity script vault and `pkill` (`x-2`). Security medium: legacy dashboard recall target (`x-5`).

Correctness high: journal clobber and stuck filed stamp (`rust-2`, `py-3`), five editor draft-loss bugs (`fe-1`, `fe-3`, `fe-4`, `fe-5`, `fe-6`). Correctness medium: SSE fingerprint (`py-9`), Grok content shape (`rust-7`), voice-only capture (`and-5`), desktop data paths (`fe-7`, the platform-specific one).

Defensive validation high: image save and park reporting success (`and-4`). Medium: chunked body limit (`py-8`).

Async high: watcher drops events scheduled during processing (`py-4`). Medium: outbox snapshot drain (`and-3`), LAN token cleared for the page lifetime (`fe-2`, downgraded).

Testability high: `parity.sh` empty-equals-empty while the venv exists (`x-1`).

Performance medium: keyword scan (`py-10`) and uncapped dashboard walk (`x-6`). The walk is awaited recursion, so it is time and memory, not a stack overflow.

## Top 10 risks

1. **Passphrase rotation can strand ciphertext** under a key the config no longer names. `ft-20f1a740ce28cbc14cbdb45da7e04a08`. Priority 0, critical, backlog. Owner decision still open.
2. **Local model allowlist accepts a userinfo URL** and posts prompts to that origin. `ft-6bbc302e38efbab8639809bf23b4ff93`.
3. **Indexed journal text stays readable when the encryption block is unreadable.** `ft-0c891193dd8d721fd4092d9bbd68d0f2`.
4. **Vault zip writer truncates the archive in place and stores symlink target bytes.** `ft-d9e10db628e28fedd9b3f132c6159888`.
5. **Planted parent-directory links escape the vault, and a self-move unlinks the note.** `ft-b1be434ccd48e25f4413793e96722382`.
6. **PBKDF2 has no ceiling, and a remote derivation is stored before the typed secret matches.** `ft-1b06074e67d64b8a12a3bf15a1b80a8f`.
7. **A failed day-file read is written back as an empty scaffold, and a missing filed stamp never retries.** `ft-1f2844139dc9c0db9ad97b1fad61aa90`.
8. **Phone sync can extract onto shared-storage root and can reject a real JDK 21.** `ft-36db2dac67e350ae743d64924938e7c5`. The JDK exit is inferred.
9. **A corrupt pairing file is replaced by one minted device record.** `ft-d02b2b2283592107342825e35528ecea`.
10. **`scripts/parity.sh` passes on empty bodies, and can modify `CHRONICLE_DIR` and `pkill -f`.** Folded into `ft-dbd3a4eb2edaa186901a33b982c2c89f` part 4.

Close behind those: Discover persisting an unpaired host and mirroring before a QR token (`ft-d9fd0266adf2cc5fad352a1cb846be9c`), and unsaved editor drafts discarded by refresh, navigation, and query (`ft-fbc822561155ef4a15dbd56add1cb2eb`).

## New cards

| Item | Title on the board | Priority | Severity | Status |
| --- | --- | --- | --- | --- |
| `ft-20f1a740ce28cbc14cbdb45da7e04a08` | Passphrase rotation must not strand ciphertext under a key the config no longer names | 0 | critical | backlog |
| `ft-0c891193dd8d721fd4092d9bbd68d0f2` | Indexed journal text stays queryable when the encryption block is unreadable | 1 | high | ready |
| `ft-d9e10db628e28fedd9b3f132c6159888` | Vault zip writer truncates the archive in place and copies symlink targets | 1 | high | ready |
| `ft-6bbc302e38efbab8639809bf23b4ff93` | Local model allowlist accepts a userinfo URL and posts prompts to that origin | 1 | high | ready |
| `ft-1f2844139dc9c0db9ad97b1fad61aa90` | Day markdown is clobbered on a failed read, and a missing filed stamp never retries | 1 | high | ready |
| `ft-36db2dac67e350ae743d64924938e7c5` | Phone sync can extract onto shared storage root and can reject a real JDK 21 | 1 | high | ready |
| `ft-1b06074e67d64b8a12a3bf15a1b80a8f` | Bound PBKDF2 iterations and store a remote derivation only after the typed secret matches | 1 | high | ready |
| `ft-d02b2b2283592107342825e35528ecea` | A corrupt pairing file is overwritten by one minted device record | 1 | high | ready |
| `ft-b1be434ccd48e25f4413793e96722382` | Planted parent links escape containment, and a self-move unlinks the source | 1 | high | ready |
| `ft-ccca0632bbfe73a2bbb8f8686149144f` | Filesystem events that arrive during processing are dropped | 1 | high | ready |
| `ft-fbc822561155ef4a15dbd56add1cb2eb` | Unsaved note, fence, and entry drafts are discarded by refresh, navigation, and query | 1 | high | ready |
| `ft-61101c95e1a2d638c52ba6f7885c733d` | Grok image messages wrap the parts array in an extra list | 2 | medium | ready |
| `ft-6e2a124296405a6d3670a74a11057222` | Capture a baseline for keyword scoring when embeddings are unavailable | 2 | medium | ready |
| `ft-f1a796017fab7d123a2e6a791a5a61d3` | Live fingerprint skips an unstatted path, so a later rewrite is invisible | 2 | medium | ready |
| `ft-f0969d15da889d93b3b36331d719688e` | Embedded desktop resolves data directories with macOS Library layout on every OS | 2 | medium | ready |
| `ft-bc99f5f8e91f0b492ef91953275ec5fc` | Connect lookup failure clears the auth header until the page reloads | 2 | medium | ready |
| `ft-95a10ba1060312ac7df469dc2539d68d` | Fallback ranking loads each document body rather than FTS | 2 | medium | backlog |

All carry label `audit-2026-10-13`. Kind is `bug`, except the benchmark card, which is `chore`. The benchmark card may add only `chronicle-pc/pipeline/tests/test_search_keyword_bench.py`. It must not edit `index_store.py`. There is no PBKDF2 benchmark that runs a huge iteration count.

A few titles were reworded so the board’s related-task check would not fold unrelated bugs together. The concern on each card is unchanged. Wording that changed: containment (parent-link escape and self-move, not “one temp per process” and not final-path write-through), editor (“query” for the search-triggered discard), live fingerprint (unstatted path), connect-lookup failure, keyword-scoring baseline, FTS fallback.

## Folded into existing cards

Re-pulled immediately before overwrite. Each overwrite sent the stored title, the full previous description plus an `## Audit 2026-10-13` section, and the full previous criteria plus the new ones. Status was omitted, so the column stayed. Priority and severity were not lowered.

| Item | Revision | What was added | Status after |
| --- | --- | --- | --- |
| `ft-099ce10efd9c49405b385c7da7d683cf` | 3 | `py-8` chunked / headerless JSON over 8 MiB must 413. `x-5` / `x-6` dashboard recall target and walk caps, N/A if the dashboard is deleted. Stack-overflow claim left off. | inbox, P1, high |
| `ft-dbd3a4eb2edaa186901a33b982c2c89f` | 3 | `x-1` heredoc steals the curl body even when the venv exists. `x-2` private temp vault unless `PARITY_VAULT` is set; kill only spawned PIDs; no `pkill -f`. Tauri note only. | inbox, P1, high |
| `ft-d9fd0266adf2cc5fad352a1cb846be9c` | 3 | `and-2` Discover must not persist host, token, or pin before a QR payload; no mirror while the token is blank. `and-3` snapshot drain. `and-4` false success on null stream and failed park. `and-5` voice-only save. Outbox path corrected to `.../app/net/LanOutboxWorker.kt`. | inbox, P1, high |
| `ft-f1ec93f73d4a0af2069b1bddf1d9c1db` | 4 | Criteria 1–3 marked `[MET — verified in source]`. Criterion 4 (user sign-off before any port) left open. New criterion: docs must not say the parity harness files implement it until they exist and fail closed. | review, P2, medium |

`ft-5a31496885dee296788d766720ce959d` (desktop CSP / DMG) was not overwritten. Desktop path resolution is its own card.

`VaultRepository.kt` is on both `ft-099ce` part 8 and the Android audit appendix. Do not build those parts in parallel. Do not put `MainViewModel.kt` on the Android card; the KDF card owns the unlock / adopt path. The CI card’s part 7 also deletes dead code in `MainViewModel.kt`; do not run that beside the KDF card.

Payloads were checked before send: parity 30 → 33 criteria, CI 27 → 29, Android 27 → 33, original description kept as the prefix. The post-write list confirms revision, status, priority, severity, and the audit label. The body was not fetched again after the overwrite.

## Verified-stale cards

- **Spike** `ft-f1ec93f73d4a0af2069b1bddf1d9c1db`. Owner table and port-plan header in `docs/ARCHITECTURE.md` match criteria 1–3. The harness files named at line 216 (`tests/parity_oracle.rs`, `scripts/parity-verify.py`) do not exist. Line 99 attributes temp+rename backup to Python; that is false for Python and true for `chronicle-pc/server/src/backup.rs`. Not moved to done. Port sign-off is still open. Do not start a port.
- **Tauri lockstep** on `ft-dbd3a`, criterion `[ft-f0608]`. Not met. A later lockfile read showed patch drift inside the same major.minor: Cargo.lock tauri 2.12.0 and plugin 2.5.0; bun.lock `@tauri-apps/api` 2.12.3, cli 2.12.1, plugin 2.5.0. The predicted minor-mismatch break has not landed. CI workflows are still Python-only. Those criteria stay open.
- **ShareActivity `FLAG_SECURE`** on `ft-d9fd0`, criterion `[ft-339e9]`. Not met. `MainActivity.kt:113` sets the flag. `ShareActivity` does not.
- **Rust backup** already uses a partial file plus rename. The open hole is the Python zip writer, filed as a new card. Archived done card `ft-57a9ccdd6abee4fee5cae3ff4c8fd671` (E2EE integrity, done 2026-10-06) was not reopened. The rotation and sealed-search cards cite it.

## Fable waves

A wave holds at most four cards and no shared files. Folds ride on their parent card. A parent that lists a file must not be built in the same wave as another card that lists that file.

**Wave 1.** Rotation `ft-20f1a7` (`e2ee.py`, `cli.py`, `tests/test_e2ee_rotate.py`). Sealed search `ft-0c8911` (`index_store.py`). Python backup `ft-d9e10d` (`backup.py`). Ollama allowlist `ft-6bbc30` (`provider.rs`). The Ollama card is not parallel with `ft-099ce` part 5, which also edits `provider.rs`.

**Wave 2.** KDF `ft-1b0607` (`e2ee.py` after wave 1, `api/auth.py`, `E2eeCrypto.kt`, `E2eeManager.kt`, `MainViewModel.kt`). Journal durability `ft-1f2844` (`journal.rs`, `journal.py`). Pairing file `ft-d02b2b2` (`pairstore.py`, `serve.py`). Phone sync `ft-36db2d` (`scripts/pc-to-phone-sync.sh`). The KDF card is not parallel with `ft-dbd3a` part 7.

**Wave 3.** Containment `ft-b1be43` (`journal.rs` after wave 2, `media.rs`, `kb.rs`, `entries.rs`, `api.rs`, `paths.rs`, `pipeline.rs`). Watcher `ft-ccca06` (`watch.py`). Search baseline `ft-6e2a12` (new test file only). Grok message shape `ft-61101c` (`grok.rs` only; not folded into the caption negative-cache criterion). Containment is not parallel with `ft-099ce` (`api.rs`).

**Wave 4.** Editor drafts `ft-fbc822` (the pane and modal TSX, plus `api.rs` after wave 3). Connect header `ft-bc99f5` (`client.ts` only). Desktop paths `ft-f0969d` (`embedded.rs` only). Live fingerprint `ft-f1a796` (`events.py`). The editor card is not parallel with `ft-dbd3a` part 5 (`JournalPane.tsx`, `App.tsx`). The connect-header and fingerprint cards are not parallel with `ft-099ce`.

**Wave 5.** FTS fallback `ft-95a10b` (`index_store.py`), after sealed-search and the baseline bench.

Missing future modules (`server/src/e2ee.rs`, `pairstore.rs`, `watch.rs`, `migrate.rs`) are the spike’s port plan, not bugs.

## Rejected or narrowed

- **`py-5` rglob / unreadable-directory `OSError` clause.** Not confirmed. Not filed.
- **`rust-6` final-path `File::create` write-through.** Rejected. `fs::rename` replaces a symlink at the final path. The filed bug is a planted parent directory, a predictable `path.{pid}.tmp` followed by create, and the self-move that writes and then unlinks the same path.
- **`rust-3` “one temp file per process”.** Narrowed. The temp name collides only for concurrent writes of the same path in one process.
- **`fe-2` abort caches a null token.** Rejected. Abort is rethrown. The sticky null is the non-abort failure path, and that is what the connect-header card covers. Severity lowered to medium, priority 2.
- **`x-6` deep walk exhausts the JS stack.** Rejected. The recursion is awaited. Unbounded time and memory stay on `ft-099ce`.
- **`py-9` every over-budget vault is blind.** Narrowed. Budget skips stats and still walks. An edit of a file that was never statted is invisible. Directory mtime still sees create and rename of direct children.
- **Dead or unwired Android components.** Not filed. The manifest registers `ShareActivity`, `ReminderReceiver`, `QuickCaptureWidgetReceiver`, and `PermissionsRationaleActivity`.
- **Empty `dead_clusters`, clones that still match, and “the spike is already done”.** Not treated as findings. Known board items were not re-filed.
- **A benchmark that runs a huge PBKDF2 iteration count.** Not filed.

## Decisions made without asking

- No DevMap rebuild and no `devmap gap-record` writes.
- Grok subagents filled the Sonnet slots. The parent verified and grouped. Filing waited until grouping was done.
- Archived E2EE card `ft-57a9ccdd6abee4fee5cae3ff4c8fd671` stays archived.
- `fe-7` is its own card, not a fold into the CSP card.
- `py-8`, `x-5`, and `x-6` fold into `ft-099ce`. `x-1` and `x-2` fold into `ft-dbd3a`. `and-2` through `and-5` fold into `ft-d9fd0`. `rust-7` stays off the caption criterion.
- KDF ceiling is 1_000_000 iterations. The default 600_000 still succeeds. Passphrase max is 1024 characters, rejected before PBKDF2. Logged on `ft-1b0607`.
- Phone sync refuses `/sdcard`, `/storage/emulated/0`, and an empty relative tree id. An unmarked directory extracts only with explicit `--force-unmarked-dir`. Logged on `ft-36db2d`.
- Search baseline is its own card and a new test file. The scan fix is a later card on `index_store.py`.
- Adding `audit-2026-10-13` to the spike was required by the fold rule. Later creates that shared one title word with an audit-labelled card were retitled, or retried with `reviewed_related` after the listed card was read. Distinct bugs were not folded together to satisfy the matcher.
- The frontend auditor did not leave a findings file. Those findings were taken from the subagent’s final message and checked against the source by the parent.
- `related_tasks_exist` collisions that were judged separate: backup vs spike, sealed-search vs rotation and spike, Ollama allowlist vs the Android card and then vs sealed-search, pairing-file vs the KDF card (the word “store”). Bench vs pairing-file on the word “record” was avoided by retitling before the successful create.

## Owner decisions needed before building

- **Rotation** (`ft-20f1a7`, backlog). Whether rotation may proceed with a dual-key window when some sealed files are unreadable. The card already requires two invariants: no new ciphertext before the new KDF is durable, and do not drop the old block if any sealed file was skipped. That choice is still open.
- **Discover** (`ft-d9fd0` audit `and-2`). Whether an mDNS-discovered host may receive outbox bodies before QR pairing. The card recommends no bodies until a pairing token exists. It does not authorize a discover-without-QR mode.
- **Spike sign-off** (`ft-f1ec93` criterion 4). User sign-off before any Rust port. Do not start the port from this audit.
- **Already on the parent cards, still open:** keep or delete `dashboard/dashboard.html` (`ft-099ce` part 6); whether the phone may create `config.json` (`ft-099ce` part 8); wire or delete `vertex.rs` (`ft-dbd3a` part 7); persistent Rust pairing versus documenting ephemeral tokens (`ft-099ce` part 3).

Stop here. No build until you say so.
