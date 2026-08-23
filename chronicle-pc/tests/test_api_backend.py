"""Smoke tests for the Phase 1 vault REST API."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from chronicle_pipeline.serve import create_app


def _client(chronicle_dir: Path) -> TestClient:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    return TestClient(app)


def test_entries_crud_pc_suffix(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)

    r = client.get("/entries")
    assert r.status_code == 200
    assert r.json()["total"] >= 3

    created = client.post(
        "/entries",
        json={"type": "log", "text": "hello from mac", "tags": ["test"], "mood": 3},
    )
    assert created.status_code == 201
    entry = created.json()
    assert entry["id"].endswith("-pc") or "-pc_" in entry["id"]
    assert entry["processed"] is False
    assert entry["version"] == 1
    eid = entry["id"]

    got = client.get(f"/entries/{eid}")
    assert got.status_code == 200
    assert got.json()["text"] == "hello from mac"

    patched = client.patch(f"/entries/{eid}", json={"text": "updated", "tags": ["a"]})
    assert patched.status_code == 200
    assert patched.json()["text"] == "updated"

    # processed lock — new writes land under _capture/entries/
    path = (
        chronicle_dir
        / "_capture"
        / "entries"
        / eid[:4]
        / eid[5:7]
        / f"{eid}.json"
    )
    data = json.loads(path.read_text(encoding="utf-8"))
    data["processed"] = True
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    locked = client.patch(f"/entries/{eid}", json={"text": "nope"})
    assert locked.status_code == 409

    # delete only while unprocessed — flip back
    data["processed"] = False
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    deleted = client.delete(f"/entries/{eid}")
    assert deleted.status_code == 200
    assert client.get(f"/entries/{eid}").status_code == 404


def test_entries_image_upload(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    created = client.post("/entries", json={"type": "idea", "text": "with photo"})
    eid = created.json()["id"]
    # minimal JPEG (SOI + EOI)
    jpeg = b"\xff\xd8\xff\xd9"
    r = client.post(
        f"/entries/{eid}/images",
        files={"file": ("shot.jpg", jpeg, "image/jpeg")},
    )
    assert r.status_code == 201
    body = r.json()
    assert body["path"].startswith("_attachments/")
    assert body["path"].endswith(".jpg")
    assert body["path"] in body["entry"]["images"]
    assert (chronicle_dir / body["path"]).is_file()


def test_kb_notes_crud_and_tree(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.post(
        "/kb/notes/skills/python.md",
        json={"content": "# Python\nFastAPI notes.\n", "title": "Python", "tags": ["skill"]},
    )
    assert r.status_code == 201
    assert "title: Python" in r.json()["content"]
    # Phase 1: new creates land in PARA Inbox (bare API path remaps)
    assert r.json()["path"] == "00-Inbox/skills/python.md"

    got = client.get("/kb/notes/00-Inbox/skills/python.md")
    assert got.status_code == 200
    assert "FastAPI" in got.json()["content"]

    # Bare alias still resolves to the same PARA note
    got_bare = client.get("/kb/notes/skills/python.md")
    assert got_bare.status_code == 200
    assert got_bare.json()["path"] == "00-Inbox/skills/python.md"

    conflict = client.post(
        "/kb/notes/skills/python.md", json={"content": "x"}
    )
    assert conflict.status_code == 409

    base_hash = got.json()["content_hash"]
    assert base_hash
    put = client.put(
        "/kb/notes/skills/python.md",
        json={"content": "# Python\nUpdated.\n", "base_hash": base_hash},
    )
    assert put.status_code == 200
    assert "Updated" in put.json()["content"]
    assert put.json()["content_hash"]

    tree = client.get("/kb/tree")
    assert tree.status_code == 200
    assert "00-Inbox/skills/python.md" in tree.json()["files"]

    deleted = client.delete("/kb/notes/skills/python.md")
    assert deleted.status_code == 200
    assert client.get("/kb/notes/00-Inbox/skills/python.md").status_code == 404


def test_kb_para_direct_path_crud(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.post(
        "/kb/notes/30-Knowledge/skills/rust.md",
        json={"content": "# Rust\n", "title": "Rust"},
    )
    assert r.status_code == 201
    assert r.json()["path"] == "30-Knowledge/skills/rust.md"
    got = client.get("/kb/notes/30-Knowledge/skills/rust.md")
    assert got.status_code == 200
    assert "Rust" in got.json()["content"]


def test_kb_tree_excludes_chrome_files(chronicle_dir: Path) -> None:
    # Nested CLAUDE.md / README.md scaffolding (seeded by init-vault-structure)
    # must never appear in the app's note lists.
    (chronicle_dir / "00-Inbox").mkdir(parents=True, exist_ok=True)
    (chronicle_dir / "00-Inbox" / "CLAUDE.md").write_text("# guide\n", encoding="utf-8")
    (chronicle_dir / "10-Work" / "Projects").mkdir(parents=True, exist_ok=True)
    (chronicle_dir / "10-Work" / "Projects" / "README.md").write_text("# stub\n", encoding="utf-8")
    client = _client(chronicle_dir)
    r = client.post("/kb/notes/00-Inbox/real-note.md", json={"content": "# Real\n"})
    assert r.status_code == 201

    files = client.get("/kb/tree").json()["files"]
    assert "00-Inbox/real-note.md" in files
    assert "00-Inbox/CLAUDE.md" not in files
    assert "10-Work/Projects/README.md" not in files


def test_kb_create_rejects_section_mismatch(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.put(
        "/kb/notes/00-Inbox/mismatched.md",
        json={"content": "# x\n", "section": "kb"},
    )
    assert r.status_code == 400


def test_kb_tree_section_query_rejects_garbage(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.get("/kb/tree?section=bogus")
    assert r.status_code == 400


def test_kb_tree_section_filter(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    client.post(
        "/kb/notes/30-Knowledge/rust.md", json={"content": "# Rust\n"}
    )
    client.post(
        "/kb/notes/00-Inbox/idea.md", json={"content": "# Idea\n"}
    )

    kb_only = client.get("/kb/tree?section=kb")
    assert kb_only.status_code == 200
    kb_files = kb_only.json()["files"]
    assert "30-Knowledge/rust.md" in kb_files
    assert "00-Inbox/idea.md" not in kb_files
    kb_areas = [c["path"] for c in kb_only.json()["tree"]["children"]]
    assert kb_areas == ["30-Knowledge"]

    notes_only = client.get("/kb/tree?section=notes")
    notes_files = notes_only.json()["files"]
    assert "00-Inbox/idea.md" in notes_files
    assert "30-Knowledge/rust.md" not in notes_files
    notes_areas = [c["path"] for c in notes_only.json()["tree"]["children"]]
    assert "30-Knowledge" not in notes_areas

    # default (no section) stays byte-compatible with pre-v1.9 behavior
    everything = client.get("/kb/tree")
    all_files = everything.json()["files"]
    assert "30-Knowledge/rust.md" in all_files
    assert "00-Inbox/idea.md" in all_files


def test_kb_create_with_section_picks_default_area(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    r = client.put(
        "/kb/notes/bare-name.md",
        json={"content": "# Bare\n", "section": "kb"},
    )
    assert r.status_code == 200
    assert r.json()["path"] == "30-Knowledge/bare-name.md"

    r2 = client.put(
        "/kb/notes/another-bare.md",
        json={"content": "# Bare\n", "section": "notes"},
    )
    assert r2.status_code == 200
    assert r2.json()["path"] == "00-Inbox/another-bare.md"


def test_kb_tree_rejects_invalid_section(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    bad = client.get("/kb/tree?section=journal")
    assert bad.status_code == 400


def test_kb_hard_section_create_rejects_cross_section(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    # Notes tab must not create under 30-Knowledge
    r = client.post(
        "/kb/notes/30-Knowledge/sneaky.md",
        json={"content": "# Nope\n", "section": "notes"},
    )
    assert r.status_code == 400
    # KB tab must not create under Inbox
    r2 = client.post(
        "/kb/notes/00-Inbox/sneaky.md",
        json={"content": "# Nope\n", "section": "kb"},
    )
    assert r2.status_code == 400
    # Same-section explicit path still works
    ok = client.post(
        "/kb/notes/30-Knowledge/ok.md",
        json={"content": "# Ok\n", "section": "kb"},
    )
    assert ok.status_code == 201


def test_notes_upcoming_md_allowed_but_traversal_still_blocked(chronicle_dir: Path) -> None:
    (chronicle_dir / "Upcoming.md").write_text("# Upcoming\n\nNothing scheduled.\n", encoding="utf-8")
    client = _client(chronicle_dir)

    got = client.get("/notes/Upcoming.md")
    assert got.status_code == 200
    assert "Nothing scheduled" in got.json()["content"]

    listed = client.get("/notes")
    paths = [f["path"] for f in listed.json()["files"]]
    assert "Upcoming.md" in paths

    traversal = client.get("/notes/%2e%2e/config.json")
    assert traversal.status_code == 400


def test_notes_readonly(chronicle_dir: Path) -> None:
    note = chronicle_dir / "notes" / "daily" / "2026-07-09.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text("# Daily\nhello\n", encoding="utf-8")
    journal = chronicle_dir / "40-Journal" / "2026-07-09.md"
    journal.parent.mkdir(parents=True, exist_ok=True)
    journal.write_text("# 2026-07-09\n\n<!-- entry:2026-07-09_090015-an -->\nhi\n<!-- /entry:2026-07-09_090015-an -->\n")
    client = _client(chronicle_dir)
    listed = client.get("/notes")
    assert listed.status_code == 200
    paths = [f["path"] for f in listed.json()["files"]]
    assert "notes/daily/2026-07-09.md" in paths
    assert "40-Journal/2026-07-09.md" in paths
    got = client.get("/notes/daily/2026-07-09.md")
    assert got.status_code == 200
    assert "hello" in got.json()["content"]
    jgot = client.get("/notes/40-Journal/2026-07-09.md")
    assert jgot.status_code == 200
    assert "entry:2026-07-09_090015-an" in jgot.json()["content"]


def test_brain_graph_insights_curation(chronicle_dir: Path) -> None:
    graph = {
        "version": 1,
        "generated": "2026-07-09T00:00:00+00:00",
        "nodes": [
            {"id": "topic:health", "kind": "topic", "label": "health"},
            {
                "id": "entry:2026-07-09_090015-an",
                "kind": "entry",
                "label": "morning",
                "entry_id": "2026-07-09_090015-an",
            },
        ],
        "edges": [
            {
                "from": "entry:2026-07-09_090015-an",
                "to": "topic:health",
                "rel": "about",
            }
        ],
    }
    (chronicle_dir / "brain" / "graph.json").write_text(
        json.dumps(graph, indent=2) + "\n", encoding="utf-8"
    )
    insight_dir = chronicle_dir / "brain" / "insights" / "2026"
    insight_dir.mkdir(parents=True, exist_ok=True)
    (insight_dir / "2026-07-09.json").write_text(
        json.dumps(
            {
                "version": 1,
                "date": "2026-07-09",
                "generated": "2026-07-09T12:00:00+00:00",
                "summary": "A day",
                "themes": ["health"],
            }
        )
        + "\n",
        encoding="utf-8",
    )

    client = _client(chronicle_dir)
    g = client.get("/brain/graph")
    assert g.status_code == 200
    assert len(g.json()["nodes"]) == 2

    insights = client.get("/brain/insights")
    assert insights.status_code == 200
    assert "2026-07-09" in insights.json()["dates"]

    one = client.get("/brain/insights", params={"date": "2026-07-09"})
    assert one.status_code == 200
    assert one.json()["insight"]["summary"] == "A day"

    op = client.post(
        "/curation/ops",
        json={"op": "pin", "node": "topic:health"},
    )
    assert op.status_code == 201
    assert op.json()["op"]["device"] == "pc"
    ops_path = chronicle_dir / "curation" / "ops" / "pc.jsonl"
    lines = [ln for ln in ops_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    assert any(json.loads(ln).get("op") == "pin" for ln in lines)


def test_recall_node_ids_and_citation_mapping(chronicle_dir: Path) -> None:
    graph = {
        "version": 1,
        "generated": "2026-07-09T00:00:00+00:00",
        "nodes": [
            {"id": "topic:health", "kind": "topic", "label": "health"},
            {
                "id": "entry:2026-07-09_090015-an",
                "kind": "entry",
                "label": "morning",
                "entry_id": "2026-07-09_090015-an",
            },
        ],
        "edges": [
            {
                "from": "entry:2026-07-09_090015-an",
                "to": "topic:health",
                "rel": "about",
            }
        ],
    }
    (chronicle_dir / "brain" / "graph.json").write_text(
        json.dumps(graph, indent=2) + "\n", encoding="utf-8"
    )
    client = _client(chronicle_dir)
    with (
        patch("chronicle_pipeline.rag.llm.try_get_provider", return_value=None),
        patch(
            "chronicle_pipeline.api.recall.index_store.search",
            return_value=[
                {
                    "id": "2026-07-09_090015-an",
                    "kind": "entry",
                    "path": "entries/.../2026-07-09_090015-an.json",
                    "text": "walked in the park",
                    "score": 0.9,
                }
            ],
        ),
        patch(
            "chronicle_pipeline.rag.build_retrieval_context",
            return_value=[
                {
                    "id": "2026-07-09_090015-an",
                    "kind": "entry",
                    "path": "entries/.../2026-07-09_090015-an.json",
                    "text": "walked in the park",
                    "score": 0.9,
                }
            ],
        ),
    ):
        r = client.post(
            "/recall",
            json={"message": "health?", "node_ids": ["topic:health"]},
        )
    assert r.status_code == 200
    data = r.json()
    assert data["degraded"] is True
    assert "topic:health" in data["seed_node_ids"]
    assert "entry:2026-07-09_090015-an" in data["seed_node_ids"]
    cite = data["citations"][0]
    assert "entry:2026-07-09_090015-an" in cite["node_ids"]


def test_process_endpoint(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    with patch(
        "chronicle_pipeline.api.process.run_process",
        return_value={
            "processed": [],
            "days": [],
            "notes_written": [],
            "dry_run": True,
            "brain": None,
            "index": None,
        },
    ) as mock_proc:
        r = client.post("/process", params={"dry_run": True, "run_brain": False})
    assert r.status_code == 200
    assert r.json()["ok"] is True
    mock_proc.assert_called_once()
    assert mock_proc.call_args.kwargs["dry_run"] is True
    assert mock_proc.call_args.kwargs["run_brain"] is False


def test_health_and_connect_still_work(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    h = client.get("/health")
    assert h.status_code == 200
    assert h.json()["ok"] is True
    c = client.get("/connect")
    assert c.status_code == 200
    assert c.json()["base"] == "http://127.0.0.1:8765"
