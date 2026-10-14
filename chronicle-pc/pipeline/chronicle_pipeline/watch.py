"""Debounced watchdog loop over the Chronicle folder."""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from .path_map import PARA_AREAS
from .paths import resolve_chronicle_dir
from .process import run_process

log = logging.getLogger("chronicle.watch")

# Absorb self-writes that land after the run returns (processed flips, index rebuild).
_IGNORE_SLACK_S = 0.5

try:
    from watchdog.events import FileSystemEventHandler

    _BaseHandler = FileSystemEventHandler
except ImportError:
    _BaseHandler = object  # type: ignore[misc,assignment]


def tree_snapshot(roots: list[Path]) -> dict[str, int]:
    """mtime_ns of watched files. Dotfiles and ``*.tmp`` are ignored, matching the handler."""
    snap: dict[str, int] = {}
    for root in roots:
        if not root.is_dir():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            name = path.name
            if name.endswith(".tmp") or name.startswith(".") or name == ".DS_Store":
                continue
            try:
                snap[str(path)] = path.stat().st_mtime_ns
            except OSError:
                continue
    return snap


def _snapshot_diff(before: dict[str, int], after: dict[str, int]) -> dict[str, int | None]:
    """Paths whose presence or mtime changed. Missing in ``after`` maps to None."""
    changed: dict[str, int | None] = {}
    for key in set(before) | set(after):
        old = before.get(key)
        new = after.get(key)
        if old != new:
            changed[key] = new
    return changed


