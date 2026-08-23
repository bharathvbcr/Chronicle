"""Entry CRUD + media upload (img/, audio/)."""

from __future__ import annotations

import logging
from datetime import date, datetime
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from pydantic import BaseModel, Field

from .. import e2ee as e2ee_mod
from ..config import load_config
from ..entries import (
    ID_RE,
    entry_day,
    entry_path,
    load_all_entries,
    load_entry,
    next_pc_id,
    save_entry,
    shard_from_id,
)
from ..lock import vault_process_lock
from ..media_paths import MediaPathError, validate_media_rel
from ..models import Entry
from ..paths import atomic_write_bytes, read_json
from .deps import get_root

log = logging.getLogger("chronicle.api.entries")

router = APIRouter(tags=["entries"])

EntryType = Literal["log", "idea", "dream", "reflection"]
MAX_UPLOAD_BYTES = 50 * 1024 * 1024  # 50 MB

# Entry field bounds (API boundary only — the shared Entry model stays
# unconstrained so legacy oversized vault files keep loading).
MAX_ENTRY_TEXT_CHARS = 1_000_000  # 1 MB of text is far past any journal note
MAX_TAGS = 100
MAX_TAG_CHARS = 120
MAX_MEDIA_LIST = 50


def _validate_entry_payload(
    *,
    text: str | None = None,
    tags: list[str] | None = None,
    images: list[str] | None = None,
    audio: list[str] | None = None,
) -> None:
    if text is not None and len(text) > MAX_ENTRY_TEXT_CHARS:
        raise HTTPException(413, f"text exceeds {MAX_ENTRY_TEXT_CHARS} characters")
    if tags is not None:
        if len(tags) > MAX_TAGS:
            raise HTTPException(413, f"more than {MAX_TAGS} tags")
        if any(len(t) > MAX_TAG_CHARS for t in tags):
            raise HTTPException(413, f"tag exceeds {MAX_TAG_CHARS} characters")
    for kind, items in (("images", images), ("audio", audio)):
        if items is not None and len(items) > MAX_MEDIA_LIST:
            raise HTTPException(413, f"more than {MAX_MEDIA_LIST} {kind} entries")


class EntryCreate(BaseModel):
    type: EntryType = "log"
    text: str = ""
    tags: list[str] = Field(default_factory=list)
    mood: int | None = None
    ts: str | None = None
    id: str | None = None
    images: list[str] = Field(default_factory=list)
    audio: list[str] = Field(default_factory=list)


class EntryPatch(BaseModel):
    type: EntryType | None = None
    text: str | None = None
    tags: list[str] | None = None
    mood: int | None = None
    images: list[str] | None = None
    audio: list[str] | None = None


def _entry_dict(entry: Entry) -> dict[str, Any]:
    data = entry.model_dump(mode="json")
    if not data.get("audio"):
        data.pop("audio", None)
    return data


def _save_entry_or_locked(root: Path, entry: Entry) -> None:
    """Persist an entry, mapping a locked-vault plaintext refusal to 423."""
    try:
        save_entry(root, entry, on_locked_plaintext="refuse")
    except e2ee_mod.E2eeError as e:
        raise HTTPException(423, str(e)) from e


def _require_mutable(entry: Entry) -> None:
    if entry.processed:
        raise HTTPException(
            409,
            f"entry {entry.id} is processed=true and cannot be edited or deleted",
        )


def _validate_pc_id(entry_id: str) -> None:
    m = ID_RE.match(entry_id)
    if not m:
        raise HTTPException(400, f"invalid entry id: {entry_id}")
    if m.group(3) != "pc":
        raise HTTPException(400, "API writes must use -pc entry ids")


class MirrorEntryBody(BaseModel):
    """Full phone entry for LAN outbox mirror (idempotent by id + content)."""

    entry: dict[str, Any]


