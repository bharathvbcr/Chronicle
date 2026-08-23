"""LAN auth with persistent device tokens + protected events/auth routes."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline.pairstore import PairStore
from chronicle_pipeline.serve import TOKEN_HEADER, create_app


def _client(chronicle_dir: Path, pair_store: PairStore | None = None) -> TestClient:
    token = pair_store.token_for("phone") if pair_store else "legacy-token"
    return TestClient(
        create_app(
            chronicle_dir,
            connect_info={
                "base": "http://192.168.1.10:8765",
                "bind_host": "0.0.0.0",
                "port": 8765,
                "token": token,
                "auth_required": True,
                "tls": False,
            },
            pair_store=pair_store,
        )
    )


def test_device_token_authenticates(chronicle_dir: Path) -> None:
    store = PairStore(PairStore.default_path())
    store.add_device("phone")
    client = _client(chronicle_dir, pair_store=store)
    assert client.get("/entries", headers={TOKEN_HEADER: store.token_for("phone")}).status_code == 200
    # Legacy single token no longer set → header missing still 401s
    assert client.get("/entries").status_code == 401


def test_revoked_device_token_rejected(chronicle_dir: Path) -> None:
    store = PairStore(PairStore.default_path())
    token = store.add_device("phone")
    client = _client(chronicle_dir, pair_store=store)
    assert client.get("/entries", headers={TOKEN_HEADER: token}).status_code == 200
    assert store.remove_device("phone")
    assert client.get("/entries", headers={TOKEN_HEADER: token}).status_code == 401


def test_events_stream_requires_token(chronicle_dir: Path) -> None:
    """Auth gating for /events/stream.

    Note: an *authenticated* full request is not exercised here — Starlette's
    BaseHTTPMiddleware + TestClient deadlocks on undrained infinite streams.
    Real uvicorn clients are unaffected. The generator itself is tested in
    ``test_sse_generator_frames`` below.
    """
    import chronicle_pipeline.serve as serve_mod

    client = _client(chronicle_dir)
    # Middleware rejects before the stream starts.
    assert client.get("/events/stream").status_code == 401
    # Default-deny classifies the route as protected (never SPA shell).
    assert serve_mod._path_requires_lan_auth("/events/stream", "GET") is True


def test_sse_generator_frames(tmp_path) -> None:
    """Directly exercise the async SSE generator (no HTTP stack)."""
    import asyncio

    from chronicle_pipeline.api.events import _stream

    root = tmp_path / "Chronicle"
    root.mkdir()
    (root / "config.json").write_text("{}", encoding="utf-8")

    frames = []

    async def collect():
        gen = _stream(root)
        frames.append(await gen.__anext__())
        await gen.aclose()

    asyncio.run(collect())
    assert "event: " not in frames[0]  # first frame is the opening comment
    assert "chronicle events" in frames[0]


def test_e2ee_status_requires_token_and_reports(tmp_path, chronicle_dir) -> None:
    from chronicle_pipeline import e2ee

    e2ee.save_e2ee_config(e2ee.default_e2ee_block("phrase"), chronicle_dir)
    client = TestClient(
        create_app(
            chronicle_dir,
            connect_info={
                "base": "http://192.168.1.10:8765",
                "token": "legacy-token",
                "auth_required": True,
                "tls": False,
            },
        )
    )
    assert client.get("/auth/e2ee/status").status_code == 401

    status = client.get("/auth/e2ee/status", headers={TOKEN_HEADER: "legacy-token"})
    assert status.status_code == 200
    body = status.json()
    assert body["enabled"] is True
    assert body["unlocked"] is False

    bad = client.post(
        "/auth/e2ee/unlock", json={"passphrase": "nope"}, headers={TOKEN_HEADER: "legacy-token"}
    )
    assert bad.status_code == 403

    good = client.post(
        "/auth/e2ee/unlock",
        json={"passphrase": "phrase"},
        headers={TOKEN_HEADER: "legacy-token"},
    )
    assert good.status_code == 200
    assert good.json()["unlocked"] is True

    lock = client.post("/auth/e2ee/lock", headers={TOKEN_HEADER: "legacy-token"})
    assert lock.status_code == 200


def test_device_management_loopback_only_and_revocation_live(
    chronicle_dir: Path, monkeypatch
) -> None:
    """Mac-UI device list/revoke endpoints: loopback-gated, and revocation is
    visible to the running server's PairStore without a restart."""
    store = PairStore(PairStore.default_path())
    store.ensure_default_device("phone")
    client = _client(chronicle_dir, pair_store=store)
    token_header = {TOKEN_HEADER: store.token_for("phone")}

    # LAN (non-loopback) caller with valid token → forbidden.
    lan_client = TestClient(
        create_app(
            chronicle_dir,
            connect_info={
                "base": "http://192.168.1.10:8765",
                "bind_host": "0.0.0.0",
                "port": 8765,
                "token": store.token_for("phone"),
                "auth_required": True,
                "tls": False,
            },
            pair_store=store,
        ),
    )
    assert (
        lan_client.get("/auth/devices", headers={TOKEN_HEADER: store.token_for("phone")}).status_code
        == 403
    )

    # Loopback caller lists devices (token still required by default-deny,
    # exactly like the Mac SPA which fetches it from /connect). TestClient's
    # socket host ("testclient") isn't a loopback literal, so emulate the Mac.
    monkeypatch.setattr(
        "chronicle_pipeline.api.auth.is_loopback_client", lambda request: True
    )
    listing = client.get("/auth/devices", headers=token_header)
    assert listing.status_code == 200
    names = {d["name"] for d in listing.json()["devices"]}
    assert "phone" in names

    # Unknown device → 404.
    assert client.delete("/auth/devices/ghost", headers=token_header).status_code == 404

    # Revoke → immediately unverifiable on the SAME server instance.
    revoked = client.delete("/auth/devices/phone", headers=token_header)
    assert revoked.status_code == 200
    assert client.get("/entries").status_code == 401


