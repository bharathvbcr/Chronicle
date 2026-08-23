"""Phase 1 migrate-v2: copy kb/notes/** into PARA areas + seed vault chrome.

Prefer copy (leave kb/notes intact) so dual-read keeps working until cutover.
Default is dry-run; --apply requires --i-have-backup.
"""

from __future__ import annotations

import logging
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import config as config_mod
from .path_map import MACHINE_EXCLUDE_DIRS, PARA_AREAS
from .paths import atomic_write_text, resolve_chronicle_dir

log = logging.getLogger("chronicle.migrate_v2")

_FRONTMATTER_RE = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n?", re.DOTALL)

# Heuristic keyword → PARA destination (relative under area, or "" for area root).
_WORK_HINTS = re.compile(
    r"(resume|portfolio|project|work|career|job|interview|exp[-_])",
    re.I,
)
_PERSONAL_HINTS = re.compile(
    r"(personal|private|journal-ish|family|ideas-backlog)",
    re.I,
)
_KNOWLEDGE_HINTS = re.compile(
    r"(skill|knowledge|map|readme|doc|research|science|bio|ml|ai|concept)",
    re.I,
)

CUTOVER_DOC = """# PARA knowledge cutover

**Done (v1.10):** Legacy `kb/notes/` dual-read is retired. Knowledge candidates are
PARA-only (`00-Inbox/`, `10-Work/`, `20-Personal/`, `30-Knowledge/`, `90-Archive/`).

If leftover files remain under `kb/notes/`:

1. Run `chronicle backup` (zip **outside** the Syncthing share).
2. Run `chronicle cutover-kb --apply --i-have-backup` to move/quarantine into PARA
   and rewrite `brain/graph.json` doc paths.
3. Confirm `chronicle doctor` reports no leftover dual-read copies.

`kb/files/` and `kb/knowledge.json` stay as today. Journal file-once is separate
(`migrate-journal-v2` / `layout_version: 2`).
"""

CLAUDE_MD = """# Chronicle vault (agent guide)

This folder is the Syncthing-synced Chronicle vault. Phone + Mac pipeline + SPA share it.

## Layout

- **Journal (layout_version 2):** `_capture/entries/`, `_attachments/`, prose in `40-Journal/`
- **Knowledge (PARA only):** `00-Inbox/`, `10-Work/`, `20-Personal/`, `30-Knowledge/`, `90-Archive/`
- **Machine / derived:** `brain/`, `index/` (exclude from sync), `_system/derived/`, `config.json`

## Anti-fight rules

- Do **not** edit `_system/derived/`, hand-edit `brain/`, or whole-file regen `40-Journal/`.
- Do **not** fight `chronicle process` / `watch` / `brain` — call those for machine state.
- Capture → entry JSON / knowledge MD / serve API — never into `brain/` or derived paths.
- Retrieve → knowledge MD + `_system/index.md` skim; journal/entities prefer serve search/recall.
- Maintenance → triage Inbox; Upcoming from 📅 checkboxes; `doctor` / `rebuild` /
  `rebuild-markdown-index` for machine + agent index state.
- Phone capture = **Android app + Syncthing** (not Obsidian mobile).
- Never store passwords or account numbers in the vault — pointer to a password manager only.

See `_system/conventions.md` and `.claude/skills/`.
"""

HOME_MD = """# Home

Welcome to this Chronicle vault.

- [[30-Knowledge/MOC-Knowledge|Knowledge MOC]]
- [[10-Work/MOC-Work|Work MOC]]
- [[20-Personal/MOC-Personal|Personal MOC]]
- Inbox: `00-Inbox/`
- Archive: `90-Archive/`
- Journal prose: `40-Journal/` (after `layout_version: 2` / migrate-journal-v2)

Captures land under `_capture/entries/` (preferred at layout_version 2).
"""

MOC_WORK = """# Work MOC

Projects, resume points, career notes.

- Resume points: `10-Work/ResumePoints/`
- Projects and READMEs land here from migrate-v2 heuristics (or Inbox if unclear).
"""

MOC_PERSONAL = """# Personal MOC

Private / personal knowledge notes.
"""