@router.post("/entries/mirror")
def post_entries_mirror(
    body: MirrorEntryBody, root: Path = Depends(get_root)
) -> dict[str, Any]:
    """Idempotent LAN push of a phone capture (outbox).

    The phone already wrote the entry into its SAF vault; this mirrors the
    identical file onto the PC side so Syncthing lag doesn't delay processing.
    Semantics: same id **and** identical content → ``{ok, deduped: true}``;
    missing → written under ``_capture/entries``; differing content for the
    same id → **409** (Syncthing remains source of truth; never clobber).
    """
    raw = dict(body.entry)
    entry_id = str(raw.get("id") or "")
    m = ID_RE.match(entry_id)
    if not m:
        raise HTTPException(400, f"invalid entry id: {entry_id!r}")
    if m.group(3) != "an":
        # Mirror is for phone captures; PC-side creates use POST /entries.
        raise HTTPException(400, "mirror accepts -an entry ids only")
    if not raw.get("ts"):
        raise HTTPException(400, "mirror entry requires ts")

    try:
        entry = Entry.model_validate(raw)
    except Exception as e:  # noqa: BLE001 — surface validation as 400
        raise HTTPException(400, f"entry does not satisfy contract schema: {e}") from e

    _validate_entry_payload(
        text=str(raw.get("text") or ""),
        tags=raw.get("tags") or [],
        images=raw.get("images") or [],
        audio=raw.get("audio") or [],
    )

    # Compare raw wire JSON against raw disk JSON (never decrypted views):
    # E2EE blobs stay byte-identical, so mirroring an encrypted capture is a
    # pure file copy regardless of unlock state. Normalization drops nulls /
    # empty defaults so wire payloads written through pydantic compare equal.
    def _normalized(d: dict[str, Any]) -> dict[str, Any]:
        # Absent and empty are the same content for idempotency: clients that
        # omit `images`/`tags`/`audio` must dedupe against stored entries
        # rather than trip a spurious 409 "diverged content".
        out = {
            k: v
            for k, v in d.items()
            if v is not None and k != "filed_content_hash"
        }
        for empty_key in ("audio", "images", "tags"):
            if not out.get(empty_key):
                out.pop(empty_key, None)
        if out.get("mood") is None:
            out.pop("mood", None)
        # processed absent ≡ false: captures are unprocessed by definition, and
        # clients that omit the field must dedupe, not trip a spurious 409.
        for default_off in ("filed", "prose_edited", "processed"):
            if out.get(default_off) is False:
                out.pop(default_off, None)
        return out

    with vault_process_lock(root):
        target = entry_path(root, entry.id, prefer_existing=True)
        if target.is_file():
            try:
                on_disk = _normalized(read_json(target))
            except Exception as e:  # noqa: BLE001
                raise HTTPException(500, "existing mirror target unreadable") from e
            if on_disk == _normalized(raw):
                return {"ok": True, "id": entry.id, "deduped": True}
            raise HTTPException(
                409,
                f"entry {entry.id} exists with different content",
            )
        _save_entry_or_locked(root, entry)
        return {"ok": True, "id": entry.id, "deduped": False}


def _check_media_list(root: Path, paths: list[str], *, kind: str) -> None:
    for rel in paths:
        try:
            validate_media_rel(root, rel, kind=kind)
        except MediaPathError as e:
            raise HTTPException(400, str(e)) from e


def _next_media_index(root: Path, folder: str, entry_id: str, ext: str) -> int:
    yyyy, mm = shard_from_id(entry_id)
    # Prefer attachments; also scan legacy folder for next index
    directories = [root / "_attachments" / yyyy / mm]
    if folder in ("img", "audio"):
        directories.append(root / folder / yyyy / mm)
    used: set[int] = set()
    prefix = f"{entry_id}_"
    for directory in directories:
        if not directory.is_dir():
            continue
        for p in directory.iterdir():
            if not p.name.startswith(prefix):
                continue
            stem = p.name[len(prefix) :]
            num_s = stem.rsplit(".", 1)[0]
            if num_s.isdigit():
                used.add(int(num_s))
    n = 1
    while n in used:
        n += 1
    return n


async def _read_upload_capped(file: UploadFile, *, limit: int = MAX_UPLOAD_BYTES) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            raise HTTPException(413, f"upload exceeds {limit} bytes")
        chunks.append(chunk)
    return b"".join(chunks)


def _parse_day_param(value: str | None, *, name: str) -> date | None:
    if value is None:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError as e:
        raise HTTPException(400, f"invalid {name}: expected YYYY-MM-DD") from e


