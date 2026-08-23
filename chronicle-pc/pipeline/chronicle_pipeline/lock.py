"""Vault-wide process lock for process / brain / enrich runs."""

from __future__ import annotations

import fcntl
import logging
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

log = logging.getLogger("chronicle.lock")

_local = threading.local()


def _depth() -> int:
    return int(getattr(_local, "depth", 0) or 0)


def _set_depth(value: int) -> None:
    _local.depth = value


def _fd():
    return getattr(_local, "fd", None)


def _set_fd(fd) -> None:
    _local.fd = fd


@contextmanager
def vault_process_lock(root: Path) -> Iterator[None]:
    """Exclusive file lock at ``index/process.lock`` (re-entrant per thread)."""
    depth = _depth()
    if depth == 0:
        lock_path = Path(root) / "index" / "process.lock"
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        fd = open(lock_path, "a+", encoding="utf-8")
        try:
            fcntl.flock(fd.fileno(), fcntl.LOCK_EX)
        except OSError:
            fd.close()
            raise
        _set_fd(fd)
        log.debug("Acquired process lock %s", lock_path)
    _set_depth(depth + 1)
    try:
        yield
    finally:
        new_depth = _depth() - 1
        _set_depth(new_depth)
        if new_depth == 0:
            fd = _fd()
            _set_fd(None)
            if fd is not None:
                try:
                    fcntl.flock(fd.fileno(), fcntl.LOCK_UN)
                finally:
                    fd.close()
                log.debug("Released process lock")
