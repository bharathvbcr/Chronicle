# Chronicle GitPulse build report

Date: 2026-10-13. Repository: Chronicle (`repo-53371-18dbd04abe4f1050-1`). Branch: `main`. No card was requested done. None were merged or pushed. `dev verify` did not pass for any card.

Gate mode is off (`dev gate status`: verification_mode off, config `/Users/bharath/Code/apps/Chronicle/.devcouncil/config.yaml`). `dev verify` on main reports the GitPulse id is not a DevCouncil task. Inside an isolated worktree it reports DevCouncil state unavailable. Done is evidence-gated by Manvi, and that gate has not judged these worktree heads. The cards below stay in review.

The parent shell cannot run git, pytest, or `dev --project-root` inside `~/.grok/worktrees`. Evidence for those commits is the implementing agent's report, a separate adversarial agent's report, and a parent read of the absolute paths. A blocked parent shell is not a passing test.

GitPulse Insights and collision, re-checked before this wave: both facets ok. One registered git worktree (this checkout, `main`). Dirty file count 2 (untracked `AUDIT_REPORT.md` and this report, 288 additions, 0 deletions). Overlapping files 0. Scanned worktrees 1. Unscanned worktrees 0. Live Claude and Codex sessions 0. Ledger recording. That scan does not see the Grok isolation clones under `~/.grok/worktrees/apps-chronicle/`. Those clones are unknown to GitPulse, not clean. This wave's three cards do not share planned files.

DevMap at that check: generation 144, available, 4826 symbols, 14646 edges, 526 files, pending 0. `source_freshness` true. `analyzer_freshness` false (`stored extraction payload is obsolete`). This session did not run `devmap build`. Walks remain lower bounds where `walk_incomplete` was set. A degraded, truncated, or fail-closed answer was treated as unknown.

## Moved to review

No task moved to done.

| Task | Revision | Commit | What landed |
| --- | --- | --- | --- |
| ft-6bbc302e38efbab8639809bf23b4ff93 | 3 | `479277c` | Rust Ollama and Grok URL parser. `provider.rs` only. Full lib: 77 passed. |
| ft-d9e10db628e28fedd9b3f132c6159888 | 3 | `8bf2d45` | Python backup zip no longer truncates in place or copies symlink targets. Adversarial: 18 passed. |
| ft-0c891193dd8d721fd4092d9bbd68d0f2 | 3 | `46f1a57` | Python sealed search. Unreadable `config.json` does not become a plain vault. Re-attack: 22 passed. |
| ft-1f2844139dc9c0db9ad97b1fad61aa90 | 3 | `beff70e` | Journal durability. Pytest journal adversarial 17 passed. `cargo test --lib journal::` 7 passed. |
| ft-1b06074e67d64b8a12a3bf15a1b80a8f | 3 | `16afd58` | KDF ceiling. Pytest 23 passed. Android unit tests on JDK 21: adopt 6, crypto 9, off-main 2. `assembleDebug` failed earlier for a missing debug keystore. |
| ft-36db2dac67e350ae743d64924938e7c5 | 4 | `9f71faf` | Phone sync refuses shared-storage roots and empty tree ids. Pytest 27 passed. `bash -n` passed. No device. |
| ft-61101c95e1a2d638c52ba6f7885c733d | 3 | `6bf44ff` | Grok image content is a two-element parts array. `cargo test --lib grok`: 1 passed. |
| ft-6e2a124296405a6d3670a74a11057222 | 3 | `adc88730` | Keyword-scoring baseline. Test file only. No production change. |
| ft-d02b2b2283592107342825e35528ecea | 8 | `593a9d46` | Pairing store double-read. Pytest 31 passed. |
| ft-f0969d15da889d93b3b36331d719688e | 3 | `c6ee815` | Desktop support and vault paths by OS string. 12 passed. Desktop crate build exited 0. |
| ft-f1a796017fab7d123a2e6a791a5a61d3 | 3 | `b2b0dd27` | Vault fingerprint tokens journal files with size and a hash prefix. Targeted pytest 27 passed. |
| ft-bc99f5f8e91f0b492ef91953275ec5fc | 3 | `c7ce7a77` | LAN token cache does not stick a failure or an abort. Frontend suite 97 passed. `npm run build` exited 0. |
| ft-fc77316d206270c43d76bb6d4684821f | 3 | `01a2b220` | Rust search omits sealed text and does not write `config.json`. `cargo test --lib index_store`: 14 passed. Hostile probe 27 passed, then deleted. |
| ft-ccca0632bbfe73a2bbb8f8686149144f | 7 | `b29895c` | Watcher follow-up is a snapshot diff. Strict suite 18 passed. Hostile probe 8 passed, then deleted. |
| ft-6400812d8bbdd516bca21f1d848da46b | 4 | `584a4cd` | Python allowlist. See the next section. |
| ft-c7be27e269c9aab27c742b893866e7df | 3 | `62c7c47` | Grok MIME from the final extension. Branch `fix/ft-c7be27-grok-mime`, parent `6bf44ff`. `cargo test --lib grok`: 2 passed. Full lib: 75 passed. Adversarial APPROVE. |
| ft-4e88abed16234489a42f52624931e650 | 4 | `5f2bf55` | Rejected Ollama and Grok base URLs are not printed. Validators unchanged. Full lib: 75 passed. Adversarial APPROVE. |
| ft-b1be434ccd48e25f4413793e96722382 | 3 | `db5567e` | Vault symlink containment, distinct temp names, delete re-check, self-move. Full lib: 83 passed. Adversarial APPROVE. Two residuals filed as ft-30cb2d. |

