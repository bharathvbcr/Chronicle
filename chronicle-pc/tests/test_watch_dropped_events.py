"""Watch events dropped while a run is in flight or inside the ignore window.

The clock is manual. Nothing here sleeps for the debounce.
"""

from __future__ import annotations

import builtins
from collections.abc import Callable
from pathlib import Path

import pytest

from chronicle_pipeline.watch import DebouncedWatch, run_watch


class ManualClock:
    def __init__(self, now: float = 1_000.0) -> None:
        self.now = now

    def time(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _snapshot(root: Path) -> dict[str, int]:
    snap: dict[str, int] = {}
    if not root.exists():
        return snap
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        name = path.name
        if name.endswith(".tmp") or name.startswith(".") or name == ".DS_Store":
            continue
        snap[str(path)] = path.stat().st_mtime_ns
    return snap


def _watch(
    root: Path,
    clock: ManualClock,
    run,
    *,
    error_message: str = "process failed during watch",
) -> DebouncedWatch:
    return DebouncedWatch(
        clock=clock.time,
        debounce_s=2.0,
        snapshot=lambda: _snapshot(root),
        run=run,
        use_timer=False,
        error_message=error_message,
    )


def _fire_initial(sched: DebouncedWatch, clock: ManualClock, root: Path) -> None:
    (root / "trigger.txt").write_text("trigger", encoding="utf-8")
    sched.turn()
    clock.advance(2.0)
    sched.turn()


def _past_window(sched: DebouncedWatch, clock: ManualClock) -> None:
    clock.advance(sched.ignore_until - clock.time())
    sched.turn()


def test_create_while_processing_runs_once_after_ignore(tmp_path: Path) -> None:
    clock = ManualClock()
    calls: list[float] = []
    own = tmp_path / "own.txt"
    user = tmp_path / "user.txt"
    holder: dict[str, DebouncedWatch] = {}

    def run() -> None:
        calls.append(clock.time())
        own.write_text(f"own-{len(calls)}", encoding="utf-8")
        if len(calls) == 1:
            assert holder["sched"].processing is True
            # Freeze the run's own writes, then create a different file
            # while processing is still true. That create is not in the delta.
            holder["sched"].capture_self_writes()
            user.write_text("user", encoding="utf-8")
            assert holder["sched"].processing is True

    sched = _watch(tmp_path, clock, run)
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    sched.turn()
    assert len(calls) == 1

    _past_window(sched, clock)
    assert len(calls) == 2

    clock.advance(10.0)
    sched.turn()
    clock.advance(10.0)
    sched.turn()
    assert len(calls) == 2


def test_create_during_ignore_window_runs_once_after_it(tmp_path: Path) -> None:
    clock = ManualClock()
    calls: list[float] = []
    own = tmp_path / "own.txt"
    user = tmp_path / "user.txt"
    holder: dict[str, DebouncedWatch] = {}

    def run() -> None:
        calls.append(clock.time())
        own.write_text(f"own-{len(calls)}", encoding="utf-8")
        holder["sched"].note({str(own)})

    sched = _watch(tmp_path, clock, run)
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1
    assert sched.processing is False
    assert clock.time() < sched.ignore_until

    user.write_text("during-ignore", encoding="utf-8")
    sched.turn()
    sched.turn()
    assert len(calls) == 1

    _past_window(sched, clock)
    assert len(calls) == 2

    clock.advance(30.0)
    sched.turn()
    sched.turn()
    assert len(calls) == 2


def test_handler_event_during_run_follows_up_once(tmp_path: Path) -> None:
    """A user path reported the way the production handler reports events.

    ``note({own})`` stays an echo. The user file must not go through ``note``,
    or finish treats it as the run's own write.
    """
    clock = ManualClock()
    calls: list[float] = []
    own = tmp_path / "own.txt"
    user = tmp_path / "user.txt"
    holder: dict[str, DebouncedWatch] = {}

    def run() -> None:
        calls.append(clock.time())
        own.write_text(f"own-{len(calls)}", encoding="utf-8")
        holder["sched"].note({str(own)})
        if len(calls) == 1:
            assert holder["sched"].processing is True
            user.write_text("user", encoding="utf-8")
            holder["sched"].observe_event({str(user)})

    sched = _watch(tmp_path, clock, run)
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    _past_window(sched, clock)
    assert len(calls) == 2

    clock.advance(30.0)
    sched.turn()
    clock.advance(30.0)
    sched.turn()
    assert len(calls) == 2


def test_self_write_alone_does_not_schedule_follow_up(tmp_path: Path) -> None:
    clock = ManualClock()
    calls: list[float] = []
    own = tmp_path / "own.txt"
    holder: dict[str, DebouncedWatch] = {}

    def run() -> None:
        calls.append(clock.time())
        own.write_text(f"own-{len(calls)}", encoding="utf-8")
        holder["sched"].note({str(own)})

    sched = _watch(tmp_path, clock, run)
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    sched.note({str(own)})
    sched.turn()
    _past_window(sched, clock)
    clock.advance(30.0)
    sched.turn()
    sched.turn()
    assert len(calls) == 1


def test_para_user_file_during_index_schedules_one_later_index(tmp_path: Path) -> None:
    clock = ManualClock()
    calls: list[float] = []
    area = tmp_path / "30-Knowledge"
    area.mkdir()
    own = area / "index-own.md"
    user = area / "user-note.md"
    holder: dict[str, DebouncedWatch] = {}

    def index() -> None:
        calls.append(clock.time())
        own.write_text(f"idx-{len(calls)}", encoding="utf-8")
        if len(calls) == 1:
            assert holder["sched"].processing is True
            holder["sched"].capture_self_writes()
            user.write_text("note", encoding="utf-8")

    sched = _watch(tmp_path, clock, index, error_message="index update failed during watch")
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    _past_window(sched, clock)
    assert len(calls) == 2

    clock.advance(30.0)
    sched.turn()
    sched.turn()
    assert len(calls) == 2


def test_para_onchange_without_path_follows_user_file_only(tmp_path: Path) -> None:
    """PARA ``on_change()`` has no path. The noted own file is not a second index."""
    clock = ManualClock()
    calls: list[float] = []
    area = tmp_path / "30-Knowledge"
    area.mkdir()
    own = area / "index-own.md"
    user = area / "user-note.md"
    holder: dict[str, DebouncedWatch] = {}

    def index() -> None:
        calls.append(clock.time())
        own.write_text(f"idx-{len(calls)}", encoding="utf-8")
        holder["sched"].note({str(own)})
        if len(calls) == 1:
            assert holder["sched"].processing is True
            user.write_text("note", encoding="utf-8")
            holder["sched"].note()

    sched = _watch(tmp_path, clock, index, error_message="index update failed during watch")
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    _past_window(sched, clock)
    assert len(calls) == 2

    clock.advance(30.0)
    sched.turn()
    clock.advance(30.0)
    sched.turn()
    assert len(calls) == 2


def test_observe_event_rewrite_does_not_follow_up(tmp_path: Path) -> None:
    clock = ManualClock()
    calls: list[float] = []
    existing = tmp_path / "existing.txt"
    existing.write_text("seed", encoding="utf-8")
    holder: dict[str, DebouncedWatch] = {}

    def run() -> None:
        calls.append(clock.time())
        existing.write_text(f"rewritten-{len(calls)}", encoding="utf-8")
        holder["sched"].observe_event({str(existing)})

    sched = _watch(tmp_path, clock, run)
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    _past_window(sched, clock)
    clock.advance(30.0)
    sched.turn()
    sched.turn()
    assert len(calls) == 1


def test_para_self_write_does_not_schedule_another_index(tmp_path: Path) -> None:
    clock = ManualClock()
    calls: list[float] = []
    area = tmp_path / "30-Knowledge"
    area.mkdir()
    own = area / "index-own.md"
    holder: dict[str, DebouncedWatch] = {}

    def index() -> None:
        calls.append(clock.time())
        own.write_text(f"idx-{len(calls)}", encoding="utf-8")
        holder["sched"].note({str(own)})

    sched = _watch(tmp_path, clock, index, error_message="index update failed during watch")
    holder["sched"] = sched
    _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1

    sched.note({str(own)})
    _past_window(sched, clock)
    clock.advance(30.0)
    sched.turn()
    assert len(calls) == 1


def test_failed_process_still_follows_up_for_unexplained_create(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    clock = ManualClock()
    calls: list[int] = []
    own = tmp_path / "own.txt"
    user = tmp_path / "user.txt"
    holder: dict[str, DebouncedWatch] = {}

    def run() -> None:
        calls.append(len(calls) + 1)
        own.write_text(f"own-{len(calls)}", encoding="utf-8")
        if len(calls) == 1:
            holder["sched"].capture_self_writes()
            user.write_text("user", encoding="utf-8")
            raise RuntimeError("process blew up")

    sched = _watch(tmp_path, clock, run)
    holder["sched"] = sched
    with caplog.at_level("ERROR"):
        _fire_initial(sched, clock, tmp_path)
    assert len(calls) == 1
    assert any("process failed during watch" in rec.message for rec in caplog.records)

    _past_window(sched, clock)
    assert len(calls) == 2
    clock.advance(30.0)
    sched.turn()
    assert len(calls) == 2


def _block_watchdog(monkeypatch: pytest.MonkeyPatch) -> None:
    real_import = builtins.__import__

    def _guarded(name, globals=None, locals=None, fromlist=(), level=0):  # noqa: ANN001
        if name == "watchdog" or name.startswith("watchdog."):
            raise ImportError("watchdog blocked")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", _guarded)


def _drive_poller(
    root: Path,
    *,
    on_first_sleep: Callable[[], None] | None = None,
    on_after_first_run: Callable[[], None] | None = None,
    process_fn: Callable[[], None] | None = None,
    index_fn: Callable[[], None] | None = None,
    calls: list[float],
    clock: ManualClock,
    prove_no_third: bool = False,
) -> None:
    """Pump ``run_watch``'s poller until two runs have happened or the step cap hits."""
    state = {"sleeps": 0, "triggered": False, "created": False, "after_second": 0}

    def sleeper(seconds: float) -> None:
        state["sleeps"] += 1
        if state["sleeps"] > 40:
            raise KeyboardInterrupt
        if not state["triggered"] and on_first_sleep is not None:
            on_first_sleep()
            state["triggered"] = True
        elif len(calls) == 1 and not state["created"] and on_after_first_run is not None:
            on_after_first_run()
            state["created"] = True
        if len(calls) >= 2 and prove_no_third:
            # The follow-up's own rewrite must not schedule another run.
            state["after_second"] += 1
            clock.advance(30.0)
            if state["after_second"] >= 2:
                raise KeyboardInterrupt
            return
        clock.advance(seconds)
        if len(calls) >= 2:
            raise KeyboardInterrupt

    run_watch(
        root,
        debounce_s=2.0,
        poll_fallback_s=5.0,
        clock=clock.time,
        sleeper=sleeper,
        process_fn=process_fn,
        index_fn=index_fn,
    )


def test_poller_does_not_swallow_create_during_ignore(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _block_watchdog(monkeypatch)
    clock = ManualClock()
    calls: list[float] = []
    capture = tmp_path / "_capture"
    own = capture / "own.txt"
    user = capture / "user.txt"

    def process() -> None:
        calls.append(clock.time())
        own.write_text(f"own-{len(calls)}", encoding="utf-8")

    def create_trigger() -> None:
        (capture / "trigger.txt").write_text("trigger", encoding="utf-8")

    def create_user() -> None:
        user.write_text("user", encoding="utf-8")

    _drive_poller(
        tmp_path,
        on_first_sleep=create_trigger,
        on_after_first_run=create_user,
        process_fn=process,
        index_fn=lambda: None,
        calls=calls,
        clock=clock,
    )
    assert len(calls) == 2


def test_poller_user_create_during_process_is_followed_up_once(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Production _process never calls capture_self_writes. A different file
    # created before process returns is not the run's own write: one follow-up
    # after the ignore window, and the poller must not adopt it into the baseline.
    _block_watchdog(monkeypatch)
    clock = ManualClock()
    calls: list[float] = []
    capture = tmp_path / "_capture"
    own = capture / "own.txt"
    user = capture / "user.txt"

    def process() -> None:
        calls.append(clock.time())
        own.write_text(f"own-{len(calls)}", encoding="utf-8")
        if len(calls) == 1:
            user.write_text("user", encoding="utf-8")

    def create_trigger() -> None:
        (capture / "trigger.txt").write_text("trigger", encoding="utf-8")

    _drive_poller(
        tmp_path,
        on_first_sleep=create_trigger,
        process_fn=process,
        index_fn=lambda: None,
        calls=calls,
        clock=clock,
        prove_no_third=True,
    )
    assert len(calls) == 2


def test_poller_para_ignore_window_schedules_one_index(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _block_watchdog(monkeypatch)
    clock = ManualClock()
    calls: list[float] = []
    area = tmp_path / "30-Knowledge"
    own = area / "index-own.md"
    user = area / "user-note.md"

    def index() -> None:
        calls.append(clock.time())
        own.write_text(f"idx-{len(calls)}", encoding="utf-8")

    def create_trigger() -> None:
        (area / "trigger.md").write_text("trigger", encoding="utf-8")

    def create_user() -> None:
        user.write_text("note", encoding="utf-8")

    _drive_poller(
        tmp_path,
        on_first_sleep=create_trigger,
        on_after_first_run=create_user,
        process_fn=lambda: None,
        index_fn=index,
        calls=calls,
        clock=clock,
    )
    assert len(calls) == 2
