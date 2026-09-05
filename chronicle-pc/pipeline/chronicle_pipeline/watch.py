"""Debounced watchdog loop over the Chronicle folder."""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path
from typing import Any

from .path_map import PARA_AREAS
from .paths import resolve_chronicle_dir
from .process import run_process

log = logging.getLogger("chronicle.watch")

try:
    from watchdog.events import FileSystemEventHandler

    _BaseHandler = FileSystemEventHandler
except ImportError:
    _BaseHandler = object  # type: ignore[misc,assignment]


class ParaHandler(_BaseHandler):
    """Event handler for PARA areas that triggers index updates only."""

    def __init__(self, root: Path, *, on_change: Any) -> None:
        self.root = root
        self.root_resolved = root.resolve()
        self.on_change = on_change

    def on_any_event(self, event: Any) -> None:
        if getattr(event, "is_directory", False):
            return
        path = Path(getattr(event, "src_path", "") or "")
        name = path.name
        if name.endswith(".tmp") or name.startswith(".") or name == ".DS_Store":
            return
        try:
            rel = path.relative_to(self.root)
        except ValueError:
            try:
                rel = path.resolve().relative_to(self.root_resolved)
            except (OSError, ValueError):
                return
        parts = set(rel.parts)
        if parts & {"index", "notes", "brain", "_system"}:
            return
        self.on_change()


def run_watch(
    chronicle_dir: Path | str | None = None,
    *,
    debounce_s: float = 2.0,
    poll_fallback_s: float = 5.0,
) -> None:
    """
    Watch entries/ img/ audio/ curation/ for changes; debounce then process.
    Uses watchdog if available; otherwise polls mtimes.

    Ignores events while a process run is in flight and briefly afterward so
    the pipeline's own entry writes (processed flips) do not re-trigger.
    Full daily-note regen is reserved for ``chronicle rebuild``.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    log.info("Watching %s (debounce=%.1fs)", root, debounce_s)

    # Layout 2: never recreate empty legacy entries/img/audio trees (feeds dual-read).
    # Still watch them if they already exist.
    try:
        from .config import load_config

        layout_version = int(load_config(root).layout_version)
    except Exception:  # noqa: BLE001
        layout_version = 2
    legacy_subs = ("entries", "img", "audio")
    core_subs = ("_capture", "_attachments", "curation", "kb")

    timer: threading.Timer | None = None
    lock = threading.Lock()
    processing = False
    ignore_until = 0.0

    def _schedule() -> None:
        nonlocal timer
        with lock:
            if processing or time.time() < ignore_until:
                return
            if timer is not None:
                timer.cancel()
            timer = threading.Timer(debounce_s, _run)
            timer.daemon = True
            timer.start()

    def _run() -> None:
        nonlocal processing, ignore_until, timer
        with lock:
            if processing:
                return
            processing = True
            timer = None
        try:
            log.info("Change detected — running process")
            run_process(root, dry_run=False, run_brain=True)
        except Exception:  # noqa: BLE001
            log.exception("process failed during watch")
        finally:
            with lock:
                processing = False
                # Absorb self-writes (entry processed flips, etc.) after the run.
                ignore_until = time.time() + debounce_s + 0.5

    para_timer: threading.Timer | None = None
    para_lock = threading.Lock()
    para_indexing = False
    para_ignore_until = 0.0

    def _schedule_para() -> None:
        nonlocal para_timer
        with para_lock:
            if para_indexing or time.time() < para_ignore_until:
                return
            if para_timer is not None:
                para_timer.cancel()
            para_timer = threading.Timer(debounce_s, _run_para)
            para_timer.daemon = True
            para_timer.start()

    def _run_para() -> None:
        nonlocal para_indexing, para_ignore_until, para_timer
        with para_lock:
            if para_indexing:
                return
            para_indexing = True
            para_timer = None
        try:
            log.info("PARA change detected — updating index")
            from .index_store import run_index
            from .markdown_index import rebuild_markdown_index

            run_index(root, dry_run=False)
            rebuild_markdown_index(root, dry_run=False)
        except Exception:  # noqa: BLE001
            log.exception("index update failed during watch")
        finally:
            with para_lock:
                para_indexing = False
                para_ignore_until = time.time() + debounce_s + 0.5

    try:
        from watchdog.events import FileSystemEventHandler
        from watchdog.observers import Observer

        root_resolved = root.resolve()

        class Handler(FileSystemEventHandler):
            def on_any_event(self, event):  # noqa: ANN001
                if event.is_directory:
                    return
                path = Path(getattr(event, "src_path", "") or "")
                name = path.name
                if name.endswith(".tmp") or name.startswith(".") or name == ".DS_Store":
                    return
                # Only vault-relative parts — an absolute path like
                # ~/notes/Chronicle/... must not match the skip set.
                try:
                    rel = path.relative_to(root)
                except ValueError:
                    try:
                        rel = path.resolve().relative_to(root_resolved)
                    except (OSError, ValueError):
                        return  # outside the watched root — ignore
                parts = set(rel.parts)
                # Derived / local-only churn — never trigger a full process cycle.
                if parts & {"index", "notes", "brain", "40-Journal", "_system"}:
                    return
                _schedule()

        observer = Observer()
        for sub in core_subs:
            d = root / sub
            d.mkdir(parents=True, exist_ok=True)
            observer.schedule(Handler(), str(d), recursive=True)
        for sub in legacy_subs:
            d = root / sub
            if layout_version < 2:
                d.mkdir(parents=True, exist_ok=True)
            if d.is_dir():
                observer.schedule(Handler(), str(d), recursive=True)
        observer.start()

        para_observer = Observer()
        para_handler = ParaHandler(root, on_change=_schedule_para)
        for area in PARA_AREAS:
            d = root / area
            d.mkdir(parents=True, exist_ok=True)
            para_observer.schedule(para_handler, str(d), recursive=True)
        para_observer.start()

        log.info("watchdog observer started (core + PARA)")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            log.info("Stopping watch")
        finally:
            observer.stop()
            observer.join()
            para_observer.stop()
            para_observer.join()
        return
    except ImportError:
        log.warning("watchdog not installed; falling back to mtime polling")

    # Polling fallback
    watch_dirs: list[Path] = []
    for sub in core_subs:
        d = root / sub
        d.mkdir(parents=True, exist_ok=True)
        watch_dirs.append(d)
    for sub in legacy_subs:
        d = root / sub
        if layout_version < 2:
            d.mkdir(parents=True, exist_ok=True)
        if d.is_dir():
            watch_dirs.append(d)

    def snapshot() -> dict[str, float]:
        snap: dict[str, float] = {}
        for d in watch_dirs:
            for p in d.rglob("*"):
                if p.is_file() and not p.name.endswith(".tmp"):
                    try:
                        snap[str(p)] = p.stat().st_mtime
                    except OSError:
                        pass
        return snap

    prev = snapshot()
    try:
        while True:
            time.sleep(poll_fallback_s)
            if processing or time.time() < ignore_until:
                prev = snapshot()
                continue
            cur = snapshot()
            if cur != prev:
                prev = cur
                _schedule()
    except KeyboardInterrupt:
        log.info("Stopping watch")