`ft-f1ec93f73d4a0af2069b1bddf1d9c1db` was already in review (revision 4) before this build. It is the runtime-owner spike. Criterion 4 is user sign-off. No port was started.

## Python allowlist (this continuation)

Task: ft-6400812d8bbdd516bca21f1d848da46b (revision 4, sequence 4139). Branch `fix/ft-6400812-python-url-allowlist`. Worktree `.../subagent-01a137e4-3fc6-7bb1-a6e3-083021271f34`. Head reported by the re-attack: `584a4cdf64ffcf96b543fc210f09a14a5760a9b8`. The parent could not `git -C` that worktree (MANVI refused). The parent read `url_allowlist.py` and `test_url_allowlist.py` at that tree before the re-attack.

First commit `757ee62` rejects userinfo and a contiguous `%40` before DNS, rejects a Grok port other than omitted or 443, and stops `apply_settings`, `grok_provider.py`, and `factory.py` from embedding the raw base URL. Implementer: pre-fix 5 failed, 18 passed; post-fix 23 passed in 0.24s.

That commit was sent back. Parent check, same venv: `validate_ollama_base_url("http://127.0.0.1\x00.evil.example:11434")` returned the URL. `getaddrinfo` truncated the host at NUL and returned `127.0.0.1`. urllib3 then refused to dial (`InvalidURL`). A 300-character DNS label raised `UnicodeError: label empty or too long` out of `is_private_or_loopback_url`, so `apply_settings` raised. `%4A` was not a send-back: it encodes `J` (0x4A), not `@` (0x40).

Second commit `584a4cd` rejects C0 and DEL before DNS and catches `UnicodeError` from `getaddrinfo`. Implementer: the new tests on `757ee62` were 3 failed, 1 passed; after the fix, 27 passed in 0.18s. `ollama.py` was not changed in that commit. Diff against main is the five planned files (298 insertions, 16 deletions across both commits).

Separate re-attack: APPROVE. Suite 27 passed in 0.33s. Probe: 162 checks, 0 failures, then deleted. Working tree left `AUDIT_REPORT.md` untracked. Parent did not re-run pytest. Not requested done.

## In progress (this wave)

Nothing from this wave is still in progress. All three cards are in review. None were requested done.

Fourth slot was left empty. Journal race shares `journal.rs` with containment. Editor shares `api.rs` and the card says land containment first. FTS shares unmerged `index_store.py`. Desktop CSP shares `api.rs` and has signing decisions. The new residual card also edits `api.rs` and waits for the same reason.

## Deferred or blocked