MOC_KNOWLEDGE = """# Knowledge MOC

Skills, maps, reference READMEs, research notes.
"""

CONVENTIONS_MD = """# Conventions

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
"""

PREFERENCES_MD = """# Preferences

## Answer style

- Lead with the direct answer, then brief context.
- Prefer short bullets over long paragraphs for recalls.
- Cite sources (wikilinks / entry ids) at the end; never fabricate vault contents.
- When unsure, say so and point at the closest notes/entries.

## Obsidian exclude (suggested)

When opening Chronicle root in Obsidian, exclude or ignore:

""" + "\n".join(f"- `{d}/`" for d in MACHINE_EXCLUDE_DIRS) + """

Also typically ignore: `*.tmp`, `.DS_Store`, Syncthing internals.

## Deprecated: vault_mirror

Do not point `config.json` `vault_mirror` at a second Obsidian vault for day-to-day use.
Open this folder directly. The one-way mirror overwrote Obsidian edits and caused confusion.

## Capture

Phone capture is the **Android Chronicle app** + Syncthing — not Obsidian mobile.
"""

CHANGELOG_MD = """# Vault changelog

## PARA knowledge + file-once journal

- Seeded PARA areas; hard cutover via `chronicle cutover-kb` (dual-read retired).
- `chronicle migrate-v2` copies notes into PARA (default dry-run).
- Journal file-once (`layout_version: 2`): `_capture/`, `_attachments/`, `40-Journal/` via `migrate-journal-v2`.
"""

INDEX_STUB = """# Vault index

Agent shortlist (regenerated — do not hand-edit as SoT). Sqlite RAG lives under
`index/`. Rebuild: `chronicle rebuild-markdown-index` or `POST /vault/rebuild-index`.

Format: `title | type | tags | updated`

(Empty until first rebuild.)
"""

STAGING_README = """# _staging

Temporary landing zone for imports and WIP files. Not Syncthing-critical;
safe to clear after promoting notes into PARA areas.

Drop exports here (Apple Notes, docs, old vault files). Then ask Claude to
"import my staging folder" or run vault-maintenance — originals archive under
`90-Archive/_staging-originals/`. Inbox filing / staging stay skill/CLI-driven
(not SPA wizards).
"""

UPCOMING_SEED = """# Upcoming

Regenerated by `chronicle process` / `init-vault-structure` — edit tasks in their source notes, not here.

Nothing scheduled. Tasks with a 📅 date in any note appear here automatically.
"""


def _subfolder_readme(area: str, folder: str) -> str:
    return f"# {folder}\n\nSub-folder of `{area}/`. See `{area}/CLAUDE.md` for conventions.\n"

NESTED_CLAUDE_INBOX = """# 00-Inbox (agent guide)

Quick-capture landing zone. Items here are **unfiled by definition** — no format required.
Vault maintenance ("clean up my inbox") classifies, moves, links, and updates the target
area's MOC, then removes the item from here. Never leave content here as its permanent home.
"""

NESTED_CLAUDE_WORK = """# 10-Work (agent guide)

Sub-folders: `Projects/`, `People/`, `Meetings/`, `Reference/`, `ResumePoints/` (flat files
also valid).

- **Projects** get `status: active | waiting | done` in frontmatter.
- **Meetings** are named `YYYY-MM-DD Meeting - Topic.md` and must link the people and project
  involved (`[[10-Work/People/...]]`, `[[10-Work/Projects/...]]`).
- **People** notes keep a running `## Interactions` log, appended chronologically.
- Finished projects move to `90-Archive/10-Work/` — never delete.
"""

NESTED_CLAUDE_PERSONAL = """# 20-Personal (agent guide)

Sub-folders: `Health/`, `Family/`, `Finance/`, `Home/`, `Travel/`. May contain sensitive
family/health/finance info.

- Keep summaries **factual, no editorializing**.
- Health entries are dated and appended chronologically, not overwritten.
- Finance notes track decisions and reference documents — **never credentials or account
  numbers**; store a pointer like "see password manager".
"""

