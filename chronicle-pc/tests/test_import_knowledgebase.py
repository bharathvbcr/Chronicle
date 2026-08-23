"""import-knowledgebase maps KB brain.json → curation ops."""

from __future__ import annotations

import json
from pathlib import Path

from chronicle_pipeline import curation
from chronicle_pipeline.import_knowledgebase import (
    DEFAULT_KB_SOURCE,
    annotation_text,
    chronicle_node_id,
    map_brain_to_ops,
    run_import_knowledgebase,
    sanitize_slug,
    strip_html,
)
from chronicle_pipeline.paths import read_json

TINY_BRAIN = {
    "meta": {"title": "test"},
    "groups": {"ai": {"label": "AI"}},
    "nodes": [
        {
            "id": "identity",
            "label": "Bharath",
            "group": "core",
            "desc": "Engineer",
            "details": "<b>Bio:</b> builds tools<br/>more",
        },
        {
            "id": "proj_scholarlm",
            "label": "ScholarLM",
            "group": "ai",
            "desc": "Literature AI",
            "details": "",
        },
    ],
    "links": [
        {"source": "identity", "target": "proj_scholarlm", "label": "builds"},
    ],
}


def test_sanitize_and_strip() -> None:
    assert sanitize_slug("proj_ScholarLM") == "proj-scholarlm"
    assert "Bio:" in strip_html("<b>Bio:</b> hello<br/>world")
    assert "\n" in strip_html("<b>Bio:</b> hello<br/>world")
    note = annotation_text(TINY_BRAIN["nodes"][0])
    assert "Engineer" in note
    assert "<b>" not in note


def test_map_brain_to_ops_tiny() -> None:
    ops = map_brain_to_ops(TINY_BRAIN, device="pc", ts="2026-07-09T12:00:00+05:30")
    creates = [o for o in ops if o["op"] == "create_concept"]
    annotates = [o for o in ops if o["op"] == "annotate"]
    links = [o for o in ops if o["op"] == "link"]

    assert {o["id"] for o in creates} == {
        "concept:identity",
        "project:proj-scholarlm",
    }
    proj = next(o for o in creates if o["id"].startswith("project:"))
    assert proj["label"] == "ScholarLM"
    assert proj["group"] == "ai"
    identity = next(o for o in creates if o["id"] == "concept:identity")
    assert identity["group"] == "core"
    assert len(annotates) == 2
    assert links == [
        {
            "op": "link",
            "ts": "2026-07-09T12:00:00+05:30",
            "device": "pc",
            "from": "concept:identity",
            "to": "project:proj-scholarlm",
            "rel": "manual",
        }
    ]
    cid, kind = chronicle_node_id(TINY_BRAIN["nodes"][1])
    assert cid == "project:proj-scholarlm"
    assert kind == "project"


def test_map_brain_idempotent_skips_existing() -> None:
    first = map_brain_to_ops(TINY_BRAIN, ts="t1")
    ids = {o["id"] for o in first if o["op"] == "create_concept"}
    edges = {(o["from"], o["to"], o["rel"]) for o in first if o["op"] == "link"}
    annotated = {o["node"] for o in first if o["op"] == "annotate"}
    second = map_brain_to_ops(
        TINY_BRAIN,
        ts="t2",
        existing_ids=ids,
        existing_edges=edges,
        existing_annotated=annotated,
    )
    assert second == []


def test_create_concept_project_kind_in_replay() -> None:
    graph = {"version": 1, "generated": "x", "nodes": [], "edges": []}
    ops = [
        {
            "op": "create_concept",
            "ts": "1",
            "device": "pc",
            "id": "project:proj-x",
            "label": "X",
        },
        {
            "op": "annotate",
            "ts": "2",
            "device": "pc",
            "node": "project:proj-x",
            "text": "note",
        },
    ]
    out = curation.apply_ops_to_graph(graph, ops)
    node = next(n for n in out["nodes"] if n["id"] == "project:proj-x")
    assert node["kind"] == "project"
    assert node["annotation"] == "note"


def test_run_import_writes_ops(tmp_path: Path) -> None:
    src = tmp_path / "brain.json"
    src.write_text(json.dumps(TINY_BRAIN), encoding="utf-8")
    vault = tmp_path / "vault"
    vault.mkdir()
    (vault / "entries").mkdir()
    result = run_import_knowledgebase(
        vault, source=src, dry_run=False, apply=False
    )
    assert result["kb_nodes"] == 2
    assert result["kb_links"] == 1
    assert result["created"] == 2
    assert result["linked"] == 1
    ops_path = vault / "curation" / "ops" / "pc.jsonl"
    assert ops_path.is_file()
    lines = [json.loads(ln) for ln in ops_path.read_text().splitlines() if ln.strip()]
    assert any(o["op"] == "create_concept" for o in lines)

    # Second run appends nothing new
    again = run_import_knowledgebase(vault, source=src, dry_run=False, apply=False)
    assert again["ops_appended"] == 0


def test_default_source_points_at_real_kb() -> None:
    assert DEFAULT_KB_SOURCE.name == "brain.json"
    assert "KnowledgeBase" in str(DEFAULT_KB_SOURCE)
    if DEFAULT_KB_SOURCE.is_file():
        data = json.loads(DEFAULT_KB_SOURCE.read_text(encoding="utf-8"))
        assert isinstance(data.get("nodes"), list)
        assert len(data["nodes"]) >= 1


