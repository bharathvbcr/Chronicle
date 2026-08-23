"""URL allowlists for Ollama (private) and Grok (api.x.ai)."""

from __future__ import annotations

import pytest

from chronicle_pipeline.url_allowlist import (
    is_allowed_grok_url,
    is_private_or_loopback_url,
    validate_grok_base_url,
    validate_ollama_base_url,
)


def test_ollama_private_urls_ok() -> None:
    assert is_private_or_loopback_url("http://127.0.0.1:11434")
    assert is_private_or_loopback_url("http://localhost:11434")
    assert is_private_or_loopback_url("http://192.168.1.10:11434")
    assert is_private_or_loopback_url("http://10.0.0.5:11434")
    assert validate_ollama_base_url("http://127.0.0.1:11434") == "http://127.0.0.1:11434"


def test_ollama_public_urls_blocked() -> None:
    assert not is_private_or_loopback_url("https://evil.example.com")
    assert not is_private_or_loopback_url("http://8.8.8.8:11434")
    with pytest.raises(ValueError, match="private or loopback"):
        validate_ollama_base_url("https://evil.example.com/v1")


def test_grok_only_api_x_ai() -> None:
    assert is_allowed_grok_url("https://api.x.ai/v1")
    assert is_allowed_grok_url("https://api.x.ai/v1/")
    assert validate_grok_base_url("https://api.x.ai/v1") == "https://api.x.ai/v1"
    assert not is_allowed_grok_url("http://api.x.ai/v1")
    assert not is_allowed_grok_url("https://api.x.ai.evil.com/v1")
    assert not is_allowed_grok_url("https://evil.example.com")
    with pytest.raises(ValueError, match="api.x.ai"):
        validate_grok_base_url("https://evil.example.com/v1")
