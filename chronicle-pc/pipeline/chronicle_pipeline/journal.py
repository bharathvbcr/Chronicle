"""File-once journal: per-block fences in 40-Journal/YYYY-MM-DD.md.

Prose SoT = MD block body. Structured SoT (mood/tags/type/ts/media) stays in JSON.
Amend gate: rewrite block iff on-disk content hash == entry.filed_content_hash.
Missing filed_content_hash with an existing fence → skip (never amend).
Insert: missing fence → append. Never whole-file regen of journal.
"""

from __future__ import annotations

import logging
import re
from datetime import date
from pathlib import Path

from .entries import entry_day, entry_path, load_entry, save_entry
from .lock import vault_process_lock
from .models import Entry
from .paths import atomic_write_text, content_hash
from .vault_paths import JOURNAL_DIR, journal_day_path

log = logging.getLogger("chronicle.journal")


class JournalAmendNotFound(Exception):
    """Entry, filed day file, or fence for the given id could not be located."""


class JournalAmendConflict(Exception):
    """on-disk fence hash != filed_content_hash and/or client base_hash."""

    def __init__(self, on_disk_hash: str | None, filed_content_hash: str | None):
        self.on_disk_hash = on_disk_hash
        self.filed_content_hash = filed_content_hash
        super().__init__("journal fence hash mismatch")

# Canonical pipeline-authored filing target. Entry JSON is user-editable and
# mirrored from phones, so filed_path reaching `root / rel` file I/O must be
# validated first (defense against hand-edits / hostile mirror payloads).
_DAY_REL_RE = re.compile(rf"^{re.escape(JOURNAL_DIR)}/\d{{4}}-\d{{2}}-\d{{2}}\.md$")


def validate_filed_rel(rel: str | None, entry_id: str = "") -> str:
    """Return `rel` iff it is a canonical 40-Journal/YYYY-MM-DD.md path.

    Raises ValueError otherwise — callers translate to their native 4xx /
    not-filed semantics. Never return a path that escapes the journal dir.
    """
    if not rel or not _DAY_REL_RE.match(rel):
        raise ValueError(f"invalid filed_path {rel!r} for entry {entry_id}")
    return rel

FENCE_OPEN = re.compile(
    r"<!--\s*entry:(?P<id>\d{4}-\d{2}-\d{2}_\d{6}-(?:an|pc)(?:_\d+)?)\s*-->",
    re.MULTILINE,
)
# Pair with matching close for same id
_CLOSE_TMPL = "<!-- /entry:{id} -->"


def is_file_ready(entry: Entry) -> bool:
    """File-ready: (no audio) OR (text non-empty). Captions are best-effort."""
    if not entry.audio:
        return True
    return bool((entry.text or "").strip())


def get_filed(entry: Entry) -> bool:
    if entry.filed:
        return True
    extra = entry.model_extra or {}
    return bool(extra.get("filed"))


def get_filed_hash(entry: Entry) -> str | None:
    if entry.filed_content_hash:
        return entry.filed_content_hash
    extra = entry.model_extra or {}
    h = extra.get("filed_content_hash")
    return str(h) if h else None


def get_filed_path(entry: Entry) -> str | None:
    if entry.filed_path:
        return entry.filed_path
    extra = entry.model_extra or {}
    p = extra.get("filed_path")
    return str(p) if p else None


def get_prose_edited(entry: Entry) -> bool:
    if entry.prose_edited:
        return True
    extra = entry.model_extra or {}
    return bool(extra.get("prose_edited"))


def render_entry_block_body(
    entry: Entry,
    *,
    image_captions: dict[str, str] | None = None,
) -> str:
    """Inner prose for the fence (hashed for amend gate)."""
    captions = image_captions or {}
    parts: list[str] = []
    header = f"### {entry.id} · {entry.type}"
    if entry.mood is not None:
        header += f" · mood {entry.mood}"
    parts.append(header)
    if entry.tags:
        parts.append("tags: " + ", ".join(sorted(entry.tags)))
    text = (entry.text or "").strip()
    if text:
        parts.append(text)
    for img in entry.images:
        cap = captions.get(img, "")
        if cap:
            parts.append(f"![]({img})\n*{cap}*")
        else:
            parts.append(f"![]({img})")
    for aud in entry.audio or []:
        parts.append(f"[audio]({aud})")
    parts.append(f"[[entry:{entry.id}]]")
    return "\n\n".join(parts).rstrip() + "\n"


