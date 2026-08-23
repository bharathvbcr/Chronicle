"""PairStore: persistent per-device LAN pairing tokens."""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path

import pytest

from chronicle_pipeline.pairstore import PairStore


def _store(tmp_path, name="pairing.json") -> PairStore:
    return PairStore(tmp_path / name)


def test_add_verify_remove_roundtrip(tmp_path) -> None:
    store = _store(tmp_path)
    token = store.add_device("phone")
    assert store.verify(token) == "phone"
    assert store.verify("not-the-token") is None
    assert store.verify(None) is None

    tablet = store.add_device("tablet")
    assert store.token_for("phone") == token  # independent tokens
    assert store.token_for("tablet") == tablet

    # Persistence: a fresh instance reads the same devices
    reloaded = _store(tmp_path)
    assert reloaded.verify(token) == "phone"

    assert store.remove_device("tablet") is True
    assert store.verify(tablet) is None


def test_default_device_created_once(tmp_path) -> None:
    store = _store(tmp_path)
    store.ensure_default_device("phone")
    first = store.token_for("phone")
    store.ensure_default_device("phone")
    assert store.token_for("phone") == first  # never rotate silently


def test_file_permissions_0600(tmp_path) -> None:
    store = _store(tmp_path)
    store.add_device("phone")
    mode = stat.S_IMODE(os.stat(tmp_path / "pairing.json").st_mode)
    assert mode & 0o077 == 0


def test_invalid_device_name_rejected(tmp_path) -> None:
    store = _store(tmp_path)
    with pytest.raises(ValueError):
        store.add_device("../escape")
    with pytest.raises(ValueError):
        store.add_device("  ")
    with pytest.raises(ValueError):
        store.add_device("a\nb")


def test_corrupt_store_starts_empty(tmp_path) -> None:
    p = tmp_path / "pairing.json"
    p.write_text("{ not json", encoding="utf-8")
    store = PairStore(p)
    assert len(store) == 0
    store.add_device("phone")  # recovers by rewriting
    assert json.loads(p.read_text())["devices"]["phone"]


def test_external_revocation_hot_reloads(tmp_path: Path) -> None:
    """A second PairStore instance (CLI) revoking must affect the first
    (running serve) without restart — regression: serve kept verifying a
    revoked token forever because it held only its in-memory snapshot."""
    path = tmp_path / "pairing.json"
    store = PairStore(path)
    token = store.add_device("phone")

    other = PairStore(path)  # what `chronicle unpair` constructs
    assert other.remove_device("phone") is True

    # Same in-memory store, no reload call — next verify sees the revocation.
    assert store.verify(token) is None


def test_external_addition_visible_and_merge_safe(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    store = PairStore(path)
    store.add_device("phone")

    cli = PairStore(path)
    new_token = cli.add_device("tablet")
    assert store.verify(new_token) == "tablet"

    # Concurrent-style mutation from both sides keeps both devices.
    store2 = PairStore(path)
    store2.add_device("laptop", token="tok-laptop")
    names = {d["name"] for d in store.list_devices()}
    assert {"phone", "tablet", "laptop"} <= names
