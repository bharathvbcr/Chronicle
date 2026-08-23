"""Ask / Resume response-shape tests (mocked Ollama — no live e2e)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from chronicle_pipeline import rag
from chronicle_pipeline.index_store import run_index
from chronicle_pipeline.serve import create_app


def _seed_kb(chronicle_dir: Path) -> None:
    note = chronicle_dir / "10-Work" / "ResumePoints" / "demo.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text(
        "# Demo\nBuilt a Python pipeline with measurable outcomes.\n",
        encoding="utf-8",
    )
    skill = chronicle_dir / "10-Work" / "skills.md"
    skill.write_text("# Skills\nPython FastAPI RAG\n", encoding="utf-8")
    run_index(chronicle_dir, force=True)


def test_ask_response_shape_with_mocked_ollama(chronicle_dir: Path) -> None:
    _seed_kb(chronicle_dir)
    fake_json = json.dumps(
        {
            "what_i_did": "Searched KB notes",
            "why_relevant": "Matches Python skills",
            "evidence": [{"file": "10-Work/skills.md", "snippet": "Python FastAPI"}],
        }
    )

    provider = MagicMock()
    provider.reachable.return_value = True
    provider.chat.return_value = fake_json
    with (
        patch("chronicle_pipeline.rag.llm.try_get_provider", return_value=provider),
        patch("chronicle_pipeline.rag.llm.provider_name", return_value="ollama"),
        patch(
            "chronicle_pipeline.rag.llm.extract_json",
            return_value=json.loads(fake_json),
        ),
    ):
        out = rag.ask(chronicle_dir, "What Python skills do I have?")

    assert out["ok"] is True
    assert out["error"] is None
    assert isinstance(out["what_i_did"], str) and out["what_i_did"]
    assert isinstance(out["why_relevant"], str) and out["why_relevant"]
    assert isinstance(out["answer"], str) and out["answer"]
    assert isinstance(out["evidence"], list)
    assert out["evidence"]
    assert "file" in out["evidence"][0]
    assert "snippet" in out["evidence"][0]


def test_ask_offline_shape_without_ollama(chronicle_dir: Path) -> None:
    _seed_kb(chronicle_dir)
    provider = MagicMock()
    provider.reachable.return_value = False
    with (
        patch("chronicle_pipeline.rag.llm.try_get_provider", return_value=provider),
        patch("chronicle_pipeline.rag.llm.provider_name", return_value="ollama"),
    ):
        out = rag.ask(chronicle_dir, "Python skills")

    assert out["ok"] is True
    assert out["error"] is None
    assert "what_i_did" in out and "why_relevant" in out
    assert isinstance(out["evidence"], list)
    assert isinstance(out["answer"], str)


def test_resume_response_shape_with_mocked_ollama(chronicle_dir: Path) -> None:
    _seed_kb(chronicle_dir)
    fake_json = json.dumps(
        {
            "bullets": ["Shipped FastAPI RAG with measurable latency wins"],
            "notes": "Prefer STAR bullets from ResumePoints",
        }
    )

    provider = MagicMock()
    provider.reachable.return_value = True
    provider.chat.return_value = fake_json
    with (
        patch("chronicle_pipeline.rag.llm.try_get_provider", return_value=provider),
        patch("chronicle_pipeline.rag.llm.provider_name", return_value="ollama"),
        patch(
            "chronicle_pipeline.rag.llm.extract_json",
            return_value=json.loads(fake_json),
        ),
    ):
        out = rag.resume(chronicle_dir, "Backend engineer")

    assert out["ok"] is True
    assert out["error"] is None
    assert isinstance(out["bullets"], list)
    assert out["bullets"] and isinstance(out["bullets"][0], str)
    assert isinstance(out["notes"], str)


def test_serve_ask_resume_http_shapes(chronicle_dir: Path) -> None:
    _seed_kb(chronicle_dir)
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)

    ask_payload: dict[str, Any] = {
        "ok": True,
        "what_i_did": "x",
        "why_relevant": "y",
        "evidence": [{"file": "10-Work/skills.md", "snippet": "z"}],
        "answer": "full",
        "error": None,
    }
    resume_payload: dict[str, Any] = {
        "ok": True,
        "bullets": ["one"],
        "notes": "n",
        "error": None,
    }

    with (
        patch("chronicle_pipeline.api.ask.rag.ask", return_value=ask_payload),
        patch("chronicle_pipeline.api.ask.rag.resume", return_value=resume_payload),
    ):
        ask_r = client.post("/ask", json={"question": "skills?"})
        resume_r = client.post("/resume", json={"role": "engineer"})

    assert ask_r.status_code == 200
    body = ask_r.json()
    for key in ("ok", "what_i_did", "why_relevant", "evidence", "answer"):
        assert key in body

    assert resume_r.status_code == 200
    rbody = resume_r.json()
    for key in ("ok", "bullets", "notes"):
        assert key in rbody
    assert isinstance(rbody["bullets"], list)