def wrap_entry_fence(entry_id: str, body: str) -> str:
    body = body.rstrip() + "\n"
    return f"<!-- entry:{entry_id} -->\n{body}<!-- /entry:{entry_id} -->\n"


def extract_block(file_text: str, entry_id: str) -> str | None:
    """Return inner body (between fences) or None if missing."""
    open_pat = re.compile(
        rf"<!--\s*entry:{re.escape(entry_id)}\s*-->\n?",
    )
    close_pat = re.compile(
        rf"<!--\s*/entry:{re.escape(entry_id)}\s*-->\n?",
    )
    m_open = open_pat.search(file_text)
    if not m_open:
        return None
    m_close = close_pat.search(file_text, m_open.end())
    if not m_close:
        return None
    return file_text[m_open.end() : m_close.start()]


def list_fenced_ids(file_text: str) -> list[str]:
    return [m.group("id") for m in FENCE_OPEN.finditer(file_text)]


def on_disk_block_hash(file_text: str, entry_id: str) -> str | None:
    body = extract_block(file_text, entry_id)
    if body is None:
        return None
    return content_hash(body)


def _ensure_day_scaffold(day: date, existing: str | None) -> str:
    if existing and existing.strip():
        return existing if existing.endswith("\n") else existing + "\n"
    return f"# {day.isoformat()}\n\n"


def upsert_entry_block(
    root: Path,
    entry: Entry,
    *,
    day: date | None = None,
    image_captions: dict[str, str] | None = None,
    dry_run: bool = False,
    force: bool = False,
) -> dict:
    """
    Insert or amend one entry fence in 40-Journal/YYYY-MM-DD.md.

    Amend only when on-disk hash == filed_content_hash (untouched), unless force.
    If a fence exists and filed_content_hash is missing → skip (never amend),
    even when force=True — avoids wiping Obsidian/human prose.

    Returns dict with keys: action, path, hash, skipped_reason?
    """
    day = day or entry_day(entry)
    rel = f"{JOURNAL_DIR}/{day.isoformat()}.md"
    path = journal_day_path(root, day.isoformat())
    body = render_entry_block_body(entry, image_captions=image_captions)
    new_hash = content_hash(body)
    fence = wrap_entry_fence(entry.id, body)

    existing = path.read_text(encoding="utf-8") if path.is_file() else None
    text = _ensure_day_scaffold(day, existing)

    disk_hash = on_disk_block_hash(text, entry.id) if existing else None
    filed_hash = get_filed_hash(entry)

    if disk_hash is None:
        # Insert / append
        if not text.endswith("\n"):
            text += "\n"
        text = text.rstrip() + "\n\n" + fence
        action = "insert"
    else:
        # Amend gate — never overwrite an existing fence without a filed hash.
        # Missing filed_content_hash + fence present is a conflict (even with force).
        if not filed_hash:
            return {
                "action": "skip",
                "path": rel,
                "hash": disk_hash,
                "skipped_reason": "missing_filed_content_hash",
            }
        if not force and get_prose_edited(entry):
            return {
                "action": "skip",
                "path": rel,
                "hash": disk_hash,
                "skipped_reason": "prose_edited",
            }
        if not force and disk_hash != filed_hash:
            return {
                "action": "skip",
                "path": rel,
                "hash": disk_hash,
                "skipped_reason": "human_or_agent_edit",
            }
        if not force and disk_hash == new_hash and get_filed(entry):
            return {
                "action": "unchanged",
                "path": rel,
                "hash": new_hash,
            }
        # Replace existing fence
        open_pat = re.compile(
            rf"<!--\s*entry:{re.escape(entry.id)}\s*-->.*?<!--\s*/entry:{re.escape(entry.id)}\s*-->\n?",
            re.DOTALL,
        )
        text, n = open_pat.subn(fence.rstrip() + "\n", text, count=1)
        if n != 1:
            text = text.rstrip() + "\n\n" + fence
            action = "insert"
        else:
            action = "amend"

    if dry_run:
        return {"action": f"would_{action}", "path": rel, "hash": new_hash}

    atomic_write_text(path, text if text.endswith("\n") else text + "\n")
    return {"action": action, "path": rel, "hash": new_hash}


