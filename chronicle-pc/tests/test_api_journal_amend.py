"""GET /journal/days, GET/PATCH /journal/entries/{id} — fence browse + hash-gated amend."""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from chronicle_pipeline.entries import load_entry
from chronicle_pipeline.journal import file_entry
from chronicle_pipeline.models import Entry
from chronicle_pipeline.serve import TOKEN_HEADER, create_app


def _client(chronicle_dir: Path, **connect_info) -> TestClient:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765", **connect_info})
    return TestClient(app)


def _file_an_entry(chronicle_dir: Path, *, text: str = "hello journal") -> Entry:
    entry = Entry(
        id="2026-07-09_213045-pc",
        ts="2026-07-09T21:30:45+05:30",
        type="log",
        text=text,
        processed=True,
    )
    from chronicle_pipeline.entries import save_entry

    save_entry(chronicle_dir, entry)
    file_entry(chronicle_dir, entry, dry_run=False)
    return entry


def test_journal_days_excludes_non_day_files(chronicle_dir: Path) -> None:
    _file_an_entry(chronicle_dir)
    # A nested agent guide living alongside day files must never show up as a "day".
    (chronicle_dir / "40-Journal" / "CLAUDE.md").write_text("# guide\n", encoding="utf-8")

    client = _client(chronicle_dir)
    r = client.get("/journal/days")
    assert r.status_code == 200
    dates = [d["date"] for d in r.json()["days"]]
    assert dates == ["2026-07-09"]


