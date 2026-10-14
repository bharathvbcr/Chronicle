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


def _assert_rejected(url: str, validate) -> None:
    """Fail closed, and never echo the candidate (or its userinfo) back."""
    try:
        returned = validate(url)
    except ValueError as exc:
        message = str(exc)
        assert url not in message
        assert "@" not in message
        assert "%40" not in message.lower()
        return
    pytest.fail(f"rejected URL was returned: {returned!r}")


def test_ollama_loopback_and_private_urls_have_no_userinfo() -> None:
    for url in (
        "http://127.0.0.1:11434/",
        "http://[::1]:11434/",
        "http://localhost:11434",
        "http://192.168.1.10:11434",
        "http://10.0.0.5:11434",
    ):
        assert is_private_or_loopback_url(url)
        ok = validate_ollama_base_url(url)
        assert "@" not in ok
        assert "%40" not in ok.lower()
    assert validate_ollama_base_url("http://127.0.0.1:11434/") == "http://127.0.0.1:11434"
    assert validate_ollama_base_url("http://[::1]:11434/") == "http://[::1]:11434"


def test_ollama_userinfo_and_encoded_at_rejected() -> None:
    blocked = (
        "http://127.0.0.1:11434@evil.example/",
        "http://evil@127.0.0.1:11434/",
        "http://user:pass@127.0.0.1:11434/",
        "http://127.0.0.1:11434%40evil.example/",
    )
    for url in blocked:
        assert not is_private_or_loopback_url(url)
        _assert_rejected(url, validate_ollama_base_url)


def test_userinfo_rejected_before_dns(monkeypatch: pytest.MonkeyPatch) -> None:
    import chronicle_pipeline.url_allowlist as allowlist

    def fail_resolve(*_args, **_kwargs):
        raise AssertionError("socket.getaddrinfo must not run for a userinfo URL")

    monkeypatch.setattr(allowlist.socket, "getaddrinfo", fail_resolve)
    url = "http://user:pass@rebind.example:11434/"
    assert not is_private_or_loopback_url(url)
    _assert_rejected(url, validate_ollama_base_url)
    assert not is_allowed_grok_url("https://user:secret@api.x.ai/v1")


def test_grok_https_443_ok_userinfo_and_other_ports_rejected() -> None:
    for url in ("https://api.x.ai/v1", "https://api.x.ai:443/v1"):
        assert is_allowed_grok_url(url)
        ok = validate_grok_base_url(url)
        assert "@" not in ok
        assert "%40" not in ok.lower()
    assert validate_grok_base_url("https://api.x.ai:443/v1") == "https://api.x.ai:443/v1"
    assert not is_allowed_grok_url("https://user:secret@api.x.ai/v1")
    assert not is_allowed_grok_url("https://api.x.ai:444/v1")
    _assert_rejected("https://user:secret@api.x.ai/v1", validate_grok_base_url)
    _assert_rejected("https://api.x.ai:444/v1", validate_grok_base_url)
    with pytest.raises(ValueError, match="api.x.ai"):
        validate_grok_base_url("http://api.x.ai/v1")


def test_apply_settings_keeps_base_and_omits_rejected_url(caplog: pytest.LogCaptureFixture) -> None:
    import logging

    from chronicle_pipeline import ollama

    previous = ollama.OLLAMA_BASE
    rejected = "http://evil@127.0.0.1:11434/"
    try:
        ollama.OLLAMA_BASE = "http://127.0.0.1:11434"
        with caplog.at_level(logging.WARNING, logger="chronicle.ollama"):
            ollama.apply_settings(rejected)
            ollama.apply_settings("https://evil.example.com/v1")
        assert ollama.OLLAMA_BASE == "http://127.0.0.1:11434"
        warning = caplog.text
        assert "evil@" not in warning
        assert rejected not in warning
        assert "https://evil.example.com/v1" not in warning
        ollama.apply_settings("")
        assert ollama.OLLAMA_BASE == "http://localhost:11434"
    finally:
        ollama.OLLAMA_BASE = previous


def test_grok_errors_omit_rejected_base_url() -> None:
    from chronicle_pipeline.llm.factory import get_provider
    from chronicle_pipeline.llm.grok_provider import GrokProvider
    from chronicle_pipeline.llm.protocol import LlmError
    from chronicle_pipeline.models import ChronicleConfig, GrokOptions, LlmOptions

    raw = "https://user:secret@api.x.ai/v1"
    with pytest.raises(LlmError) as provider_exc:
        GrokProvider(api_key="k", base_url=raw)
    provider_message = str(provider_exc.value)
    assert "secret" not in provider_message
    assert raw not in provider_message
    assert "https://api.x.ai" in provider_message

    cfg = ChronicleConfig(
        llm=LlmOptions(
            provider="grok",
            cloud_consent=True,
            grok=GrokOptions(base_url=raw),
        )
    )
    with pytest.raises(LlmError) as factory_exc:
        get_provider(cfg, secrets={"grok_api_key": "k"})
    factory_message = str(factory_exc.value)
    assert "secret" not in factory_message
    assert raw not in factory_message
    assert "https://api.x.ai" in factory_message


