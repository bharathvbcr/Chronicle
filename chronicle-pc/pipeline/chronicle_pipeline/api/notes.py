"""Derived notes + journal browse (40-Journal, _system/derived, legacy notes/)."""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from .deps import get_root

log = logging.getLogger("chronicle.api.notes")

router = APIRouter(tags=["notes"])

_SAFE = re.compile(r"^[A-Za-z0-9._\- /]+$")

_ALLOWED_PREFIXES = (
    "notes/",
    "40-Journal/",
    "_system/derived/",
)


def _normalize_vault_md(path: str) -> str:
    p = path.strip().lstrip("/").replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    if not p or any(x in p for x in ("..", "\0")):
        raise HTTPException(400, "invalid note path")
    if not _SAFE.match(p):
        raise HTTPException(400, "note path has invalid characters")
    if not p.endswith(".md"):
        raise HTTPException(400, "note path must end with .md")
    if p == "Upcoming.md":
        return p
    # Allow bare relative under notes/ for back-compat
    if not any(p.startswith(pref) for pref in _ALLOWED_PREFIXES):
        p = f"notes/{p}"
    if not any(p.startswith(pref) for pref in _ALLOWED_PREFIXES):
        raise HTTPException(400, "path must be under notes/, 40-Journal/, or _system/derived/")
    return p


def _abs(root: Path, rel: str) -> Path:
    base = root.resolve()
    target = (root / rel).resolve()
    if not target.is_relative_to(base):
        raise HTTPException(400, "path escapes vault")
    return target


def _list_md_under(root: Path, prefix: str) -> list[dict[str, str]]:
    base = root / prefix.rstrip("/")
    files: list[dict[str, str]] = []
    if not base.is_dir():
        return files
    for p in sorted(base.rglob("*.md")):
        if p.name.startswith(".") or ".sync-conflict" in p.name:
            continue
        rel = p.relative_to(root).as_posix()
        files.append({"path": rel, "name": p.name})
    return files


@router.get("/notes")
def list_notes(root: Path = Depends(get_root)) -> dict[str, Any]:
    files: list[dict[str, str]] = []
    if (root / "Upcoming.md").is_file():
        files.append({"path": "Upcoming.md", "name": "Upcoming.md"})
    files.extend(_list_md_under(root, "40-Journal"))
    files.extend(_list_md_under(root, "_system/derived"))
    files.extend(_list_md_under(root, "notes"))
    return {"files": files}


@router.get("/notes/{path:path}")
def get_note(path: str, root: Path = Depends(get_root)) -> dict[str, Any]:
    rel = _normalize_vault_md(path)
    abs_path = _abs(root, rel)
    if not abs_path.is_file():
        raise HTTPException(404, f"note not found: {rel}")
    content = abs_path.read_text(encoding="utf-8", errors="replace")
    return {"path": rel, "content": content}
