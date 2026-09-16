# Develop and verify Chronicle

Read the root `AGENTS.md` and `.devcouncil/repo_map.json` before broad searches. Source wins when the map is stale. Android has additional subtree instructions. Preserve unrelated changes in shared checkouts.

## Build and test commands

Run these from the repository root after installing the appropriate existing project dependencies:

| Check | Command |
| --- | --- |
| Contract and schema copy equality | `bash scripts/check-contract-sync.sh` |
| Python behavior | `(cd chronicle-pc && ./.venv/bin/python -m pytest -q)` |
| Python lint | `(cd chronicle-pc && ./.venv/bin/ruff check pipeline tests)` |
| Rust server tests | `(cd chronicle-pc/server && cargo test)` |
| Rust CLI build | `(cd chronicle-pc/server && cargo build --release)` |
| Frontend tests | `(cd chronicle-pc/frontend && npm run test)` |
| Frontend lint | `(cd chronicle-pc/frontend && npm run lint)` |
| Frontend types + build | `(cd chronicle-pc/frontend && npm run build)` |
| Desktop bundle | `(cd chronicle-pc/desktop && npm run tauri:build)` |
| Android lint | `(cd chronicle-android && ./gradlew :app:lintDebug)` |
| Android unit tests | `(cd chronicle-android && ./gradlew :app:testDebugUnitTest)` |
| Android compilation | `(cd chronicle-android && ./gradlew :app:compileDebugKotlin)` |

The current root CI runs the Python suite/lint and contract sync. It does not establish a complete Android, Tauri, and Rust release matrix. Read `.github/workflows/` before claiming CI coverage.

Use a disposable vault for tests that process or migrate files. Never point an exploratory test at the user's live synced vault.

## Contract changes

Update `CONTRACT.md` and corresponding `contract/*.schema.json` in **both** apps together. Keep byte equality and add regression coverage at affected readers/writers. Contract v1.11 and vault layout v2 measure different things.

The two CLIs have different command sets. An available Rust REST endpoint does not imply a matching native CLI subcommand. Consult the [command matrix](../chronicle-pc/README.md#command-availability).

## Parity harness limitation

`scripts/parity.sh` is a legacy comparison helper, not current proof of complete parity. Its normalizer combines a Python heredoc with reading JSON from stdin, and its Python-unavailable branch reports `RUST-ONLY` while incrementing its pass counter. Do not present a green run as an assertion-backed Rust/Python equivalence result. Repair and validate the harness before relying on it for release decisions; that runtime-tooling change is outside this documentation overhaul.

## Website and public content

The public page for `chronicle.vbcr.dev` is maintained in the **Portfolio** repository (`bharathvbcr/Portfolio-VBCR`), not `chronicle-pc/frontend/`. The same page is routed at `bharath.vbcr.dev/chronicle`.

Owners in Portfolio:

- `src/pages/Chronicle.jsx` and `src/pages/Chronicle.css`: page content, illustrative interaction, scoped styling.
- `src/pages/__tests__/Chronicle.test.jsx`: content and navigation regression checks.
- `src/data/projectData.js`: Chronicle project card and detail content.
- `functions/knowledge.json`: assistant grounding description.
- `src/lib/routes.js`: existing subdomain/path routing.

Keep the public page and project/assistant descriptions aligned with the source. Walkthroughs must be labeled illustrative; fabricated timings, successful diagnostics, and claims of perfect offline behavior are not product evidence. Link to this docs index rather than duplicating a full installation manual on the landing page.

Run Portfolio's documented `npm run ci:local` for its full local pipeline. Check its worktree before touching shared content. Website verification includes rendered desktop/mobile layouts and real interactions. Local changes and a successful build do not deploy the site.

## Evidence and release boundaries

Use these terms precisely:

- **Verified:** a specific check ran and its output supports the claim. Source inspection verifies the described code path, not successful execution in a deployment.
- **Inferred:** the design suggests behavior that has not yet been demonstrated.
- **Unverified:** missing runtime, device, service, or artifact evidence.

Before a release, separately qualify: fresh installation and asset discovery; signing/notarization; physical Android capture and SAF permissions; two-way Syncthing behavior; pinned TLS/pairing and revocation on a real LAN; provider/transcription calls; encryption lock/rotation behavior; backup restore; and cross-runtime compatibility. Report failures and skipped checks explicitly.
