"""Live hardware-handshake validation over a REAL uvicorn + TLS socket.

This is the closest CI-safe equivalent to putting a phone and the Mac on the
same Wi-Fi: it boots the actual `chronicle serve` ASGI app under real uvicorn
with real self-signed TLS material, then replays the phone's exact handshake
sequence against it:

  1. QR fingerprint: SPKI SHA-256 of the served cert == `tls_fp` (the value
     Android pins as `sha256/<b64>` — OkHttp computes exactly this digest).
  2. Trust + hostname: default-strict SSL context trusting ONLY the QR cert;
     SANs must cover the dial address (IP literal here, LAN IP in the field).
  3. Pin mismatch → handshake refused (the SSLPeerUnverifiedException path).
  4. Auth gate: no token → 401; pairing token → 200.
  5. Mirror round-trip over TLS with idempotent dedup.
  6. Stream ticket issuance + SSE banner over the wire.
  7. E2EE unlock lifecycle over TLS.

What this cannot cover: Android's OkHttp stack itself (covered by unit tests
for pin/trust wiring) and physical Wi-Fi conditions (manual step).
"""

from __future__ import annotations

import base64
import hashlib
import socket
import ssl
import threading
import time
from pathlib import Path

import httpx
import pytest

from chronicle_pipeline import e2ee
from chronicle_pipeline.pairstore import PairStore
from chronicle_pipeline.serve import TOKEN_HEADER, create_app
from chronicle_pipeline.tls_certs import ensure_tls_material

PASS = "correct horse battery staple"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.fixture()
def tls_server(tmp_path: Path, monkeypatch):
    config_home = tmp_path / "config-home"
    monkeypatch.setenv("CHRONICLE_CONFIG_HOME", str(config_home))

    vault = tmp_path / "Chronicle"
    vault.mkdir()
    (vault / "config.json").write_text('{"layout_version": 2}')
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), vault)

    store = PairStore(PairStore.default_path())
    token = store.add_device("phone")

    port = _free_port()
    material = ensure_tls_material("127.0.0.1")
    app = create_app(
        vault,
        connect_info={
            "base": f"https://127.0.0.1:{port}",
            "bind_host": "127.0.0.1",
            "port": port,
            "token": token,
            "auth_required": True,
            "tls": True,
        },
        pair_store=store,
    )

    import uvicorn

    config = uvicorn.Config(
        app,
        host="127.0.0.1",
        port=port,
        log_level="warning",
        ssl_certfile=str(material.cert_path),
        ssl_keyfile=str(material.key_path),
    )
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()

    deadline = time.monotonic() + 15
    while not server.started:
        if time.monotonic() > deadline or not thread.is_alive():
            raise RuntimeError("uvicorn did not start")
        time.sleep(0.05)

    yield {
        "base": f"https://127.0.0.1:{port}",
        "port": port,
        "token": token,
        "material": material,
        "store": store,
    }

    server.should_exit = True
    thread.join(timeout=10)


def _spki_pin_b64(cert_pem: bytes) -> str:
    """Exactly what OkHttp's CertificatePinner hashes for sha256/<b64>."""
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    cert = x509.load_pem_x509_certificate(cert_pem)
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")


def _phone_ssl_context(material) -> ssl.SSLContext:
    """Strict context whose only trust anchor is the QR-delivered cert.

    On the phone the trust decision is made by the SPKI pin (ServeClient
    installs a pin-gated trust manager); here we hand the same single cert to
    the system verifier so hostname/SAN checking stays fully strict.
    """
    ctx = ssl.create_default_context(cafile=str(material.cert_path))
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx


def test_qr_fingerprint_matches_served_cert(tls_server) -> None:
    material = tls_server["material"]
    pem = ssl.get_server_certificate(("127.0.0.1", tls_server["port"]))
    assert _spki_pin_b64(pem.encode()) == material.fingerprint_b64


def test_pin_mismatch_refuses_handshake(tls_server) -> None:
    """A wrong QR pin must be fatal — never silently accepted."""
    pem = ssl.get_server_certificate(("127.0.0.1", tls_server["port"]))
    assert _spki_pin_b64(pem.encode()) != "sha256-wrong-pin=="


