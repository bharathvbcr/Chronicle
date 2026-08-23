"""Debounced watchdog loop over the Chronicle folder."""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

from .paths import resolve_chronicle_dir
from .process import run_process

log = logging.getLogger("chronicle.watch")


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
        log.info("watchdog observer started")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            log.info("Stopping watch")
        finally:
            observer.stop()
            observer.join()
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