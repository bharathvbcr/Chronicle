"""Dated zip backup (oracle for server/src/backup.rs).

Exclusions are read from ``contract/backup-exclusions.json``, the single list
shared with the Rust owner: a directory component in ``dir_names`` excludes
everything beneath it, a file is excluded when its name is in ``file_names``
or ends with one of ``file_suffixes``. Dotfiles not on the list are archived.
"""

from __future__ import annotations

import json
import logging
import zipfile
from dataclasses import dataclass
from datetime import datetime
from functools import cache
from pathlib import Path

from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.backup")

# Resolved against the source tree (chronicle-pc/contract/), the same file the
# Rust server embeds with include_str!. Works for the editable install; a
# built wheel does not ship it and fails loud in load_exclusions().
EXCLUSIONS_PATH = Path(__file__).resolve().parents[2] / "contract" / "backup-exclusions.json"


@dataclass(frozen=True)
class Exclusions:
    dir_names: frozenset[str]
    file_names: frozenset[str]
    file_suffixes: tuple[str, ...]

    def skips(self, rel: Path) -> bool:
        if any(part in self.dir_names for part in rel.parts[:-1]):
            return True
        return rel.name in self.file_names or rel.name.endswith(self.file_suffixes)


@cache
def load_exclusions(path: Path = EXCLUSIONS_PATH) -> Exclusions:
    """Parse the shared exclusion list; raise if it is missing or malformed."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise RuntimeError(f"cannot load backup exclusions from {path}: {e}") from e
    keys = {"dir_names", "file_names", "file_suffixes"}
    if not isinstance(raw, dict) or set(raw) != keys:
        raise RuntimeError(f"backup exclusions {path} must have exactly the keys {sorted(keys)}")
    for k in keys:
        if not isinstance(raw[k], list) or not all(isinstance(v, str) for v in raw[k]):
            raise RuntimeError(f"backup exclusions {path}: {k!r} must be a list of strings")
    return Exclusions(
        dir_names=frozenset(raw["dir_names"]),
        file_names=frozenset(raw["file_names"]),
        file_suffixes=tuple(raw["file_suffixes"]),
    )


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

    exclusions = load_exclusions()
    count = 0
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(root.rglob("*")):
            if not p.is_file():
                continue
            if p.resolve() == out:
                continue  # never zip the in-progress backup into itself
            rel = p.relative_to(root)
            if exclusions.skips(rel):
                continue
            zf.write(p, arcname=str(Path("Chronicle") / rel))
            count += 1

    log.info("Backup wrote %d files → %s (index/ excluded)", count, out)
    return {"path": str(out), "files": count}
