"""KDF inputs must be rejected before PBKDF2, not after a hostile count starts.

A peer who can write config.json can set ``e2ee.kdf.iter``. Counts below
100_000 were already refused; counts above 1_000_000 and passphrases longer
than 1024 characters must fail the same way — before hashlib runs.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from chronicle_pipeline import e2ee
from chronicle_pipeline.lock import vault_process_lock
from chronicle_pipeline.serve import TOKEN_HEADER, create_app

SALT = b"0123456789abcdef"
PASS = "correct horse"
HDR = {TOKEN_HEADER: "tok"}


def _patch_pbkdf2(monkeypatch: pytest.MonkeyPatch) -> list[int]:
    """Replace PBKDF2 with a counter. The real function must not run."""
    calls: list[int] = []

    def fake(*_args: object, **_kwargs: object) -> bytes:
        calls.append(1)
        raise AssertionError("PBKDF2 must not run")

    monkeypatch.setattr(e2ee.hashlib, "pbkdf2_hmac", fake)
    return calls


def _block(passphrase: str, iterations: int = 100_000) -> dict:
    salt = SALT
    key = e2ee.derive_key(passphrase, salt, iterations)
    nonce = b"0123456789ab"
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    ct = AESGCM(key).encrypt(nonce, e2ee.E2EE_CHECK_PLAINTEXT, None)
    return {
        "enabled": True,
        "kdf": {"alg": "pbkdf2-sha256", "iter": iterations, "salt": e2ee._b64(salt)},
        "check": {"nonce": e2ee._b64(nonce), "ct": e2ee._b64(ct)},
    }


def test_iter_above_ceiling_raises_before_pbkdf2(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = _patch_pbkdf2(monkeypatch)
    with pytest.raises(e2ee.E2eeError):
        e2ee.derive_key(PASS, SALT, 1_000_001)
    assert calls == []


def test_iter_ceiling_and_default_still_derive() -> None:
    hi = e2ee.derive_key(PASS, SALT, 1_000_000)
    default = e2ee.derive_key(PASS, SALT, 600_000)
    floor = e2ee.derive_key(PASS, SALT, 100_000)
    assert len(hi) == len(default) == len(floor) == 32
    assert len({hi, default, floor}) == 3


def test_passphrase_above_1024_raises_before_pbkdf2(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = _patch_pbkdf2(monkeypatch)
    with pytest.raises(e2ee.E2eeError):
        e2ee.derive_key("a" * 1025, SALT, 100_000)
    assert calls == []


def test_passphrase_of_1024_still_derives() -> None:
    key = e2ee.derive_key("a" * 1024, SALT, 100_000)
    assert len(key) == 32


def test_unlock_rejects_over_cap_and_non_int_before_pbkdf2(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    block = _block(PASS, 100_000)
    e2ee.save_e2ee_config(block, tmp_path)
    calls = _patch_pbkdf2(monkeypatch)

    cfg_path = tmp_path / "config.json"
    raw = json.loads(cfg_path.read_text(encoding="utf-8"))
    for hostile in (1_000_001, "600000", True, 1.0, None):
        raw["e2ee"]["kdf"]["iter"] = hostile
        cfg_path.write_text(json.dumps(raw), encoding="utf-8")
        with pytest.raises(e2ee.E2eeError):
            e2ee.unlock(tmp_path, PASS)
        assert calls == [], hostile
    assert not e2ee.is_unlocked(tmp_path)


def test_unlock_rejects_over_long_passphrase_before_pbkdf2(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    e2ee.save_e2ee_config(_block(PASS, 100_000), tmp_path)
    calls = _patch_pbkdf2(monkeypatch)
    with pytest.raises(e2ee.E2eeError):
        e2ee.unlock(tmp_path, "b" * 1025)
    assert calls == []
    assert not e2ee.is_unlocked(tmp_path)


def _client(root: Path) -> TestClient:
    return TestClient(
        create_app(
            root,
            connect_info={
                "base": "http://192.168.1.10:8765",
                "token": "tok",
                "auth_required": True,
                "tls": False,
            },
        )
    )


def test_rotate_over_cap_iter_is_rejected_inside_the_lock_before_derive(
    chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    block = _block(PASS, 100_000)
    block["kdf"]["iter"] = 1_000_001
    e2ee.save_e2ee_config(block, chronicle_dir)

    derives: list[int] = []

    def boom(*_args: object, **_kwargs: object) -> bytes:
        derives.append(1)
        raise AssertionError("derive_key called")

    entered: list[int] = []

    @contextmanager
    def spy(root: Path) -> Iterator[None]:
        entered.append(1)
        with vault_process_lock(root):
            yield

    monkeypatch.setattr(e2ee, "derive_key", boom)
    monkeypatch.setattr("chronicle_pipeline.lock.vault_process_lock", spy)

    res = _client(chronicle_dir).post(
        "/auth/e2ee/rotate",
        headers=HDR,
        json={"old_passphrase": PASS, "new_passphrase": "rotated horse"},
    )
    assert res.status_code == 403
    assert derives == []
    # Config is read under the one lock rotate already holds — not before it,
    # and not around a KDF.
    assert entered == [1]


def test_rotate_over_long_passphrase_does_not_take_the_lock_or_derive(
    chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    e2ee.save_e2ee_config(_block(PASS, 100_000), chronicle_dir)
    derives: list[int] = []

    def boom(*_args: object, **_kwargs: object) -> bytes:
        derives.append(1)
        raise AssertionError("derive_key called")

    entered: list[int] = []

    @contextmanager
    def spy(root: Path) -> Iterator[None]:
        entered.append(1)
        with vault_process_lock(root):
            yield

    monkeypatch.setattr(e2ee, "derive_key", boom)
    monkeypatch.setattr("chronicle_pipeline.lock.vault_process_lock", spy)

    res = _client(chronicle_dir).post(
        "/auth/e2ee/rotate",
        headers=HDR,
        json={"old_passphrase": PASS, "new_passphrase": "n" * 1025},
    )
    assert res.status_code == 403
    assert derives == []
    assert entered == []


def test_unlock_endpoint_rejects_over_cap_before_derive(
    chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    block = _block(PASS, 100_000)
    block["kdf"]["iter"] = 1_000_001
    e2ee.save_e2ee_config(block, chronicle_dir)
    derives: list[int] = []

    def boom(*_args: object, **_kwargs: object) -> bytes:
        derives.append(1)
        raise AssertionError("derive_key called")

    monkeypatch.setattr(e2ee, "derive_key", boom)
    res = _client(chronicle_dir).post(
        "/auth/e2ee/unlock",
        headers=HDR,
        json={"passphrase": PASS},
    )
    assert res.status_code == 403
    assert derives == []
