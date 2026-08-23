"""FastAPI local server: vault REST API + Recall / Ask / Resume.

LAN is the default: binds ``0.0.0.0``, advertises a LAN base URL, and shows a
QR for phone pairing. Use ``--no-lan`` for localhost-only.

When bound beyond localhost, auth is **default-deny**: any non-exempt path
requires header ``X-Chronicle-Token`` matching a paired-device token
(persistent across restarts — see ``pairstore.PairStore``), including
``/vault/*`` mutators. Exempt: ``/``, ``/legacy``, ``/health``, ``/connect*``,
``/assets``, favicons, and SPA shell GET/HEAD. OpenAPI is disabled on LAN.
Auth failures are rate-limited per client IP. The QR embeds the phone device
token; ``GET /connect`` returns it only to loopback clients.

TLS: LAN serves **https by default** with a persistent self-signed cert whose
SPKI SHA-256 fingerprint ships in the QR (``tls_fp``) so the phone pins it.
``--no-tls`` downgrades to cleartext (discouraged). Route handlers live in
``chronicle_pipeline.api``; this module owns app creation, LAN/connect
helpers, mDNS advertisement, and ``run_serve``.
"""

from __future__ import annotations

import json
import logging
import secrets
import socket
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.base import BaseHTTPMiddleware

# Re-exported so existing tests can patch ``chronicle_pipeline.serve.ollama`` etc.
from . import index_store, kb_enrich, llm, ollama, rag  # noqa: F401
from .api import register_routers
from .api.system import CONNECT_VERSION, connect_payload, frontend_dist, frontend_index
from .mdns_advertise import MdnsAdvertiser
from .pairstore import PairStore
from .paths import resolve_chronicle_dir
from .ratelimit import AuthRateLimiter
from .tls_certs import TlsMaterial, ensure_tls_material

log = logging.getLogger("chronicle.serve")

# JSON APIs refuse absurd bodies before parsing (media uploads are capped
# per-route at 50 MB instead).
JSON_BODY_LIMIT_BYTES = 8 * 1024 * 1024  # 8 MiB


class BodyLimitMiddleware:
    """Reject requests whose declared body exceeds the JSON limit with 413.

    Upload routes under /entries/{id}/images|audio are exempt — they stream
    through their own capped reader. Headerless (chunked) bodies still hit
    the per-field caps in the entries handlers, so nothing is unbounded.
    """

    def __init__(self, app, limit: int = JSON_BODY_LIMIT_BYTES):
        self.app = app
        self.limit = limit

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = {
                k.decode("latin-1").lower(): v.decode("latin-1")
                for k, v in scope.get("headers", [])
            }
            path = scope.get("path", "")
            is_upload = (
                path.startswith("/entries/")
                and (path.endswith("/images") or path.endswith("/audio"))
            )
            raw_len = headers.get("content-length")
            if raw_len and not is_upload:
                try:
                    if int(raw_len) > self.limit:
                        resp = JSONResponse(
                            status_code=413,
                            content={"detail": f"request body exceeds {self.limit} bytes"},
                        )
                        await resp(scope, receive, send)
                        return
                except ValueError:
                    pass
        await self.app(scope, receive, send)


TOKEN_HEADER = "X-Chronicle-Token"
# Pairing / health / static — no token (default-deny for everything else).
# Prefix entries must end in "/" (or be listed exact) so a future sibling
# route like /connected-devices can never inherit the exemption silently.
_AUTH_EXEMPT_PREFIXES = (
    "/assets/",
)
_AUTH_EXEMPT_EXACT = {"/", "/legacy", "/favicon.svg", "/favicon.ico", "/health", "/connect"}
# React SPA client routes (GET/HEAD only) — browser loads shell without token.
_SPA_SHELL_EXACT = frozenset(
    {
        "/settings",
        "/vault",
        "/vault/notes",
        "/vault/journal",
        "/vault/kb",
    }
)
# First path segment of vault REST APIs — GET/HEAD require token.
_API_ROOT_SEGMENTS = frozenset(
    {
        "entries",
        "kb",
        "notes",
        "journal",
        "brain",
        "models",
        "recall",
        "ask",
        "resume",
        "search",
        "process",
        "enrich",
        "curation",
        "vault",
        "docs",
        "redoc",
        "openapi.json",
        # Authenticated live surfaces (never SPA shells):
        "events",  # SSE stream — token required
        "auth",  # e2ee unlock/lock/status — token required
    }
)