@router.get("/entries")
def list_entries(
    root: Path = Depends(get_root),
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    type: EntryType | None = None,
    processed: bool | None = None,
    from_: str | None = Query(None, alias="from", description="Inclusive YYYY-MM-DD"),
    to: str | None = Query(None, description="Inclusive YYYY-MM-DD"),
) -> dict[str, Any]:
    entries = load_all_entries(root)
    entries = list(reversed(entries))  # newest first
    if type is not None:
        entries = [e for e in entries if e.type == type]
    if processed is not None:
        entries = [e for e in entries if e.processed is processed]
    from_d = _parse_day_param(from_, name="from")
    to_d = _parse_day_param(to, name="to")
    if from_d is not None or to_d is not None:
        if from_d is not None and to_d is not None and from_d > to_d:
            raise HTTPException(400, "from must be on or before to")
        cfg = load_config(root)
        fallback_tz = cfg.timezone
        entries = [
            e
            for e in entries
            if (from_d is None or entry_day(e, fallback_tz=fallback_tz) >= from_d)
            and (to_d is None or entry_day(e, fallback_tz=fallback_tz) <= to_d)
        ]
    total = len(entries)
    page = entries[offset : offset + limit]
    return {
        "total": total,
        "offset": offset,
        "limit": limit,
        "entries": [_entry_dict(e) for e in page],
    }


@router.post("/entries", status_code=201)
def create_entry(body: EntryCreate, root: Path = Depends(get_root)) -> dict[str, Any]:
    with vault_process_lock(root):
        when = datetime.now().astimezone()
        ts_verbatim: str | None = None
        if body.ts:
            try:
                when = datetime.fromisoformat(body.ts)
                if when.tzinfo is None:
                    # Naive ts: normalize (schema requires an offset) and store
                    # the aware form, not the raw naive string.
                    when = when.astimezone()
                else:
                    ts_verbatim = body.ts
            except ValueError as e:
                raise HTTPException(400, f"invalid ts: {e}") from e

        if body.id:
            _validate_pc_id(body.id)
            if entry_path(root, body.id).exists():
                raise HTTPException(409, f"entry already exists: {body.id}")
            entry_id = body.id
        else:
            entry_id = next_pc_id(root, when)

        if body.mood is not None and not (1 <= body.mood <= 5):
            raise HTTPException(400, "mood must be 1–5 or null")

        _validate_entry_payload(
            text=body.text, tags=body.tags, images=body.images, audio=body.audio
        )
        _check_media_list(root, body.images, kind="img")
        _check_media_list(root, body.audio, kind="audio")

        entry = Entry(
            version=1,
            id=entry_id,
            ts=ts_verbatim or when.isoformat(timespec="seconds"),
            type=body.type,
            text=body.text,
            tags=list(body.tags),
            images=list(body.images),
            audio=list(body.audio),
            mood=body.mood,
            processed=False,
        )
        _save_entry_or_locked(root, entry)
        return _entry_dict(entry)


@router.get("/entries/{entry_id}")
def get_entry(entry_id: str, root: Path = Depends(get_root)) -> dict[str, Any]:
    if not ID_RE.match(entry_id):
        raise HTTPException(400, f"invalid entry id: {entry_id}")
    path = entry_path(root, entry_id)
    if not path.is_file():
        raise HTTPException(404, f"entry not found: {entry_id}")
    entry = load_entry(path, root)
    if entry is None:
        raise HTTPException(500, f"failed to load entry: {entry_id}")
    return _entry_dict(entry)


@router.patch("/entries/{entry_id}")
def patch_entry(
    entry_id: str, body: EntryPatch, root: Path = Depends(get_root)
) -> dict[str, Any]:
    with vault_process_lock(root):
        if not ID_RE.match(entry_id):
            raise HTTPException(400, f"invalid entry id: {entry_id}")
        path = entry_path(root, entry_id)
        if not path.is_file():
            raise HTTPException(404, f"entry not found: {entry_id}")
        entry = load_entry(path, root)
        if entry is None:
            raise HTTPException(500, f"failed to load entry: {entry_id}")
        _require_mutable(entry)
        if e2ee_mod.entry_locked(entry, root):
            raise HTTPException(
                423,
                f"entry {entry_id} is encrypted and the vault is locked; unlock to edit",
            )

        fields = body.model_fields_set
        if not fields:
            raise HTTPException(400, "provide at least one field to update")
        _validate_entry_payload(
            text=body.text if "text" in fields else None,
            tags=body.tags if "tags" in fields else None,
            images=body.images if "images" in fields else None,
            audio=body.audio if "audio" in fields else None,
        )

        if "type" in fields and body.type is not None:
            entry.type = body.type
        if "text" in fields and body.text is not None:
            entry.text = body.text
        if "tags" in fields and body.tags is not None:
            entry.tags = list(body.tags)
        if "mood" in fields:
            if body.mood is not None and not (1 <= body.mood <= 5):
                raise HTTPException(400, "mood must be 1–5 or null")
            entry.mood = body.mood
        if "images" in fields and body.images is not None:
            _check_media_list(root, body.images, kind="img")
            entry.images = list(body.images)
        if "audio" in fields and body.audio is not None:
            _check_media_list(root, body.audio, kind="audio")
            entry.audio = list(body.audio)

        _save_entry_or_locked(root, entry)
        return _entry_dict(entry)


