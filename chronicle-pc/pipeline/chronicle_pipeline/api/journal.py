"""Journal fence browse + amend (40-Journal/, file-once, hash-gated amend).

Read endpoints expose the per-entry fence body without exposing the whole
day file as a writable blob. The PATCH endpoint is the only contract-sanctioned
way any UI may write into 40-Journal/ — see journal.py::amend_filed_block for
the amend gate and the prose_edited rebuild-protection flag.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from .. import journal
from ..entries import entry_path, load_entry
from .deps import get_root

log = logging.getLogger("chronicle.api.journal")

router = APIRouter(tags=["journal"])

# Day files only — excludes 40-Journal/CLAUDE.md (nested agent guide) and any
# other non-day markdown that may live alongside the fenced day files.
_DAY_FILE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}\.md$")


class AmendBody(BaseModel):
    body: str = Field(min_length=1)
    base_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


def _journal_dir(root: Path) -> Path:
    return root / "40-Journal"


@router.get("/journal/days")
def list_journal_days(root: Path = Depends(get_root)) -> dict[str, Any]:
    jdir = _journal_dir(root)
    days: list[dict[str, Any]] = []
    if jdir.is_dir():
        for p in sorted(jdir.glob("*.md"), reverse=True):
            if not _DAY_FILE_RE.match(p.name):
                continue
            try:
                text = p.read_text(encoding="utf-8")
            except OSError:
                continue
            days.append(
                {
                    "date": p.stem,
                    "path": f"40-Journal/{p.name}",
                    "entry_ids": journal.list_fenced_ids(text),
                }
            )
    return {"days": days}


def _load_filed_entry(root: Path, entry_id: str):
    path = entry_path(root, entry_id)
    entry = load_entry(path) if path.is_file() else None
    if entry is None:
        raise HTTPException(404, f"entry not found: {entry_id}")
    filed_rel = journal.get_filed_path(entry)
    if not filed_rel or not journal.get_filed(entry):
        raise HTTPException(404, f"entry not filed: {entry_id}")
    try:
        filed_rel = journal.validate_filed_rel(filed_rel, entry_id)
    except ValueError as e:
        # Corrupt/hostile filed_path in the entry JSON: the fence is unusable.
        raise HTTPException(404, str(e)) from e
    return entry, filed_rel


@router.get("/journal/entries/{entry_id}")
def get_journal_entry(entry_id: str, root: Path = Depends(get_root)) -> dict[str, Any]:
    entry, filed_rel = _load_filed_entry(root, entry_id)
    day_path = root / filed_rel
    if not day_path.is_file():
        raise HTTPException(404, f"journal day file missing: {filed_rel}")
    text = day_path.read_text(encoding="utf-8")
    body = journal.extract_block(text, entry_id)
    if body is None:
        raise HTTPException(404, f"fence missing for entry: {entry_id}")
    body_hash = journal.on_disk_block_hash(text, entry_id)
    filed_hash = journal.get_filed_hash(entry)
    return {
        "id": entry_id,
        "date": Path(filed_rel).stem,
        "path": filed_rel,
        "body": body,
        "body_hash": body_hash,
        "filed_content_hash": filed_hash,
        "editable": bool(filed_hash) and body_hash == filed_hash,
    }


@router.patch("/journal/entries/{entry_id}")
def amend_journal_entry(
    entry_id: str, body: AmendBody, root: Path = Depends(get_root)
) -> dict[str, Any]:
    try:
        return journal.amend_filed_block(
            root, entry_id, new_body=body.body, base_hash=body.base_hash
        )
    except journal.JournalAmendNotFound as e:
        raise HTTPException(404, str(e)) from e
    except journal.JournalAmendConflict as e:
        raise HTTPException(
            409,
            {
                "detail": "journal fence hash mismatch",
                "on_disk_hash": e.on_disk_hash,
                "filed_content_hash": e.filed_content_hash,
            },
        ) from e


@router.post("/journal/entries/{entry_id}/accept-disk")
def accept_disk_journal_entry(
    entry_id: str, root: Path = Depends(get_root)
) -> dict[str, Any]:
    """Accept on-disk Obsidian/external fence body as the new amend base."""
    try:
        return journal.accept_disk_as_base(root, entry_id)
    except journal.JournalAmendNotFound as e:
        raise HTTPException(404, str(e)) from e
