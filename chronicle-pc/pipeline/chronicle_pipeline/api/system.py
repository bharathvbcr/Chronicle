"""Health, connect, dashboard, models, enrich-kb."""

from __future__ import annotations

import io
import json
import logging
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import (
    FileResponse,
    JSONResponse,
    RedirectResponse,
    Response,
)
from pydantic import BaseModel

from .. import index_store, kb_enrich, llm, ollama
from ..config import ensure_config, load_config, save_config
from ..models import GrokOptions, LlmOptions, OllamaOptions, VertexOptions
from .deps import get_connect_info, get_root

log = logging.getLogger("chronicle.api.system")

router = APIRouter(tags=["system"])

CONNECT_VERSION = 2
_PC_ROOT = Path(__file__).resolve().parents[3]
_FRONTEND_DIST = _PC_ROOT / "frontend" / "dist"
_DASHBOARD_HTML = _PC_ROOT / "dashboard" / "dashboard.html"


def is_loopback_client(request: Request) -> bool:
    """True when the HTTP client is on loopback (SPA / local tooling)."""
    host = ""
    if request.client is not None:
        host = (request.client.host or "").strip().lower()
    return host in ("127.0.0.1", "::1", "localhost")


def frontend_index() -> Path | None:
    """Return SPA index.html when a Vite build is present."""
    index = _FRONTEND_DIST / "index.html"
    return index if index.is_file() else None


def frontend_dist() -> Path | None:
    return _FRONTEND_DIST if frontend_index() is not None else None


class ModelsBody(BaseModel):
    llm: str | None = None
    embed: str | None = None
    vision: str | None = None
    base_url: str | None = None
    num_ctx: int | None = None
    temperature: float | None = None
    provider: Literal["ollama", "grok", "vertex"] | None = None
    cloud_consent: bool | None = None
    vision_cloud_consent: bool | None = None
    grok_base_url: str | None = None
    grok_model: str | None = None
    vertex_project: str | None = None
    vertex_location: str | None = None
    vertex_model: str | None = None


def connect_payload(
    base: str, token: str | None = None, tls_fp: str | None = None
) -> dict[str, Any]:
    payload: dict[str, Any] = {"v": CONNECT_VERSION, "base": base.rstrip("/")}
    if token:
        payload["token"] = token
    if tls_fp:
        payload["tls_fp"] = tls_fp
    return payload


def _connect_dict(info: dict[str, Any], *, include_token: bool = True) -> dict[str, Any]:
    """Build the ``/connect`` JSON body.

    When ``include_token`` is false (non-loopback clients), omit ``token`` /
    ``qr.token`` so the secret is not leaked over LAN JSON. The terminal QR and
    loopback Settings QR still embed the real token.
    """
    scheme = "https" if info.get("tls") else "http"
    port = info.get("port", 8765)
    default_base = f"{scheme}://127.0.0.1:{port}"
    base = info.get("base") or default_base
    raw_token = info.get("token") if isinstance(info.get("token"), str) else None
    token = raw_token if include_token else None
    tls_fp = info.get("tls_fp") or None
    return {
        "v": CONNECT_VERSION,
        "host": info.get("host", "127.0.0.1"),
        "port": port,
        "bind_host": info.get("bind_host", "127.0.0.1"),
        "lan_ip": info.get("lan_ip"),
        "base": base,
        "tls": bool(info.get("tls")),
        "tls_fp": tls_fp,
        "kb_proxied": False,
        "token": token,
        "auth_required": bool(info.get("auth_required")),
        "qr": connect_payload(base, token=token, tls_fp=tls_fp),
    }