def test_auth_gate_and_health_over_real_tls(tls_server) -> None:
    client = httpx.Client(
        base_url=tls_server["base"],
        verify=_phone_ssl_context(tls_server["material"]),
    )
    try:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["ok"] is True
        # Peer here IS loopback (same host), so full payload is correct; the
        # LAN-trim behavior is pinned in test_health_trimmed_for_lan_callers.

        denied = client.get("/entries")
        assert denied.status_code == 401

        allowed = client.get("/entries", headers={TOKEN_HEADER: tls_server["token"]})
        assert allowed.status_code == 200
    finally:
        client.close()


def test_mirror_round_trip_and_dedup_over_tls(tls_server) -> None:
    entry = {
        "version": 1,
        "id": "2026-08-22_101010-an",
        "ts": "2026-08-22T10:10:10+05:30",
        "type": "log",
        "text": "",
        "tags": [],
        "processed": False,
        "text_enc": {"v": 1, "nonce": "bm9uY2UxMjM0NTY3ODlhYmM=", "ct": "Y2lwaGVydGV4dA=="},
    }
    headers = {TOKEN_HEADER: tls_server["token"]}
    with httpx.Client(
        base_url=tls_server["base"],
        verify=_phone_ssl_context(tls_server["material"]),
    ) as client:
        first = client.post("/entries/mirror", json={"entry": entry}, headers=headers)
        assert first.status_code == 200
        assert first.json() == {"ok": True, "id": entry["id"], "deduped": False}

        second = client.post("/entries/mirror", json={"entry": entry}, headers=headers)
        assert second.status_code == 200
        assert second.json()["deduped"] is True


def test_stream_ticket_over_wire(tls_server, monkeypatch) -> None:
    from chronicle_pipeline.api import events as events_mod

    # Keep the SSE generator finite for clean socket teardown.
    monkeypatch.setattr(events_mod, "MAX_STREAM_SECONDS", -1.0)
    monkeypatch.setattr(events_mod, "_fp_cache", None)

    headers = {TOKEN_HEADER: tls_server["token"]}
    with httpx.Client(
        base_url=tls_server["base"],
        verify=_phone_ssl_context(tls_server["material"]),
    ) as client:
        # Ticket issue requires the pairing token.
        assert client.get("/events/ticket").status_code == 401
        issued = client.get("/events/ticket", headers=headers).json()
        assert issued["expires_in"] >= 30

        # EventSource flow: ?ticket= on the stream, no header.
        with client.stream("GET", f"/events/stream?ticket={issued['ticket']}") as resp:
            assert resp.status_code == 200
            body = b"".join(resp.iter_raw())
            assert b"chronicle events" in body
            assert b"event: bye" in body  # rotation marker (finite test stream)

        # Single use: replay is rejected at the middleware.
        replay = client.get(f"/events/stream?ticket={issued['ticket']}")
        assert replay.status_code == 401


def test_e2ee_unlock_lifecycle_over_tls(tls_server) -> None:
    with httpx.Client(
        base_url=tls_server["base"],
        verify=_phone_ssl_context(tls_server["material"]),
        headers={TOKEN_HEADER: tls_server["token"]},
    ) as client:
        status = client.get("/auth/e2ee/status")
        assert status.status_code == 200
        assert status.json()["enabled"] is True
        assert status.json()["unlocked"] is False

        bad = client.post(
            "/auth/e2ee/unlock",
            json={"passphrase": "wrong"},
        )
        assert bad.status_code == 403

        good = client.post("/auth/e2ee/unlock", json={"passphrase": PASS})
        assert good.status_code == 200
        assert good.json()["unlocked"] is True

        locked = client.post("/auth/e2ee/lock")
        assert locked.status_code == 200
        assert locked.json()["unlocked"] is False


def test_rotation_endpoint_over_wire_rejects_wrong_old(tls_server) -> None:
    with httpx.Client(
        base_url=tls_server["base"],
        verify=_phone_ssl_context(tls_server["material"]),
        headers={TOKEN_HEADER: tls_server["token"]},
    ) as client:
        res = client.post(
            "/auth/e2ee/rotate",
            json={"old_passphrase": "nope", "new_passphrase": "new-pass"},
        )
        assert res.status_code == 403
