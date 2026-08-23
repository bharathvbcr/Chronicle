# PARA knowledge cutover

**Done (v1.10):** Legacy `kb/notes/` dual-read is retired. Knowledge candidates are
PARA-only (`00-Inbox/`, `10-Work/`, `20-Personal/`, `30-Knowledge/`, `90-Archive/`).

If leftover files remain under `kb/notes/`:

1. Run `chronicle backup` (zip **outside** the Syncthing share).
2. Run `chronicle cutover-kb --apply --i-have-backup` to move/quarantine into PARA
   and rewrite `brain/graph.json` doc paths.
3. Confirm `chronicle doctor` reports no leftover dual-read copies.

`kb/files/` and `kb/knowledge.json` stay as today. Journal file-once is separate
(`migrate-journal-v2` / `layout_version: 2`).
