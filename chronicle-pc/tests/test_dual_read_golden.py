"""Golden path-map / cutover fixtures after dual-read retirement.

Shared vectors with Android KnowledgePathMapTest (keep in sync).
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from chronicle_pipeline import path_map
from chronicle_pipeline.cutover_kb import classify_for_cutover, run_cutover_kb
from chronicle_pipeline.serve import create_app

# --- Shared golden vectors (PC ↔ Android) ---------------------------------


def test_candidate_order_para_only(tmp_path: Path) -> None:
    """PARA exact path only; no legacy kb/notes peers."""
    (tmp_path / "10-Work" / "Projects").mkdir(parents=True)
    (tmp_path / "20-Personal").mkdir(parents=True)
    (tmp_path / "kb" / "notes" / "Projects").mkdir(parents=True)
    (tmp_path / "10-Work" / "Projects" / "idea.md").write_text("# work\n", encoding="utf-8")
    (tmp_path / "20-Personal" / "idea.md").write_text("# personal\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "Projects" / "idea.md").write_text("# legacy\n", encoding="utf-8")

    # Legacy path has no candidates after cutover
    assert path_map.candidate_read_paths(tmp_path, "kb/notes/Projects/idea.md") == []
    assert path_map.resolve_read(tmp_path, "kb/notes/Projects/idea.md") is None

    para_cands = [
        p.relative_to(tmp_path).as_posix()
        for p in path_map.candidate_read_paths(tmp_path, "10-Work/Projects/idea.md")
    ]
    assert para_cands == ["10-Work/Projects/idea.md"]
    assert "kb/notes/Projects/idea.md" not in para_cands


def test_basename_collision_does_not_false_open(tmp_path: Path) -> None:
    (tmp_path / "00-Inbox").mkdir()
    (tmp_path / "30-Knowledge").mkdir()
    (tmp_path / "00-Inbox" / "shared.md").write_text("# inbox\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "shared.md").write_text("# kb\n", encoding="utf-8")

    # Bare → Inbox after cutover
    abs_path = path_map.resolve_read_abs(tmp_path, "shared.md")
    assert abs_path is not None
    assert abs_path == (tmp_path / "00-Inbox" / "shared.md").resolve()

    got = path_map.resolve_read(tmp_path, "30-Knowledge/shared.md")
    assert got == "30-Knowledge/shared.md"


def test_resumepoints_bare_maps_to_work(tmp_path: Path) -> None:
    (tmp_path / "10-Work" / "ResumePoints").mkdir(parents=True)
    (tmp_path / "10-Work" / "ResumePoints" / "Foo.md").write_text("# rp\n", encoding="utf-8")

    cands = [
        p.relative_to(tmp_path).as_posix()
        for p in path_map.candidate_read_paths(tmp_path, "ResumePoints/Foo.md")
    ]
    assert cands == ["10-Work/ResumePoints/Foo.md"]
    assert path_map.resolve_read(tmp_path, "ResumePoints/Foo.md") == "10-Work/ResumePoints/Foo.md"


def test_list_excludes_legacy(tmp_path: Path) -> None:
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "kb" / "notes").mkdir(parents=True)
    (tmp_path / "00-Inbox" / "dup.md").write_text("# para\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "dup.md").write_text("# legacy\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "solo.md").write_text("# solo\n", encoding="utf-8")

    files = path_map.list_knowledge_files(tmp_path)
    assert "00-Inbox/dup.md" in files
    assert "kb/notes/dup.md" not in files
    assert "kb/notes/solo.md" not in files


def test_chrome_hidden_moc_kept(tmp_path: Path) -> None:
    (tmp_path / "30-Knowledge").mkdir()
    (tmp_path / "30-Knowledge" / "CLAUDE.md").write_text("# c\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "README.md").write_text("# r\n", encoding="utf-8")
    (tmp_path / "30-Knowledge" / "MOC-Knowledge.md").write_text("# moc\n", encoding="utf-8")
    files = path_map.list_knowledge_files(tmp_path)
    assert "30-Knowledge/MOC-Knowledge.md" in files
    assert "30-Knowledge/CLAUDE.md" not in files
    assert "30-Knowledge/README.md" not in files


def test_section_scope(tmp_path: Path) -> None:
    assert path_map.section_for("30-Knowledge/a.md") == "kb"
    assert path_map.section_for("kb/notes/a.md") is None
    assert path_map.section_for("00-Inbox/a.md") == "notes"
    assert path_map.path_allowed_for_section("00-Inbox/a.md", "notes")
    assert not path_map.path_allowed_for_section("00-Inbox/a.md", "kb")


def test_find_dual_copy_pairs(tmp_path: Path) -> None:
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "kb" / "notes").mkdir(parents=True)
    (tmp_path / "00-Inbox" / "x.md").write_text("# p\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "x.md").write_text("# l\n", encoding="utf-8")
    pairs = path_map.find_dual_copy_pairs(tmp_path)
    assert len(pairs) == 1
    assert pairs[0]["para"] == "00-Inbox/x.md"
    assert pairs[0]["legacy"] == "kb/notes/x.md"


def test_validate_rejects_legacy() -> None:
    with pytest.raises(ValueError, match="retired"):
        path_map.validate_knowledge_rel("kb/notes/alive.md")


def test_classify_for_cutover_defaults_knowledge() -> None:
    assert classify_for_cutover("zzzz-unknown-xyz.md") == "30-Knowledge/zzzz-unknown-xyz.md"
    assert classify_for_cutover("ResumePoints/foo.md") == "10-Work/ResumePoints/foo.md"
    assert classify_for_cutover("KnowledgeMap.md") == "30-Knowledge/KnowledgeMap.md"


# --- Cutover + API -----------------------------------------------------------


def _kb_client(root: Path) -> TestClient:
    app = create_app(root, connect_info={"base": "http://127.0.0.1:8765"})
    return TestClient(app)


def test_cutover_move_and_quarantine(tmp_path: Path) -> None:
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "kb" / "notes" / "Projects").mkdir(parents=True)
    (tmp_path / "brain").mkdir()
    (tmp_path / "00-Inbox" / "peer.md").write_text("# para\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "peer.md").write_text("# legacy leftover\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "Projects" / "solo.md").write_text("# only legacy\n", encoding="utf-8")
    (tmp_path / "brain" / "graph.json").write_text(
        '{"version":1,"nodes":[{"id":"c:1","doc":"kb/notes/Projects/solo.md"},'
        '{"id":"c:2","doc":"kb/notes/peer.md"}],"edges":[]}',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="i-have-backup"):
        run_cutover_kb(tmp_path, dry_run=False, apply=True, i_have_backup=False)

    dry = run_cutover_kb(tmp_path, dry_run=True, apply=False)
    assert dry["dry_run"] is True
    assert (tmp_path / "kb" / "notes" / "peer.md").is_file()

    result = run_cutover_kb(tmp_path, dry_run=False, apply=True, i_have_backup=True)
    assert result["dry_run"] is False
    assert result["stats"]["quarantined"] >= 1
    assert result["stats"]["moved"] >= 1
    assert (tmp_path / "90-Archive" / "_legacy-kb" / "peer.md").is_file()
    assert not (tmp_path / "kb" / "notes" / "peer.md").exists()
    # solo moved into PARA (Work for Projects/ heuristic or Knowledge default)
    assert not (tmp_path / "kb" / "notes" / "Projects" / "solo.md").exists()
    solo_dests = list(tmp_path.rglob("solo.md"))
    assert any("kb/notes" not in p.as_posix() for p in solo_dests)
    assert (tmp_path / "kb" / "notes" / ".gitkeep").is_file() or (
        tmp_path / "kb" / "notes"
    ).is_dir()

    import json

    graph = json.loads((tmp_path / "brain" / "graph.json").read_text(encoding="utf-8"))
    docs = {n["id"]: n.get("doc") for n in graph["nodes"]}
    # Quarantined leftover: doc points at PARA peer, not archive
    assert docs["c:2"] == "00-Inbox/peer.md"
    assert not str(docs["c:1"]).startswith("kb/notes/")
    assert docs["c:1"].endswith("Projects/solo.md")


def test_api_legacy_path_returns_410(tmp_path: Path) -> None:
    (tmp_path / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}', encoding="utf-8"
    )
    legacy = tmp_path / "kb" / "notes" / "alive.md"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("# old\n", encoding="utf-8")

    client = _kb_client(tmp_path)
    r = client.get("/kb/notes/kb/notes/alive.md")
    assert r.status_code == 410
    assert "cutover-kb" in r.json()["detail"]

    r2 = client.put(
        "/kb/notes/kb/notes/alive.md",
        json={"content": "# updated\n", "section": "kb"},
    )
    assert r2.status_code == 410


def test_api_para_put_and_delete(tmp_path: Path) -> None:
    (tmp_path / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}', encoding="utf-8"
    )
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "00-Inbox" / "alive.md").write_text("# old\n", encoding="utf-8")

    client = _kb_client(tmp_path)
    got = client.get("/kb/notes/00-Inbox/alive.md")
    assert got.status_code == 200
    base_hash = got.json()["content_hash"]
    r = client.put(
        "/kb/notes/00-Inbox/alive.md",
        json={"content": "# updated\n", "section": "notes", "base_hash": base_hash},
    )
    assert r.status_code == 200
    assert r.json()["path"] == "00-Inbox/alive.md"
    assert (tmp_path / "00-Inbox" / "alive.md").read_text(encoding="utf-8").startswith(
        "# updated"
    )

    r_del = client.delete("/kb/notes/00-Inbox/alive.md")
    assert r_del.status_code == 200
    assert not (tmp_path / "00-Inbox" / "alive.md").exists()


def test_move_and_archive_para(tmp_path: Path) -> None:
    (tmp_path / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}', encoding="utf-8"
    )
    (tmp_path / "00-Inbox").mkdir(parents=True)
    (tmp_path / "10-Work").mkdir(parents=True)
    (tmp_path / "00-Inbox" / "filed.md").write_text("# para\n", encoding="utf-8")

    client = _kb_client(tmp_path)
    moved = client.post(
        "/kb/move",
        json={"from_path": "00-Inbox/filed.md", "to_path": "10-Work/filed.md"},
    )
    assert moved.status_code == 200
    body = moved.json()
    assert body["to_path"] == "10-Work/filed.md"
    assert (tmp_path / "10-Work" / "filed.md").is_file()
    assert not (tmp_path / "00-Inbox" / "filed.md").exists()

    archived = client.post("/kb/archive", json={"path": "10-Work/filed.md"})
    assert archived.status_code == 200
    assert archived.json()["to_path"] == "90-Archive/filed.md"
    assert (tmp_path / "90-Archive" / "filed.md").is_file()


def test_kb_templates_lists_seeded(tmp_path: Path) -> None:
    (tmp_path / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}', encoding="utf-8"
    )
    (tmp_path / "_templates").mkdir()
    (tmp_path / "_templates" / "note.md").write_text("# {{title}}\n", encoding="utf-8")
    client = _kb_client(tmp_path)
    r = client.get("/kb/templates")
    assert r.status_code == 200
    names = {f["name"] for f in r.json()["files"]}
    assert "note" in names


def test_tree_excludes_legacy(tmp_path: Path) -> None:
    (tmp_path / "config.json").write_text(
        '{"version": 1, "layout_version": 2, "timezone": "UTC"}', encoding="utf-8"
    )
    (tmp_path / "30-Knowledge").mkdir(parents=True)
    (tmp_path / "kb" / "notes").mkdir(parents=True)
    (tmp_path / "30-Knowledge" / "a.md").write_text("# a\n", encoding="utf-8")
    (tmp_path / "kb" / "notes" / "b.md").write_text("# b\n", encoding="utf-8")
    client = _kb_client(tmp_path)
    tree = client.get("/kb/tree")
    assert tree.status_code == 200
    files = tree.json()["files"]
    assert "30-Knowledge/a.md" in files
    assert not any(f.startswith("kb/notes/") for f in files)