def test_import_real_kb_into_temp_vault(tmp_path: Path, chronicle_dir: Path) -> None:
    """Smoke: real KnowledgeBase/brain.json → ops → brain graph (if file present)."""
    if not DEFAULT_KB_SOURCE.is_file():
        return
    # Use fixture vault (has entries) so apply can run
    result = run_import_knowledgebase(
        chronicle_dir,
        source=DEFAULT_KB_SOURCE,
        dry_run=False,
        apply=True,
    )
    assert result["kb_nodes"] >= 1
    assert result["created"] == result["kb_nodes"]
    assert result["linked"] == result["kb_links"]
    graph = read_json(chronicle_dir / "brain" / "graph.json")
    ids = {n["id"] for n in graph["nodes"]}
    assert any(i.startswith("concept:") for i in ids)
    assert any(i.startswith("project:") for i in ids)
    proj = next(n for n in graph["nodes"] if n["id"].startswith("project:"))
    assert proj["kind"] == "project"


def test_migrate_kb_emits_set_doc(tmp_path: Path) -> None:
    from chronicle_pipeline.migrate_kb import (
        apply_groups_to_graph,
        copy_kb_tree,
        emit_set_doc_ops,
        resolve_kb_file_to_doc,
    )

    kb = tmp_path / "KnowledgeBase"
    (kb / "ReadMe").mkdir(parents=True)
    (kb / "Docs" / "ResumePoints").mkdir(parents=True)
    (kb / "ReadMe" / "alpha_README.md").write_text("# Alpha\n", encoding="utf-8")
    (kb / "Docs" / "ResumePoints" / "Alpha_Points.md").write_text("- star\n", encoding="utf-8")
    (kb / "Docs" / "knowledge.json").write_text("{}", encoding="utf-8")
    (kb / "Docs" / "cv.pdf").write_bytes(b"%PDF")
    (kb / "KnowledgeMap.md").write_text("# Map\n", encoding="utf-8")

    brain = {
        "groups": {"ai": {"label": "AI", "color": "#5C4F8A"}},
        "nodes": [
            {
                "id": "alpha",
                "label": "Alpha Project",
                "group": "ai",
                "file": "alpha_README.md",
            },
            {"id": "skills", "label": "Skills", "file": "knowledge.json"},
        ],
        "links": [],
    }

    vault = tmp_path / "vault"
    vault.mkdir()
    stats = copy_kb_tree(kb, vault, dry_run=False, brain=brain)
    assert (vault / "kb" / "notes" / "alpha_README.md").is_file()
    assert (vault / "kb" / "notes" / "ResumePoints" / "Alpha_Points.md").is_file()
    assert (vault / "kb" / "notes" / "KnowledgeMap.md").is_file()
    assert (vault / "kb" / "knowledge.json").is_file()
    assert (vault / "kb" / "files" / "cv.pdf").is_file()
    assert stats["notes"] >= 3
    assert stats["frontmatter"] >= 3

    alpha_text = (vault / "kb" / "notes" / "alpha_README.md").read_text(encoding="utf-8")
    assert alpha_text.startswith("---\n")
    assert "title: Alpha Project" in alpha_text
    assert "group: ai" in alpha_text
    assert "source: KnowledgeBase/ReadMe/alpha_README.md" in alpha_text
    assert "tags:" in alpha_text
    assert "# Alpha" in alpha_text

    assert resolve_kb_file_to_doc("alpha_README.md", stats["note_paths"]) == (
        "kb/notes/alpha_README.md"
    )
    assert resolve_kb_file_to_doc("knowledge.json", stats["note_paths"]) is None

    ops = emit_set_doc_ops(vault, brain, stats["note_paths"], dry_run=False)
    assert len(ops) == 1
    assert ops[0]["op"] == "set_doc"
    assert ops[0]["node"] == "concept:alpha"
    assert ops[0]["doc"] == "kb/notes/alpha_README.md"
    # Idempotent
    again = emit_set_doc_ops(vault, brain, stats["note_paths"], dry_run=False)
    assert again == []

    # Groups stamped onto graph after brain-shaped skeleton exists
    (vault / "brain").mkdir(parents=True)
    (vault / "brain" / "graph.json").write_text(
        json.dumps(
            {
                "version": 1,
                "generated": "x",
                "nodes": [
                    {"id": "concept:alpha", "kind": "concept", "label": "Alpha Project"}
                ],
                "edges": [],
            }
        ),
        encoding="utf-8",
    )
    gstats = apply_groups_to_graph(vault, brain, dry_run=False)
    assert gstats["groups"] == 1
    assert gstats["nodes_tagged"] == 1
    graph = json.loads((vault / "brain" / "graph.json").read_text(encoding="utf-8"))
    assert graph["groups"]["ai"]["label"] == "AI"
    assert graph["nodes"][0]["group"] == "ai"
    meta = json.loads((vault / "brain" / "kb_meta.json").read_text(encoding="utf-8"))
    assert meta["node_groups"]["concept:alpha"] == "ai"

