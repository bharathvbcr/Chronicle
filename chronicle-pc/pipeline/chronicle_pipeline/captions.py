"""Persist vision image captions under index/ (Mac-only, not synced)."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from .paths import atomic_write_json, read_json

log = logging.getLogger("chronicle.captions")


def captions_path(root: Path) -> Path:
    return Path(root) / "index" / "image_captions.json"


def load_captions(root: Path) -> dict[str, str]:
    path = captions_path(root)
    if not path.is_file():
        return {}
    try:
        data = read_json(path)
    except (OSError, ValueError) as e:
        log.warning("Failed to load image captions %s: %s", path, e)
        return {}
    if not isinstance(data, dict):
        return {}
    captions = data.get("captions") if "captions" in data else data
    if not isinstance(captions, dict):
        return {}
    out: dict[str, str] = {}
    for k, v in captions.items():
        if isinstance(k, str) and isinstance(v, str):
            out[k] = v
    return out


def save_captions(root: Path, captions: dict[str, str]) -> Path:
    path = captions_path(root)
    cleaned = {k: v for k, v in captions.items() if isinstance(k, str) and isinstance(v, str)}
    payload: dict[str, Any] = {"version": 1, "captions": cleaned}
    atomic_write_json(path, payload)
    return path