def amend_filed_block(root: Path, entry_id: str, *, new_body: str, base_hash: str) -> dict:
    """
    User-driven amend of one fence body via serve PATCH /journal/entries/{id}.

    Unlike upsert_entry_block (pipeline-driven, re-renders from JSON), this
    writes the caller-supplied body verbatim and marks the entry prose_edited
    so the pipeline never re-renders it again. Requires on-disk hash ==
    filed_content_hash == base_hash (JournalAmendConflict otherwise).
    """
    with vault_process_lock(root):
        path = entry_path(root, entry_id)
        entry = load_entry(path) if path.is_file() else None
        if entry is None:
            raise JournalAmendNotFound(f"entry not found: {entry_id}")

        filed_rel = get_filed_path(entry)
        if not filed_rel or not get_filed(entry):
            raise JournalAmendNotFound(f"entry not filed: {entry_id}")
        try:
            filed_rel = validate_filed_rel(filed_rel, entry_id)
        except ValueError as e:
            raise JournalAmendNotFound(str(e)) from e

        day_path = root / filed_rel
        if not day_path.is_file():
            raise JournalAmendNotFound(f"journal day file missing: {filed_rel}")

        text = day_path.read_text(encoding="utf-8")
        disk_hash = on_disk_block_hash(text, entry_id)
        if disk_hash is None:
            raise JournalAmendNotFound(f"fence missing for entry: {entry_id}")

        filed_hash = get_filed_hash(entry)
        # Match the pipeline gate (upsert_entry_block): a fence with no
        # filed_content_hash is a conflict — resolve via accept-disk first.
        if not filed_hash or disk_hash != base_hash or disk_hash != filed_hash:
            raise JournalAmendConflict(on_disk_hash=disk_hash, filed_content_hash=filed_hash)

        body = new_body.rstrip() + "\n"
        new_hash = content_hash(body)
        fence = wrap_entry_fence(entry_id, body)

        open_pat = re.compile(
            rf"<!--\s*entry:{re.escape(entry_id)}\s*-->.*?<!--\s*/entry:{re.escape(entry_id)}\s*-->\n?",
            re.DOTALL,
        )
        new_text, n = open_pat.subn(fence.rstrip() + "\n", text, count=1)
        if n != 1:
            raise JournalAmendNotFound(f"fence missing for entry: {entry_id}")

        atomic_write_text(day_path, new_text if new_text.endswith("\n") else new_text + "\n")

        entry.filed_content_hash = new_hash
        entry.prose_edited = True
        if entry.model_extra:
            for k in ("filed_content_hash", "prose_edited"):
                entry.model_extra.pop(k, None)
        save_entry(root, entry)

        return {"id": entry_id, "path": filed_rel, "hash": new_hash, "prose_edited": True}


def accept_disk_as_base(root: Path, entry_id: str) -> dict:
    """
    Resync filed_content_hash to the on-disk fence body after an external
    (e.g. Obsidian) edit. Marks prose_edited so pipeline won't overwrite.
    Does not modify the day markdown.
    """
    with vault_process_lock(root):
        path = entry_path(root, entry_id)
        entry = load_entry(path) if path.is_file() else None
        if entry is None:
            raise JournalAmendNotFound(f"entry not found: {entry_id}")

        filed_rel = get_filed_path(entry)
        if not filed_rel or not get_filed(entry):
            raise JournalAmendNotFound(f"entry not filed: {entry_id}")
        try:
            filed_rel = validate_filed_rel(filed_rel, entry_id)
        except ValueError as e:
            raise JournalAmendNotFound(str(e)) from e

        day_path = root / filed_rel
        if not day_path.is_file():
            raise JournalAmendNotFound(f"journal day file missing: {filed_rel}")

        text = day_path.read_text(encoding="utf-8")
        disk_hash = on_disk_block_hash(text, entry_id)
        if disk_hash is None:
            raise JournalAmendNotFound(f"fence missing for entry: {entry_id}")

        entry.filed_content_hash = disk_hash
        entry.prose_edited = True
        if entry.model_extra:
            for k in ("filed_content_hash", "prose_edited"):
                entry.model_extra.pop(k, None)
        save_entry(root, entry)

        return {
            "id": entry_id,
            "path": filed_rel,
            "hash": disk_hash,
            "prose_edited": True,
            "accepted_disk": True,
        }