def test_percent_4a_is_letter_j_not_userinfo() -> None:
    # %4A / %4a encode 'J' (0x4A). They are not a case variant of %40.
    ollama_url = "http://127.0.0.1:11434%4Aevil.example/"
    assert is_private_or_loopback_url(ollama_url)
    assert validate_ollama_base_url(ollama_url) == "http://127.0.0.1:11434%4Aevil.example"
    assert is_allowed_grok_url("https://api.x.ai/%4A")
    assert is_allowed_grok_url("https://api.x.ai/%4a")
    assert validate_grok_base_url("https://api.x.ai/%4A") == "https://api.x.ai/%4A"


def test_control_chars_rejected_before_dns(monkeypatch: pytest.MonkeyPatch) -> None:
    import chronicle_pipeline.url_allowlist as allowlist

    def fail_resolve(*_args, **_kwargs):
        raise AssertionError("socket.getaddrinfo must not run for a control character URL")

    monkeypatch.setattr(allowlist.socket, "getaddrinfo", fail_resolve)
    nul = "http://127.0.0.1\x00.evil.example:11434"
    assert not is_private_or_loopback_url(nul)
    with pytest.raises(ValueError, match="private or loopback") as nul_exc:
        validate_ollama_base_url(nul)
    nul_message = str(nul_exc.value)
    assert type(nul_exc.value) is ValueError
    assert nul not in nul_message
    assert "\x00" not in nul_message
    assert "evil.example" not in nul_message

    for url in (
        "http://127.0.0.1\x7f.evil.example:11434",
        "http://127.0.0.1\x01.evil.example:11434",
        "http://127.0.0.1\t.evil.example:11434",
    ):
        assert not is_private_or_loopback_url(url)
        with pytest.raises(ValueError, match="private or loopback") as exc:
            validate_ollama_base_url(url)
        assert type(exc.value) is ValueError
        assert url not in str(exc.value)

    for url in (
        "https://api.x.ai\x00/v1",
        "https://api.x.ai\t/v1",
        "https://api.x.ai/v1\t",
        "https://api.x.ai\x7f/v1",
    ):
        assert not is_allowed_grok_url(url)
        with pytest.raises(ValueError, match="api.x.ai") as exc:
            validate_grok_base_url(url)
        message = str(exc.value)
        assert type(exc.value) is ValueError
        assert url not in message
        assert "\x00" not in message
        assert "\t" not in message
        assert "\x7f" not in message

    assert is_allowed_grok_url("https://api.x.ai/v1")
    assert validate_grok_base_url("https://api.x.ai/v1") == "https://api.x.ai/v1"
    assert is_allowed_grok_url("https://api.x.ai:443/v1")
    assert validate_grok_base_url("https://api.x.ai:443/v1") == "https://api.x.ai:443/v1"


def test_apply_settings_rejects_nul_without_logging_url(caplog: pytest.LogCaptureFixture) -> None:
    import logging

    from chronicle_pipeline import ollama

    previous = ollama.OLLAMA_BASE
    rejected = "http://127.0.0.1\x00.evil.example:11434"
    try:
        ollama.OLLAMA_BASE = "http://127.0.0.1:11434"
        with caplog.at_level(logging.WARNING, logger="chronicle.ollama"):
            ollama.apply_settings(rejected)
        assert ollama.OLLAMA_BASE == "http://127.0.0.1:11434"
        warning = caplog.text
        assert rejected not in warning
        assert "\x00" not in warning
        assert "evil.example" not in warning
    finally:
        ollama.OLLAMA_BASE = previous


def test_overlong_idna_label_is_rejected(caplog: pytest.LogCaptureFixture) -> None:
    import logging

    from chronicle_pipeline import ollama

    label = "a" * 300
    url = "http://" + label + ".example:11434/"
    assert not is_private_or_loopback_url(url)
    with pytest.raises(ValueError, match="private or loopback") as exc:
        validate_ollama_base_url(url)
    assert type(exc.value) is ValueError
    assert not isinstance(exc.value, UnicodeError)
    assert label not in str(exc.value)

    previous = ollama.OLLAMA_BASE
    try:
        ollama.OLLAMA_BASE = "http://10.0.0.5:11434"
        with caplog.at_level(logging.WARNING, logger="chronicle.ollama"):
            ollama.apply_settings(url)
        assert ollama.OLLAMA_BASE == "http://10.0.0.5:11434"
        assert label not in caplog.text
        assert url not in caplog.text
    finally:
        ollama.OLLAMA_BASE = previous