| Task | Status | Why it was not started |
| --- | --- | --- |
| ft-20f1a740ce28cbc14cbdb45da7e04a08 | backlog, revision 2 | Owner decision: rotation when the sealed file is unreadable, including dual-key behavior. |
| ft-fbc822561155ef4a15dbd56add1cb2eb | ready, revision 1 | Editor drafts. The card says land containment first. Not split. Not started beside containment (`api.rs`). |
| ft-95a10ba1060312ac7df469dc2539d68d | backlog, revision 1 | FTS fallback edits `index_store.py`, which the unmerged sealed-search branch owns. The keyword baseline now exists, so the measurement wait is over. The file wait is not. |
| ft-9d1e3dea6f192159be57e9a60fd1b038 | backlog, revision 1 | Journal `file_entry` race. Containment is now in review and did not add this lock. Still do not start while that unmerged branch owns `journal.rs`. |
| ft-30cb2d1f8261f3795b8d7a7c3d877044 | backlog, revision 1 | Residual of ft-b1be43. `delete_entry` still deletes through a `_capture` symlink. `journal_entry` still reads an outside day file. Do not start beside containment or the editor card (`api.rs`). |
| ft-8c30b844fb4fca5b18f46a7371912486 | backlog, revision 1 | Owner decision: link-local and unspecified addresses. Not a send-back of the Python allowlist. |
| ft-099ce10efd9c49405b385c7da7d683cf | inbox, revision 3 | Whole parity card left inbox. Its slices of `client.ts` and `events.py` were built as their own audit cards. Do not start the rest beside those unmerged branches. |
| ft-dbd3a4eb2edaa186901a33b982c2c89f | inbox, revision 3 | Not an audit wave card. `MainViewModel.kt` was touched by the KDF branch. |
| ft-d9fd0266adf2cc5fad352a1cb846be9c | inbox, revision 3 | Discover mDNS before QR is an owner decision. `VaultRepository.kt` is also on the parity card. |
| ft-5a31496885dee296788d766720ce959d | inbox, revision 2 | Desktop CSP and DMG. Not in the audit wave order. |
| ft-f1ec93f73d4a0af2069b1bddf1d9c1db | review, revision 4 | Spike. No Rust port until the user signs criterion 4. |

## Benchmarks

Keyword baseline, ft-6e2a12, checked in: `keyword_bench rows_read=1004 wall_s=0.002426 row_cap=none docs=1004`. Adversarial re-run printed `wall_s=0.001747` with the same row count. The test does not assert the wall time. No production code changed on that card, so there is no 5% regression on a code path.

No other card is that hot path. No benchmark was run on them. There is no 5% regression claim, and no 10^9-iteration KDF benchmark.

## New tasks filed

| Id | Board key | Status | Concern |
| --- | --- | --- | --- |
| ft-6400812d8bbdd516bca21f1d848da46b | (audit id) | review, revision 4 | Python userinfo and Grok port. Done as far as review. |
| ft-fc77316d206270c43d76bb6d4684821f | (audit id) | review, revision 3 | Rust sealed search. Done as far as review. |
| ft-4e88abed16234489a42f52624931e650 | (audit id) | review, revision 4 | Rust stderr and `refused '{}'` no longer echo the raw URL. |
| ft-9d1e3dea6f192159be57e9a60fd1b038 | (audit id) | backlog, revision 1 | Concurrent `file_entry` can drop a fence. |
| ft-c7be27e269c9aab27c742b893866e7df | gp-grok-image-mime | review, revision 3 | gif and webp labeled from the final extension. Stacked on `6bf44ff`. |
| ft-8c30b844fb4fca5b18f46a7371912486 | gp-ollama-link-local | backlog, revision 1 | Whether link-local and unspecified addresses stay allowed. |
| ft-30cb2d1f8261f3795b8d7a7c3d877044 | gp-vault-read-unlink | backlog, revision 1 | Entry removal and day-file fetch still follow a directory symlink. |

## Fable decisions