def _provider_status(root: Path) -> dict[str, Any]:
    cfg = ensure_config(root)
    pname = llm.provider_name(cfg)
    provider_ok = False
    provider_error: str | None = None
    try:
        # Health probe should not fail solely on missing consent — report it.
        provider = llm.get_provider(cfg, enforce_consent=False)
        provider_ok = provider.reachable()
        if llm.is_cloud_provider(pname) and not llm.resolve_cloud_consent(
            cfg_consent=bool(cfg.llm.cloud_consent)
        ):
            provider_error = "cloud_consent required"
            provider_ok = False
    except llm.LlmError as e:
        provider_error = str(e)
        provider_ok = False
    except Exception as e:  # noqa: BLE001
        provider_error = str(e)
        provider_ok = False
    ollama_ok = ollama.ollama_reachable()
    return {
        "provider": pname,
        "provider_ok": provider_ok,
        "provider_error": provider_error,
        "ollama": ollama_ok,
        "embed_ok": ollama_ok,  # embeds always Ollama
    }


@router.get("/health")
def health(request: Request, root: Path = Depends(get_root)) -> dict[str, Any]:
    cfg = ensure_config(root)
    status = _provider_status(root)
    if not is_loopback_client(request):
        # LAN callers get booleans only — no vault path, no model inventory.
        return {
            "ok": True,
            "ollama": status["ollama"],
            "provider": status["provider"],
            "provider_ok": status["provider_ok"],
        }
    chronicle = {
        "ok": True,
        "chronicle_dir": str(root),
        "ollama": status["ollama"],
        "provider": status["provider"],
        "provider_ok": status["provider_ok"],
        "embed_ok": status["embed_ok"],
        "models": cfg.models.model_dump(),
        "ask_resume": "native",
    }
    return {
        "ok": True,
        "chronicle": chronicle,
        "chronicle_dir": chronicle["chronicle_dir"],
        "ollama": chronicle["ollama"],
        "provider": chronicle["provider"],
        "provider_ok": chronicle["provider_ok"],
        "models": chronicle["models"],
    }


@router.get("/")
def get_dashboard() -> FileResponse:
    """Prefer the React SPA build; fall back to legacy dashboard.html."""
    spa = frontend_index()
    if spa is not None:
        return FileResponse(spa, media_type="text/html; charset=utf-8")
    if _DASHBOARD_HTML.is_file():
        return FileResponse(_DASHBOARD_HTML, media_type="text/html; charset=utf-8")
    raise HTTPException(
        404,
        f"no UI found (build frontend/ or missing {_DASHBOARD_HTML})",
    )


@router.get("/legacy", response_model=None)
def get_legacy_dashboard(root: Path = Depends(get_root)) -> Response:
    """Layout 2+ redirects to the SPA — legacy FS Access writes wrong trees."""
    try:
        cfg = load_config(root)
        layout = int(cfg.layout_version)
    except Exception:  # noqa: BLE001
        layout = 2
    if layout >= 2:
        return RedirectResponse(url="/", status_code=302)
    if not _DASHBOARD_HTML.is_file():
        raise HTTPException(404, f"dashboard not found at {_DASHBOARD_HTML}")
    return FileResponse(_DASHBOARD_HTML, media_type="text/html; charset=utf-8")


@router.get("/connect")
def get_connect(
    request: Request, info: dict[str, Any] = Depends(get_connect_info)
) -> dict[str, Any]:
    resp = JSONResponse(_connect_dict(info, include_token=is_loopback_client(request)))
    resp.headers["Cache-Control"] = "no-store"
    return resp


@router.get("/connect/qr.svg")
def get_connect_qr_svg(
    request: Request, info: dict[str, Any] = Depends(get_connect_info)
) -> Response:
    try:
        import segno
    except ImportError as e:
        raise HTTPException(503, "segno not installed") from e
    # Embed token only for loopback (Mac Settings). LAN clients must use the
    # terminal QR or a previously paired token — not an unauthenticated SVG leak.
    include_token = is_loopback_client(request)
    base = _connect_dict(info, include_token=include_token)["base"]
    token = (
        info.get("token")
        if include_token and isinstance(info.get("token"), str)
        else None
    )
    qr = segno.make(
        json.dumps(
            connect_payload(
                base,
                token=token,
                tls_fp=info.get("tls_fp") or None,
            ),
            separators=(",", ":"),
        ),
        error="m",
    )
    buff = io.BytesIO()
    qr.save(buff, kind="svg", scale=4, dark="#2A0E12", light="#ffffff")
    return Response(
        content=buff.getvalue(),
        media_type="image/svg+xml",
        headers={"Cache-Control": "no-store"},
    )


