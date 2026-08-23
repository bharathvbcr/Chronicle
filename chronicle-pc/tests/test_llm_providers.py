"""Multi-provider LLM facade: factory, consent, caps, Grok/Vertex mocks."""

from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import patch

import pytest

from chronicle_pipeline import llm
from chronicle_pipeline.config import load_config, save_config
from chronicle_pipeline.llm.grok_provider import GrokProvider
from chronicle_pipeline.llm.ollama_provider import OllamaProvider
from chronicle_pipeline.llm.protocol import LlmError
from chronicle_pipeline.llm.vertex_provider import VertexProvider
from chronicle_pipeline.models import LlmOptions


def test_factory_defaults_to_ollama(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    assert llm.provider_name(cfg) == "ollama"
    provider = llm.get_provider(cfg)
    assert isinstance(provider, OllamaProvider)
    assert provider.name == "ollama"


def test_cloud_without_consent_raises(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    cfg.llm = LlmOptions(provider="grok", cloud_consent=False)
    save_config(cfg, chronicle_dir)
    cfg = load_config(chronicle_dir)
    with pytest.raises(LlmError, match="consent"):
        llm.get_provider(cfg, secrets={"grok_api_key": "x"})


def test_grok_with_consent_builds(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    cfg.llm = LlmOptions(provider="grok", cloud_consent=True)
    save_config(cfg, chronicle_dir)
    cfg = load_config(chronicle_dir)
    provider = llm.get_provider(cfg, secrets={"grok_api_key": "test-key"})
    assert isinstance(provider, GrokProvider)
    assert provider.name == "grok"


def test_context_limits_stricter_for_cloud() -> None:
    local = llm.context_limits("ollama")
    cloud = llm.context_limits("grok")
    assert cloud.hit_text_limit < local.hit_text_limit
    assert cloud.recall_top_k < local.recall_top_k
    assert cloud.num_ctx_recall < local.num_ctx_recall


def test_rate_limit_cloud_only() -> None:
    from chronicle_pipeline.llm import rate_limit

    llm.reset_for_tests()
    assert rate_limit.allow_cloud_request(provider="ollama") is True
    for _ in range(rate_limit.DEFAULT_MAX_REQUESTS):
        assert rate_limit.allow_cloud_request(provider="grok") is True
    assert rate_limit.allow_cloud_request(provider="grok") is False
    llm.reset_for_tests()


def test_grok_chat_mocked() -> None:
    provider = GrokProvider(api_key="k", default_model="grok-2")

    class Resp:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, Any]:
            return {
                "choices": [{"message": {"content": '{"ok": true}'}}],
            }

    with patch("chronicle_pipeline.llm.grok_provider.requests.post", return_value=Resp()):
        out = provider.chat([{"role": "user", "content": "hi"}], format_json=True)
    assert "ok" in out


def test_vertex_extracts_generate_content() -> None:
    provider = VertexProvider(
        project="demo",
        location="us-central1",
        default_model="gemini-2.0-flash-001",
        access_token="tok",
    )

    class Resp:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, Any]:
            return {
                "candidates": [
                    {"content": {"parts": [{"text": "hello from vertex"}]}}
                ]
            }

    with patch(
        "chronicle_pipeline.llm.vertex_provider.requests.post", return_value=Resp()
    ):
        out = provider.chat([{"role": "user", "content": "hi"}])
    assert out == "hello from vertex"


def test_vision_cloud_requires_extra_consent(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    cfg.llm = LlmOptions(
        provider="grok",
        cloud_consent=True,
        vision_cloud_consent=False,
    )
    with pytest.raises(LlmError, match="Vision"):
        llm.require_vision_cloud_consent(
            "grok",
            cloud_consent=True,
            vision_consent=False,
            secrets={},
        )


def test_secrets_env_override(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    from chronicle_pipeline.llm import secrets as secrets_mod

    secrets_file = tmp_path / "secrets.json"
    secrets_file.write_text('{"grok_api_key": "from-file"}', encoding="utf-8")
    monkeypatch.setenv("CHRONICLE_SECRETS", str(secrets_file))
    monkeypatch.delenv("GROK_API_KEY", raising=False)
    assert secrets_mod.grok_api_key() == "from-file"
    monkeypatch.setenv("GROK_API_KEY", "from-env")
    assert secrets_mod.grok_api_key() == "from-env"


def test_ollama_provider_delegates() -> None:
    provider = OllamaProvider()
    with (
        patch(
            "chronicle_pipeline.llm.ollama_provider.ollama_mod.ollama_reachable",
            return_value=True,
        ),
        patch(
            "chronicle_pipeline.llm.ollama_provider.ollama_mod.chat",
            return_value="hi",
        ) as chat,
    ):
        assert provider.reachable() is True
        assert provider.chat([{"role": "user", "content": "x"}]) == "hi"
        chat.assert_called_once()


# --- Non-JSON HTTP 200 from a provider must degrade, not crash (captive
# portal / proxy HTML / truncated body). Regression: r.json() sat outside the
# RequestException handler and leaked JSONDecodeError through try_chat. ---
def _html_resp() -> Any:
    import requests as requests_mod

    class Resp:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self) -> Any:
            raise requests_mod.exceptions.JSONDecodeError("Expecting value", "<html>", 0)

    return Resp()


def test_grok_try_chat_survives_non_json_200() -> None:
    provider = GrokProvider(api_key="k", default_model="grok-2")
    with patch("chronicle_pipeline.llm.grok_provider.requests.post", return_value=_html_resp()):
        with patch.object(GrokProvider, "reachable", return_value=True):
            assert provider.try_chat([{"role": "user", "content": "x"}]) is None


def test_vertex_try_chat_survives_non_json_200(chronicle_dir: Path) -> None:
    cfg = load_config(chronicle_dir)
    cfg.llm = LlmOptions(provider="vertex", cloud_consent=True)
    save_config(cfg, chronicle_dir)
    provider = VertexProvider(project="test-project", default_model="gemini-2.0-flash")
    with patch("chronicle_pipeline.llm.vertex_provider.requests.post", return_value=_html_resp()):
        assert provider.try_chat([{"role": "user", "content": "x"}]) is None


def test_ollama_chat_non_json_raises_ollama_error() -> None:
    from chronicle_pipeline import ollama as ollama_mod

    with patch("chronicle_pipeline.ollama.requests.post", return_value=_html_resp()):
        with pytest.raises(ollama_mod.OllamaError):
            ollama_mod.chat([{"role": "user", "content": "x"}])


def test_ollama_embed_non_json_raises_ollama_error() -> None:
    from chronicle_pipeline import ollama as ollama_mod

    with patch("chronicle_pipeline.ollama.requests.post", return_value=_html_resp()):
        with pytest.raises(ollama_mod.OllamaError):
            ollama_mod.embed("hello world")
