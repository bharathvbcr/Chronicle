"""Chronicle folder path helpers and atomic I/O."""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any


def resolve_chronicle_dir(explicit: str | Path | None = None) -> Path:
    """Resolve Chronicle root: --chronicle-dir, CHRONICLE_DIR, or cwd."""
    if explicit is not None:
        return Path(explicit).expanduser().resolve()
    env = os.environ.get("CHRONICLE_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return Path.cwd().resolve()


def atomic_write_text(path: Path, text: str, *, encoding: str = "utf-8") -> None:
    """Write via temp file in the same directory, then rename."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=str(path.parent),
    )
    try:
        with os.fdopen(fd, "w", encoding=encoding) as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def atomic_write_json(path: Path, data: Any, *, indent: int = 2) -> None:
    text = json.dumps(data, indent=indent, ensure_ascii=False, sort_keys=False)
    if not text.endswith("\n"):
        text += "\n"
    atomic_write_text(path, text)


def atomic_write_bytes(path: Path, data: bytes) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=str(path.parent),
    )
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def read_json(path: Path) -> Any:
    with Path(path).open(encoding="utf-8") as f:
        return json.load(f)


def content_hash(text: str) -> str:
    import hashlib

    return hashlib.sha256(text.encode("utf-8")).hexdigest()
