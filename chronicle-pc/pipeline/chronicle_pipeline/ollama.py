"""Thin Ollama HTTP client (chat, vision, embeddings) with graceful degradation.

Tuned for Ornith 35B (reasoning model): strips ``<think>`` blocks, uses
temp/top-p/top-k defaults suited to Ornith, and exposes per-task ``num_ctx``.
"""

from __future__ import annotations

import base64
import json
import logging
import math
import re
from pathlib import Path
from typing import Any

import requests

log = logging.getLogger("chronicle.ollama")

OLLAMA_BASE = "http://localhost:11434"
DEFAULT_TIMEOUT = 300

# Ornith supports up to 262K; Mac defaults stay practical, recall/ask go higher.
DEFAULT_NUM_CTX = 32768
NUM_CTX_RECALL = 131072
NUM_CTX_ASK = 65536
NUM_CTX_RESUME = 65536
NUM_CTX_ENRICH = 16384
NUM_CTX_BRAIN = 32768
NUM_CTX_VISION = 8192

# Ornith-oriented sampling (overridden by GLOBAL_TEMPERATURE when set).
DEFAULT_TEMPERATURE = 0.6
DEFAULT_TOP_P = 0.95
DEFAULT_TOP_K = 20

# When set, overrides per-call temperature; None keeps call-site defaults.
GLOBAL_TEMPERATURE: float | None = None

# Defaults from plan; overridden by config.json models
CHAT_MODEL = "maxwell1500/ornith-35b:Q4_K_M"
EMBED_MODEL = "nomic-embed-text"
VISION_MODEL = "llama3.2-vision:11b"

_THINK_BLOCK_RE = re.compile(r"<think\b[^>]*>[\s\S]*?</think>", re.IGNORECASE)
_THINK_UNCLOSED_RE = re.compile(r"<think\b[^>]*>[\s\S]*\Z", re.IGNORECASE)


class OllamaError(RuntimeError):
    pass


def apply_settings(
    base_url: str | None = None,
    num_ctx: int | None = None,
    temperature: float | None = None,
) -> None:
    """Update module-level Ollama connection / generation defaults from config."""
    global OLLAMA_BASE, DEFAULT_NUM_CTX, GLOBAL_TEMPERATURE
    if base_url is not None:
        from .url_allowlist import is_private_or_loopback_url

        cleaned = (base_url or "").strip().rstrip("/")
        if cleaned and not is_private_or_loopback_url(cleaned):
            log.warning(
                "Rejecting non-private ollama.base_url %r; keeping %s",
                cleaned,
                OLLAMA_BASE,
            )
        else:
            OLLAMA_BASE = cleaned or "http://localhost:11434"
    if num_ctx is not None:
        try:
            n = int(num_ctx)
            DEFAULT_NUM_CTX = n if n > 0 else 32768
        except (TypeError, ValueError):
            DEFAULT_NUM_CTX = 32768
    # Always assign: None means "use per-feature temperatures"
    GLOBAL_TEMPERATURE = temperature


def strip_think_blocks(text: str) -> str:
    """Remove Ornith ``<think>…</think>`` reasoning blocks before parsing/display."""
    raw = text or ""
    cleaned = _THINK_BLOCK_RE.sub("", raw)
    cleaned = _THINK_UNCLOSED_RE.sub("", cleaned)
    return cleaned.strip()


def ollama_reachable(timeout: float = 2.0) -> bool:
    try:
        r = requests.get(f"{OLLAMA_BASE}/api/tags", timeout=timeout)
        return r.status_code == 200
    except requests.RequestException:
        return False


def list_available_models(timeout: float = 3.0) -> list[str]:
    """Return model names from Ollama /api/tags, or [] if unreachable."""
    try:
        r = requests.get(f"{OLLAMA_BASE}/api/tags", timeout=timeout)
        r.raise_for_status()
        data = r.json()
    except (requests.RequestException, ValueError, TypeError):
        return []
    models = data.get("models") if isinstance(data, dict) else None
    if not isinstance(models, list):
        return []
    names: list[str] = []
    for m in models:
        if isinstance(m, dict):
            name = m.get("name") or m.get("model")
            if isinstance(name, str) and name.strip():
                names.append(name.strip())
    return sorted(set(names), key=str.lower)


