"""Upcoming.md: task scan, month grouping, overdue, idempotent rewrite, checked boxes drop."""

from __future__ import annotations

from datetime import date
from pathlib import Path

from chronicle_pipeline.upcoming import find_tasks, regenerate_upcoming, render_upcoming


def _note(root: Path, rel: str, body: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(body, encoding="utf-8")


def test_find_tasks_scans_unchecked_dated_lines(tmp_path: Path) -> None:
    _note(
        tmp_path,
        "00-Inbox/idea.md",
        "# Idea\n\n- [ ] Renew passport 📅 2027-03-01\n- [x] Done thing 📅 2020-01-01\n- [ ] no date here\n",
    )
    _note(tmp_path, "30-Knowledge/ref.md", "# Ref\n\n- [ ] Read book 📅 2026-08-15\n")

    tasks = find_tasks(tmp_path)
    descs = {t["desc"]: t["date"] for t in tasks}
    assert descs == {"Renew passport": "2027-03-01", "Read book": "2026-08-15"}


def test_render_upcoming_groups_by_month_and_flags_overdue() -> None:
    tasks = [
        {"desc": "A", "date": "2026-06-01", "source_rel": "00-Inbox/a.md", "source_title": "a"},
        {"desc": "B", "date": "2026-08-01", "source_rel": "00-Inbox/b.md", "source_title": "b"},
        {"desc": "C", "date": "2026-08-15", "source_rel": "00-Inbox/c.md", "source_title": "c"},
    ]
    text = render_upcoming(tasks, today=date(2026, 7, 12))
    assert "## Overdue" in text
    assert text.index("## Overdue") < text.index("A 📅 2026-06-01")
    assert "## August 2026" in text
    assert text.index("B 📅 2026-08-01") < text.index("C 📅 2026-08-15")
    assert "[[00-Inbox/a|a]]" in text


def test_render_upcoming_empty_state() -> None:
    text = render_upcoming([], today=date(2026, 7, 12))
    assert "Nothing scheduled" in text
    assert "## Overdue" not in text


def test_regenerate_upcoming_idempotent_and_drops_checked(tmp_path: Path) -> None:
    _note(tmp_path, "00-Inbox/idea.md", "- [ ] Task A 📅 2026-08-01\n")
    today = date(2026, 7, 12)

    wrote_first = regenerate_upcoming(tmp_path, today=today)
    assert wrote_first is True
    content_first = (tmp_path / "Upcoming.md").read_text(encoding="utf-8")
    assert "Task A" in content_first

    wrote_second = regenerate_upcoming(tmp_path, today=today)
    assert wrote_second is False  # unchanged content, write_if_changed short-circuits

    # Checking the box in the source note removes it from Upcoming on next regen.
    _note(tmp_path, "00-Inbox/idea.md", "- [x] Task A 📅 2026-08-01\n")
    regenerate_upcoming(tmp_path, today=today)
    content_after = (tmp_path / "Upcoming.md").read_text(encoding="utf-8")
    assert "Task A" not in content_after
    assert "Nothing scheduled" in content_after
