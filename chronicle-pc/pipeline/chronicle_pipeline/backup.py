"""Dated zip backup (oracle for server/src/backup.rs).

Exclusions are read from ``contract/backup-exclusions.json``, the single list
shared with the Rust owner: a directory component in ``dir_names`` excludes
everything beneath it, a file is excluded when its name is in ``file_names``
or ends with one of ``file_suffixes``. Dotfiles not on the list are archived.
"""

from __future__ import annotations

import contextlib
import json
import logging
import os
import tempfile
import zipfile
from collections.abc import Callable
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


def _collect_files(root: Path, exclusions: Exclusions, out: Path) -> list[Path]:
    """Regular files to archive, in archive order.

    ``Path.rglob`` swallows ``scandir`` errors (a mode-000 directory is omitted
    and the walk continues). ``os.walk`` reports them through ``onerror``;
    re-raising aborts before the destination zip is replaced.

    Symlinks are omitted, not followed: ``Path.is_file`` is true for a symlink
    to a regular file, and a linked directory would otherwise pull in files
    from outside the vault.
    """
    files: list[Path] = []

    def onerror(err: OSError) -> None:
        raise err

    for dirpath, dirnames, filenames in os.walk(root, followlinks=False, onerror=onerror):
        base = Path(dirpath)
        kept: list[str] = []
        for name in dirnames:
            child = base / name
            if name in exclusions.dir_names or child.is_symlink():
                continue
            kept.append(name)
        dirnames[:] = kept
        for name in filenames:
            path = base / name
            # is_symlink() before is_file(); the latter follows links.
            if path.is_symlink() or not path.is_file():
                continue
            rel = path.relative_to(root)
            if exclusions.skips(rel):
                continue
            if path.resolve() == out:
                continue
            files.append(path)
    files.sort()
    return files


def run_backup(
    chronicle_dir: Path | str | None = None,
    *,
    path: str | Path | None = None,
    force: bool = False,
    commit: Callable[[Path, Path], None] | None = None,
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
    # Collect before the partial exists so a listing failure cannot publish
    # and the partial itself is not part of the walk.
    files = _collect_files(root, exclusions, out)
    publish = os.replace if commit is None else commit

    fd, partial_name = tempfile.mkstemp(
        prefix=f".{out.name}.",
        suffix=".partial",
        dir=os.fspath(out.parent),
    )
    os.close(fd)
    partial = Path(partial_name)
    published = False
    count = 0
    try:
        with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            partial_resolved = partial.resolve()
            for file in files:
                resolved = file.resolve()
                if resolved == out or resolved == partial_resolved:
                    continue
                rel = file.relative_to(root)
                zf.write(file, arcname=str(Path("Chronicle") / rel))
                count += 1
        # Same directory as ``out`` so replace is atomic. Never open ``out``
        # with ZipFile mode "w": that truncates an existing archive first.
        publish(partial, out)
        published = True
    finally:
        if not published:
            with contextlib.suppress(OSError):
                partial.unlink(missing_ok=True)

    log.info("Backup wrote %d files → %s (index/ excluded)", count, out)
    return {"path": str(out), "files": count}