- KDF ceiling is 1_000_000 iterations inclusive. Default 600_000 and minimum 100_000 still succeed. Passphrase max is 1024 characters. Android counts UTF-16 code units, so 1024 emoji is rejected there and allowed in Python. That stricter check does not start PBKDF2.
- Phone sync refuses `/sdcard`, `/storage/emulated/0`, and `/` even with `--force-unmarked-dir`. An empty relative tree id fails before any probe. `/storage/emulated/0/..` normalizes to `/storage/emulated`, and force may extract there. `..` is popped with the default IFS.
- `JAVA_HOME` wins when it has a `java` binary. JDK 17 is rejected even if Homebrew OpenJDK 21 exists. A JDK 21 stub that exits 141 after a full read is rejected.
- Keyword criterion "fails if unbounded" must not leave a permanent red test. The measured number is printed and checked in. A later run may print a different `wall_s`.
- Pairing accepts a parse only when a second read returns the same text and the stat still matches, including the two reads immediately before `os.replace`. mtime and size are not content identity. No third read was added. A non-dict device value is corrupt. Empty `{"devices": {}}` may still mint.
- Desktop paths are a pure function of a platform string plus an env map. Tests do not call `std::env::set_var`. Empty `CHRONICLE_DIR` falling through is accepted. Linux vault stays `~/Chronicle`, not `XDG_DATA_HOME`.
- Watcher: production `Handler.on_any_event` calls `observe_event({path})`. `note({paths})` is only the echo declaration the self-write tests use. At finish, when capture ran, `self._owed = bool(late)` where `late` is the snapshot diff. A path that existed in the pre-run snapshot does not schedule. Echoes do not schedule. A file created after the freeze and still present at finish schedules one follow-up.
- Fingerprint: files under `40-Journal` are hashed even after the capture and brain budget. Over-budget capture files stay on directory mtime. A journal symlink may hash an outside target; the token path stays inside the vault.
- LAN token: a failure or abort is not cached. A missing or empty token from a successful `GET /connect` is cached as null. A whitespace-only token is cached and sent. That residual was accepted and not filed, because filing it would edit `client.ts` on the unmerged review branch.
- Rust sealed search matches Python posture and does not delete rows. No `sealed` column means no document text. `sealed = 2` still returns, because the filter is `sealed != 1`. `run_index` still calls `ensure_config`. That is indexing, not the search card.
- Python `%4A` is the letter J. It is not a case variant of `%40`. Double-encoded `%2540` stays allowed when the host remains `127.0.0.1` and urllib3 raises `LocationParseError`.
- Containment starts from main. `journal.rs` edits are path containment only. Fence and repair stay on unmerged `beff70e`. `journal.py` is not edited. Distinct temp names belong to this card, not the journal-race card.
- Reject-log starts from main and changes only the static strings. It does not change either validator. The warning does not print the previous runtime base. The probe URL must embed `secret-token-zz` and must be one the current main validator rejects.
- Grok MIME does not start from main. It is a new branch fetched from the parts clone at `6bf44ff`. An unknown final extension is an error, not `image/jpeg`. If that fetch fails, the card stops.

## Accepted residuals

These were recorded and not sent back.

- Backup hard links to outside files are stored. Concurrent `force=True` is last writer wins. `backup.rs` still follows symlinks. `ARCHITECTURE.md` still says a stale line count.
- Journal IO can be reported as a missing fence. Python and Rust CRLF hashes can diverge.
- Pairing has a window after the two agreeing reads and before `os.replace`.
- The poller cannot tell a user rewrite of an existing file from the pipeline's own rewrite. `observe_event` during the ignore window can owe a path that is not in the baseline without checking that the file exists.
- Fingerprint over-budget capture rewrites stay invisible. Journal reads have no size cap.
- Rust search has a small gap between the posture check and the query.
- Containment on `db5567e`: a dangling parent symlink is not rejected by `abs_under_root`. The later create fails with `EEXIST` and does not create the missing target. `delete_entry` and `journal_entry` still follow a directory symlink; that is ft-30cb2d, not a send-back of the six criteria.
- Reject-log: the Grok test sets `CHRONICLE_SECRETS` for the duration of one test. A torn env cannot make the exact error-string assertion pass.
- Python allowlist: `%2540` and `%4A` as above. `0.0.0.0` and `::` are approved because `ipaddress` marks them private. Link-local, including `169.254.169.254`, stays approved and is now its own backlog card. A trailing tab or newline is removed by `apply_settings`'s `str.strip` before the check, so the stored base does not contain that character.

## Owner decisions still pending

- ft-20f1a7: how passphrase rotation behaves when the sealed file is unreadable, including dual-key.
- ft-d9fd0: Discover mDNS bodies before QR.
- ft-f1ec93 criterion 4: sign-off before any Rust port of a Python-owned module.
- ft-099ce part 6: keep or delete `dashboard/dashboard.html`.
- ft-099ce part 8: whether the phone may create `config.json`.
- ft-099ce part 3: persistent Rust pairing, or document that tokens are ephemeral.
- ft-dbd3a part 7: wire or delete `vertex.rs`.
- ft-8c30b8: block only `169.254.169.254`, block all link-local, or leave link-local allowed. Whether `0.0.0.0` and `::` stay allowed.

## Verification still open

- Manvi has not passed any of these heads, so none were requested done.
- `dev verify` was not a pass.
- Full `chronicle-pc` pytest was not run for these cards.
- Windows was not run.
- Android `assembleDebug` did not succeed (missing debug keystore). Unit tests did.
- DevMap generation 144 is not analyzer-fresh (`source_freshness` true, `analyzer_freshness` false). Impact and affected-test answers are lower bounds where `walk_incomplete` was set. Gaps recorded include phone-sync, grok-image, keyword-bench, embedded-paths, client-auth, fingerprint, rust-sealed-search, python-url-allowlist, `containment-tests-fail-closed` (affected_tests of the seven containment files returned fail_closed, not "no tests"), `grok-mime-impact-lower-bound`, and `ollama-log-impact-truncated`.