class DebouncedWatch:
    """One in-flight run plus an ignore window.

    An explicit ``capture_self_writes`` freezes the self-write snapshot.
    Otherwise a path ``note`` saw during the run is an echo, and any other path
    that did not exist before the run is one follow-up. That path is not copied
    into the baseline. A rewrite of a path that already existed is the run's own
    write and does not schedule another pass.
    """

    def __init__(
        self,
        *,
        clock: Callable[[], float],
        debounce_s: float,
        snapshot: Callable[[], dict[str, int]],
        run: Callable[[], None],
        use_timer: bool = True,
        error_message: str = "watch callback failed",
    ) -> None:
        self._clock = clock
        self.debounce_s = debounce_s
        self._snapshot = snapshot
        self._run = run
        self._use_timer = use_timer
        self._error_message = error_message
        self._lock = threading.RLock()
        self.processing = False
        self.ignore_until = 0.0
        self._closed = False
        self._due_at: float | None = None
        self._owed = False
        self._timer: threading.Timer | None = None
        self._accounted = snapshot()
        self._before: dict[str, int] = {}
        self._captured: dict[str, int] | None = None
        self._capture_explicit = False
        self._external: set[str] = set()
        self._observed: set[str] = set()

    def close(self) -> None:
        """Cancel timers. Safe during shutdown; does not wait for an in-flight run."""
        with self._lock:
            self._closed = True
            self._due_at = None
            self._owed = False
            self._cancel_timer_locked()

    def seconds_until_due(self, cap: float) -> float:
        """How long the poll loop should sleep, never longer than ``cap``."""
        with self._lock:
            now = self._clock()
            wait = cap
            if self._due_at is not None:
                wait = min(wait, max(0.0, self._due_at - now))
            if self._owed:
                wait = min(wait, max(0.0, self.ignore_until - now))
            return wait

    def capture_self_writes(self) -> None:
        """Freeze the self-write snapshot while ``processing`` is still true.

        Only an explicit call does this. Finish does not invent one: without it,
        a new path is a self-write only when ``note`` was given that path.
        """
        with self._lock:
            if self._closed or not self.processing or self._captured is not None:
                return
            self._captured = self._snapshot()
            self._capture_explicit = True

    def note(self, paths: set[str] | None = None) -> None:
        """Echo declaration. Paths named here during a run are the run's own writes.

        A call with no path (PARA ``on_change``) does not declare every new file
        an echo, and does not by itself schedule a follow-up.
        """
        with self._lock:
            if self._closed:
                return
            now = self._clock()
            if self.processing:
                if paths:
                    self._external |= paths
                if self._captured is not None and _snapshot_diff(self._captured, self._snapshot()):
                    self._owed = True
                return
            if now < self.ignore_until:
                if self._snapshot() != self._accounted:
                    self._owed = True
                return
            self._arm_locked()

    def observe_event(self, paths: set[str]) -> None:
        """Filesystem event from the production handler. Not an echo declaration."""
        with self._lock:
            if self._closed or not paths:
                return
            now = self._clock()
            if self.processing:
                self._observed |= paths
                if self._capture_explicit and self._captured is not None:
                    if _snapshot_diff(self._captured, self._snapshot()):
                        self._owed = True
                return
            if now < self.ignore_until:
                # A path already in the baseline is a rewrite. Owing on it loops.
                if any(path not in self._accounted for path in paths):
                    self._owed = True
                return
            self._arm_locked()

    def turn(self) -> None:
        """One decision: fire a due run, sample the window, or arm a debounce."""
        with self._lock:
            self._turn_locked()

    def _turn_locked(self) -> None:
        if self._closed:
            return
        if self.processing:
            self._observe_processing_locked()
            return
        now = self._clock()
        if self._due_at is not None:
            if now >= self._due_at:
                self._due_at = None
                self._cancel_timer_locked()
                self._run_locked()
            return
        if now < self.ignore_until:
            self._observe_window_locked()
            return
        if self._owed:
            self._owed = False
            self._cancel_timer_locked()
            self._run_locked()
            return
        self._observe_idle_locked()

    def _observe_processing_locked(self) -> None:
        # A poll during the run must not replace the baseline. Don't freeze the
        # self-write snapshot here; later writes from this same run belong to it.
        if self._captured is None:
            return
        if _snapshot_diff(self._captured, self._snapshot()):
            self._owed = True

    def _observe_window_locked(self) -> None:
        if self._snapshot() != self._accounted:
            self._owed = True

    def _observe_idle_locked(self) -> None:
        if self._snapshot() != self._accounted:
            self._arm_locked()

    def _arm_locked(self) -> None:
        self._due_at = self._clock() + self.debounce_s
        if self._use_timer:
            self._start_timer_locked(self.debounce_s)

    def _run_locked(self) -> None:
        if self.processing or self._closed:
            return
        self.processing = True
        self._before = self._snapshot()
        self._captured = None
        self._capture_explicit = False
        self._external.clear()
        self._observed.clear()
        self._owed = False
        self._lock.release()
        try:
            self._run()
        except Exception:
            log.exception("%s", self._error_message)
        finally:
            self._lock.acquire()
            self._finish_locked()

    def _finish_locked(self) -> None:
        try:
            if self._closed:
                return
            if self._capture_explicit and self._captured is not None:
                after = self._snapshot()
                # note() paths are echoes here too. A follow-up is only a
                # create that landed after the frozen self-write snapshot.
                late = _snapshot_diff(self._captured, after)
                self._accounted = dict(self._captured)
                if late:
                    self._owed = True
            else:
                after = self._snapshot()
                # note() paths are echoes. A new path is a follow-up when the
                # poller saw it with no event, or the handler observed it and
                # note() did not claim it. A path that already existed is a rewrite.
                user_creates = {
                    path
                    for path in after
                    if path not in self._before and path not in self._external
                }
                for path in self._observed:
                    if path in self._external or path in self._before:
                        continue
                    if path in after:
                        user_creates.add(path)
                accounted = dict(after)
                for path in user_creates:
                    accounted.pop(path, None)
                self._accounted = accounted
                if user_creates:
                    self._owed = True
            self.ignore_until = self._clock() + self.debounce_s + _IGNORE_SLACK_S
            if self._owed and self._use_timer:
                delay = max(0.0, self.ignore_until - self._clock())
                self._start_timer_locked(delay)
        finally:
            self._captured = None
            self._capture_explicit = False
            self._external.clear()
            self._observed.clear()
            self.processing = False
            self._before = {}

    def _start_timer_locked(self, delay: float) -> None:
        self._cancel_timer_locked()
        timer = threading.Timer(max(0.0, delay), self.turn)
        timer.daemon = True
        self._timer = timer
        timer.start()

    def _cancel_timer_locked(self) -> None:
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None


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


def _pump_wait(core: DebouncedWatch, para: DebouncedWatch, cap: float) -> float:
    return min(core.seconds_until_due(cap), para.seconds_until_due(cap))


