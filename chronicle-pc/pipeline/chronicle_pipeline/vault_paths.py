"""Capture + attachment path map with dual-read of legacy journal paths.

Phase 4 preferred layout:
  _capture/entries/yyyy/MM/<id>.json
  _attachments/yyyy/MM/<file>
  40-Journal/YYYY-MM-DD.md
  _system/derived/{weekly,monthly,yearly,topics,daily}/

Legacy (dual-read until migrate completes):
  entries/, img/, audio/, notes/
"""

from __future__ import annotations

import re
from pathlib import Path

CAPTURE_ENTRIES = "_capture/entries"
LEGACY_ENTRIES = "entries"
ATTACHMENTS = "_attachments"
LEGACY_IMG = "img"
LEGACY_AUDIO = "audio"
JOURNAL_DIR = "40-Journal"
DERIVED_DIR = "_system/derived"

_SAFE_REL = re.compile(r"^[A-Za-z0-9._\- /]+$")

# Preferred + legacy media relative path patterns
_ATTACH_RE = re.compile(r"^_attachments/\d{4}/\d{2}/[^/]+$")
_LEGACY_IMG_RE = re.compile(r"^img/\d{4}/\d{2}/[^/]+$")
_LEGACY_AUDIO_RE = re.compile(r"^audio/\d{4}/\d{2}/[^/]+\.m4a$")
_ATTACH_AUDIO_RE = re.compile(r"^_attachments/\d{4}/\d{2}/[^/]+\.m4a$")


def _norm(path: str) -> str:
    p = (path or "").strip().lstrip("/").replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    return p


def is_legacy_media(rel: str) -> bool:
    p = _norm(rel)
    return p.startswith("img/") or p.startswith("audio/")


def is_attachment_media(rel: str) -> bool:
    return _norm(rel).startswith(f"{ATTACHMENTS}/")


def preferred_entry_rel(entry_id: str, yyyy: str, mm: str) -> str:
    return f"{CAPTURE_ENTRIES}/{yyyy}/{mm}/{entry_id}.json"


def legacy_entry_rel(entry_id: str, yyyy: str, mm: str) -> str:
    return f"{LEGACY_ENTRIES}/{yyyy}/{mm}/{entry_id}.json"


def entry_candidate_rels(entry_id: str, yyyy: str, mm: str) -> list[str]:
    """Prefer new capture path, then legacy sharded."""
    return [
        preferred_entry_rel(entry_id, yyyy, mm),
        legacy_entry_rel(entry_id, yyyy, mm),
    ]


def preferred_attachment_rel(yyyy: str, mm: str, file_name: str) -> str:
    return f"{ATTACHMENTS}/{yyyy}/{mm}/{file_name}"


def legacy_media_rel(kind: str, yyyy: str, mm: str, file_name: str) -> str:
    """kind is 'img' or 'audio'."""
    return f"{kind}/{yyyy}/{mm}/{file_name}"


def media_rewrite_legacy_to_attachments(rel: str) -> str:
    """Map img/… or audio/… → _attachments/… (same yyyy/MM/basename)."""
    p = _norm(rel)
    if p.startswith("img/"):
        return f"{ATTACHMENTS}/{p[len('img/') :]}"
    if p.startswith("audio/"):
        return f"{ATTACHMENTS}/{p[len('audio/') :]}"
    return p


def validate_media_rel_pattern(rel: str, *, kind: str) -> str:
    """
    Validate image or audio vault-relative path (new or legacy).

    kind: 'img' | 'audio' — for attachments, both live under _attachments/.
    """
    cleaned = _norm(rel)
    if ".." in cleaned.split("/") or "\0" in cleaned:
        raise ValueError(f"invalid media path: {rel!r}")
    if kind == "img":
        if _ATTACH_RE.match(cleaned) or _LEGACY_IMG_RE.match(cleaned):
            return cleaned
        raise ValueError(f"invalid image path: {rel}")
    if kind == "audio":
        if _ATTACH_AUDIO_RE.match(cleaned) or _LEGACY_AUDIO_RE.match(cleaned):
            return cleaned
        raise ValueError(f"invalid audio path: {rel}")
    raise ValueError(f"unknown media kind: {kind}")


def resolve_media_abs(root: Path, rel: str) -> Path:
    """Resolve media path under vault; dual-read attachments vs img/audio."""
    cleaned = _norm(rel)
    if ".." in cleaned.split("/") or "\0" in cleaned:
        raise ValueError(f"invalid media path: {rel!r}")

    candidates: list[str] = [cleaned]
    if cleaned.startswith("img/"):
        candidates.append(media_rewrite_legacy_to_attachments(cleaned))
    elif cleaned.startswith("audio/"):
        candidates.append(media_rewrite_legacy_to_attachments(cleaned))
    elif cleaned.startswith(f"{ATTACHMENTS}/"):
        # Also try legacy img/audio with same suffix
        suffix = cleaned[len(ATTACHMENTS) + 1 :]
        candidates.append(f"img/{suffix}")
        if suffix.endswith(".m4a"):
            candidates.append(f"audio/{suffix}")

    base = root.resolve()
    for cand in candidates:
        target = (root / cand).resolve()
        if not target.is_relative_to(base):
            continue
        if target.is_file():
            return target
    # Return preferred absolute even if missing (caller checks is_file)
    preferred = candidates[0]
    target = (root / preferred).resolve()
    if not target.is_relative_to(base):
        raise ValueError(f"media path escapes vault: {rel}")
    return target


def iter_entry_roots(root: Path) -> list[Path]:
    """Ordered roots to scan for entry JSON (prefer capture, then legacy)."""
    out: list[Path] = []
    for name in (CAPTURE_ENTRIES, LEGACY_ENTRIES):
        p = root / name if "/" not in name else root.joinpath(*name.split("/"))
        # CAPTURE_ENTRIES has a slash
        p = root
        for part in name.split("/"):
            p = p / part
        if p.is_dir() and p not in out:
            out.append(p)
    return out


def capture_entries_dir(root: Path) -> Path:
    return root / "_capture" / "entries"


def legacy_entries_dir(root: Path) -> Path:
    return root / "entries"


def attachments_dir(root: Path) -> Path:
    return root / ATTACHMENTS


def journal_day_path(root: Path, day_iso: str) -> Path:
    return root / JOURNAL_DIR / f"{day_iso}.md"


def derived_path(root: Path, *parts: str) -> Path:
    return root.joinpath(DERIVED_DIR, *parts)


def machine_exclude_dirs() -> tuple[str, ...]:
    return (
        "index",
        "brain",
        "_capture",
        "_attachments",
        "_staging",
        "entries",
        "img",
        "audio",
        ".stfolder",
    )
