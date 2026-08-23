"""Phase 1: PARA path map (post cutover) + migrate-v2 dry_run/apply idempotency."""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

from chronicle_pipeline import path_map
from chronicle_pipeline.migrate_v2 import (
    classify_kb_note,
    plan_kb_copies,
    run_migrate_v2,
    seed_vault_chrome,
)


def test_classify_resumepoints() -> None:
    assert classify_kb_note("ResumePoints/foo.md") == "10-Work/ResumePoints/foo.md"


def test_classify_personal_group() -> None:
    text = "---\ngroup: personal\n---\n# x\n"
    assert classify_kb_note("secret.md", text).startswith("20-Personal/")


def test_classify_knowledgemap() -> None:
    assert classify_kb_note("KnowledgeMap.md") == "30-Knowledge/KnowledgeMap.md"


def test_classify_unclear_to_inbox() -> None:
    assert classify_kb_note("zzzz-unknown-xyz.md") == "00-Inbox/zzzz-unknown-xyz.md"


def test_path_map_para_only_no_legacy_read(tmp_path: Path) -> None:
    legacy = tmp_path / "kb" / "notes" / "idea.md"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("# legacy\n", encoding="utf-8")
    para = tmp_path / "00-Inbox" / "idea.md"
    para.parent.mkdir(parents=True)
    para.write_text("# para\n", encoding="utf-8")

    assert path_map.resolve_read(tmp_path, "kb/notes/idea.md") is None
    assert path_map.resolve_read(tmp_path, "00-Inbox/idea.md") == "00-Inbox/idea.md"

    files = path_map.list_knowledge_files(tmp_path)
    assert "00-Inbox/idea.md" in files
    assert "kb/notes/idea.md" not in files


def test_path_map_legacy_not_listed(tmp_path: Path) -> None:
    legacy = tmp_path / "kb" / "notes" / "solo.md"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("# solo\n", encoding="utf-8")
    assert path_map.resolve_read(tmp_path, "solo.md") is None  # bare → 00-Inbox/solo.md missing
    assert "kb/notes/solo.md" not in path_map.list_knowledge_files(tmp_path)


def test_section_for() -> None:
    assert path_map.section_for("30-Knowledge/foo.md") == "kb"
    assert path_map.section_for("kb/notes/foo.md") is None
    assert path_map.section_for("00-Inbox/foo.md") == "notes"
    assert path_map.section_for("10-Work/Projects/foo.md") == "notes"
    assert path_map.section_for("20-Personal/foo.md") == "notes"
    assert path_map.section_for("90-Archive/foo.md") == "notes"
    assert path_map.section_for("40-Journal/2026-07-09.md") is None
    assert path_map.section_for("brain/graph.json") is None


def test_default_create_area() -> None:
    assert path_map.default_create_area("kb") == "30-Knowledge"
    assert path_map.default_create_area("notes") == "00-Inbox"


def test_validate_section() -> None:
    assert path_map.validate_section(None) is None
    assert path_map.validate_section("kb") == "kb"
    assert path_map.validate_section("notes") == "notes"
    with pytest.raises(ValueError):
        path_map.validate_section("journal")
    with pytest.raises(ValueError):
        path_map.validate_section("foo")