def chat(
    messages: list[dict[str, Any]],
    *,
    model: str | None = None,
    temperature: float = DEFAULT_TEMPERATURE,
    top_p: float = DEFAULT_TOP_P,
    top_k: int = DEFAULT_TOP_K,
    format_json: bool = False,
    num_predict: int | None = None,
    num_ctx: int | None = None,
    timeout: float = DEFAULT_TIMEOUT,
) -> str:
    resolved = model or CHAT_MODEL
    effective_temp = GLOBAL_TEMPERATURE if GLOBAL_TEMPERATURE is not None else temperature
    options: dict[str, Any] = {
        "temperature": effective_temp,
        "top_p": top_p,
        "top_k": int(top_k),
        "num_ctx": num_ctx if num_ctx is not None else DEFAULT_NUM_CTX,
    }
    if num_predict is not None:
        options["num_predict"] = int(num_predict)
    payload: dict[str, Any] = {
        "model": resolved,
        "messages": messages,
        "stream": False,
        "options": options,
        "keep_alive": "10m",
    }
    if format_json:
        payload["format"] = "json"
    try:
        r = requests.post(f"{OLLAMA_BASE}/api/chat", json=payload, timeout=timeout)
        r.raise_for_status()
        # Inside try: a non-JSON 200 (captive portal / truncated body) is a
        # RequestException (JSONDecodeError subclasses it), not a crash.
        data = r.json()
    except requests.RequestException as e:
        raise OllamaError(f"Ollama chat failed: {e}") from e
    msg = data.get("message") or {}
    return strip_think_blocks((msg.get("content") or "").strip())


def embed(text: str, *, model: str | None = None, timeout: float = 60.0) -> list[float]:
    """Local Ollama embeddings only (nomic @ 768). Never route through cloud LLM providers."""
    text = (text or "").strip()
    if not text:
        return []
    resolved = model or EMBED_MODEL
    if len(text) > 2000:
        text = text[:2000]
    try:
        r = requests.post(
            f"{OLLAMA_BASE}/api/embeddings",
            json={"model": resolved, "prompt": text},
            timeout=timeout,
        )
        r.raise_for_status()
        data = r.json()
    except requests.RequestException as e:
        raise OllamaError(f"Ollama embed failed: {e}") from e
    vec = data.get("embedding")
    if not isinstance(vec, list):
        raise OllamaError("Ollama embed response missing embedding")
    return [float(x) for x in vec]


def cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def extract_json(text: str) -> Any:
    """Parse JSON from model output, tolerating think blocks and markdown fences."""
    raw = strip_think_blocks(text or "")
    if not raw:
        raise ValueError("empty model output")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    fence = re.search(r"```(?:json)?\s*([\s\S]*?)```", raw)
    if fence:
        try:
            return json.loads(fence.group(1).strip())
        except json.JSONDecodeError:
            pass
    decoder = json.JSONDecoder()
    for opener in ("{", "["):
        start = raw.find(opener)
        if start < 0:
            continue
        try:
            obj, _end = decoder.raw_decode(raw[start:])
            return obj
        except json.JSONDecodeError:
            continue
    raise ValueError(f"could not parse JSON from model output: {raw[:200]!r}")


def describe_image(
    image_path: Path,
    *,
    model: str | None = None,
    prompt: str = "Describe this journal photo in 1-2 factual sentences.",
    timeout: float = DEFAULT_TIMEOUT,
) -> str:
    """Vision describe via Ollama chat with base64 image."""
    resolved = model or VISION_MODEL
    raw = Path(image_path).read_bytes()
    b64 = base64.b64encode(raw).decode("ascii")
    messages = [
        {
            "role": "user",
            "content": prompt,
            "images": [b64],
        }
    ]
    return chat(
        messages,
        model=resolved,
        temperature=0.1,
        num_predict=200,
        num_ctx=NUM_CTX_VISION,
        timeout=timeout,
    )


def try_chat(*args: Any, **kwargs: Any) -> str | None:
    """Chat that returns None on failure instead of raising."""
    if not ollama_reachable():
        log.warning("Ollama unreachable; skipping LLM call")
        return None
    try:
        return chat(*args, **kwargs)
    except OllamaError as e:
        log.warning("Ollama chat skipped: %s", e)
        return None


def try_embed(text: str, *, model: str | None = None) -> list[float]:
    if not ollama_reachable():
        return []
    try:
        return embed(text, model=model)
    except OllamaError as e:
        log.warning("Ollama embed skipped: %s", e)
        return []


def try_describe_image(image_path: Path, *, model: str | None = None) -> str | None:
    if not ollama_reachable():
        log.warning("Ollama unreachable; skipping vision for %s", image_path)
        return None
    try:
        return describe_image(image_path, model=model)
    except (OllamaError, OSError) as e:
        log.warning("Vision describe skipped for %s: %s", image_path, e)
        return None
