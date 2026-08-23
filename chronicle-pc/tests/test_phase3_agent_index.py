"""Phase 3: live _system/index.md rebuild + create frontmatter."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.markdown_index import rebuild_markdown_index
from chronicle_pipeline.migrate_v2 import seed_vault_chrome
from chronicle_pipeline.note_frontmatter import ensure_create_frontmatter
from chronicle_pipeline.serve import create_app


def _client(root: Path) -> TestClient:
    app = create_app(root, connect_info={"base": "http://127.0.0.1:8765"})
    return TestClient(app)


def test_rebuild_markdown_index_walks_para_and_journal(tmp_path: Path) -> None:
    (tmp_path / "30-Knowledge").mkdir(parents=True)
    (tmp_path / "30-Knowledge" / "Skills.md").write_text(
        "---\ntitle: Skills\ntype: note\ntags: [python]\nupdated: 2026-07-01\n---\n\n# Skills\n",
        encoding="utf-8",
    )
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "00-Inbox" / "CLAUDE.md").write_text("# chrome\n", encoding="utf-8")
    (tmp_path / "00-Inbox" / "idea.md").write_text("# Loose idea\n", encoding="utf-8")
    day = tmp_path / "40-Journal" / "2026" / "07"
    day.mkdir(parents=True)
    (day / "2026-07-10.md").write_text(
        "---\ntype: journal\nupdated: 2026-07-10\n---\n\n# 2026-07-10\n",
        encoding="utf-8",
    )

    result = rebuild_markdown_index(tmp_path, dry_run=False)
    assert result["ok"] is True
    assert result["rows"] == 3  # Skills, idea, journal day — not CLAUDE.md

    text = (tmp_path / "_system" / "index.md").read_text(encoding="utf-8")
    assert "Skills | note | python | 2026-07-01" in text
    assert "Loose idea | note |" in text
    assert "2026-07-10 | journal |" in text
    assert "rebuild-markdown-index" in text
    assert "CLAUDE" not in text.split("Format:")[-1]


def test_ensure_create_frontmatter_fills_defaults() -> None:
    out = ensure_create_frontmatter("# Hello\n", title="Hello", today="2026-07-12")
    assert "title: Hello" in out
    assert "created: 2026-07-12" in out
    assert "updated: 2026-07-12" in out
    assert "type: note" in out
    assert "tags: []" in out
    assert "# Hello" in out


def test_ensure_create_frontmatter_preserves_existing() -> None:
    src = "---\ntitle: Keep\ntype: project\ncreated: 2020-01-01\n---\n\nbody\n"
    out = ensure_create_frontmatter(src, title="Ignored", today="2026-07-12")
    assert "title: Keep" in out
    assert "type: project" in out
    assert "created: 2020-01-01" in out
    assert "updated: 2026-07-12" in out


def test_post_kb_notes_applies_create_frontmatter(tmp_path: Path) -> None:
    (tmp_path / "00-Inbox").mkdir(parents=True)
    client = _client(tmp_path)
    r = client.post(
        "/kb/notes/00-Inbox/fresh.md",
        json={"content": "# Fresh\n"},
    )
    assert r.status_code == 201
    content = r.json()["content"]
    assert "created:" in content
    assert "updated:" in content
    assert "type: note" in content
    assert "title: fresh" in content or "title: Fresh" in content


def test_vault_rebuild_index_endpoint(tmp_path: Path) -> None:
    (tmp_path / "30-Knowledge").mkdir(parents=True)
    (tmp_path / "30-Knowledge" / "A.md").write_text(
        "---\ntitle: A\ntype: note\ntags: []\nupdated: 2026-06-01\n---\n\n# A\n",
        encoding="utf-8",
    )
    client = _client(tmp_path)
    r = client.post("/vault/rebuild-index", json={})
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["markdown_index"]["rows"] == 1
    assert (tmp_path / "_system" / "index.md").is_file()


def test_seed_refresh_skills_overwrites_dual_read_text(tmp_path: Path) -> None:
    seed_vault_chrome(tmp_path, dry_run=False)
    skill = tmp_path / ".claude" / "skills" / "retrieval-format" / "SKILL.md"
    skill.write_text("legacy kb/notes dual-read stale\n", encoding="utf-8")

    again = seed_vault_chrome(tmp_path, dry_run=False, refresh_skills=False)
    assert again == []
    assert "dual-read stale" in skill.read_text(encoding="utf-8")

    refreshed = seed_vault_chrome(tmp_path, dry_run=False, refresh_skills=True)
    assert ".claude/skills/retrieval-format/SKILL.md" in refreshed
    text = skill.read_text(encoding="utf-8")
    assert "rebuild-markdown-index" in text
    assert "legacy kb/notes/" not in text or "do not write under legacy" in text.lower()
    # PARA-only guidance; no "Prefer PARA when dual-read"
    assert "Prefer PARA when dual-read" not in text