@router.delete("/entries/{entry_id}")
def delete_entry(entry_id: str, root: Path = Depends(get_root)) -> dict[str, Any]:
    with vault_process_lock(root):
        if not ID_RE.match(entry_id):
            raise HTTPException(400, f"invalid entry id: {entry_id}")
        path = entry_path(root, entry_id)
        if not path.is_file():
            raise HTTPException(404, f"entry not found: {entry_id}")
        entry = load_entry(path, root)
        if entry is None:
            raise HTTPException(500, f"failed to load entry: {entry_id}")
        _require_mutable(entry)
        if e2ee_mod.entry_locked(entry, root):
            # Deleting ciphertext the caller cannot read destroys data
            # irrecoverably — require an unlocked vault, like edits.
            raise HTTPException(
                423,
                f"entry {entry_id} is encrypted and the vault is locked; unlock to delete",
            )
        path.unlink()
        return {"ok": True, "deleted": entry_id}


@router.post("/entries/{entry_id}/images", status_code=201)
async def upload_image(
    entry_id: str,
    file: UploadFile = File(...),
    root: Path = Depends(get_root),
) -> dict[str, Any]:
    data = await _read_upload_capped(file)
    with vault_process_lock(root):
        if not ID_RE.match(entry_id):
            raise HTTPException(400, f"invalid entry id: {entry_id}")
        path = entry_path(root, entry_id)
        if not path.is_file():
            raise HTTPException(404, f"entry not found: {entry_id}")
        entry = load_entry(path, root)
        if entry is None:
            raise HTTPException(500, f"failed to load entry: {entry_id}")
        _require_mutable(entry)

        if len(data) < 3 or data[:2] != b"\xff\xd8":
            raise HTTPException(400, "images must be JPEG")

        yyyy, mm = shard_from_id(entry_id)
        n = _next_media_index(root, "img", entry_id, "jpg")
        rel = f"_attachments/{yyyy}/{mm}/{entry_id}_{n}.jpg"
        atomic_write_bytes(root / rel, data)
        entry.images = list(entry.images) + [rel]
        _save_entry_or_locked(root, entry)
        return {"path": rel, "entry": _entry_dict(entry)}


@router.post("/entries/{entry_id}/audio", status_code=201)
async def upload_audio(
    entry_id: str,
    file: UploadFile = File(...),
    root: Path = Depends(get_root),
) -> dict[str, Any]:
    filename = (file.filename or "").lower()
    data = await _read_upload_capped(file)
    with vault_process_lock(root):
        if not ID_RE.match(entry_id):
            raise HTTPException(400, f"invalid entry id: {entry_id}")
        path = entry_path(root, entry_id)
        if not path.is_file():
            raise HTTPException(404, f"entry not found: {entry_id}")
        entry = load_entry(path, root)
        if entry is None:
            raise HTTPException(500, f"failed to load entry: {entry_id}")
        _require_mutable(entry)

        if not filename:
            raise HTTPException(400, "audio upload requires a filename")
        if not filename.endswith(".m4a"):
            raise HTTPException(400, "audio must be .m4a")

        if not data:
            raise HTTPException(400, "empty audio upload")

        if len(data) < 12 or data[4:8] != b"ftyp":
            raise HTTPException(400, "audio must be an MP4 (.m4a) container")

        yyyy, mm = shard_from_id(entry_id)
        n = _next_media_index(root, "audio", entry_id, "m4a")
        rel = f"_attachments/{yyyy}/{mm}/{entry_id}_{n}.m4a"
        atomic_write_bytes(root / rel, data)
        entry.audio = list(entry.audio) + [rel]
        _save_entry_or_locked(root, entry)
        return {"path": rel, "entry": _entry_dict(entry)}