def _models_state(root: Path) -> dict[str, Any]:
    cfg = ensure_config(root)
    status = _provider_status(root)
    try:
        available = ollama.list_available_models() if status["ollama"] else []
    except Exception:  # noqa: BLE001 — Ollama may be down; degrade gracefully
        log.exception("list_available_models failed")
        available = []
    if not isinstance(available, list):
        available = []
    llm_opts = cfg.llm or LlmOptions()
    grok = llm_opts.grok or GrokOptions()
    vertex = llm_opts.vertex or VertexOptions()
    return {
        "llm": cfg.models.llm,
        "embed": cfg.models.embed,
        "vision": cfg.models.vision,
        "base_url": cfg.ollama.base_url,
        "num_ctx": cfg.ollama.num_ctx,
        "temperature": cfg.ollama.temperature,
        "available": available,
        "ollama_ok": status["ollama"],
        "provider": status["provider"],
        "provider_ok": status["provider_ok"],
        "provider_error": status.get("provider_error"),
        "cloud_consent": bool(llm_opts.cloud_consent),
        "vision_cloud_consent": bool(llm_opts.vision_cloud_consent),
        "grok_base_url": grok.base_url,
        "grok_model": grok.model,
        "vertex_project": vertex.project,
        "vertex_location": vertex.location,
        "vertex_model": vertex.model,
        "embed_note": "Embeddings always use local Ollama nomic-embed-text @ 768",
    }


@router.get("/models")
def get_models(root: Path = Depends(get_root)) -> dict[str, Any]:
    try:
        return _models_state(root)
    except Exception:  # noqa: BLE001 — never 500 the settings panel
        log.exception("get_models failed; returning config defaults")
        cfg = load_config(root)
        llm_opts = cfg.llm or LlmOptions()
        grok = llm_opts.grok or GrokOptions()
        vertex = llm_opts.vertex or VertexOptions()
        return {
            "llm": cfg.models.llm,
            "embed": cfg.models.embed,
            "vision": cfg.models.vision,
            "base_url": cfg.ollama.base_url,
            "num_ctx": cfg.ollama.num_ctx,
            "temperature": cfg.ollama.temperature,
            "available": [],
            "ollama_ok": False,
            "provider": llm.provider_name(cfg),
            "provider_ok": False,
            "provider_error": None,
            "cloud_consent": bool(llm_opts.cloud_consent),
            "vision_cloud_consent": bool(llm_opts.vision_cloud_consent),
            "grok_base_url": grok.base_url,
            "grok_model": grok.model,
            "vertex_project": vertex.project,
            "vertex_location": vertex.location,
            "vertex_model": vertex.model,
            "embed_note": "Embeddings always use local Ollama nomic-embed-text @ 768",
        }


