"""Identity of ``vault_fingerprint`` for the phone timeline.

A same-length rewrite of an existing ``40-Journal`` day file, with
``st_mtime_ns`` restored, must change the fingerprint even when ``_capture``
has already exhausted ``CHRONICLE_EVENTS_MAX_FILES``. Suffix plus mtime is
not an identity: two journal files that share a suffix stay distinct by
vault-relative path and content hash.

``_capture`` and ``brain`` files past that budget stay on directory mtime
only. Creates and renames still move the parent directory mtime, which is
enough for those trees. An equal-length content swap of two over-budget
capture files is an accepted residual — directory mtime does not move, and
this fingerprint does not hash the whole vault to see it.
"""

from __future__ import annotations

import os
from pathlib import Path

from chronicle_pipeline.api.events import vault_fingerprint

_NS = 1_700_000_000_000_000_000


def _pin(path: Path, ns: int) -> None:
    os.utime(path, ns=(ns, ns))


def _snapshot_mtimes(root: Path) -> dict[Path, int]:
    saved: dict[Path, int] = {}
    for dirpath, _dirnames, filenames in os.walk(root):
        current = Path(dirpath)
        saved[current] = current.stat().st_mtime_ns
        for name in filenames:
            child = current / name
            try:
                saved[child] = child.stat().st_mtime_ns
            except OSError:
                continue
    return saved


def _restore_mtimes(saved: dict[Path, int]) -> None:
    for path, ns in saved.items():
        os.utime(path, ns=(ns, ns))


def test_journal_edit_visible_after_capture_budget_with_mtime_restored(
    tmp_path: Path, monkeypatch
) -> None:
    """Spent capture budget must not hide a same-length journal rewrite."""
    monkeypatch.setenv("CHRONICLE_EVENTS_MAX_FILES", "3")
    root = tmp_path / "vault"
    capture = root / "_capture" / "entries" / "2026" / "07"
    capture.mkdir(parents=True)
    for i in range(8):
        (capture / f"2026-07-01_00000{i}-an.json").write_bytes(b"{}")
    config = root / "config.json"
    config.write_text('{"layout_version": 2}\n', encoding="utf-8")
    day = root / "40-Journal" / "2026-07-09.md"
    day.parent.mkdir(parents=True)
    original = b"alpha-journal-body"
    day.write_bytes(original)
    _pin(day, _NS)
    saved = _snapshot_mtimes(root)
    before = vault_fingerprint(root)
    config_st = config.stat()
    assert f"config.json:{config_st.st_mtime_ns}:{config_st.st_size}" in before.split(";")

    rewritten = b"bravo-journal-body"
    assert len(rewritten) == len(original)
    day.write_bytes(rewritten)
    _restore_mtimes(saved)
    assert day.stat().st_mtime_ns == saved[day]
    assert day.stat().st_size == len(original)
    assert vault_fingerprint(root) != before


def test_same_suffix_journal_swap_restored_mtimes_changes_fingerprint(
    tmp_path: Path, monkeypatch
) -> None:
    """Equal mtimes: a content swap is invisible unless the path is in the token."""
    monkeypatch.setenv("CHRONICLE_EVENTS_MAX_FILES", "50")
    root = tmp_path / "vault"
    journal = root / "40-Journal"
    journal.mkdir(parents=True)
    first = journal / "2026-07-01.md"
    second = journal / "2026-07-02.md"
    first.write_bytes(b"left-side-note!!")
    second.write_bytes(b"right-side-note!")
    assert first.stat().st_size == second.stat().st_size
    assert first.suffix == second.suffix == ".md"
    _pin(first, _NS)
    _pin(second, _NS)
    saved = _snapshot_mtimes(root)
    before = vault_fingerprint(root)

    first_bytes = first.read_bytes()
    second_bytes = second.read_bytes()
    first.write_bytes(second_bytes)
    second.write_bytes(first_bytes)
    _restore_mtimes(saved)
    assert first.stat().st_mtime_ns == second.stat().st_mtime_ns == _NS
    assert vault_fingerprint(root) != before


def test_over_budget_capture_create_or_rename_changes_fingerprint(
    tmp_path: Path, monkeypatch
) -> None:
    """Directory mtime still reports a capture create or rename past the budget.

    The new capture file's bytes are not required to appear as a hash.
    """
    monkeypatch.setenv("CHRONICLE_EVENTS_MAX_FILES", "1")
    root = tmp_path / "vault"
    capture = root / "_capture" / "entries" / "2026" / "07"
    capture.mkdir(parents=True)
    for i in range(5):
        (capture / f"c{i}.json").write_bytes(b"{}")
    before = vault_fingerprint(root)

    created = capture / "fresh.json"
    created.write_bytes(b"{}")
    after_create = vault_fingerprint(root)
    assert after_create != before

    renamed = capture / "fresh-renamed.json"
    created.rename(renamed)
    assert vault_fingerprint(root) != after_create