def mark_filed(root: Path, entry: Entry, *, block_hash: str, filed_path: str) -> Entry:
    """Set filed fields on entry and persist (in-place; no archived/ move)."""
    entry.filed = True
    entry.filed_content_hash = block_hash
    entry.filed_path = filed_path
    # Drop duplicates from extras if present
    if entry.model_extra:
        for k in ("filed", "filed_content_hash", "filed_path"):
            entry.model_extra.pop(k, None)
    save_entry(root, entry)
    return entry


def file_entry(
    root: Path,
    entry: Entry,
    *,
    image_captions: dict[str, str] | None = None,
    dry_run: bool = False,
    force: bool = False,
) -> dict:
    """
    Atomicity: write MD block → set filed=true + filed_content_hash + filed_path.
    """
    if not is_file_ready(entry):
        return {"action": "skip", "skipped_reason": "not_file_ready", "id": entry.id}

    result = upsert_entry_block(
        root,
        entry,
        image_captions=image_captions,
        dry_run=dry_run,
        force=force,
    )
    if result["action"] in ("skip",) and result.get("skipped_reason") in (
        "human_or_agent_edit",
        "missing_filed_content_hash",
        "prose_edited",
    ):
        return {**result, "id": entry.id}

    if dry_run:
        return {**result, "id": entry.id, "filed": False}

    if result["action"] in ("insert", "amend", "unchanged"):
        if not get_filed(entry) or result["action"] != "unchanged":
            mark_filed(root, entry, block_hash=result["hash"], filed_path=result["path"])
        return {**result, "id": entry.id, "filed": True}

    if result["action"].startswith("would_"):
        return {**result, "id": entry.id}

    return {**result, "id": entry.id}


def file_entries_for_days(
    root: Path,
    entries: list[Entry],
    *,
    days: set[date] | None = None,
    image_captions: dict[str, str] | None = None,
    dry_run: bool = False,
    fallback_tz: str = "UTC",
    only_unfiled: bool = False,
) -> list[dict]:
    """File processed (file-ready) entries into 40-Journal."""
    results: list[dict] = []
    for entry in sorted(entries, key=lambda e: (e.ts, e.id)):
        d = entry_day(entry, fallback_tz=fallback_tz)
        if days is not None and d not in days:
            continue
        if not entry.processed:
            continue
        if only_unfiled and get_filed(entry):
            continue
        if not is_file_ready(entry):
            continue
        results.append(
            file_entry(
                root,
                entry,
                image_captions=image_captions,
                dry_run=dry_run,
            )
        )
    return results


def detect_journal_hash_mismatches(root: Path, entries: list[Entry]) -> list[dict]:
    """Entries where filed_content_hash != on-disk block hash (human edit or drift)."""
    issues: list[dict] = []
    by_path: dict[str, str] = {}
    for entry in entries:
        if not get_filed(entry):
            continue
        rel = get_filed_path(entry) or ""
        if rel:
            try:
                rel = validate_filed_rel(rel, entry.id)
            except ValueError:
                # Hostile/corrupt filed_path: report as a doctor issue instead
                # of reading whatever the field points at.
                issues.append(
                    {
                        "id": entry.id,
                        "issue": "invalid_filed_path",
                        "path": rel,
                        "prose_edited": get_prose_edited(entry),
                    }
                )
                continue
        if not rel:
            day = entry_day(entry)
            rel = f"{JOURNAL_DIR}/{day.isoformat()}.md"
        if rel not in by_path:
            path = root / rel
            by_path[rel] = path.read_text(encoding="utf-8") if path.is_file() else ""
        text = by_path[rel]
        disk = on_disk_block_hash(text, entry.id) if text else None
        expected = get_filed_hash(entry)
        if disk is None:
            issues.append(
                {
                    "id": entry.id,
                    "issue": "missing_fence",
                    "path": rel,
                    "prose_edited": get_prose_edited(entry),
                }
            )
        elif expected and disk != expected:
            issues.append(
                {
                    "id": entry.id,
                    "issue": "hash_mismatch",
                    "path": rel,
                    "filed_content_hash": expected,
                    "on_disk_hash": disk,
                    "prose_edited": get_prose_edited(entry),
                }
            )
    return issues