NESTED_CLAUDE_KNOWLEDGE = """# 30-Knowledge (agent guide)

Evergreen style: **one concept per note**, densely linked (`[[wikilinks]]`), prefer updating
an existing note over creating a near-duplicate. This is the app's "Knowledge Base" section.
"""

NESTED_CLAUDE_JOURNAL = """# 40-Journal (agent guide)

File-once prose SoT. Body inside `<!-- entry:<id> --> ... <!-- /entry:<id> -->` fences.

- **Never whole-file regen** this folder or hand-edit inside a fence directly — the pipeline
  amend gate compares an on-disk hash and will skip/conflict on drift.
- To edit a filed entry's prose, use the app's Journal editor (serve
  `PATCH /journal/entries/{id}`), which hash-checks, rewrites the fence, and marks the entry
  `prose_edited: true` so the pipeline never re-renders it from JSON.
- Structured fields (`mood`/`tags`/`type`/`ts`/`media`) live in entry JSON forever, not here.
"""

NESTED_CLAUDE_ARCHIVE = """# 90-Archive (agent guide)

Read-mostly. Archived notes still count for retrieval searches. New content never lands
here directly — it arrives via maintenance moving stale/completed notes, preserving subpath
(e.g. `10-Work/Projects/Foo.md` → `90-Archive/10-Work/Projects/Foo.md`).
"""

TEMPLATE_NOTE = """---
title: "{{title}}"
created: "{{date}}"
updated: "{{date}}"
type: note
tags: []
aliases: []
---

# {{title}}

"""

TEMPLATE_PROJECT = """---
title: "{{title}}"
created: "{{date}}"
updated: "{{date}}"
type: project
status: active
tags: []
---

# {{title}}

## Summary

## Status

## Links

"""

TEMPLATE_PERSON = """---
title: "{{title}}"
created: "{{date}}"
updated: "{{date}}"
type: person
tags: []
aliases: []
---

# {{title}}

## Interactions

- {{date}}:
"""

TEMPLATE_MEETING = """---
title: "{{title}}"
created: "{{date}}"
updated: "{{date}}"
type: meeting
tags: []
---

# {{title}}

## Attendees

## Notes

## Follow-ups

"""

TEMPLATE_DAILY = """---
title: "{{date}}"
created: "{{date}}"
updated: "{{date}}"
type: journal
tags: []
---

# {{date}}

"""

TEMPLATE_ATTACHMENT_NOTE = """---
title: "{{title}}"
created: "{{date}}"
updated: "{{date}}"
type: attachment-note
tags: []
attachments: []
---

# {{title}}

![[{{attachment}}]]

## Summary

"""

SKILL_CAPTURE = """---
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
"""

SKILL_RETRIEVAL = """---
name: retrieval-format
description: Use whenever the user asks about stored information — find, summarize, remind, temporal queries. Knowledge via MD + index skim; journal/entities prefer serve search/recall. Do NOT use for statements providing new information (that is capture-workflow).
user-invocable: false
---

# Retrieval format

1. Read `_system/preferences.md` for answer style.
2. **Knowledge:** skim `_system/index.md` (rebuild via `chronicle rebuild-markdown-index`) → shortlist PARA notes (`00-Inbox` … `90-Archive`). PARA-only after dual-read cutover.
3. **Journal / entities:** when Mac `chronicle serve` is up, prefer `POST /search`, `/recall`, `/ask`, `/resume` over scraping `brain/` by hand.
4. **Respect SoT split:**
   - **Prose** = body inside `40-Journal/` `entry:<id>` fences (after filed)
   - **Structured** = `mood`/`tags`/`type`/`ts`/`media` in entry JSON forever
5. Temporal / upcoming → check `Upcoming.md` and source notes with 📅 dated checkboxes, then verify sources.
6. Lead with the answer; end with Sources (wikilinks / entry ids). Never fabricate vault contents.
"""

SKILL_MAINTENANCE = """---
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
"""

