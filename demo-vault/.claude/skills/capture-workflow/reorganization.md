# Reorganization (capture-workflow support)

## Split

A note that grew past ~300 lines or covers multiple topics: extract each topic into its own
note in the same area, link the original as a hub (`## See also`), and update the area MOC.

## Merge

Two notes covering the same topic: fold the newer/thinner one into the more complete note,
preserving both notes' unique content, then archive the merged-away note (never delete) with
a one-line pointer to where its content went.

## Move

Moving or renaming a note is only complete once every reference is fixed:

1. Move/rename the file.
2. **Grep the entire vault** for wikilinks and markdown links to the old name/path
   (`[[OldTitle]]`, `[[old/path]]`, `[[old/path.md]]`, `[text](old/path.md)`) and rewrite
   each hit to the new title/path (preserve basename-vs-path and `.md` style).
3. Update the area MOC and any `_system/index.md` entry.
4. Log one line in `_system/changelog.md`:
   `- YYYY-MM-DD: moved <from> → <to> (N links in M files)`.

Apps run this same procedure automatically on `POST /kb/move` and `POST /kb/archive`
(pipeline `link_repair`). Agents that move/rename files outside the API must apply these
steps themselves before considering the move complete.

## Archive

Never delete. Move to `90-Archive/`, preserving the original subpath
(e.g. `10-Work/Projects/Foo.md` → `90-Archive/10-Work/Projects/Foo.md`), then apply the same
link-refactor + changelog rule as a move (apps: `POST /kb/archive`).