def test_chrome_excluded_from_tree_and_list(tmp_path: Path) -> None:
    (tmp_path / "30-Knowledge").mkdir()
    (tmp_path / "30-Knowledge" / "CLAUDE.md").write_text("# guide\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "README.md").write_text("# stub\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / ".gitkeep").write_text("", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "MOC-Knowledge.md").write_text("# MOC\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "skill.md").write_text("# skill\n", encoding="utf-8")

    files = path_map.list_knowledge_files(tmp_path)
    assert "30-Knowledge/skill.md" in files
    assert "30-Knowledge/MOC-Knowledge.md" in files
    assert "30-Knowledge/CLAUDE.md" not in files
    assert "30-Knowledge/README.md" not in files

    tree = path_map.build_knowledge_tree(tmp_path, section="kb")
    names = _tree_file_names(tree)
    assert "skill.md" in names
    assert "MOC-Knowledge.md" in names
    assert "CLAUDE.md" not in names
    assert "README.md" not in names


def test_build_knowledge_tree_rejects_bad_section(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        path_map.build_knowledge_tree(tmp_path, section="nope")


def test_assert_path_allowed_for_section() -> None:
    path_map.assert_path_allowed_for_section("30-Knowledge/a.md", "kb")
    path_map.assert_path_allowed_for_section("00-Inbox/a.md", "notes")
    with pytest.raises(ValueError):
        path_map.assert_path_allowed_for_section("00-Inbox/a.md", "kb")
    with pytest.raises(ValueError):
        path_map.assert_path_allowed_for_section("30-Knowledge/a.md", "notes")


def _tree_file_names(node: dict) -> set[str]:
    out: set[str] = set()
    if node.get("type") == "file":
        out.add(str(node.get("name") or Path(str(node.get("path") or "")).name))
    for child in node.get("children") or []:
        out |= _tree_file_names(child)
    return out


def test_preferred_write_create_goes_inbox() -> None:
    assert path_map.preferred_write_rel("kb/notes/x.md", create=True) == "00-Inbox/x.md"
    assert (
        path_map.preferred_write_rel("kb/notes/ResumePoints/a.md", create=True)
        == "10-Work/ResumePoints/a.md"
    )
    assert path_map.normalize_api_path("x.md") == "00-Inbox/x.md"
    assert path_map.normalize_api_path("ResumePoints/a.md") == "10-Work/ResumePoints/a.md"


def test_seed_vault_chrome_idempotent(tmp_path: Path) -> None:
    a = seed_vault_chrome(tmp_path, dry_run=False)
    assert "CLAUDE.md" in a
    assert (tmp_path / "00-Inbox").is_dir()
    assert (
        tmp_path / ".claude" / "skills" / "capture-workflow" / "SKILL.md"
    ).is_file()
    b = seed_vault_chrome(tmp_path, dry_run=False)
    assert b == []


def test_seed_vault_chrome_v19_structure(tmp_path: Path) -> None:
    """Nested CLAUDE.md, richer templates, sub-folders, Upcoming.md, skill support files."""
    (tmp_path / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}', encoding="utf-8"
    )
    seeded = seed_vault_chrome(tmp_path, dry_run=False)

    for rel in (
        "Upcoming.md",
        "00-Inbox/CLAUDE.md",
        "10-Work/CLAUDE.md",
        "20-Personal/CLAUDE.md",
        "30-Knowledge/CLAUDE.md",
        "40-Journal/CLAUDE.md",
        "90-Archive/CLAUDE.md",
        "_templates/project.md",
        "_templates/person.md",
        "_templates/meeting.md",
        "_templates/daily.md",
        "_templates/attachment-note.md",
        "10-Work/Projects/README.md",
        "10-Work/People/README.md",
        "10-Work/Meetings/README.md",
        "10-Work/Reference/README.md",
        "20-Personal/Health/README.md",
        "20-Personal/Family/README.md",
        "20-Personal/Finance/README.md",
        "20-Personal/Home/README.md",
        "20-Personal/Travel/README.md",
        ".claude/skills/capture-workflow/attachments.md",
        ".claude/skills/capture-workflow/reorganization.md",
        ".claude/skills/vault-maintenance/link-repair.md",
    ):
        assert rel in seeded, f"expected {rel} to be seeded"
        assert (tmp_path / rel).is_file()

    # layout_version 1 vaults don't get 40-Journal/CLAUDE.md seeded ahead of migrate-journal-v2.
    v1_root = tmp_path / "v1"
    v1_root.mkdir()
    (v1_root / "config.json").write_text(
        '{"version": 1, "layout_version": 1, "timezone": "UTC"}', encoding="utf-8"
    )
    seeded_v1 = seed_vault_chrome(v1_root, dry_run=False)
    assert "40-Journal/CLAUDE.md" not in seeded_v1
    assert not (v1_root / "40-Journal").exists()


def test_seed_vault_chrome_never_overwrites_user_edits(tmp_path: Path) -> None:
    seed_vault_chrome(tmp_path, dry_run=False)
    conventions = tmp_path / "_system" / "conventions.md"
    conventions.write_text("# my custom conventions\n", encoding="utf-8")

    seed_vault_chrome(tmp_path, dry_run=False)
    assert conventions.read_text(encoding="utf-8") == "# my custom conventions\n"


def test_migrate_v2_dry_run_default(tmp_path: Path) -> None:
    notes = tmp_path / "kb" / "notes"
    notes.mkdir(parents=True)
    (notes / "KnowledgeMap.md").write_text("# map\n", encoding="utf-8")
    (notes / "ResumePoints").mkdir()
    (notes / "ResumePoints" / "r.md").write_text("# rp\n", encoding="utf-8")

    result = run_migrate_v2(tmp_path, dry_run=True, apply=False)
    assert result["dry_run"] is True
    assert not (tmp_path / "30-Knowledge" / "KnowledgeMap.md").is_file()
    assert not (tmp_path / "CLAUDE.md").is_file()
    assert result["plan_count"] >= 2


def test_migrate_v2_apply_requires_backup_flag(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="i-have-backup"):
        run_migrate_v2(tmp_path, dry_run=False, apply=True, i_have_backup=False)


def test_migrate_v2_apply_and_idempotent(tmp_path: Path) -> None:
    notes = tmp_path / "kb" / "notes"
    notes.mkdir(parents=True)
    (notes / "KnowledgeMap.md").write_text("# map\n", encoding="utf-8")
    rp = notes / "ResumePoints"
    rp.mkdir()
    (rp / "r.md").write_text("# rp\n", encoding="utf-8")
    (notes / "weird.md").write_text("# ??\n", encoding="utf-8")

    first = run_migrate_v2(
        tmp_path, dry_run=False, apply=True, i_have_backup=True
    )
    assert first["dry_run"] is False
    assert (tmp_path / "30-Knowledge" / "KnowledgeMap.md").is_file()
    assert (tmp_path / "10-Work" / "ResumePoints" / "r.md").is_file()
    assert (tmp_path / "00-Inbox" / "weird.md").is_file()
    # Legacy left intact
    assert (notes / "KnowledgeMap.md").is_file()
    assert (tmp_path / "CLAUDE.md").is_file()
    assert first["copies"]["copied"] >= 3

    second = run_migrate_v2(
        tmp_path, dry_run=False, apply=True, i_have_backup=True
    )
    assert second["copies"]["copied"] == 0
    assert second["copies"]["skipped_identical"] >= 3


def test_demo_vault_cutover_complete() -> None:
    """demo-vault ships PARA-only after Phase 5 cutover (empty kb/notes tombstone)."""
    demo = Path(__file__).resolve().parents[2] / "demo-vault"
    if not (demo / "kb" / "notes").is_dir():
        pytest.skip("demo-vault not found")

    leftover = [
        p
        for p in (demo / "kb" / "notes").rglob("*.md")
        if p.is_file() and not p.name.startswith(".")
    ]
    assert leftover == []
    assert (demo / "30-Knowledge" / "KnowledgeMap.md").is_file()
    assert (demo / "10-Work" / "ResumePoints").is_dir()
    assert (demo / "90-Archive" / "_legacy-kb").is_dir()
    assert "Done (v1.10)" in (demo / "_system" / "para-cutover.md").read_text(
        encoding="utf-8"
    )
    assert plan_kb_copies(demo) == []


def test_migrate_v2_against_demo_vault_copy(tmp_path: Path) -> None:
    """Restock a unique leftover into kb/notes and exercise migrate-v2 copy."""
    # .../Chronicle/chronicle-pc/tests/this.py → parents[2] = Chronicle
    demo = Path(__file__).resolve().parents[2] / "demo-vault"
    if not (demo / "kb" / "notes").is_dir():
        pytest.skip("demo-vault not found")

    archive = demo / "90-Archive" / "_legacy-kb"
    sample = archive / "KnowledgeMap.md"
    if not sample.is_file():
        pytest.skip("demo-vault _legacy-kb sample missing")

    copy = tmp_path / "demo"
    shutil.copytree(
        demo,
        copy,
        ignore=shutil.ignore_patterns("index", ".git", "__pycache__"),
    )
    # Unique leftover (no PARA peer) so migrate-v2 must copy, not skip_exists
    restock = copy / "kb" / "notes" / "CutoverDemoOnly.md"
    restock.parent.mkdir(parents=True, exist_ok=True)
    restock.write_text(
        sample.read_text(encoding="utf-8"),
        encoding="utf-8",
    )

    dry = run_migrate_v2(copy, dry_run=True, apply=False)
    assert dry["dry_run"] is True
    assert dry["plan_count"] > 0

    applied = run_migrate_v2(
        copy, dry_run=False, apply=True, i_have_backup=True
    )
    assert applied["copies"]["copied"] >= 1
    assert (copy / "10-Work" / "ResumePoints").is_dir()
    assert (copy / "CLAUDE.md").is_file()
    # migrate-v2 copies; leftover remains until cutover-kb
    assert (copy / "kb" / "notes" / "CutoverDemoOnly.md").is_file()
    assert (copy / "30-Knowledge" / "CutoverDemoOnly.md").is_file()
    plans = plan_kb_copies(copy)
    assert all(p["action"] in {"skip_identical", "skip_exists"} for p in plans)


def test_api_kb_para_create_bare_to_inbox(chronicle_dir: Path) -> None:
    from fastapi.testclient import TestClient

    from chronicle_pipeline.serve import create_app

    client = TestClient(
        create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    )
    r = client.post(
        "/kb/notes/skills/python.md",
        json={"content": "# Python\nFastAPI.\n", "title": "Python", "tags": ["skill"]},
    )
    assert r.status_code == 201
    assert r.json()["path"].startswith("00-Inbox/")

    got = client.get("/kb/notes/00-Inbox/skills/python.md")
    assert got.status_code == 200
    assert "FastAPI" in got.json()["content"]

    # Legacy path rejected
    legacy = client.get("/kb/notes/kb/notes/skills/python.md")
    assert legacy.status_code == 410

    tree = client.get("/kb/tree")
    assert tree.status_code == 200
    files = tree.json()["files"]
    assert any(f.endswith("skills/python.md") for f in files)
    assert any(f.startswith("00-Inbox/") for f in files)
    assert not any(f.startswith("kb/notes/") for f in files)