SKILL_CAPTURE_ATTACHMENTS = """# Attachments (capture-workflow support)

Two separate attachment pipelines exist — don't conflate them.

## Journal capture media (app-owned)

Images/audio attached to a journal entry go through the app (phone capture or PC upload),
land under `_attachments/yyyy/MM/<id>_<n>.jpg|.m4a`, and are referenced from the entry JSON
`images`/`audio` fields. Do not hand-place files here — use the capture flow.

## Knowledge-note attachments (manual / agent-placed)

For a reference file attached to a `30-Knowledge/`, `10-Work/`, `20-Personal/`, or `00-Inbox/`
note (a PDF, doc, or image dropped in manually):

1. Copy the binary under `_attachments/notes/<area>/YYYY-MM-DD-description.ext`
   (e.g. `_attachments/notes/10-Work/2026-07-09-vendor-contract.pdf`).
2. Create or update the companion note in the right PARA area: embed the file
   (`![[...]]`), summarize its contents as searchable text (read the PDF / describe the
   image), and list the path under an `attachments:` frontmatter key.
3. `kb/files/` is legacy, Mac-pipeline-owned, read-only reference — do not write there.
"""

SKILL_CAPTURE_REORGANIZATION = """# Reorganization (capture-workflow support)

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
"""

SKILL_MAINTENANCE_LINK_REPAIR = """# Link repair (vault-maintenance support)

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
"""


def _parse_frontmatter(text: str) -> dict[str, str]:
    m = _FRONTMATTER_RE.match(text)
    if not m:
        return {}
    meta: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" not in line:
            continue
        key, _, val = line.partition(":")
        meta[key.strip().lower()] = val.strip().strip("\"'")
    return meta


def classify_kb_note(rel_under_kb_notes: str, text: str = "") -> str:
    """
    Return vault-relative PARA destination for a path under kb/notes/.

    Examples:
      ResumePoints/foo.md → 10-Work/ResumePoints/foo.md
      KnowledgeMap.md → 30-Knowledge/KnowledgeMap.md
    """
    rel = rel_under_kb_notes.replace("\\", "/").lstrip("/")
    name = Path(rel).name
    meta = _parse_frontmatter(text) if text else {}
    group = (meta.get("group") or "").lower()
    tags = (meta.get("tags") or "").lower()
    blob = f"{rel} {group} {tags} {name}"

    if rel.startswith("ResumePoints/") or "/ResumePoints/" in rel:
        return f"10-Work/{rel}" if rel.startswith("ResumePoints/") else f"10-Work/ResumePoints/{name}"

    if group == "personal" or _PERSONAL_HINTS.search(blob):
        return f"20-Personal/{rel}"

    if name.lower() in {"knowledgemap.md", "life_sciences_skills.md"} or (
        "skill" in name.lower() and "resume" not in name.lower()
    ):
        return f"30-Knowledge/{rel}"

    if _WORK_HINTS.search(blob) and not _PERSONAL_HINTS.search(blob):
        # Project READMEs and resume-ish → Work
        if name.endswith("_README.md") or "Resume" in name or "Portfolio" in name:
            return f"10-Work/{rel}"
        if _WORK_HINTS.search(name):
            return f"10-Work/{rel}"

    if _KNOWLEDGE_HINTS.search(blob):
        return f"30-Knowledge/{rel}"

    if name.endswith("_README.md"):
        return f"10-Work/{rel}"

    # Unclear → Inbox (keep flat name to avoid deep unknown trees)
    return f"00-Inbox/{rel}"


