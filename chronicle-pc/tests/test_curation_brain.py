"""Curation replay and brain artifacts."""

from __future__ import annotations

import json
from pathlib import Path

from chronicle_pipeline import curation
from chronicle_pipeline.brain import run_brain
from chronicle_pipeline.paths import read_json
from chronicle_pipeline.process import run_process


def test_curation_replay_applies_ops(chronicle_dir: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=True)
    graph = read_json(chronicle_dir / "brain" / "graph.json")
    node_ids = {n["id"] for n in graph["nodes"]}
    assert "concept:startup-idea" in node_ids
    work = next(n for n in graph["nodes"] if n["id"] == "topic:work")
    assert work.get("pinned") is True
    edge_pairs = {(e["from"], e["to"], e["rel"]) for e in graph["edges"]}
    assert (
        "entry:2026-07-09_090015-an",
        "concept:startup-idea",
        "manual",
    ) in edge_pairs


def test_apply_ops_last_write_wins() -> None:
    graph = {
        "version": 1,
        "generated": "x",
        "nodes": [{"id": "topic:a", "kind": "topic", "label": "a"}],
        "edges": [],
    }
    ops = [
        {"op": "rename", "ts": "1", "device": "pc", "node": "topic:a", "label": "first"},
        {"op": "rename", "ts": "2", "device": "pc", "node": "topic:a", "label": "second"},
        {"op": "pin", "ts": "3", "device": "pc", "node": "topic:a"},
    ]
    out = curation.apply_ops_to_graph(graph, ops)
    node = out["nodes"][0]
    assert node["label"] == "second"
    assert node["pinned"] is True


def test_set_doc_and_delete_concept_replay() -> None:
    graph = {
        "version": 1,
        "generated": "x",
        "nodes": [
            {"id": "concept:alpha", "kind": "concept", "label": "Alpha"},
            {"id": "concept:beta", "kind": "concept", "label": "Beta"},
            {"id": "topic:keep", "kind": "topic", "label": "keep"},
        ],
        "edges": [
            {"from": "concept:alpha", "to": "concept:beta", "rel": "manual", "score": 1},
            {"from": "topic:keep", "to": "concept:alpha", "rel": "about", "score": 1},
        ],
    }
    ops = [
        {
            "op": "set_doc",
            "ts": "1",
            "device": "pc",
            "node": "concept:alpha",
            "doc": "kb/notes/alpha.md",
        },
        {"op": "delete_concept", "ts": "2", "device": "pc", "node": "concept:beta"},
    ]
    out = curation.apply_ops_to_graph(graph, ops)
    ids = {n["id"] for n in out["nodes"]}
    assert "concept:alpha" in ids
    assert "concept:beta" not in ids
    assert "topic:keep" in ids
    alpha = next(n for n in out["nodes"] if n["id"] == "concept:alpha")
    assert alpha["doc"] == "kb/notes/alpha.md"
    edge_ends = {(e["from"], e["to"]) for e in out["edges"]}
    assert ("concept:alpha", "concept:beta") not in edge_ends
    assert ("topic:keep", "concept:alpha") in edge_ends


def test_delete_concept_ignores_non_concept() -> None:
    graph = {
        "version": 1,
        "generated": "x",
        "nodes": [{"id": "topic:work", "kind": "topic", "label": "work"}],
        "edges": [],
    }
    out = curation.apply_ops_to_graph(
        graph,
        [{"op": "delete_concept", "ts": "1", "device": "pc", "node": "topic:work"}],
    )
    assert out["nodes"][0]["id"] == "topic:work"


def test_compact_ops_retains_set_doc_and_delete_concept(tmp_path: Path) -> None:
    root = tmp_path / "vault"
    ops_path = root / "curation" / "ops" / "pc.jsonl"
    ops_path.parent.mkdir(parents=True)
    lines = [
        {
            "op": "create_concept",
            "ts": "1",
            "device": "pc",
            "id": "concept:keep",
            "label": "Keep",
        },
        {
            "op": "create_concept",
            "ts": "2",
            "device": "pc",
            "id": "concept:gone",
            "label": "Gone",
        },
        {
            "op": "set_doc",
            "ts": "3",
            "device": "pc",
            "node": "concept:keep",
            "doc": "kb/notes/keep.md",
        },
        {
            "op": "set_doc",
            "ts": "4",
            "device": "pc",
            "node": "concept:keep",
            "doc": "kb/notes/keep-v2.md",
        },
        {"op": "delete_concept", "ts": "5", "device": "pc", "node": "concept:gone"},
        {"op": "pin", "ts": "6", "device": "pc", "node": "concept:keep"},
    ]
    ops_path.write_text(
        "".join(json.dumps(o) + "\n" for o in lines),
        encoding="utf-8",
    )
    stats = curation.compact_ops(root, dry_run=False)
    assert stats["before"] == 6
    compacted = [
        json.loads(ln)
        for ln in ops_path.read_text(encoding="utf-8").splitlines()
        if ln.strip()
    ]
    ops_kinds = {o["op"] for o in compacted}
    assert "set_doc" in ops_kinds
    assert "delete_concept" not in ops_kinds  # create+delete cancelled
    assert not any(o.get("id") == "concept:gone" for o in compacted)
    set_docs = [o for o in compacted if o["op"] == "set_doc"]
    assert len(set_docs) == 1
    assert set_docs[0]["doc"] == "kb/notes/keep-v2.md"
    assert any(o["op"] == "create_concept" and o["id"] == "concept:keep" for o in compacted)


def test_compact_ops_keeps_orphan_delete(tmp_path: Path) -> None:
    """delete_concept without a matching create in the same log must survive."""
    root = tmp_path / "vault"
    ops_path = root / "curation" / "ops" / "pc.jsonl"
    ops_path.parent.mkdir(parents=True)
    ops_path.write_text(
        json.dumps(
            {
                "op": "delete_concept",
                "ts": "1",
                "device": "pc",
                "node": "concept:imported",
            }
        )
        + "\n",
        encoding="utf-8",
    )
    curation.compact_ops(root, dry_run=False)
    compacted = [
        json.loads(ln)
        for ln in ops_path.read_text(encoding="utf-8").splitlines()
        if ln.strip()
    ]
    assert len(compacted) == 1
    assert compacted[0]["op"] == "delete_concept"


def test_brain_insights_and_enrich(chronicle_dir: Path) -> None:
    run_process(chronicle_dir, dry_run=False, run_brain=False)
    run_brain(chronicle_dir, dry_run=False)
    enrich = chronicle_dir / "brain" / "enrich" / "2026-07.json"
    assert enrich.is_file()
    data = read_json(enrich)
    assert "entries" in data
    insight = chronicle_dir / "brain" / "insights" / "2026" / "2026-07-09.json"
    assert insight.is_file()
    ins = read_json(insight)
    assert ins["date"] == "2026-07-09"
    assert "related_entries" in ins
    assert (chronicle_dir / "brain" / "prompts.json").is_file()
