"""Test watchdog event routing for PARA areas."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

import pytest
from chronicle_pipeline.watch import ParaHandler


class SyntheticEvent:
    def __init__(self, src_path: str, is_directory: bool = False):
        self.src_path = src_path
        self.is_directory = is_directory


def test_watch_para_trigger_schedules_index_not_process(chronicle_dir: Path) -> None:
    schedule_index = MagicMock()
    schedule_process = MagicMock()

    handler = ParaHandler(chronicle_dir, on_change=schedule_index)

    # Event in 30-Knowledge/
    event = SyntheticEvent(str(chronicle_dir / "30-Knowledge" / "test.md"))
    handler.on_any_event(event)
    assert schedule_index.called
    assert not schedule_process.called


def test_watch_para_trigger_skips_index_and_system(chronicle_dir: Path) -> None:
    schedule_index = MagicMock()
    handler = ParaHandler(chronicle_dir, on_change=schedule_index)

    # Event in index/
    event1 = SyntheticEvent(str(chronicle_dir / "index" / "chronicle.sqlite"))
    handler.on_any_event(event1)
    assert not schedule_index.called

    # Event in _system/
    event2 = SyntheticEvent(str(chronicle_dir / "_system" / "index.md"))
    handler.on_any_event(event2)
    assert not schedule_index.called

    # Event in brain/
    event3 = SyntheticEvent(str(chronicle_dir / "brain" / "graph.json"))
    handler.on_any_event(event3)
    assert not schedule_index.called
