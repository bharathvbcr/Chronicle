"""Dated zip backup excluding index/."""

from __future__ import annotations

import logging
import zipfile
from datetime import datetime
from pathlib import Path

from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.backup")

SKIP_DIR_NAMES = {"index", ".git", ".venv", "__pycache__", ".stfolder"}
SKIP_FILE_SUFFIXES = (".tmp",)
SKIP_FILE_NAMES = {".DS_Store"}


def run_backup(
    chronicle_dir: Path | str | None = None,
    *,
    path: str | Path | None = None,
    force: bool = False,
) -> dict:
    root = resolve_chronicle_dir(chronicle_dir)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = Path(path) if path else root.parent / f"chronicle-backup-{stamp}.zip"
    out = out.expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists() and not force:
        raise FileExistsError(
            f"backup target exists: {out} (pass --force to overwrite)"
        )

    count = 0
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(root.rglob("*")):
            if not p.is_file():
                continue
            if p.resolve() == out:
                continue  # never zip the in-progress backup into itself
            rel = p.relative_to(root)
            if any(part in SKIP_DIR_NAMES for part in rel.parts):
                continue
            if p.name in SKIP_FILE_NAMES or p.name.endswith(SKIP_FILE_SUFFIXES):
                continue
            zf.write(p, arcname=str(Path("Chronicle") / rel))
            count += 1

    log.info("Backup wrote %d files → %s (index/ excluded)", count, out)
    return {"path": str(out), "files": count}
