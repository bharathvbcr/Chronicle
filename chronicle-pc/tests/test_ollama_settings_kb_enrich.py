"""Config ollama block, /models API, and KB enrichment idempotency."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from chronicle_pipeline import kb_enrich, ollama
from chronicle_pipeline.config import ensure_config, load_config, save_config
from chronicle_pipeline.models import ChronicleConfig, OllamaOptions
from chronicle_pipeline.paths import content_hash
from chronicle_pipeline.serve import create_app


def test_config_ollama_defaults_and_round_trip(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    assert cfg.ollama.base_url == "http://localhost:11434"
    assert cfg.ollama.num_ctx == 32768
    assert cfg.ollama.temperature is None

    cfg.ollama = OllamaOptions(
        base_url="http://127.0.0.1:11434",
        num_ctx=8192,
        temperature=0.4,
    )
    save_config(cfg, chronicle_dir)

    assert ollama.OLLAMA_BASE == "http://127.0.0.1:11434"
    assert ollama.DEFAULT_NUM_CTX == 8192
    assert ollama.GLOBAL_TEMPERATURE == 0.4

    reloaded = load_config(chronicle_dir)
    assert reloaded.ollama.base_url == "http://127.0.0.1:11434"
    assert reloaded.ollama.num_ctx == 8192
    assert reloaded.ollama.temperature == 0.4

    # Clear temperature override (null = per-feature defaults)
    reloaded.ollama.temperature = None
    save_config(reloaded, chronicle_dir)
    again = load_config(chronicle_dir)
    assert again.ollama.temperature is None
    assert ollama.GLOBAL_TEMPERATURE is None


def test_config_merges_partial_ollama_block(tmp_path: Path) -> None:
    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text(
        json.dumps(
            {
                "version": 1,
                "layout_version": 2,
                "timezone": "UTC",
                "models": {"llm": "maxwell1500/ornith-35b:Q4_K_M"},
                "ollama": {"num_ctx": 2048},
            }
        ),
        encoding="utf-8",
    )
    cfg = load_config(root)
    assert cfg.ollama.num_ctx == 2048
    assert cfg.ollama.base_url == "http://localhost:11434"
    assert cfg.ollama.temperature is None


def test_ensure_config_writes_ollama(tmp_path: Path) -> None:
    root = tmp_path / "empty"
    root.mkdir()
    cfg = ensure_config(root)
    assert isinstance(cfg, ChronicleConfig)
    raw = json.loads((root / "config.json").read_text(encoding="utf-8"))
    assert "ollama" in raw
    assert raw["ollama"]["base_url"] == "http://localhost:11434"
    assert raw["ollama"]["num_ctx"] == 32768
    assert raw["models"]["llm"] == "maxwell1500/ornith-35b:Q4_K_M"


def test_get_models_with_mocked_tags(chronicle_dir: Path) -> None:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    tags = ["maxwell1500/ornith-35b:Q4_K_M", "nomic-embed-text", "llama3.2-vision:11b"]
    with (
        patch("chronicle_pipeline.api.system.ollama.list_available_models", return_value=tags),
        patch("chronicle_pipeline.api.system.ollama.ollama_reachable", return_value=True),
    ):
        r = client.get("/models")
    assert r.status_code == 200
    data = r.json()
    assert data["llm"] == "maxwell1500/ornith-35b:Q4_K_M"
    assert data["embed"] == "nomic-embed-text"
    assert data["vision"] == "llama3.2-vision:11b"
    assert data["available"] == tags
    assert data["ollama_ok"] is True
    assert data["provider"] == "ollama"
    assert data["provider_ok"] is True
    assert data["base_url"]
    assert data["num_ctx"] == 32768
    assert data["temperature"] is None
    assert "cloud_consent" in data


def test_get_models_when_ollama_down(chronicle_dir: Path) -> None:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    with (
        patch("chronicle_pipeline.api.system.ollama.list_available_models", return_value=[]),
        patch("chronicle_pipeline.api.system.ollama.ollama_reachable", return_value=False),
    ):
        r = client.get("/models")
    assert r.status_code == 200
    data = r.json()
    assert data["available"] == []
    assert data["ollama_ok"] is False


def test_post_models_rejects_unknown_when_ollama_up(chronicle_dir: Path) -> None:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    tags = ["maxwell1500/ornith-35b:Q4_K_M", "nomic-embed-text"]
    with patch(
        "chronicle_pipeline.api.system.ollama.list_available_models", return_value=tags
    ):
        r = client.post("/models", json={"llm": "not-a-real-model"})
    assert r.status_code == 400
    assert "not-a-real-model" in str(r.json()["detail"])


def test_post_models_updates_and_persists(chronicle_dir: Path) -> None:
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    tags = ["gemma2:9b", "nomic-embed-text", "llava:7b"]
    with (
        patch("chronicle_pipeline.api.system.ollama.list_available_models", return_value=tags),
        patch("chronicle_pipeline.api.system.ollama.ollama_reachable", return_value=True),
    ):
        r = client.post(
            "/models",
            json={
                "llm": "gemma2:9b",
                "vision": "llava:7b",
                "base_url": "http://localhost:11434",
                "num_ctx": 8192,
                "temperature": 0.55,
            },
        )
    assert r.status_code == 200
    data = r.json()
    assert data["llm"] == "gemma2:9b"
    assert data["vision"] == "llava:7b"
    assert data["num_ctx"] == 8192
    assert data["temperature"] == 0.55

    cfg = load_config(chronicle_dir)
    assert cfg.models.llm == "gemma2:9b"
    assert cfg.models.vision == "llava:7b"
    assert cfg.ollama.num_ctx == 8192
    assert cfg.ollama.temperature == 0.55


def test_post_models_clears_temperature(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    cfg.ollama.temperature = 0.7
    save_config(cfg, chronicle_dir)

    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    with patch(
        "chronicle_pipeline.api.system.ollama.list_available_models", return_value=[]
    ):
        r = client.post("/models", json={"temperature": None})
    assert r.status_code == 200
    assert r.json()["temperature"] is None
    assert load_config(chronicle_dir).ollama.temperature is None


def _seed_kb_note(chronicle_dir: Path) -> Path:
    note = chronicle_dir / "10-Work" / "ResumePoints" / "demo.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text(
        "# Demo\nBuilt a Python pipeline reducing latency by 40%.\n",
        encoding="utf-8",
    )
    return note


def _fake_provider(*, reachable: bool = True, chat_return: str = "{}"):

    provider = MagicMock()
    provider.reachable.return_value = reachable
    provider.chat.return_value = chat_return
    return provider


def test_kb_enrich_idempotent_and_force(chronicle_dir: Path) -> None:
    note = _seed_kb_note(chronicle_dir)
    text = note.read_text(encoding="utf-8")
    fake = {
        "summary": "Python pipeline with latency wins",
        "skills": ["Python", "FastAPI"],
        "highlights": ["Cut latency 40% on RAG path"],
    }
    provider = _fake_provider(chat_return=json.dumps(fake))

    with (
        patch(
            "chronicle_pipeline.kb_enrich.llm.try_get_provider",
            return_value=provider,
        ),
        patch("chronicle_pipeline.kb_enrich.llm.provider_name", return_value="ollama"),
        patch(
            "chronicle_pipeline.kb_enrich.llm.extract_json",
            return_value=fake,
        ),
    ):
        first = kb_enrich.run_kb_enrich(chronicle_dir)
        assert first["enriched"] == 1
        assert first["skipped"] == 0
        assert first["failed"] == 0
        assert provider.chat.call_count == 1

        second = kb_enrich.run_kb_enrich(chronicle_dir)
        assert second["enriched"] == 0
        assert second["skipped"] == 1
        assert provider.chat.call_count == 1

        forced = kb_enrich.run_kb_enrich(chronicle_dir, force=True)
        assert forced["enriched"] == 1
        assert forced["skipped"] == 0
        assert provider.chat.call_count == 2

    cache = kb_enrich.load_enrich_cache(chronicle_dir)
    doc_id = "10-Work/ResumePoints/demo.md"
    entry = cache["notes"][doc_id]
    assert entry["content_hash"] == content_hash(text)
    assert entry["summary"] == fake["summary"]
    assert entry["skills"] == fake["skills"]
    assert entry["highlights"] == fake["highlights"]
    assert "Skills: Python" in kb_enrich.format_enrichment_prefix(entry)


def test_kb_enrich_offline_noop(chronicle_dir: Path) -> None:
    _seed_kb_note(chronicle_dir)
    with patch(
        "chronicle_pipeline.kb_enrich.llm.try_get_provider",
        return_value=None,
    ):
        out = kb_enrich.run_kb_enrich(chronicle_dir)
    assert out["provider_ok"] is False
    assert out["enriched"] == 0
    assert out["skipped"] == 1
    cache = kb_enrich.load_enrich_cache(chronicle_dir)
    assert cache["notes"] == {}


def test_post_enrich_kb_endpoint(chronicle_dir: Path) -> None:
    _seed_kb_note(chronicle_dir)
    app = create_app(chronicle_dir, connect_info={"base": "http://127.0.0.1:8765"})
    client = TestClient(app)
    fake = {
        "summary": "Demo project",
        "skills": ["Python"],
        "highlights": ["Shipped pipeline"],
    }
    provider = _fake_provider(chat_return=json.dumps(fake))
    with (
        patch(
            "chronicle_pipeline.kb_enrich.llm.try_get_provider",
            return_value=provider,
        ),
        patch("chronicle_pipeline.kb_enrich.llm.provider_name", return_value="ollama"),
        patch(
            "chronicle_pipeline.kb_enrich.llm.extract_json",
            return_value=fake,
        ),
        patch(
            "chronicle_pipeline.api.system.index_store.run_index",
            return_value={"upserted": 1, "skipped": 0},
        ),
    ):
        r = client.post("/enrich/kb")
    assert r.status_code == 200
    data = r.json()
    assert data["enriched"] == 1
    assert data["index"]["upserted"] == 1