@router.post("/models")
def post_models(body: ModelsBody, root: Path = Depends(get_root)) -> dict[str, Any]:
    fields = body.model_fields_set
    if not fields:
        raise HTTPException(400, "provide at least one field to update")
    cfg = ensure_config(root)
    if cfg.llm is None:
        cfg.llm = LlmOptions()
    if cfg.ollama is None:
        cfg.ollama = OllamaOptions()

    available = ollama.list_available_models()
    available_set = set(available)
    provider = body.provider if "provider" in fields and body.provider else llm.provider_name(cfg)

    def _check_ollama_model(name: str, field: str) -> str:
        name = name.strip()
        if not name:
            raise HTTPException(400, f"{field} must be a non-empty model name")
        # Only validate against Ollama tags when provider is ollama (or embed always).
        if field == "embed" or provider == "ollama":
            if available_set and name not in available_set:
                raise HTTPException(
                    400,
                    f"{field} {name!r} is not in Ollama tags. "
                    f"Available: {', '.join(available)}",
                )
        return name

    try:
        if "provider" in fields and body.provider is not None:
            cfg.llm.provider = body.provider
            provider = body.provider
        if "cloud_consent" in fields and body.cloud_consent is not None:
            cfg.llm.cloud_consent = bool(body.cloud_consent)
        if "vision_cloud_consent" in fields and body.vision_cloud_consent is not None:
            cfg.llm.vision_cloud_consent = bool(body.vision_cloud_consent)
        if "llm" in fields and body.llm is not None:
            if provider == "ollama":
                cfg.models.llm = _check_ollama_model(body.llm, "llm")
            else:
                cleaned = body.llm.strip()
                if not cleaned:
                    raise HTTPException(400, "llm must be a non-empty model name")
                cfg.models.llm = cleaned
        if "embed" in fields and body.embed is not None:
            # Embed is always Ollama — validate against tags when available.
            cfg.models.embed = _check_ollama_model(body.embed, "embed")
        if "vision" in fields and body.vision is not None:
            if provider == "ollama":
                cfg.models.vision = _check_ollama_model(body.vision, "vision")
            else:
                cleaned = body.vision.strip()
                if not cleaned:
                    raise HTTPException(400, "vision must be a non-empty model name")
                cfg.models.vision = cleaned
        if "base_url" in fields and body.base_url is not None:
            from ..url_allowlist import validate_ollama_base_url

            try:
                cfg.ollama.base_url = validate_ollama_base_url(body.base_url)
            except ValueError as e:
                raise HTTPException(400, str(e)) from e
        if "num_ctx" in fields and body.num_ctx is not None:
            if body.num_ctx <= 0:
                raise HTTPException(400, "num_ctx must be a positive integer")
            cfg.ollama.num_ctx = int(body.num_ctx)
        if "temperature" in fields:
            if body.temperature is not None and (
                body.temperature < 0 or body.temperature > 2
            ):
                raise HTTPException(400, "temperature must be between 0 and 2")
            cfg.ollama.temperature = body.temperature
        if "grok_base_url" in fields and body.grok_base_url is not None:
            from ..url_allowlist import validate_grok_base_url

            if cfg.llm.grok is None:
                cfg.llm.grok = GrokOptions()
            try:
                cfg.llm.grok.base_url = validate_grok_base_url(body.grok_base_url)
            except ValueError as e:
                raise HTTPException(400, str(e)) from e
        if "grok_model" in fields:
            if cfg.llm.grok is None:
                cfg.llm.grok = GrokOptions()
            cfg.llm.grok.model = (
                body.grok_model.strip() if body.grok_model else None
            )
        if "vertex_project" in fields:
            if cfg.llm.vertex is None:
                cfg.llm.vertex = VertexOptions()
            cfg.llm.vertex.project = (
                body.vertex_project.strip() if body.vertex_project else None
            )
        if "vertex_location" in fields and body.vertex_location is not None:
            if cfg.llm.vertex is None:
                cfg.llm.vertex = VertexOptions()
            cleaned = body.vertex_location.strip()
            if not cleaned:
                raise HTTPException(400, "vertex_location must be non-empty")
            cfg.llm.vertex.location = cleaned
        if "vertex_model" in fields:
            if cfg.llm.vertex is None:
                cfg.llm.vertex = VertexOptions()
            cfg.llm.vertex.model = (
                body.vertex_model.strip() if body.vertex_model else None
            )
    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(400, str(e)) from e

    save_config(cfg, root)
    return _models_state(root)


@router.post("/enrich/kb")
def post_enrich_kb(root: Path = Depends(get_root)) -> dict[str, Any]:
    result = kb_enrich.run_kb_enrich(root)
    idx = index_store.run_index(root)
    result["index"] = {
        "upserted": idx.get("upserted"),
        "skipped": idx.get("skipped"),
    }
    return result
