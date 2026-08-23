# Conventions

## PARA areas

| Area | Use |
|------|-----|
| `00-Inbox/` | Unsorted captures; triage regularly |
| `10-Work/` | Jobs, projects, ResumePoints (`Projects/`, `People/`, `Meetings/`, `Reference/`, `ResumePoints/`) |
| `20-Personal/` | Personal / private (`Health/`, `Family/`, `Finance/`, `Home/`, `Travel/`) |
| `30-Knowledge/` | Evergreen skills & reference |
| `90-Archive/` | Cold storage |

PARA is the only knowledge candidate set (legacy `kb/notes/` dual-read retired — see
`_system/para-cutover.md` / `chronicle cutover-kb`). Sub-folders are optional structure
inside an area — files may also live flat at the area root.

## Notes sections (app UI)

Both apps present three sub-areas over the same folders:

| Section | Folders | Editable |
|---------|---------|----------|
| **Notes** | `00-Inbox/`, `10-Work/`, `20-Personal/`, `90-Archive/` | Yes |
| **Knowledge Base** | `30-Knowledge/` | Yes |
| **Journal** | `40-Journal/` + `_system/derived/` | Fence-body amend only, via serve `PATCH /journal/entries/{id}` |

Create defaults: Notes → `00-Inbox/`, Knowledge Base → `30-Knowledge/`.

## Frontmatter schema

```yaml
---
title: "Note Title"
created: YYYY-MM-DD
updated: YYYY-MM-DD
type: note | project | person | meeting | journal | reference | attachment-note
tags: []
aliases: []       # person/entity notes: name variants, nicknames, emails, handles
status: active | waiting | done   # projects only
attachments: []   # only if any
---
```

Bump `updated` on every edit. Dates are real calendar dates — resolve relative phrasing
("next Friday") to absolute `YYYY-MM-DD` at capture time.

## Task format

Anything with a due/expiry/follow-up date is a checkbox task, not just a fact:

```
- [ ] Renew passport 📅 2027-03-01
```

`Upcoming.md` at the vault root is regenerated from these (`chronicle process` /
`init-vault-structure`) — it is derived; edit the task in its source note, not in `Upcoming.md`.

## Journal (file-once)

| Path | Role |
|------|------|
| `_capture/entries/` | Capture JSON (preferred at `layout_version: 2`) |
| `_attachments/` | Media |
| `40-Journal/` | Prose SoT (`entry:<id>` fences) — never whole-file regen |
| Entry JSON | Structured SoT forever (`mood`/`tags`/`type`/`ts`/`media`) |
| `_system/derived/` | Regenerable aggregates — never hand-edit as SoT |

Amends to a fence body go through serve `PATCH /journal/entries/{id}` (hash-gated,
sets `prose_edited: true`) — never hand-edit inside `entry:<id>` fences directly, and never
whole-file regen `40-Journal/`.

Upgrade with `chronicle backup` then `chronicle migrate-journal-v2 --apply --i-have-backup`.
Vault structure additions (sub-folders, nested `CLAUDE.md`, templates, `Upcoming.md`) are
seeded idempotently by `chronicle init-vault-structure` — additive, no layout_version bump.
Use `--refresh-skills` to overwrite skill/CLAUDE seed bodies after dual-read cutover.

## Obsidian

Open the **Chronicle vault root**. Exclude machine dirs (see `_system/preferences.md`).
`vault_mirror` is deprecated. Phone capture = Android app + Syncthing, not Obsidian mobile.
