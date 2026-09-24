"""Validate vault-relative media paths (attachments + legacy img/audio)."""

from __future__ import annotations

from pathlib import Path

from .vault_paths import (
    is_attachment_media,
    is_legacy_media,
    resolve_media_abs,
    validate_media_rel_pattern,
)


class MediaPathError(ValueError):
    """Invalid or escaping media path."""


def normalize_media_rel(rel: str) -> str:
    p = (rel or "").strip().replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    p = p.lstrip("/")
    if not p or ".." in p.split("/") or "\0" in p:
        raise MediaPathError(f"invalid media path: {rel!r}")
    return p


def validate_media_rel(root: Path, rel: str, *, kind: str) -> Path:
    """Return resolved absolute path under vault for img/audio/_attachments."""
    try:
        cleaned = validate_media_rel_pattern(rel, kind=kind)
    except ValueError as e:
        raise MediaPathError(str(e)) from e
    try:
        target = resolve_media_abs(root, cleaned)
    except ValueError as e:
        raise MediaPathError(str(e)) from e
    base = Path(root).resolve()
    if not target.is_relative_to(base):
        raise MediaPathError(f"{kind} path escapes vault: {rel}")
    return target


def safe_media_path(root: Path, rel: str) -> Path:
    """Resolve media under vault (attachments or legacy img/audio)."""
    cleaned = normalize_media_rel(rel)
    legacy = is_legacy_media(cleaned)
    attached = is_attachment_media(cleaned)
    if (legacy and cleaned.startswith("audio/")) or (
        attached and cleaned.endswith(".m4a")
    ):
        return validate_media_rel(root, cleaned, kind="audio")
    if (legacy and cleaned.startswith("img/")) or attached:
        return validate_media_rel(root, cleaned, kind="img")
    raise MediaPathError(
        f"media path must be under _attachments/, img/, or audio/: {rel}"
    )