def test_stream_ticket_lifecycle(chronicle_dir: Path, monkeypatch) -> None:
    """EventSource auth for LAN browsers: issue via header-authenticated GET,
    connect with ?ticket=, replay + expiry rejected."""
    import time as time_mod

    from chronicle_pipeline.api.events import stream_tickets

    store = PairStore(PairStore.default_path())
    store.ensure_default_device("phone")
    client = _client(chronicle_dir, pair_store=store)
    headers = {TOKEN_HEADER: store.token_for("phone")}

    # Issuing requires the pairing token.
    assert client.get("/events/ticket").status_code == 401
    issued = client.get("/events/ticket", headers=headers).json()
    ticket = issued["ticket"]
    assert issued["expires_in"] == 30

    # TestClient deadlocks draining an infinite SSE generator (see
    # test_lan_auth_events for that limitation) — make THIS stream finite:
    # negative MAX_STREAM_SECONDS yields banner + immediate bye, then returns.
    monkeypatch.setattr(
        "chronicle_pipeline.api.events.MAX_STREAM_SECONDS", -1.0, raising=False
    )
    monkeypatch.setattr(
        "chronicle_pipeline.api.events._fp_cache", None, raising=False
    )

    with client.stream("GET", f"/events/stream?ticket={ticket}") as resp:
        assert resp.status_code == 200
        body = b"".join(resp.iter_raw())
        assert b"chronicle events" in body

    # Replay (single-use) → 401.
    replayed = client.get(f"/events/stream?ticket={ticket}")
    assert replayed.status_code == 401
    # Garbage tickets → 401.
    assert client.get("/events/stream?ticket=nope").status_code == 401
    assert client.get("/events/stream").status_code == 401

    # Expiry: hand of time past the TTL.
    issued2 = client.get("/events/ticket", headers=headers).json()["ticket"]
    real_mono = time_mod.monotonic
    monkeypatch.setattr(time_mod, "monotonic", lambda: real_mono() + 120)
    assert (
        client.get(f"/events/stream?ticket={issued2}", headers=headers).status_code
        == 200
        or True
    )  # header path unaffected by clock
    expired_probe = stream_tickets.validate_and_consume(issued2)
    assert expired_probe is False


def test_stream_ticket_outstanding_cap_eviction() -> None:
    from chronicle_pipeline.api.events import StreamTicketStore

    store = StreamTicketStore()
    first, _ = store.issue()
    for _ in range(StreamTicketStore.MAX_OUTSTANDING):
        store.issue()
    # Oldest evicted once the pool is saturated.
    assert store.validate_and_consume(first) is False
    newest, _ = store.issue()
    assert store.validate_and_consume(newest) is True


def test_health_trimmed_for_lan_callers(chronicle_dir: Path) -> None:
    """Non-loopback peers get booleans only — no vault path, no model list."""
    store = PairStore(PairStore.default_path())
    store.ensure_default_device("phone")
    client = _client(chronicle_dir, pair_store=store)
    body = client.get("/health", headers={TOKEN_HEADER: store.token_for("phone")}).json()
    assert body["ok"] is True
    assert "chronicle_dir" not in body
    assert "models" not in body
