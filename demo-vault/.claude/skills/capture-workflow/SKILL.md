---
name: capture-workflow
description: Use whenever the user provides information to store — journal captures, knowledge notes, corrections, pasted content. Write entry JSON / knowledge MD / serve API. Do NOT use for questions about existing information (that is retrieval-format).
user-invocable: false
---

# Capture workflow

## Where to write

| Kind | Destination |
|------|-------------|
| Journal | Entry JSON under `_capture/entries/yyyy/MM/` — or SPA / **Android app** / `POST /entries` |
| Quick knowledge | `00-Inbox/<slug>.md` |
| Work / resume | `10-Work/` (`ResumePoints/` under it) |
| Personal | `20-Personal/` |
| Evergreen | `30-Knowledge/` |

PARA only — do not write under legacy `kb/notes/`. Phone capture = **Android + Syncthing**, not Obsidian mobile. Never store passwords in the vault.

## Checklist

1. Classify (journal vs knowledge vs preference → `_system/preferences.md`).
2. Search for an existing home before creating (PARA + `_system/index.md` skim).
3. Journal: write/update entry JSON (or serve); let `chronicle process` file into `40-Journal/` fences.
4. Knowledge: create/update MD with frontmatter (`created`/`updated`/`type`/`title`); link MOCs; Inbox if unclear.
5. Tasks/deadlines → checkbox with 📅 YYYY-MM-DD in the note (Upcoming is derived from these — do not invent a parallel task system).
6. Confirm in 1–2 lines: what / where.

## Never

- Edit `_system/derived/` (regenerable aggregates)
- Hand-edit `brain/` or `index/` as SoT
- Whole-file regenerate `40-Journal/` (pipeline inserts/amends fences only)
- Hand-edit inside `<!-- entry:<id> -->` fences — use SPA / `PATCH /journal/entries/{id}`
- Fight `chronicle process` — it marks `processed` / `filed`