def detect_lan_ip() -> str:
    """Best-effort non-loopback IPv4 for LAN advertising (QR /connect)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                return ip
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127."):
                return ip
    except OSError:
        pass
    return "127.0.0.1"


def qr_payload_string(
    base: str, token: str | None = None, tls_fp: str | None = None
) -> str:
    return json.dumps(
        connect_payload(base, token=token, tls_fp=tls_fp), separators=(",", ":")
    )


def print_ascii_qr(
    base: str, token: str | None = None, tls_fp: str | None = None
) -> None:
    """Print terminal ASCII QR + base URL for phone scanning.

    The pairing token is embedded in the QR only — never printed as JSON.
    """
    try:
        import segno
    except ImportError:
        log.warning("segno not installed; skip ASCII QR (pip install segno)")
        print(f"\nConnect phone base URL: {base}\n", flush=True)
        if token:
            print("Token: in QR only (segno not installed — rescan after install)\n", flush=True)
        return
    payload = qr_payload_string(base, token=token, tls_fp=tls_fp)
    qr = segno.make(payload, error="m")
    print(flush=True)
    print("Scan with Chronicle Android → Settings → Scan Mac QR", flush=True)
    print(f"Base URL: {base}", flush=True)
    if token:
        print("Token: in QR only (not printed)", flush=True)
    print(flush=True)
    qr.terminal(compact=True)
    print(flush=True)


def _is_localhost_bind(bind_host: str) -> bool:
    h = (bind_host or "").strip().lower()
    return h in ("127.0.0.1", "localhost", "::1")


# Host-header allowlist for ``/connect*`` (DNS-rebinding guard). TestClient
# sends "testserver"; real browsers use loopback or the advertised LAN host.
_CONNECT_HOST_ALLOWED_BASE = frozenset({"127.0.0.1", "localhost", "::1", "testserver"})


def _host_header_hostname(raw: str) -> str:
    """Hostname from a Host header value ('[::1]:8765' → '::1', 'a:80' → 'a')."""
    h = (raw or "").strip().lower()
    if h.startswith("["):
        return h[1:].partition("]")[0]
    return h.rsplit(":", 1)[0] if ":" in h else h


def _connect_allowed_hosts(connect_info: dict[str, Any]) -> frozenset[str]:
    allowed = set(_CONNECT_HOST_ALLOWED_BASE)
    for key in ("host", "lan_ip", "bind_host"):
        v = connect_info.get(key)
        if isinstance(v, str) and v.strip():
            allowed.add(v.strip().lower())
    return frozenset(allowed)


class ConnectHostGuardMiddleware(BaseHTTPMiddleware):
    """Reject ``/connect*`` requests whose Host header is not ours.

    A DNS-rebinding page resolves an attacker hostname to 127.0.0.1 and reads
    the loopback-only pairing token same-origin. The Host header still names
    the attacker domain, so an allowlist of loopback + advertised hosts blocks
    it without affecting legitimate clients.
    """

    def __init__(self, app, *, allowed_hosts: frozenset[str]) -> None:
        super().__init__(app)
        self.allowed_hosts = allowed_hosts

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        if path == "/connect" or path.startswith("/connect/"):
            host = _host_header_hostname(request.headers.get("host", ""))
            if host not in self.allowed_hosts:
                return JSONResponse(
                    status_code=403,
                    content={"ok": False, "error": "invalid Host header"},
                )
        return await call_next(request)


def _cors_origins(connect_info: dict[str, Any]) -> list[str]:
    """Restrict CORS to the advertised serve origin (+ localhost mirrors)."""
    origins: list[str] = []
    base = (connect_info.get("base") or "").rstrip("/")
    if base:
        origins.append(base)
    port = connect_info.get("port") or 8765
    for host in ("127.0.0.1", "localhost"):
        origin = f"http://{host}:{port}"
        if origin not in origins:
            origins.append(origin)
    return origins or ["http://127.0.0.1:8765"]


def _path_requires_lan_auth(path: str, method: str = "GET") -> bool:
    """Default-deny: token required unless explicitly exempt.

    Exempt: pairing/health/static, SPA shell GET/HEAD, and GET/HEAD to
    non-API first segments (SPA deep-link fallback). Mutations and API roots
    (including ``/vault`` and unknown prefixes) always require auth.
    """
    if path in _AUTH_EXEMPT_EXACT:
        return False
    if any(path.startswith(p) for p in _AUTH_EXEMPT_PREFIXES):
        return False
    # /connect/qr.svg etc. — subpaths of an exact-exempt root.
    if path == "/connect" or path.startswith("/connect/"):
        return False

    method_u = (method or "GET").upper()
    parts = [p for p in path.split("/") if p]
    first = parts[0] if parts else ""

    if method_u in ("GET", "HEAD"):
        if path in _SPA_SHELL_EXACT or path.startswith("/settings/"):
            return False
        # /vault/{notes|journal|kb} SPA shells (not /vault/rebuild-index etc.)
        if first == "vault" and len(parts) >= 2 and parts[1] in ("notes", "journal", "kb"):
            return False
        if first == "vault" and len(parts) == 1:
            return False
        # Non-API first segment → SPA / static file from dist
        if first and first not in _API_ROOT_SEGMENTS:
            return False
        return True

    # POST/PUT/PATCH/DELETE: default-deny (unknown prefixes included).
    return True


class LanAuthMiddleware(BaseHTTPMiddleware):
    """Require ``X-Chronicle-Token`` on non-exempt routes when LAN auth is on.

    Accepts the legacy single token **or** any active per-device token from
    the persistent :class:`~chronicle_pipeline.pairstore.PairStore`. Failed
    attempts are rate-limited per client IP; blocked callers get 429.
    """

    def __init__(
        self,
        app,
        *,
        token: str | None,
        auth_required: bool,
        pair_store: PairStore | None = None,
        rate_limiter: AuthRateLimiter | None = None,
    ) -> None:
        super().__init__(app)
        self.token = token
        self.auth_required = auth_required
        self.pair_store = pair_store
        self.rate_limiter = rate_limiter or AuthRateLimiter()

    async def dispatch(self, request: Request, call_next):
        if not self.auth_required or not (self.token or self.pair_store):
            return await call_next(request)
        if request.method == "OPTIONS":
            return await call_next(request)
        path = request.url.path
        if not _path_requires_lan_auth(path, request.method):
            return await call_next(request)

        client_ip = ""
        if request.client is not None:
            client_ip = (request.client.host or "").strip()
        if not self.rate_limiter.allow(client_ip):
            return JSONResponse(
                status_code=429,
                content={"ok": False, "error": "too many failed attempts"},
            )

        got = request.headers.get(TOKEN_HEADER) or request.headers.get(
            TOKEN_HEADER.lower()
        )
        if not got and path == "/events/stream":
            # EventSource cannot send custom headers, so LAN browsers
            # authenticate the stream with a short-lived single-use ticket
            # fetched via a header-authenticated GET /events/ticket.
            # Invalid tickets do NOT burn rate-limit failures: browser auto-
            # retries would lock out legitimate peers, and the ticket space
            # (256-bit) is not brute-forceable anyway.
            ticket = request.query_params.get("ticket") or ""
            from .api.events import validate_stream_ticket

            if ticket and validate_stream_ticket(ticket):
                return await call_next(request)
            return JSONResponse(
                status_code=401,
                content={"ok": False, "error": "invalid or expired stream ticket"},
            )
        ok = False
        if got:
            got = got.strip()
            if self.pair_store is not None:
                # PairStore is authoritative when wired — revocation must be
                # immediate, so no parallel legacy-token acceptance.
                ok = self.pair_store.verify(got) is not None
            elif self.token:
                ok = secrets.compare_digest(got, self.token)
        if not ok:
            self.rate_limiter.record_fail(client_ip)
            return JSONResponse(
                status_code=401,
                content={
                    "ok": False,
                    "error": f"missing or invalid {TOKEN_HEADER}",
                },
            )
        self.rate_limiter.record_success(client_ip)
        return await call_next(request)


def create_app(
    chronicle_dir: Path | str | None = None,
    *,
    connect_info: dict[str, Any] | None = None,
    pair_store: PairStore | None = None,
    rate_limiter: AuthRateLimiter | None = None,
):
    root = resolve_chronicle_dir(chronicle_dir)
    info = dict(connect_info or {})
    bind_host = str(info.get("bind_host") or "127.0.0.1")
    store_token = pair_store.token_for("phone") if pair_store else None
    legacy_token = info.get("token")
    if "auth_required" in info:
        auth_required = bool(info["auth_required"])
    else:
        auth_required = bool(legacy_token or store_token) and not _is_localhost_bind(
            bind_host
        )
    # Fail closed: a non-loopback bind with no credential source would come up
    # with LAN auth silently disabled (empty/corrupt pairing store, missing
    # token). Refuse instead of serving the vault open to the network.
    if not _is_localhost_bind(bind_host) and not (legacy_token or store_token):
        raise ValueError(
            "LAN bind requires pairing credentials: pass connect_info['token'] "
            "or a PairStore with at least one device (see run_serve's "
            "ensure_default_device)"
        )

    # Loud layout hard-gate (Phase 4) — refuse mismatched vault before serving
    try:
        from .config import load_config
        from .vault_layout import LayoutVersionError, require_layout_version

        require_layout_version(root, cfg=load_config(root))
    except LayoutVersionError as e:
        log.error("%s", e)
        raise

    # Disable OpenAPI/Swagger on LAN — schema must not be unauthenticated.
    openapi_kwargs: dict[str, Any] = {}
    if auth_required:
        openapi_kwargs = {
            "docs_url": None,
            "redoc_url": None,
            "openapi_url": None,
        }
    app = FastAPI(title="Chronicle", version="0.2.0", **openapi_kwargs)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_cors_origins(info),
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*", TOKEN_HEADER],
        allow_private_network=True,
    )
    app.add_middleware(
        LanAuthMiddleware,
        token=str(legacy_token) if legacy_token else None,
        auth_required=auth_required,
        pair_store=pair_store,
        rate_limiter=rate_limiter,
    )
    app.add_middleware(
        ConnectHostGuardMiddleware,
        allowed_hosts=_connect_allowed_hosts(info),
    )
    # Outermost: refuse oversized bodies before any parsing downstream.
    app.add_middleware(BodyLimitMiddleware)

    register_routers(app, root, connect_info=info)
    _mount_frontend(app)
    return app


def _mount_frontend(app: FastAPI) -> None:
    """Serve Vite ``frontend/dist`` assets + SPA fallback when built.

    API routes are registered first and take precedence. Missing builds fall
    back to ``dashboard/dashboard.html`` via ``GET /`` in ``api.system``.
    """
    dist = frontend_dist()
    if dist is None:
        log.info("No frontend/dist build; serving legacy dashboard.html at /")
        return

    dist_resolved = dist.resolve()
    assets = dist / "assets"
    if assets.is_dir():
        app.mount("/assets", StaticFiles(directory=assets), name="frontend-assets")

    # Other static files from dist (favicon, etc.) + SPA deep-link fallback.
    @app.get("/{full_path:path}")
    def spa_fallback(full_path: str) -> FileResponse:
        from fastapi import HTTPException

        # Never allow path traversal outside dist/.
        candidate = (dist / full_path).resolve()
        if not candidate.is_relative_to(dist_resolved):
            raise HTTPException(400, "invalid path")
        if candidate.is_file():
            return FileResponse(candidate)
        index = frontend_index()
        if index is None:
            raise HTTPException(404, "frontend index missing")
        return FileResponse(index, media_type="text/html; charset=utf-8")

    log.info("Serving React SPA from %s", dist)


def find_free_port(
    host: str = "127.0.0.1",
    preferred: int = 8765,
    attempts: int = 50,
    *,
    skip: set[int] | None = None,
) -> int:
    """Return `preferred` if free, otherwise the next available port."""
    blocked = skip or set()
    for port in range(preferred, preferred + attempts):
        if port in blocked:
            continue
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, port))
                return port
            except OSError:
                continue
    raise RuntimeError(f"no free port in range {preferred}-{preferred + attempts - 1}")


def run_serve(
    chronicle_dir: Path | str | None = None,
    *,
    host: str = "127.0.0.1",
    port: int = 8765,
    lan: bool = True,
    tls: bool | None = None,
    pair_as: str = "phone",
) -> None:
    """Serve the vault.

    ``tls=None`` auto-enables https for LAN binds (``--no-tls`` forces it off).
    Pairing tokens persist across restarts (PairStore); the QR embeds the
    ``[pair_as]`` device token so the phone never needs to re-scan after a
    Mac reboot.
    """
    import uvicorn

    from .paths import atomic_write_json

    root = resolve_chronicle_dir(chronicle_dir)
    bind_host = "0.0.0.0" if lan else host
    actual_port = find_free_port(bind_host, port)
    if actual_port != port:
        log.warning("Port %d busy; using %d instead", port, actual_port)

    lan_ip = detect_lan_ip() if lan else None
    advertise_host = lan_ip if lan and lan_ip else ("127.0.0.1" if bind_host == "0.0.0.0" else bind_host)

    auth_required = not _is_localhost_bind(bind_host)
    use_tls = auth_required if tls is None else bool(tls) and auth_required

    pair_store: PairStore | None = None
    token: str | None = None
    tls_fp: str | None = None
    ssl_kwargs: dict[str, Any] = {}
    if auth_required:
        pair_store = PairStore.default_path()
        pair_store.ensure_default_device(pair_as)
        token = pair_store.token_for(pair_as)
    if use_tls:
        material: TlsMaterial = ensure_tls_material(lan_ip if lan else None)
        tls_fp = material.fingerprint_b64
        ssl_kwargs = {
            "ssl_certfile": str(material.cert_path),
            "ssl_keyfile": str(material.key_path),
        }

    scheme = "https" if use_tls else "http"
    base = f"{scheme}://{advertise_host}:{actual_port}"

    connect_info: dict[str, Any] = {
        "host": advertise_host,
        "port": actual_port,
        "bind_host": bind_host,
        "lan_ip": lan_ip,
        "base": base,
        "tls": use_tls,
        "tls_fp": tls_fp,
        "kb_proxied": False,
        "v": CONNECT_VERSION,
        "token": token,
        "auth_required": auth_required,
        "qr": connect_payload(base, token=token, tls_fp=tls_fp),
    }
    # Disk copy omits the secret — tokens live in the PairStore file only.
    atomic_write_json(
        root / "index" / "serve.json",
        {
            "host": advertise_host,
            "port": actual_port,
            "bind_host": bind_host,
            "lan_ip": lan_ip,
            "base": base,
            "kb_proxied": False,
            "v": CONNECT_VERSION,
            "auth_required": auth_required,
            "tls": use_tls,
        },
    )

    app = create_app(root, connect_info=connect_info, pair_store=pair_store)
    log.info(
        "Serving Chronicle API on %s://%s:%d (advertise %s, dir=%s, ask/resume=native%s%s)",
        scheme,
        bind_host,
        actual_port,
        base,
        root,
        f", auth={TOKEN_HEADER}" if auth_required else ", auth=off",
        ", tls=pinned" if use_tls else ", tls=off",
    )
    if lan:
        print_ascii_qr(base, token=token, tls_fp=tls_fp)

    advertiser = MdnsAdvertiser()
    advertised = False
    if lan:
        advertised = advertiser.start(
            name="Chronicle",
            port=actual_port,
            tls_fp=tls_fp,
            lan_ip=lan_ip,
            tls=use_tls,
        )
    try:
        uvicorn.run(app, host=bind_host, port=actual_port, log_level="info", **ssl_kwargs)
    finally:
        if advertised:
            advertiser.stop()