def test_journal_entry_get_and_amend_happy_path(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    client = _client(chronicle_dir)

    got = client.get(f"/journal/entries/{entry.id}")
    assert got.status_code == 200
    body = got.json()
    assert body["editable"] is True
    assert "hello journal" in body["body"]
    base_hash = body["body_hash"]

    amended = client.patch(
        f"/journal/entries/{entry.id}",
        json={"body": "amended prose body", "base_hash": base_hash},
    )
    assert amended.status_code == 200
    result = amended.json()
    assert result["prose_edited"] is True
    assert result["hash"] != base_hash

    on_disk = load_entry(
        chronicle_dir / "_capture" / "entries" / "2026" / "07" / f"{entry.id}.json"
    )
    assert on_disk is not None
    assert on_disk.prose_edited is True
    assert on_disk.filed_content_hash == result["hash"]

    day_text = (chronicle_dir / "40-Journal" / "2026-07-09.md").read_text(encoding="utf-8")
    assert "amended prose body" in day_text


def test_journal_amend_conflict_on_stale_hash(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    client = _client(chronicle_dir)

    stale = client.patch(
        f"/journal/entries/{entry.id}",
        json={"body": "should not apply", "base_hash": "a" * 64},
    )
    assert stale.status_code == 409
    detail = stale.json()["detail"]
    assert detail["on_disk_hash"]
    assert detail["filed_content_hash"]

    day_text = (chronicle_dir / "40-Journal" / "2026-07-09.md").read_text(encoding="utf-8")
    assert "should not apply" not in day_text


def test_journal_amend_conflict_after_external_edit(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    client = _client(chronicle_dir)
    base_hash = client.get(f"/journal/entries/{entry.id}").json()["body_hash"]

    # Simulate an external (Obsidian) edit landing on disk after the client fetched its base_hash.
    day_path = chronicle_dir / "40-Journal" / "2026-07-09.md"
    text = day_path.read_text(encoding="utf-8")
    day_path.write_text(text.replace("hello journal", "obsidian edit"), encoding="utf-8")

    r = client.patch(
        f"/journal/entries/{entry.id}",
        json={"body": "client draft", "base_hash": base_hash},
    )
    assert r.status_code == 409

    refreshed = client.get(f"/journal/entries/{entry.id}")
    assert refreshed.json()["editable"] is False


def test_journal_entry_404_when_not_filed(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.get("/journal/entries/2026-07-09_999999-pc")
    assert r.status_code == 404


def test_journal_amend_requires_token_when_lan_bound(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    client = _client(
        chronicle_dir,
        bind_host="0.0.0.0",
        token="secret-token",
        auth_required=True,
    )
    # Vault GETs require the pairing token when LAN-bound.
    denied_get = client.get(f"/journal/entries/{entry.id}")
    assert denied_get.status_code == 401

    got = client.get(
        f"/journal/entries/{entry.id}",
        headers={TOKEN_HEADER: "secret-token"},
    )
    assert got.status_code == 200
    base_hash = got.json()["body_hash"]

    unauthed = client.patch(
        f"/journal/entries/{entry.id}",
        json={"body": "x", "base_hash": base_hash},
    )
    assert unauthed.status_code == 401

    authed = client.patch(
        f"/journal/entries/{entry.id}",
        json={"body": "x", "base_hash": base_hash},
        headers={TOKEN_HEADER: "secret-token"},
    )
    assert authed.status_code == 200


# --- filed_path is pipeline-authored (40-Journal/YYYY-MM-DD.md). Entry JSON is
# user-editable and mirrored from phones, so a hostile filed_path must never
# reach `root / rel` file I/O. Regression: GET/PATCH used the raw field. ---
def _corrupt_filed_path(chronicle_dir: Path, entry_id: str, bad_rel: str) -> None:
    path = chronicle_dir / "_capture" / "entries" / "2026" / "07" / f"{entry_id}.json"
    raw = __import__("json").loads(path.read_text(encoding="utf-8"))
    raw["filed"] = True
    raw["filed_path"] = bad_rel
    path.write_text(__import__("json").dumps(raw), encoding="utf-8")


def test_get_journal_entry_rejects_traversal_filed_path(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    secret = chronicle_dir / "secret.txt"
    secret.write_text(
        f"fence marker <!-- entry:{entry.id} --> here", encoding="utf-8"
    )
    _corrupt_filed_path(chronicle_dir, entry.id, "../secret.txt")

    client = _client(chronicle_dir)
    r = client.get(f"/journal/entries/{entry.id}")
    assert r.status_code == 404
    assert "invalid filed_path" in r.text


def test_amend_journal_entry_rejects_traversal_filed_path(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    _corrupt_filed_path(chronicle_dir, entry.id, "../../etc/passwd")

    client = _client(chronicle_dir)
    r = client.patch(
        f"/journal/entries/{entry.id}",
        json={"body": "owned", "base_hash": "0" * 64},
    )
    assert r.status_code == 404


def test_amend_rejects_absolute_and_para_escape_filed_paths(chronicle_dir: Path) -> None:
    entry = _file_an_entry(chronicle_dir)
    for bad in ("/etc/passwd", "00-Inbox/2026-07-09.md", "40-Journal/../../config.json"):
        _corrupt_filed_path(chronicle_dir, entry.id, bad)
        client = _client(chronicle_dir)
        assert client.patch(
            f"/journal/entries/{entry.id}",
            json={"body": "x", "base_hash": "0" * 64},
        ).status_code == 404, bad


def test_validate_filed_rel_accepts_only_canonical_day_files() -> None:
    from chronicle_pipeline.journal import validate_filed_rel

    assert validate_filed_rel("40-Journal/2026-07-09.md", "e1") == "40-Journal/2026-07-09.md"
    for bad in (None, "", "00-Inbox/2026-07-09.md", "40-Journal/note.md",
                "40-Journal/2026-7-9.md", "../secret.txt", "/etc/passwd"):
        with pytest.raises(ValueError):
            validate_filed_rel(bad, "e1")


def test_doctor_flags_invalid_filed_path_instead_of_reading_it(
    chronicle_dir: Path,
) -> None:
    from chronicle_pipeline.entries import load_all_entries
    from chronicle_pipeline.journal import detect_journal_hash_mismatches

    entry = _file_an_entry(chronicle_dir)
    outside = chronicle_dir / "outside.md"
    outside.write_text("unrelated prose", encoding="utf-8")
    _corrupt_filed_path(chronicle_dir, entry.id, "../outside.md")

    entries = load_all_entries(chronicle_dir)
    issues = detect_journal_hash_mismatches(chronicle_dir, entries)
    assert any(i.get("issue") == "invalid_filed_path" for i in issues)
