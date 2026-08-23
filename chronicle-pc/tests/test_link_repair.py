"""Unit + API tests for wikilink / markdown link repair on move/archive."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.link_repair import (
    repair_links_after_move,
    rewrite_content,
)
from chronicle_pipeline.serve import create_app


def _kb_client(root: Path) -> TestClient:
    app = create_app(root, connect_info={"base": "http://127.0.0.1:8765"})
    return TestClient(app)


def _seed_config(root: Path) -> None:
    (root / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}',
        encoding="utf-8",
    )


# --- rewrite_content ---------------------------------------------------------


def test_rewrite_basename_wikilink() -> None:
    text = "See [[Old Title]] and [[Old Title|label]] plus ![[Old Title#sec]].\n"
    out, n = rewrite_content(
        text, "30-Knowledge/Old Title.md", "10-Work/New Title.md"
    )
    assert n == 3
    assert "[[New Title]]" in out
    assert "[[New Title|label]]" in out
    assert "![[New Title#sec]]" in out
    assert "Old Title" not in out


def test_rewrite_path_wikilink() -> None:
    text = (
        "Path [[30-Knowledge/Topics/Alpha]] and "
        "[[30-Knowledge/Topics/Alpha.md]] stay path-shaped.\n"
    )
    out, n = rewrite_content(
        text,
        "30-Knowledge/Topics/Alpha.md",
        "10-Work/Projects/Alpha.md",
    )
    assert n == 2
    assert "[[10-Work/Projects/Alpha]]" in out
    assert "[[10-Work/Projects/Alpha.md]]" in out
    assert "30-Knowledge" not in out


def test_rewrite_markdown_link_to_old_path() -> None:
    text = "Read [Alpha](30-Knowledge/Topics/Alpha.md) and [enc](30-Knowledge/Topics/Alpha.md).\n"
    out, n = rewrite_content(
        text,
        "30-Knowledge/Topics/Alpha.md",
        "90-Archive/Topics/Alpha.md",
    )
    assert n == 2
    assert out.count("90-Archive/Topics/Alpha.md") == 2


def test_rewrite_skips_unrelated_links() -> None:
    text = "[[Other Note]] and [x](30-Knowledge/Other.md)\n"
    out, n = rewrite_content(text, "30-Knowledge/Alpha.md", "10-Work/Alpha.md")
    assert n == 0
    assert out == text


# --- repair_links_after_move (fixtures) --------------------------------------


def test_repair_basename_and_path_across_vault(tmp_path: Path) -> None:
    """Basename + path wikilinks in other notes are rewritten; changelog logged."""
    (tmp_path / "30-Knowledge" / "Topics").mkdir(parents=True)
    (tmp_path / "10-Work").mkdir(parents=True)
    (tmp_path / "00-Inbox").mkdir(parents=True)

    target = tmp_path / "30-Knowledge" / "Topics" / "Widget.md"
    target.write_text("# Widget\n", encoding="utf-8")

    hub = tmp_path / "00-Inbox" / "hub.md"
    hub.write_text(
        "Basename [[Widget]] and path [[30-Knowledge/Topics/Widget]].\n"
        "Also [md](30-Knowledge/Topics/Widget.md).\n",
        encoding="utf-8",
    )
    moc = tmp_path / "30-Knowledge" / "MOC-Knowledge.md"
    moc.write_text("- [[Widget]]\n", encoding="utf-8")

    # Simulate move already done on disk
    new_path = tmp_path / "10-Work" / "Widget.md"
    new_path.write_text(target.read_text(encoding="utf-8"), encoding="utf-8")
    target.unlink()

    result = repair_links_after_move(
        tmp_path,
        "30-Knowledge/Topics/Widget.md",
        "10-Work/Widget.md",
    )
    assert result.replacements >= 3
    assert result.files_updated >= 1
    assert result.changelog_appended is True

    hub_text = hub.read_text(encoding="utf-8")
    assert "[[Widget]]" in hub_text  # basename unchanged (same stem)
    assert "[[10-Work/Widget]]" in hub_text
    assert "30-Knowledge/Topics/Widget" not in hub_text
    assert "[md](10-Work/Widget.md)" in hub_text

    changelog = (tmp_path / "_system" / "changelog.md").read_text(encoding="utf-8")
    assert "30-Knowledge/Topics/Widget.md → 10-Work/Widget.md" in changelog


def test_repair_on_rename_updates_basename(tmp_path: Path) -> None:
    (tmp_path / "30-Knowledge").mkdir(parents=True)
    (tmp_path / "30-Knowledge" / "linker.md").write_text(
        "See [[Old Name]] and [[30-Knowledge/Old Name]].\n",
        encoding="utf-8",
    )
    (tmp_path / "30-Knowledge" / "New Name.md").write_text("# New Name\n", encoding="utf-8")

    result = repair_links_after_move(
        tmp_path,
        "30-Knowledge/Old Name.md",
        "30-Knowledge/New Name.md",
    )
    assert result.replacements == 2
    text = (tmp_path / "30-Knowledge" / "linker.md").read_text(encoding="utf-8")
    assert "[[New Name]]" in text
    assert "[[30-Knowledge/New Name]]" in text
    assert "Old Name" not in text


def test_repair_archive_under_90_archive(tmp_path: Path) -> None:
    """Archive destination `90-Archive/<suffix>` rewrites inbound links."""
    (tmp_path / "10-Work" / "Projects").mkdir(parents=True)
    (tmp_path / "30-Knowledge").mkdir(parents=True)
    (tmp_path / "90-Archive").mkdir(parents=True)

    (tmp_path / "30-Knowledge" / "refs.md").write_text(
        "Project [[Ship It]] / [[10-Work/Projects/Ship It]] / "
        "[link](10-Work/Projects/Ship%20It.md).\n",
        encoding="utf-8",
    )
    archived = tmp_path / "90-Archive" / "Projects" / "Ship It.md"
    archived.parent.mkdir(parents=True)
    archived.write_text("# Ship It\n", encoding="utf-8")

    result = repair_links_after_move(
        tmp_path,
        "10-Work/Projects/Ship It.md",
        "90-Archive/Projects/Ship It.md",
    )
    assert result.replacements >= 2
    refs = (tmp_path / "30-Knowledge" / "refs.md").read_text(encoding="utf-8")
    assert "[[Ship It]]" in refs  # basename unchanged
    assert "[[90-Archive/Projects/Ship It]]" in refs
    assert "10-Work/Projects/Ship It" not in refs
    assert "90-Archive/Projects/Ship It.md" in refs

    changelog = (tmp_path / "_system" / "changelog.md").read_text(encoding="utf-8")
    assert "90-Archive/Projects/Ship It.md" in changelog


# --- API move / archive ------------------------------------------------------


def test_api_move_repairs_links_and_changelogs(tmp_path: Path) -> None:
    _seed_config(tmp_path)
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "10-Work").mkdir(parents=True)
    (tmp_path / "30-Knowledge").mkdir(parents=True)

    (tmp_path / "00-Inbox" / "filed.md").write_text("# filed\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "pointer.md").write_text(
        "Go [[filed]] and [[00-Inbox/filed]] and [here](00-Inbox/filed.md).\n",
        encoding="utf-8",
    )

    client = _kb_client(tmp_path)
    moved = client.post(
        "/kb/move",
        json={"from_path": "00-Inbox/filed.md", "to_path": "10-Work/filed.md"},
    )
    assert moved.status_code == 200
    body = moved.json()
    assert body["to_path"] == "10-Work/filed.md"
    assert body["links_repaired"] >= 2
    assert body["changelog_appended"] is True
    assert (tmp_path / "10-Work" / "filed.md").is_file()
    assert not (tmp_path / "00-Inbox" / "filed.md").exists()

    pointer = (tmp_path / "30-Knowledge" / "pointer.md").read_text(encoding="utf-8")
    assert "[[filed]]" in pointer
    assert "[[10-Work/filed]]" in pointer
    assert "[here](10-Work/filed.md)" in pointer
    assert "00-Inbox/filed" not in pointer

    log = (tmp_path / "_system" / "changelog.md").read_text(encoding="utf-8")
    assert "00-Inbox/filed.md → 10-Work/filed.md" in log


def test_api_archive_repairs_links(tmp_path: Path) -> None:
    _seed_config(tmp_path)
    (tmp_path / "10-Work").mkdir(parents=True)
    (tmp_path / "20-Personal").mkdir(parents=True)

    (tmp_path / "10-Work" / "done.md").write_text("# done\n", encoding="utf-8")
    (tmp_path / "20-Personal" / "see.md").write_text(
        "Was [[10-Work/done]] — [md](10-Work/done.md).\n",
        encoding="utf-8",
    )

    client = _kb_client(tmp_path)
    archived = client.post("/kb/archive", json={"path": "10-Work/done.md"})
    assert archived.status_code == 200
    body = archived.json()
    assert body["to_path"] == "90-Archive/done.md"
    assert body["links_repaired"] >= 2
    assert (tmp_path / "90-Archive" / "done.md").is_file()

    see = (tmp_path / "20-Personal" / "see.md").read_text(encoding="utf-8")
    assert "[[90-Archive/done]]" in see
    assert "[md](90-Archive/done.md)" in see
    assert "10-Work/done" not in see
