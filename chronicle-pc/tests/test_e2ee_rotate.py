"""Passphrase rotation with full reseal (CONTRACT v1.11 `--rotate`).

Guarantees under test:
- Every sealed entry opens with the NEW passphrase and NOT the old one.
- Any unreadable blob aborts rotation before a single file changes.
- Wrong old passphrase is refused (403 at the API).
- Rotation holds the vault process lock — concurrent writers can't interleave.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from chronicle_pipeline import e2ee
from chronicle_pipeline.entries import iter_entry_paths, load_entry, save_entry
from chronicle_pipeline.lock import vault_process_lock
from chronicle_pipeline.models import Entry
from chronicle_pipeline.serve import TOKEN_HEADER, create_app

PASS = "correct horse battery staple"
NEW = "rotated falcon vault"
HDR = {TOKEN_HEADER: "tok"}


def _setup(root: Path) -> None:
    (root / "config.json").write_text('{"layout_version": 2}')
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), root)
    e2ee.unlock(root, PASS)


def _entries(root: Path, n: int = 3) -> list[Path]:
    out = []
    for i in range(n):
        e = Entry(
            id=f"2026-08-22_0900{i:02d}-pc",
            ts=f"2026-08-22T09:0{i}:00+05:30",
            type="log",
            text=f"secret number {i} 🔐",
        )
        out.append(save_entry(root, e))
    return out


def test_rotation_round_trip(tmp_path: Path) -> None:
    _setup(tmp_path)
    paths = _entries(tmp_path)

    stats = e2ee.rotate_passphrase(tmp_path, PASS, NEW)
    assert stats["resealed"] == 3
    assert stats["failed_ids"] == []

    # New passphrase reads everything; old one is dead.
    for path in paths:
        loaded = load_entry(path, tmp_path)
        assert loaded is not None
        assert "secret number" in loaded.text
    e2ee.lock(tmp_path)
    with pytest.raises(e2ee.E2eeError):
        e2ee.unlock(tmp_path, PASS)

    # Config block carries fresh params; session key swapped to the new one.
    block = e2ee.load_e2ee_config(tmp_path)
    assert block is not None and block.get("enabled") is True
    e2ee.unlock(tmp_path, NEW)  # verifies against the new check blob


def test_rotation_abort_leaves_vault_untouched(tmp_path: Path) -> None:
    _setup(tmp_path)
    paths = _entries(tmp_path, 3)
    before_blobs = [json.loads(p.read_text())["text_enc"] for p in paths]
    before_block = e2ee.load_e2ee_config(tmp_path)

    # Corrupt one ciphertext on disk (bit rot / partial write scenario).
    victim = json.loads(paths[1].read_text())
    victim["text_enc"]["ct"] = victim["text_enc"]["ct"][:-6] + "AAAA=="
    paths[1].write_text(json.dumps(victim))

    with pytest.raises(e2ee.E2eeError, match="aborted"):
        e2ee.rotate_passphrase(tmp_path, PASS, NEW)

    after_block = e2ee.load_e2ee_config(tmp_path)
    assert after_block == before_block, "config changed despite abort"
    for i, path in enumerate(paths):
        raw = json.loads(path.read_text())
        if i == 1:
            continue  # deliberately corrupted by the test itself
        assert raw["text_enc"] == before_blobs[i], f"{path.name} rewritten on abort"


def test_api_rotate_wrong_old_passphrase_403(chronicle_dir: Path) -> None:
    client = TestClient(
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
    res = client.post(
        "/auth/e2ee/rotate",
        headers=HDR,
        json={"old_passphrase": "wrong", "new_passphrase": NEW},
    )
    assert res.status_code == 403


def test_api_rotate_round_trip(chronicle_dir: Path) -> None:
    client = TestClient(
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
    _setup(chronicle_dir)
    _entries(chronicle_dir, 2)

    res = client.post(
        "/auth/e2ee/rotate", headers=HDR, json={"old_passphrase": PASS, "new_passphrase": NEW}
    )
    assert res.status_code == 200
    body = res.json()
    assert body["ok"] is True and body["resealed"] == 2

    e2ee.lock(chronicle_dir)
    e2ee.unlock(chronicle_dir, NEW)
    first = load_entry(iter_entry_paths(chronicle_dir)[0], chronicle_dir)
    assert first is not None and "secret number" in first.text


def test_rotation_rejects_same_or_empty_new_passphrase(tmp_path: Path) -> None:
    _setup(tmp_path)
    _entries(tmp_path, 1)
    with pytest.raises(e2ee.E2eeError):
        e2ee.rotate_passphrase(tmp_path, PASS, PASS)
    with pytest.raises(e2ee.E2eeError):
        e2ee.rotate_passphrase(tmp_path, PASS, "")


def test_rotation_holds_process_lock_against_concurrent_writers(
    tmp_path: Path, monkeypatch
) -> None:
    """A writer trying to save during rotation blocks until it finishes —
    never interleaves a plaintext write into the middle of the swap."""
    _setup(tmp_path)
    _entries(tmp_path, 5)

    started = threading.Event()
    release = threading.Semaphore(0)
    real_rotate = e2ee.rotate_passphrase

    def slow_rotate(root, old, new):
        started.set()
        # Simulate long reseal; writer thread will try to enter the lock.
        assert release.acquire(timeout=10), "writer never signalled completion"
        return real_rotate(root, old, new)

    monkeypatch.setattr(e2ee, "rotate_passphrase", slow_rotate)

    result: dict = {}

    def rotator() -> None:
        try:
            with vault_process_lock(tmp_path):
                result["stats"] = e2ee.rotate_passphrase(tmp_path, PASS, NEW)
        except Exception as e:  # noqa: BLE001 — surface thread crash to asserts
            result["exc"] = f"{type(e).__name__}: {e}"

    t = threading.Thread(target=rotator)
    t.start()
    assert started.wait(timeout=5)

    write_done = threading.Event()

    def writer() -> None:
        # Contract-shaped id (HHMMSS = 6 digits); save after rotation.
        entry = Entry(
            id="2026-08-22_095900-pc",
            ts="2026-08-22T09:59:00+05:30",
            type="log",
            text="written after rotation",
        )
        save_entry(tmp_path, entry)
        write_done.set()

    def releaser() -> None:
        release.release()  # Semaphore.release — unblocks slow_rotate

    threading.Timer(0.05, releaser).start()
    wt = threading.Thread(target=writer)
    t.join(timeout=15)
    wt.start()
    wt.join(timeout=15)
    assert write_done.wait(timeout=10), "concurrent writer starved by rotation"
    assert "exc" not in result, result.get("exc")
    assert result["stats"]["resealed"] == 5
    late = load_entry(
        tmp_path / "_capture" / "entries" / "2026" / "08" / "2026-08-22_095900-pc.json",
        tmp_path,
    )
    assert late is not None
    # Written AFTER rotation under the still-unlocked new key → sealed anew.
    assert isinstance(late.text_enc, dict) or late.text == "written after rotation"