def seed_vault_chrome(
    root: Path,
    *,
    dry_run: bool = False,
    refresh_skills: bool = False,
) -> list[str]:
    """Create PARA dirs, MOCs, _system, templates, staging, skills. Idempotent.

    Default is create-only — never overwrites an existing file. Pass
    ``refresh_skills=True`` (CLI ``--refresh-skills``) to overwrite skill bodies
    and related guide seeds that still mention dual-read / stale index guidance.
    """
    written: list[str] = []

    try:
        layout_version = config_mod.load_config(root).layout_version
    except Exception:  # noqa: BLE001 — seeding must not fail on a bad/missing config
        layout_version = 2

    files: dict[str, str] = {
        "CLAUDE.md": CLAUDE_MD,
        "Home.md": HOME_MD,
        "10-Work/MOC-Work.md": MOC_WORK,
        "20-Personal/MOC-Personal.md": MOC_PERSONAL,
        "30-Knowledge/MOC-Knowledge.md": MOC_KNOWLEDGE,
        "00-Inbox/.gitkeep": "",
        "90-Archive/.gitkeep": "",
        "10-Work/ResumePoints/.gitkeep": "",
        "_system/conventions.md": CONVENTIONS_MD,
        "_system/preferences.md": PREFERENCES_MD,
        "_system/changelog.md": CHANGELOG_MD,
        "_system/index.md": INDEX_STUB,
        "_system/para-cutover.md": CUTOVER_DOC,
        "_templates/note.md": TEMPLATE_NOTE,
        "_templates/project.md": TEMPLATE_PROJECT,
        "_templates/person.md": TEMPLATE_PERSON,
        "_templates/meeting.md": TEMPLATE_MEETING,
        "_templates/daily.md": TEMPLATE_DAILY,
        "_templates/attachment-note.md": TEMPLATE_ATTACHMENT_NOTE,
        "_staging/README.md": STAGING_README,
        "Upcoming.md": UPCOMING_SEED,
        "00-Inbox/CLAUDE.md": NESTED_CLAUDE_INBOX,
        "10-Work/CLAUDE.md": NESTED_CLAUDE_WORK,
        "20-Personal/CLAUDE.md": NESTED_CLAUDE_PERSONAL,
        "30-Knowledge/CLAUDE.md": NESTED_CLAUDE_KNOWLEDGE,
        "90-Archive/CLAUDE.md": NESTED_CLAUDE_ARCHIVE,
        ".claude/skills/capture-workflow/SKILL.md": SKILL_CAPTURE,
        ".claude/skills/capture-workflow/attachments.md": SKILL_CAPTURE_ATTACHMENTS,
        ".claude/skills/capture-workflow/reorganization.md": SKILL_CAPTURE_REORGANIZATION,
        ".claude/skills/retrieval-format/SKILL.md": SKILL_RETRIEVAL,
        ".claude/skills/vault-maintenance/SKILL.md": SKILL_MAINTENANCE,
        ".claude/skills/vault-maintenance/link-repair.md": SKILL_MAINTENANCE_LINK_REPAIR,
    }

    # 40-Journal/CLAUDE.md only makes sense once the journal folder is relevant
    # (layout_version >= 2); seeding it on a still-v1 vault would create an
    # empty 40-Journal/ ahead of migrate-journal-v2.
    if layout_version >= 2:
        files["40-Journal/CLAUDE.md"] = NESTED_CLAUDE_JOURNAL

    # Paths refreshed when --refresh-skills (stale dual-read / index stub text).
    refresh_rels: frozenset[str] = frozenset(
        {
            "CLAUDE.md",
            "_system/conventions.md",
            "_system/para-cutover.md",
            "_system/changelog.md",
            "_staging/README.md",
            "00-Inbox/CLAUDE.md",
            "10-Work/CLAUDE.md",
            "20-Personal/CLAUDE.md",
            "30-Knowledge/CLAUDE.md",
            "40-Journal/CLAUDE.md",
            "90-Archive/CLAUDE.md",
            ".claude/skills/capture-workflow/SKILL.md",
            ".claude/skills/capture-workflow/attachments.md",
            ".claude/skills/capture-workflow/reorganization.md",
            ".claude/skills/retrieval-format/SKILL.md",
            ".claude/skills/vault-maintenance/SKILL.md",
            ".claude/skills/vault-maintenance/link-repair.md",
        }
    )

    for area in PARA_AREAS:
        files.setdefault(f"{area}/.gitkeep", "")

    for area, folder in (
        ("10-Work", "Projects"),
        ("10-Work", "People"),
        ("10-Work", "Meetings"),
        ("10-Work", "Reference"),
        ("20-Personal", "Health"),
        ("20-Personal", "Family"),
        ("20-Personal", "Finance"),
        ("20-Personal", "Home"),
        ("20-Personal", "Travel"),
    ):
        files.setdefault(f"{area}/{folder}/README.md", _subfolder_readme(area, folder))

    for rel, content in files.items():
        dest = root / rel
        overwrite = refresh_skills and rel in refresh_rels
        if dest.is_file() and not overwrite:
            continue
        written.append(rel)
        if dry_run:
            log.info("[dry-run] would seed %s%s", rel, " (refresh)" if overwrite else "")
            continue
        if content == "" and rel.endswith(".gitkeep"):
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text("", encoding="utf-8")
        else:
            atomic_write_text(dest, content if content.endswith("\n") else content + "\n")

    return written


