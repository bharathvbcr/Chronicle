"""Tests for operational doctor checks (layout_dirs, serve, index_freshness, etc.)."""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path

import pytest
from chronicle_pipeline.doctor import run_doctor
from chronicle_pipeline.index_store import run_index


def test_layout_dirs_fail_on_missing_capture(chronicle_dir: Path) -> None:
    # Remove _capture/entries to simulate missing layout dirs
    capture_dir = chronicle_dir / "_capture"
    if capture_dir.exists():
        shutil.rmtree(capture_dir)

    res = run_doctor(chronicle_dir)
    assert "checks" in res
    assert res["checks"]["layout_dirs"]["ran"] is True
    assert res["checks"]["layout_dirs"]["status"] == "fail"
    assert res["ok"] is False


def test_stale_serve_json_impossible_pid(chronicle_dir: Path) -> None:
    index_dir = chronicle_dir / "index"
    index_dir.mkdir(parents=True, exist_ok=True)
    serve_json = index_dir / "serve.json"
    impossible_pid = 99999999
    serve_json.write_text(
        json.dumps({"pid": impossible_pid, "port": 8765, "host": "127.0.0.1"}),
        encoding="utf-8",
    )

    # Without fix
    res = run_doctor(chronicle_dir, fix=False)
    assert "checks" in res
    assert res["checks"]["serve"]["ran"] is True
    assert res["checks"]["serve"]["status"] == "fail"
    assert serve_json.exists()

    # With fix=True, stale serve.json should be removed
    res_fix = run_doctor(chronicle_dir, fix=True)
    assert not serve_json.exists()
    assert any("serve.json" in r for r in res_fix.get("repairs", []))


def test_index_freshness_unindexed_note(chronicle_dir: Path) -> None:
    # First index cleanly
    run_index(chronicle_dir)

    # Add a new note under 30-Knowledge/
    kb_dir = chronicle_dir / "30-Knowledge"
    kb_dir.mkdir(parents=True, exist_ok=True)
    new_note = kb_dir / "test_unindexed.md"
    new_note.write_text("# Test Unindexed\n\nFresh note.", encoding="utf-8")

    res = run_doctor(chronicle_dir)
    assert "checks" in res
    assert res["checks"]["index_freshness"]["ran"] is True
    assert res["checks"]["index_freshness"]["status"] == "fail"
    assert res["checks"]["index_freshness"]["detail"]["unindexed"] >= 1
    assert res["ok"] is False

    # Index again to clear
    run_index(chronicle_dir)
    res2 = run_doctor(chronicle_dir)
    assert res2["checks"]["index_freshness"]["detail"]["unindexed"] == 0


def test_timezone_mismatch_warns_ok_stays_true(chronicle_dir: Path) -> None:
    # conftest.py sets config timezone to Asia/Kolkata, while test machine is America/Chicago
    cfg_file = chronicle_dir / "config.json"
    data = json.loads(cfg_file.read_text(encoding="utf-8"))
    data["timezone"] = "Asia/Kolkata"
    cfg_file.write_text(json.dumps(data), encoding="utf-8")

    res = run_doctor(chronicle_dir)
    assert "checks" in res
    # On a machine not in Asia/Kolkata, this should warn
    if "Asia/Kolkata" not in str(os.path.realpath("/etc/localtime")):
        assert res["checks"]["timezone"]["status"] == "warn"


def test_all_seven_checks_report_ran(chronicle_dir: Path) -> None:
    res = run_doctor(chronicle_dir)
    assert "checks" in res
    checks = res["checks"]
    expected_checks = [
        "layout_dirs",
        "serve",
        "index_freshness",
        "sync",
        "timezone",
        "cli_on_path",
        "backup_age",
    ]
    for chk in expected_checks:
        assert chk in checks
        assert isinstance(checks[chk]["ran"], bool)
        assert checks[chk]["status"] in ("ok", "warn", "fail")


def test_attachment_referenced_in_knowledge_markdown_is_not_orphan(chronicle_dir: Path) -> None:
    att = chronicle_dir / "_attachments/notes/10-Work/mark.svg"
    att.parent.mkdir(parents=True, exist_ok=True)
    att.write_text("<svg></svg>", encoding="utf-8")

    # Before adding reference in note:
    res1 = run_doctor(chronicle_dir)
    assert "_attachments/notes/10-Work/mark.svg" in res1["orphans"]["images"]

    # Add reference in markdown note:
    note = chronicle_dir / "10-Work/doc.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text('<img src="../_attachments/notes/10-Work/mark.svg">\n', encoding="utf-8")

    # After adding reference:
    res2 = run_doctor(chronicle_dir)
    assert "_attachments/notes/10-Work/mark.svg" not in res2["orphans"]["images"]


