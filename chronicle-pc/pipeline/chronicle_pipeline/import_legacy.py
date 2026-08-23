"""Import legacy flat entries/*.json into v1.2 sharded layout."""

from __future__ import annotations

import json
import logging
import re
import shutil
from datetime import datetime
from pathlib import Path
from typing import Any

from .entries import entry_path, save_entry
from .models import Entry
from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.import_legacy")

LEGACY_ID_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_(\d{4})(?:_(\d+))?$")
NEW_ID_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_(\d{6})-(an|pc)(_[0-9]+)?$")


def _convert_id(old_id: str, *, device: str = "an") -> str:
    """Convert legacy yyyy-MM-dd_HHmm to yyyy-MM-dd_HHmmss-dev."""
    if NEW_ID_RE.match(old_id):
        return old_id
    m = LEGACY_ID_RE.match(old_id)
    if m:
        day, hm, suffix = m.group(1), m.group(2), m.group(3)
        # Pad HHmm → HHmmss with 00 seconds
        new_id = f"{day}_{hm}00-{device}"
        if suffix:
            new_id = f"{new_id}_{suffix}"
        return new_id
    # Fallback: timestamp-based
    safe = re.sub(r"[^0-9A-Za-z_-]", "", old_id)[:40]
    return f"1970-01-01_000000-{device}_{safe}"


def convert_legacy_entry(raw: dict[str, Any], *, device: str = "an") -> Entry:
    """Convert a legacy entry dict to Entry v1.2 (drops city/weather)."""
    old_id = str(raw.get("id") or raw.get("filename") or "")
    new_id = _convert_id(old_id or "unknown", device=device)

    ts = raw.get("ts") or raw.get("timestamp") or raw.get("date")
    if not ts:
        # Derive from id date
        day = new_id[:10]
        ts = f"{day}T00:00:00+00:00"
    elif isinstance(ts, (int, float)):
        ts = datetime.fromtimestamp(ts).astimezone().isoformat()

    etype = raw.get("type") or "log"
    if etype not in ("log", "idea", "dream", "reflection"):
        etype = "log"

    tags = raw.get("tags") or []
    if isinstance(tags, str):
        tags = [t.strip() for t in tags.split(",") if t.strip()]

    images = []
    for img in raw.get("images") or raw.get("photos") or []:
        img = str(img)
        if img.startswith("img/"):
            images.append(img)
        elif "/" in img:
            images.append(img if img.startswith("img/") else f"img/{img}")
        else:
            # Remap flat image name into shard from new id
            yyyy, mm = new_id[:4], new_id[5:7]
            images.append(f"img/{yyyy}/{mm}/{img}")

    audio = []
    for a in raw.get("audio") or []:
        a = str(a)
        if a.startswith("audio/"):
            audio.append(a)

    mood = raw.get("mood")
    if mood is not None:
        try:
            mood = int(mood)
            if mood < 1 or mood > 5:
                mood = None
        except (TypeError, ValueError):
            mood = None

    return Entry(
        version=1,
        id=new_id,
        ts=str(ts),
        type=etype,  # type: ignore[arg-type]
        text=str(raw.get("text") or raw.get("content") or ""),
        tags=list(tags),
        images=images,
        audio=audio,
        mood=mood,
        processed=bool(raw.get("processed", False)),
    )


def run_import_legacy(
    legacy_dir: Path | str,
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
    device: str = "an",
) -> dict:
    legacy_root = Path(legacy_dir).expanduser().resolve()
    root = resolve_chronicle_dir(chronicle_dir)

    # Accept either <dir>/entries/*.json or <dir>/*.json
    candidates: list[Path] = []
    flat = legacy_root / "entries"
    if flat.is_dir():
        candidates = sorted(flat.glob("*.json"))
    else:
        candidates = sorted(legacy_root.glob("*.json"))

    imported: list[str] = []
    skipped: list[str] = []

    for path in candidates:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            log.warning("Skip %s: %s", path, e)
            skipped.append(path.name)
            continue
        if not isinstance(raw, dict):
            skipped.append(path.name)
            continue

        entry = convert_legacy_entry(raw, device=device)
        dest = entry_path(root, entry.id)
        if dest.exists():
            log.info("Exists, skip: %s", entry.id)
            skipped.append(entry.id)
            continue
        if dry_run:
            log.info("[dry-run] would import %s → %s", path.name, dest)
        else:
            save_entry(root, entry)
            # Copy media if present beside legacy entries
            _copy_legacy_media(legacy_root, root, raw, entry)
            log.info("Imported %s → %s", path.name, dest)
        imported.append(entry.id)

    return {
        "imported": imported,
        "skipped": skipped,
        "dry_run": dry_run,
        "chronicle_dir": str(root),
    }


def _copy_legacy_media(
    legacy_root: Path,
    root: Path,
    raw: dict[str, Any],
    entry: Entry,
) -> None:
    for rel in entry.images:
        dest = root / rel
        if dest.exists():
            continue
        # Try flat img/ or images/
        name = Path(rel).name
        for cand in (
            legacy_root / "img" / name,
            legacy_root / "images" / name,
            legacy_root / name,
        ):
            if cand.is_file():
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(cand, dest)
                break
    for rel in entry.audio or []:
        dest = root / rel
        if dest.exists():
            continue
        name = Path(rel).name
        for cand in (
            legacy_root / "audio" / name,
            legacy_root / name,
        ):
            if cand.is_file():
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(cand, dest)
                break
