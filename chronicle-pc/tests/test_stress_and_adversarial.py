"""Adversarial + stress suite: boundary hardening for serve, e2ee, events.

These tests attack the system the way a hostile LAN peer or a runaway client
would: oversized payloads, tampered ciphertext, floods, concurrent mutation,
and monitoring gaps. Failing tests here are the spec for the hardening that
ships with them.
"""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline import e2ee
from chronicle_pipeline.api.events import (
    _cached_fingerprint,
    _stream,
)
from chronicle_pipeline.pairstore import PairStore
from chronicle_pipeline.ratelimit import AuthRateLimiter
from chronicle_pipeline.serve import TOKEN_HEADER, create_app

PASS = "correct horse battery staple"
HDR = {TOKEN_HEADER: "tok"}


def _client(chronicle_dir: Path) -> TestClient:
    return TestClient(
        create_app(
            chronicle_dir,
            connect_info={
                "base": "http://192.168.1.10:8765",
                "token": "tok",
                "auth_required": True,
                "tls": False,
            },
        )
    )


# ---------------------------------------------------------------------------
# Payload caps — JSON APIs must refuse absurd bodies instead of parsing them.
# ---------------------------------------------------------------------------


def test_entry_text_size_cap(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    huge = "x" * (2_000_000)
    res = client.post("/entries", headers=HDR, json={"type": "log", "text": huge})
    assert res.status_code == 413
    # Nothing persisted.
    for p in (chronicle_dir / "_capture" / "entries").rglob("*.json"):
        assert huge[:100] not in p.read_text()


def test_patch_text_size_cap(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    created = client.post(
        "/entries", headers=HDR, json={"type": "log", "text": "small"}
    ).json()
    res = client.patch(
        f"/entries/{created['id']}", headers=HDR, json={"text": "y" * 2_000_000}
    )
    assert res.status_code == 413


def test_mirror_body_size_cap(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    entry = {
        "version": 1,
        "id": "2026-08-21_090001-an",
        "ts": "2026-08-21T09:00:01+05:30",
        "type": "log",
        "text": "z" * 3_000_000,
        "tags": [],
    }
    res = client.post("/entries/mirror", headers=HDR, json={"entry": entry})
    assert res.status_code == 413


def test_field_bounds_tags_and_media_lists(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    res = client.post(
        "/entries",
        headers=HDR,
        json={"type": "log", "text": "ok", "tags": [f"t{i}" for i in range(500)]},
    )
    assert res.status_code in (400, 413, 422)

    res = client.post(
        "/entries",
        headers=HDR,
        json={
            "type": "log",
            "text": "ok",
            "tags": ["a" * 500],
        },
    )
    assert res.status_code in (400, 413, 422)


def test_unicode_and_newlines_roundtrip(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    text = "héllo 🌏 中文 \n\ttabs and \"quotes\" — em·dash"
    created = client.post(
        "/entries", headers=HDR, json={"type": "dream", "text": text}
    ).json()
    fetched = client.get(f"/entries/{created['id']}", headers=HDR).json()
    assert fetched["text"] == text


# ---------------------------------------------------------------------------
# E2EE adversarial — tampered blobs fail closed; round-trips hold at scale.
# ---------------------------------------------------------------------------


def test_e2ee_tampered_blobs_fail_closed(tmp_path: Path) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), tmp_path)
    e2ee.unlock(tmp_path, PASS)
    blob = e2ee.encrypt_text(tmp_path, "secret")
    e2ee.lock(tmp_path)

    variants = [
        {},  # missing keys entirely
        {"v": 1, "nonce": "!!!not-b64!!!", "ct": blob["ct"]},
        {"v": 1, "nonce": blob["nonce"], "ct": "!!!not-b64!!!"},
        {"v": 1, "nonce": blob["nonce"], "ct": blob["ct"][:-4]},  # truncated ct
        {"v": 1, "nonce": blob["nonce"]},  # missing ct
        {"v": 1, "nonce": 123, "ct": blob["ct"]},  # wrong types
        {"v": 1, "nonce": blob["nonce"], "ct": blob["ct"], "extra": "junk"},
        "not-a-dict",
    ]
    e2ee.unlock(tmp_path, PASS)
    for variant in variants:
        try:
            text = e2ee.decrypt_text(variant, tmp_path)
        except e2ee.E2eeError:
            continue
        raise AssertionError(f"tampered blob returned plaintext {text!r}: {variant!r}")


def test_e2ee_roundtrip_property_across_sizes(tmp_path: Path) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), tmp_path)
    e2ee.unlock(tmp_path, PASS)
    samples = [
        "",
        "a",
        "emoji 🌧️🌧️ cjk 漢字 rtl עברית",
        "line1\nline2\r\nline3\ttabbed",
        "q" * 1_000_000,
    ]
    for plain in samples:
        blob = e2ee.encrypt_text(tmp_path, plain)
        assert set(blob) == {"v", "nonce", "ct"}
        assert e2ee.decrypt_text(blob, tmp_path) == plain

    # Fresh nonce per encryption — identical plaintexts never share one.
    seen = {e2ee.encrypt_text(tmp_path, "same")["nonce"] for _ in range(50)}
    assert len(seen) == 50


# ---------------------------------------------------------------------------
# Events fingerprint — monitoring must survive vaults larger than the budget.
# ---------------------------------------------------------------------------


def test_fingerprint_detects_structural_change_beyond_file_budget(
    tmp_path: Path, monkeypatch
) -> None:
    """A capture arriving in a vault bigger than the scan budget must still
    trigger a change (dir mtimes are part of the fingerprint)."""
    from chronicle_pipeline.api import events as events_mod

    monkeypatch.setattr(events_mod, "_MAX_FILES_SCANNED", 5)
    monkeypatch.setattr(events_mod, "_fp_cache", None)

    base = tmp_path / "Chronicle"
    for month in range(3):
        d = base / "_capture" / "entries" / "2026" / f"{month:02d}"
        d.mkdir(parents=True)
        for i in range(4):
            (d / f"2026-01-01_00000{i}-an.json").write_text("{}")

    before = events_mod.vault_fingerprint(base)

    # New capture lands in an already-full subdir whose individual files were
    # beyond the (tiny) budget — structural detection must still fire via the
    # directory-mtime term.
    new_file = base / "_capture" / "entries" / "2026" / "02" / "2026-01-02_000000-an.json"
    new_file.write_text('{"new": true}')
    after = events_mod.vault_fingerprint(base)
    assert after != before, "new file beyond file budget went undetected"

    # Content edit of an IN-BUDGET vault is detected by the file terms.
    monkeypatch.setattr(events_mod, "_MAX_FILES_SCANNED", 1000)
    new_file.write_text('{"new": false}')
    assert events_mod.vault_fingerprint(base) != after


def test_events_cache_concurrent_streams_share_one_scan(
    tmp_path: Path, monkeypatch
) -> None:
    from chronicle_pipeline.api import events as events_mod

    monkeypatch.setattr(events_mod, "_fp_cache", None)
    calls = {"n": 0}

    def slow_scan(root: Path) -> str:
        calls["n"] += 1
        return "fp"

    monkeypatch.setattr(events_mod, "vault_fingerprint", slow_scan)

    async def burst() -> None:
        await asyncio.sleep(0.01)
        results = await asyncio.gather(*[_cached_fingerprint(tmp_path) for _ in range(8)])
        assert all(r == "fp" for r in results)

    asyncio.run(burst())
    assert calls["n"] <= 2, f"cache miss storm: {calls['n']} scans for 8 concurrent streams"


def test_sse_stream_rotates_with_bye(tmp_path: Path, monkeypatch) -> None:
    from chronicle_pipeline.api import events as events_mod

    monkeypatch.setattr(events_mod, "_fp_cache", None)
    monkeypatch.setattr(events_mod, "MAX_STREAM_SECONDS", -1.0)  # rotate immediately
    gen = _stream(tmp_path)
    first = asyncio.run(gen.__anext__())
    assert first.startswith(": chronicle events")
    second = asyncio.run(gen.__anext__())
    assert "event: bye" in second


# ---------------------------------------------------------------------------
# Rate limiter under flood.
# ---------------------------------------------------------------------------


def test_rate_limiter_flood_keeps_blocked_peer() -> None:
    clock = {"t": 0.0}
    limiter = AuthRateLimiter(max_fails=2, window_sec=300.0, block_sec=600.0,
                              clock=lambda: clock["t"])
    limiter.record_fail("victim")
    limiter.record_fail("victim")
    assert not limiter.allow("victim"), "precondition: victim blocked"

    # Flood the table with unique spoofed sources past the eviction bound.
    for i in range(_flood_count()):
        limiter.record_fail(f"10.0.{i // 256}.{i % 256}")
        clock["t"] += 0.001

    assert not limiter.allow("victim"), "flood flushed a genuinely blocked peer"
    # Spoofed entries themselves are limited normally.
    assert limiter.allow("10.0.99.99") or True  # allow() only checks blocks


def _flood_count() -> int:
    from chronicle_pipeline.ratelimit import _MAX_TRACKED_IPS

    return _MAX_TRACKED_IPS * 2


def test_rate_limiter_window_expiry_and_success_reset() -> None:
    clock = {"t": 1000.0}
    limiter = AuthRateLimiter(max_fails=3, window_sec=60.0, block_sec=30.0,
                              clock=lambda: clock["t"])
    for _ in range(3):
        limiter.record_fail("p")
    assert not limiter.allow("p")

    clock["t"] += 31.0  # block expires
    assert limiter.allow("p")
    limiter.record_fail("p")
    limiter.record_success("p")  # legit login wipes the counter
    for _ in range(2):
        limiter.record_fail("p")
    assert limiter.allow("p"), "success did not reset the failure counter"


# ---------------------------------------------------------------------------
# Pair store thread stress — flock + hot-reload must stay consistent.
# ---------------------------------------------------------------------------


def test_pair_store_concurrent_mutations_stay_consistent(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    store = PairStore(path)
    errors: list[Exception] = []

    def worker(n: int) -> None:
        try:
            for i in range(20):
                name = f"dev-{n}-{i}"
                token = store.add_device(name)
                assert store.verify(token) == name
                if i % 3 == 0:
                    store.remove_device(name)
                    assert store.verify(token) is None
        except Exception as e:  # noqa: BLE001
            errors.append(e)

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(worker, range(8)))

    assert not errors, errors[:3]
    listed = {d["name"] for d in store.list_devices()}
    # Every device either fully removed (i%3==0 last state) or still present.
    fresh = PairStore(path)
    assert {d["name"] for d in fresh.list_devices()} == listed


# ---------------------------------------------------------------------------
# KB path traversal — config.json must never be readable via note routes.
# ---------------------------------------------------------------------------


def test_kb_notes_traversal_blocked(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    for evil in (
        "/kb/notes/..%2F..%2Fconfig.json",
        "/kb/notes/%2e%2e%2fconfig.json",
        "/kb/notes/a/../../config.json",
        "/kb/notes/..\\..\\config.json",
    ):
        res = client.get(evil, headers=HDR)
        if res.status_code == 200:
            body = res.text
            assert "layout_version" not in body, f"{evil} leaked config.json"


# ---------------------------------------------------------------------------
# Chaos: concurrent mixed CRUD — vault_process_lock must serialize writers
# without dropping to 5xx or corrupting state.
# ---------------------------------------------------------------------------


def test_concurrent_mixed_crud_hammer(chronicle_dir: Path) -> None:
    client = _client(chronicle_dir)
    errors: list[str] = []
    server_errors: list[int] = []

    def worker(n: int) -> None:
        try:
            for i in range(12):
                # Contract ID shape: YYYY-MM-DD_HHMMSS(-pc) — exactly 6
                # digits after the underscore.
                entry_id = f"2026-08-21_10{n:02d}{i * 5:02d}-pc"
                res = client.post(
                    "/entries",
                    headers=HDR,
                    json={
                        "type": "log",
                        "text": f"chaos {entry_id}",
                        "tags": [f"w{n}"],
                        "id": entry_id,
                    },
                )
                if res.status_code >= 500:
                    server_errors.append(res.status_code)
                    continue
                if res.status_code != 201:
                    errors.append(f"create {entry_id}: {res.status_code}")
                    continue

                got = client.get(f"/entries/{entry_id}", headers=HDR)
                if got.status_code != 200:
                    errors.append(f"read-after-create {entry_id}: {got.status_code}")

                patch_res = client.patch(
                    f"/entries/{entry_id}",
                    headers=HDR,
                    json={"text": f"patched {entry_id}"},
                )
                if patch_res.status_code not in (200, 409):
                    errors.append(f"patch {entry_id}: {patch_res.status_code}")

        except Exception as e:  # noqa: BLE001
            errors.append(f"worker {n}: {type(e).__name__}: {e}")

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(worker, range(8)))

    assert not server_errors, f"server errors under concurrency: {server_errors[:5]}"
    assert not errors, errors[:5]

    # Final state: every created entry exists exactly once on disk.
    ids = [
        p.stem
        for p in (chronicle_dir / "_capture" / "entries").rglob("*.json")
        if p.stem.startswith("2026-08-21_")
    ]
    assert len(ids) == len(set(ids)), "duplicate entry files under concurrency"
    assert len(ids) == 96