def plan_kb_copies(root: Path) -> list[dict[str, str]]:
    """List {src, dest, action} for kb/notes → PARA copies."""
    legacy = root / "kb" / "notes"
    plans: list[dict[str, str]] = []
    if not legacy.is_dir():
        return plans
    for src in sorted(legacy.rglob("*.md")):
        if src.name.startswith(".") or ".sync-conflict" in src.name:
            continue
        rel = src.relative_to(legacy).as_posix()
        try:
            text = src.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        dest_rel = classify_kb_note(rel, text)
        dest = root / dest_rel
        if dest.is_file():
            # Idempotent: skip if same size+mtime-ish or identical bytes
            try:
                if dest.read_bytes() == src.read_bytes():
                    plans.append({"src": f"kb/notes/{rel}", "dest": dest_rel, "action": "skip_identical"})
                    continue
            except OSError:
                pass
            plans.append({"src": f"kb/notes/{rel}", "dest": dest_rel, "action": "skip_exists"})
            continue
        plans.append({"src": f"kb/notes/{rel}", "dest": dest_rel, "action": "copy"})
    return plans


def apply_copies(root: Path, plans: list[dict[str, str]], *, dry_run: bool) -> dict[str, int]:
    stats = {"copied": 0, "skipped_identical": 0, "skipped_exists": 0}
    for item in plans:
        action = item["action"]
        if action == "skip_identical":
            stats["skipped_identical"] += 1
            continue
        if action == "skip_exists":
            stats["skipped_exists"] += 1
            log.warning("dest exists (different content), left untouched: %s", item["dest"])
            continue
        src = root / item["src"]
        dest = root / item["dest"]
        if dry_run:
            log.info("[dry-run] would copy %s → %s", item["src"], item["dest"])
            stats["copied"] += 1
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        stats["copied"] += 1
    return stats


def run_migrate_v2(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = True,
    apply: bool = False,
    i_have_backup: bool = False,
    seed_only: bool = False,
) -> dict[str, Any]:
    """
    Seed PARA chrome and copy kb/notes into PARA areas.

    Default dry_run=True. Set apply=True (and i_have_backup=True) to write.
    If apply is False, dry_run is forced True.
    """
    root = resolve_chronicle_dir(chronicle_dir)

    if apply and not i_have_backup:
        raise ValueError(
            "Refusing --apply without --i-have-backup. "
            "Run `chronicle backup` first (zip outside Syncthing share), then "
            "re-run: chronicle migrate-v2 --apply --i-have-backup"
        )

    effective_dry = dry_run or not apply
    if apply and not dry_run:
        effective_dry = False

    seeded = seed_vault_chrome(root, dry_run=effective_dry)
    copy_stats: dict[str, int] = {
        "copied": 0,
        "skipped_identical": 0,
        "skipped_exists": 0,
    }
    plans: list[dict[str, str]] = []
    if not seed_only:
        plans = plan_kb_copies(root)
        copy_stats = apply_copies(root, plans, dry_run=effective_dry)

    when = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    result = {
        "chronicle_dir": str(root),
        "when": when,
        "dry_run": effective_dry,
        "seeded": seeded,
        "seeded_count": len(seeded),
        "plans": plans if effective_dry else [p for p in plans if p["action"] == "copy"][:50],
        "plan_count": len(plans),
        "copies": copy_stats,
        "cutover": (
            "Old kb/notes left intact after migrate-v2 copy. "
            "Run chronicle cutover-kb to retire leftover kb/notes. "
            "See _system/para-cutover.md."
        ),
        "hint": (
            "This was a dry-run. After `chronicle backup`, apply with: "
            "chronicle migrate-v2 --apply --i-have-backup"
            if effective_dry
            else "Applied. Verify SPA Knowledge tree, then optionally retire kb/notes later."
        ),
    }
    return result
