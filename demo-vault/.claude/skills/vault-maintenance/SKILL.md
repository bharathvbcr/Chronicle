---
name: vault-maintenance
description: Use for vault housekeeping — process inbox, Upcoming from 📅 checkboxes, duplicates/orphans, doctor/rebuild/rebuild-markdown-index, staging import. Destructive bulk ops need confirmation first.
---

# Vault maintenance

1. **Inbox filing** — classify `00-Inbox/` → move into `10`/`20`/`30`/`90`; update MOCs/links. Skill/CLI — not an SPA wizard.
2. **Upcoming** — derive from vault 📅 dated checkboxes (`chronicle process`); do not invent a second task database. Edit tasks in source notes, not `Upcoming.md`.
3. **Machine state** — `chronicle doctor` (report-only) or `doctor --fix` for JSON sync-conflicts; `chronicle rebuild` when derived state is wrong. Markdown `.sync-conflict-*` = merge by hand; never auto-delete.
4. **`_system/index.md`** — agent shortlist; rebuild with `chronicle rebuild-markdown-index` (or `index --write-markdown`, or `POST /vault/rebuild-index`). Sqlite under `index/` is RAG SoT — do not hand-maintain the markdown file.
5. **Backup before migrate** — `chronicle backup` (zip outside Syncthing) before `migrate-v2 --apply`, `cutover-kb --apply`, or `migrate-journal-v2 --apply`.
6. **Staging** — process `_staging/` into PARA via this skill; archive originals under `90-Archive/_staging-originals/`. Skill/CLI — not an SPA wizard.

Do not hand-edit `brain/` or `_system/derived/` to "fix" the graph — use curation ops + `chronicle brain` / `rebuild`.
