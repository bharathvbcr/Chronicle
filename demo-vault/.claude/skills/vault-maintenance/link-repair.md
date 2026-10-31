# Link repair (vault-maintenance support)

## Detecting broken links

Grep all `.md` files (including `90-Archive/`) for `[[wikilink]]` and `[text](path.md)`
patterns; for each target, verify a file exists at that vault-relative path or matching
title. Report unresolved links grouped by source note.

## Repairing after a rename/move

Same procedure as capture-workflow's `reorganization.md` move rule (and the same steps the
apps run via `POST /kb/move` / `POST /kb/archive`): grep for every reference to the old
name/path across the whole vault — `[[OldTitle]]`, `[[old/path]]`, markdown links — rewrite
to the new title/path, then append one changelog line. Do this **before** considering a
rename/move complete when working outside the API.

## Orphan notes

A note with no incoming and no outgoing links is an orphan. Not automatically wrong (a fresh
stub is expected to be an orphan briefly), but flag notes older than the note's own `created`
+ 30 days that are still orphaned — likely candidates for linking into a MOC or archiving.

## Never

- Never auto-delete a note to "fix" a broken link — fix the link or flag it for the user.
- Never auto-merge markdown conflicts (`*.sync-conflict-*`) — report only.
