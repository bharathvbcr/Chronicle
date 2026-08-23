"""Ornith AI layer: think-tag strip, graph-aware retrieval helpers."""

from __future__ import annotations

from pathlib import Path

from chronicle_pipeline import ollama, rag
from chronicle_pipeline.paths import atomic_write_json


def test_strip_think_blocks() -> None:
    raw = "<think>\nreasoning here\n</think>\n{\"ok\": true}"
    assert ollama.strip_think_blocks(raw) == '{"ok": true}'
    assert ollama.extract_json(raw) == {"ok": True}


def test_strip_unclosed_think() -> None:
    raw = "<think>still thinking"
    assert ollama.strip_think_blocks(raw) == ""


def test_chat_options_include_sampling(monkeypatch) -> None:
    captured: dict = {}

    class FakeResp:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"message": {"content": "<think>x</think>hello"}}

    def fake_post(url, json=None, timeout=None):  # noqa: A002
        captured["url"] = url
        captured["json"] = json
        return FakeResp()

    monkeypatch.setattr(ollama.requests, "post", fake_post)
    out = ollama.chat([{"role": "user", "content": "hi"}], num_ctx=131072)
    assert out == "hello"
    opts = captured["json"]["options"]
    assert opts["temperature"] == 0.6
    assert opts["top_p"] == 0.95
    assert opts["top_k"] == 20
    assert opts["num_ctx"] == 131072


def test_neighbor_and_graph_hits(chronicle_dir: Path) -> None:
    entry_id = "2026-07-09_090015-an"
    graph = {
        "version": 1,
        "generated": "x",
        "nodes": [
            {
                "id": f"entry:{entry_id}",
                "kind": "entry",
                "label": "A",
                "entry_id": entry_id,
            },
            {
                "id": "concept:x",
                "kind": "concept",
                "label": "X",
                "doc": "10-Work/x.md",
            },
        ],
        "edges": [
            {
                "from": f"entry:{entry_id}",
                "to": "concept:x",
                "rel": "mentions",
                "score": 1,
            }
        ],
    }
    atomic_write_json(chronicle_dir / "brain" / "graph.json", graph)
    note = chronicle_dir / "10-Work" / "x.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text("# X\nLinked note body.\n", encoding="utf-8")

    # Index the note so get_documents_by_ids can find it
    from chronicle_pipeline.index_store import run_index

    run_index(chronicle_dir, force=True)

    seed = f"entry:{entry_id}"
    expanded = rag.neighbor_node_ids(graph, [seed], hops=1)
    assert seed in expanded and "concept:x" in expanded
    hits = rag.graph_aware_hits(chronicle_dir, [seed])
    ids = {h["id"] for h in hits}
    assert entry_id in ids or "10-Work/x.md" in ids


def test_multi_day_rollup_context(chronicle_dir: Path) -> None:
    weekly = chronicle_dir / "notes" / "weekly"
    weekly.mkdir(parents=True, exist_ok=True)
    from datetime import date, timedelta

    d = date.today() - timedelta(days=3)
    # Align to Monday-ish iso date filename
    (weekly / f"{d.isoformat()}.md").write_text("# Week\nRollup summary.\n", encoding="utf-8")
    hits = rag.multi_day_rollup_context(chronicle_dir, around=date.today(), days=14)
    assert hits
    assert hits[0]["from_rollup"] is True
    assert "Rollup" in hits[0]["text"]