def run_watch(
    chronicle_dir: Path | str | None = None,
    *,
    debounce_s: float = 2.0,
    poll_fallback_s: float = 5.0,
    clock: Callable[[], float] | None = None,
    sleeper: Callable[[float], None] | None = None,
    process_fn: Callable[[], None] | None = None,
    index_fn: Callable[[], None] | None = None,
) -> None:
    """
    Watch entries/ img/ audio/ curation/ for changes; debounce then process.
    Uses watchdog if available; otherwise polls mtimes.

    Ignores the pipeline's own writes. A different create during the run or the
    short ignore window schedules one follow-up after the window, not a second
    concurrent run. Full daily-note regen is reserved for ``chronicle rebuild``.

    ``clock`` and ``sleeper`` are the time seam. When ``clock`` is omitted the
    debounce timer uses wall time. An injected clock does not start wall timers;
    the caller drives :meth:`DebouncedWatch.turn` by sleeping.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    log.info("Watching %s (debounce=%.1fs)", root, debounce_s)
    clk = clock if clock is not None else time.time
    sleep = sleeper if sleeper is not None else time.sleep
    use_timer = clock is None

    # Layout 2: never recreate empty legacy entries/img/audio trees (feeds dual-read).
    # Still watch them if they already exist.
    try:
        from .config import load_config

        layout_version = int(load_config(root).layout_version)
    except Exception:  # noqa: BLE001
        layout_version = 2
    legacy_subs = ("entries", "img", "audio")
    core_subs = ("_capture", "_attachments", "curation", "kb")

    watch_dirs: list[Path] = []
    for sub in core_subs:
        watched = root / sub
        watched.mkdir(parents=True, exist_ok=True)
        watch_dirs.append(watched)
    for sub in legacy_subs:
        watched = root / sub
        if layout_version < 2:
            watched.mkdir(parents=True, exist_ok=True)
        if watched.is_dir():
            watch_dirs.append(watched)

    para_dirs: list[Path] = []
    for area in PARA_AREAS:
        watched = root / area
        watched.mkdir(parents=True, exist_ok=True)
        para_dirs.append(watched)

    def _process() -> None:
        log.info("Change detected — running process")
        if process_fn is not None:
            process_fn()
            return
        run_process(root, dry_run=False, run_brain=True)

    def _index() -> None:
        log.info("PARA change detected — updating index")
        if index_fn is not None:
            index_fn()
            return
        from .index_store import run_index
        from .markdown_index import rebuild_markdown_index

        run_index(root, dry_run=False)
        rebuild_markdown_index(root, dry_run=False)

    core = DebouncedWatch(
        clock=clk,
        debounce_s=debounce_s,
        snapshot=lambda: tree_snapshot(watch_dirs),
        run=_process,
        use_timer=use_timer,
        error_message="process failed during watch",
    )
    para = DebouncedWatch(
        clock=clk,
        debounce_s=debounce_s,
        snapshot=lambda: tree_snapshot(para_dirs),
        run=_index,
        use_timer=use_timer,
        error_message="index update failed during watch",
    )

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
                core.observe_event({str(path)})

        observer = Observer()
        for watched in watch_dirs:
            observer.schedule(Handler(), str(watched), recursive=True)
        observer.start()

        para_observer = Observer()
        para_handler = ParaHandler(root, on_change=para.note)
        for watched in para_dirs:
            para_observer.schedule(para_handler, str(watched), recursive=True)
        para_observer.start()

        log.info("watchdog observer started (core + PARA)")
        try:
            while True:
                sleep(_pump_wait(core, para, 1.0))
                core.turn()
                para.turn()
        except KeyboardInterrupt:
            log.info("Stopping watch")
        finally:
            core.close()
            para.close()
            observer.stop()
            observer.join()
            para_observer.stop()
            para_observer.join()
        return
    except ImportError:
        log.warning("watchdog not installed; falling back to mtime polling")

    try:
        while True:
            sleep(_pump_wait(core, para, poll_fallback_s))
            core.turn()
            para.turn()
    except KeyboardInterrupt:
        log.info("Stopping watch")
    finally:
        core.close()
        para.close()
