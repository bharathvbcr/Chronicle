"""Sharded entry read/write with permitted mutations only.

Phase 4: prefer ``_capture/entries/yyyy/MM/``; dual-read legacy ``entries/``.
"""

from __future__ import annotations

import logging
import re
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from .models import Entry
from .paths import atomic_write_json, read_json, resolve_chronicle_dir
from .vault_paths import (
    capture_entries_dir,
    entry_candidate_rels,
    legacy_entries_dir,
)

log = logging.getLogger("chronicle.entries")

ID_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_(\d{6})-(an|pc)(_[0-9]+)?$")


def entry_day(entry: Entry, *, fallback_tz: str = "UTC") -> date:
    """Day attribution from entry ts offset (travel-safe)."""
    try:
        dt = datetime.fromisoformat(entry.ts)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=ZoneInfo(fallback_tz))
        return dt.date()
    except (ValueError, TypeError, KeyError):
        m = ID_RE.match(entry.id)
        if m:
            return date.fromisoformat(m.group(1))
        return datetime.now(ZoneInfo(fallback_tz)).date()


def shard_from_id(entry_id: str) -> tuple[str, str]:
    m = ID_RE.match(entry_id)
    if not m:
        raise ValueError(f"invalid entry id: {entry_id}")
    yyyy, mm, _ = m.group(1).split("-")
    return yyyy, mm


def entry_path(root: Path, entry_id: str, *, prefer_existing: bool = True) -> Path:
    """
    Absolute path for an entry.

    When prefer_existing, return the first existing dual-read candidate.
    Writes use preferred ``_capture/entries/`` when no existing file.
    """
    m = ID_RE.match(entry_id)
    if not m:
        raise ValueError(f"invalid entry id: {entry_id}")
    yyyy_mm_dd = m.group(1)
    yyyy, mm, _ = yyyy_mm_dd.split("-")
    candidates = entry_candidate_rels(entry_id, yyyy, mm)
    if prefer_existing:
        for rel in candidates:
            p = root / rel
            if p.is_file():
                return p
    return root / candidates[0]


def _decrypt_entry_inplace(entry: Entry, root: Path | None) -> None:
    """Transparent decrypt-on-load for e2ee entries while the vault is unlocked.

    Locked entries keep ``text == ""`` — every consumer sees ciphertext-free
    emptiness rather than plaintext (fail closed).
    """
    from . import e2ee as e2ee_mod

    if not isinstance(getattr(entry, "text_enc", None), dict):
        return
    if root is None or entry.text.strip():
        # No vault context, or plaintext already populated (nothing to open).
        return
    try:
        entry.text = e2ee_mod.decrypt_text(entry.text_enc, root)
    except e2ee_mod.E2eeError as e:
        log.warning("e2ee decrypt failed for %s (%s); leaving locked", entry.id, e)


def load_entry(path: Path, root: Path | str | None = None) -> Entry | None:
    try:
        raw = read_json(path)
        entry = Entry.model_validate(raw)
    except Exception as e:  # noqa: BLE001
        log.warning("Failed to load entry %s: %s", path, e)
        return None
    resolved = resolve_chronicle_dir(root) if root is not None else None
    _decrypt_entry_inplace(entry, resolved)
    return entry


def save_entry(
    root: Path,
    entry: Entry,
    *,
    on_locked_plaintext: str = "keep",
) -> Path:
    """Persist an entry atomically, enforcing the E2EE plaintext invariant.

    ``on_locked_plaintext``: behavior for a fresh plaintext write (entry has no
    ``text_enc`` yet) while the vault is enabled but locked —
    ``"keep"`` stores as-is (pipeline-safe: the bytes were already plaintext
    on disk; refusing would kill whole runs over pre-enable entries) or
    ``"refuse"`` raises :class:`e2ee.E2eeError` (API boundary: fail loud so
    the UI can prompt for unlock instead of silently writing cleartext).
    """
    from . import e2ee as e2ee_mod

    # E2EE invariant: an enabled vault never persists plaintext.
    has_plain = bool((entry.text or "").strip())
    if has_plain and e2ee_mod.is_unlocked(root):
        # Covers both resealing edited blobs and first-time sealing of fresh
        # entries created while encryption is on (e.g. "Add from Mac").
        entry.text_enc = e2ee_mod.encrypt_text(root, entry.text)
        entry.text = ""
    elif has_plain and isinstance(getattr(entry, "text_enc", None), dict):
        log.warning(
            "Vault locked; dropping plaintext set on encrypted entry %s",
            entry.id,
        )
        entry.text = ""
    elif has_plain and on_locked_plaintext == "refuse":
        block = e2ee_mod.load_e2ee_config(root)
        if block is not None and block.get("enabled"):
            raise e2ee_mod.E2eeError(
                "vault is locked; unlock before saving plaintext entries"
            )
    path = entry_path(root, entry.id, prefer_existing=True)
    # New entries go under _capture/; existing stay in place (legacy or capture).
    if not path.is_file():
        path = entry_path(root, entry.id, prefer_existing=False)
    data = entry.model_dump(mode="json")
    if not data.get("audio"):
        data.pop("audio", None)
    # Omit false/empty optional filed fields for tidy pre-file JSON
    if not data.get("filed"):
        data.pop("filed", None)
        data.pop("filed_content_hash", None)
        data.pop("filed_path", None)
    atomic_write_json(path, data)
    return path


def iter_entry_paths(root: Path) -> list[Path]:
    """Dual-read capture + legacy; prefer capture when same id exists in both."""
    seen_ids: set[str] = set()
    out: list[Path] = []
    for base in (capture_entries_dir(root), legacy_entries_dir(root)):
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.json")):
            if ".sync-conflict" in path.name:
                continue
            eid = path.stem
            if eid in seen_ids:
                continue
            seen_ids.add(eid)
            out.append(path)
    return sorted(out, key=lambda p: p.as_posix())


def load_all_entries(root: Path | str, *, fallback_tz: str = "UTC") -> list[Entry]:
    root = resolve_chronicle_dir(root)
    out: list[Entry] = []
    for path in iter_entry_paths(root):
        e = load_entry(path, root)
        if e is not None:
            out.append(e)
    out.sort(key=lambda e: (e.ts, e.id))
    return out


def load_unprocessed(root: Path | str) -> list[Entry]:
    return [e for e in load_all_entries(root) if not e.processed]


def entries_for_day(entries: list[Entry], day: date, *, fallback_tz: str = "UTC") -> list[Entry]:
    return [e for e in entries if entry_day(e, fallback_tz=fallback_tz) == day]


def set_processed(root: Path, entry: Entry, *, processed: bool = True) -> Entry:
    entry.processed = processed
    save_entry(root, entry)
    return entry


def fill_text(root: Path, entry: Entry, text: str) -> Entry:
    """Permitted mutation: fill empty text from transcription."""
    entry.text = text
    save_entry(root, entry)
    return entry


def next_pc_id(root: Path, when: datetime | None = None) -> str:
    """Generate a collision-safe -pc id for dashboard quick-entry."""
    when = when or datetime.now().astimezone()
    base = when.strftime("%Y-%m-%d_%H%M%S") + "-pc"
    candidate = base
    n = 2
    while entry_path(root, candidate).exists():
        candidate = f"{base}_{n}"
        n += 1
    return candidate
