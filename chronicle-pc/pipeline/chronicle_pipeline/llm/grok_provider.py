"""xAI Grok provider (OpenAI-compatible chat + vision)."""

from __future__ import annotations

import base64
import logging
import mimetypes
from pathlib import Path
from typing import Any

import requests

from ..ollama import strip_think_blocks
from .protocol import LlmError

log = logging.getLogger("chronicle.llm.grok")

DEFAULT_GROK_BASE = "https://api.x.ai/v1"
DEFAULT_GROK_MODEL = "grok-2-latest"
DEFAULT_TIMEOUT = 300.0


class GrokProvider:
    name = "grok"

    def __init__(
        self,
        *,
        api_key: str,
        base_url: str = DEFAULT_GROK_BASE,
        default_model: str = DEFAULT_GROK_MODEL,
        vision_model: str | None = None,
    ) -> None:
        key = (api_key or "").strip()
        if not key:
            raise LlmError("Grok provider requires GROK_API_KEY or secrets.grok_api_key")
        from ..url_allowlist import is_allowed_grok_url, validate_grok_base_url

        raw_base = (base_url or DEFAULT_GROK_BASE).rstrip("/")
        if not is_allowed_grok_url(raw_base):
            raise LlmError(
                f"Grok base_url must be https://api.x.ai only; refused {raw_base!r}"
            )
        self.api_key = key
        self.base_url = validate_grok_base_url(raw_base)
        self.default_model = default_model or DEFAULT_GROK_MODEL
        self.vision_model = vision_model or self.default_model

    def reachable(self, timeout: float = 2.0) -> bool:
        try:
            r = requests.get(
                f"{self.base_url}/models",
                headers=self._headers(),
                timeout=timeout,
            )
            return r.status_code < 500
        except requests.RequestException:
            return False

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

    def chat(
        self,
        messages: list[dict[str, Any]],
        *,
        model: str | None = None,
        temperature: float = 0.6,
        top_p: float = 0.95,
        top_k: int = 20,  # noqa: ARG002 — OpenAI-compat; unused
        format_json: bool = False,
        num_predict: int | None = None,
        num_ctx: int | None = None,  # noqa: ARG002 — server-side
        timeout: float = DEFAULT_TIMEOUT,
    ) -> str:
        resolved = model or self.default_model
        payload: dict[str, Any] = {
            "model": resolved,
            "messages": messages,
            "temperature": temperature,
            "top_p": top_p,
            "stream": False,
        }
        if num_predict is not None:
            payload["max_tokens"] = int(num_predict)
        if format_json:
            payload["response_format"] = {"type": "json_object"}
        try:
            r = requests.post(
                f"{self.base_url}/chat/completions",
                headers=self._headers(),
                json=payload,
                timeout=timeout,
            )
            r.raise_for_status()
            data = r.json()
        except requests.RequestException as e:
            raise LlmError(f"Grok chat failed: {e}") from e
        choices = data.get("choices") if isinstance(data, dict) else None
        if not isinstance(choices, list) or not choices:
            raise LlmError("Grok chat response missing choices")
        msg = choices[0].get("message") if isinstance(choices[0], dict) else {}
        content = (msg or {}).get("content") or ""
        return strip_think_blocks(str(content).strip())

    def describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
        prompt: str = "Describe this journal photo in 1-2 factual sentences.",
        timeout: float = DEFAULT_TIMEOUT,
    ) -> str:
        path = Path(image_path)
        raw = path.read_bytes()
        mime = mimetypes.guess_type(path.name)[0] or "image/jpeg"
        b64 = base64.b64encode(raw).decode("ascii")
        data_url = f"data:{mime};base64,{b64}"
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": data_url}},
                ],
            }
        ]
        return self.chat(
            messages,
            model=model or self.vision_model,
            temperature=0.1,
            num_predict=200,
            timeout=timeout,
        )

    def try_chat(self, *args: Any, **kwargs: Any) -> str | None:
        if not self.reachable():
            log.warning("Grok unreachable; skipping LLM call")
            return None
        try:
            return self.chat(*args, **kwargs)
        except LlmError as e:
            log.warning("Grok chat skipped: %s", e)
            return None

    def try_describe_image(
        self,
        image_path: Path,
        *,
        model: str | None = None,
    ) -> str | None:
        if not self.reachable():
            log.warning("Grok unreachable; skipping vision for %s", image_path)
            return None
        try:
            return self.describe_image(image_path, model=model)
        except (LlmError, OSError) as e:
            log.warning("Grok vision skipped for %s: %s", image_path, e)
            return None
