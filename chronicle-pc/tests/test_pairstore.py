"""PairStore: persistent per-device LAN pairing tokens."""

from __future__ import annotations

import json
import logging
import os
import stat
from pathlib import Path

import pytest

from chronicle_pipeline.pairstore import PairingCorrupt, PairStore


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
    # A corrupt file used to be treated as an empty store and rewritten.
    # It must stay empty in memory and unchanged on disk.
    p = tmp_path / "pairing.json"
    raw = b"{ not json"
    p.write_bytes(raw)
    store = PairStore(p)
    assert len(store) == 0
    with pytest.raises(PairingCorrupt):
        store.add_device("phone")
    assert p.read_bytes() == raw


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


def test_garbage_pairing_file_is_not_replaced(tmp_path: Path, caplog: pytest.LogCaptureFixture) -> None:
    """Garbage bytes must survive ensure_default_device, and no device is minted."""
    path = tmp_path / "pairing.json"
    garbage = b"{ not json, token=super-secret-pairing-token"
    path.write_bytes(garbage)
    with caplog.at_level(logging.WARNING, logger="chronicle.pair"):
        store = PairStore(path)
        store.ensure_default_device("phone")
    assert path.read_bytes() == garbage
    assert len(store) == 0
    assert store.list_devices() == []
    assert store.token_for("phone") is None
    assert caplog.records, "corrupt pairing store must be logged"
    assert "super-secret-pairing-token" not in caplog.text
    assert garbage.decode("utf-8") not in caplog.text


def test_devices_not_object_is_not_replaced(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    raw = b'{"devices": []}'
    path.write_bytes(raw)
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert path.read_bytes() == raw
    assert len(store) == 0
    assert store.list_devices() == []


def test_missing_pairing_file_creates_one_device(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    assert not path.exists()
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert len(store) == 1
    body = json.loads(path.read_text(encoding="utf-8"))
    assert isinstance(body["devices"], dict)
    assert isinstance(body["devices"]["phone"], dict)
    assert body["devices"]["phone"]["token"]
    reloaded = PairStore(path)
    assert len(reloaded) == 1
    assert reloaded.token_for("phone") == store.token_for("phone")


def test_valid_two_device_file_round_trips_add(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    path.write_text(
        json.dumps(
            {
                "devices": {
                    "phone": {"token": "tok-phone", "created": "t1"},
                    "tablet": {"token": "tok-tablet", "created": "t2"},
                }
            }
        ),
        encoding="utf-8",
    )
    store = PairStore(path)
    assert store.verify("tok-phone") == "phone"
    assert store.verify("tok-tablet") == "tablet"
    laptop = store.add_device("laptop")
    reloaded = PairStore(path)
    assert reloaded.verify("tok-phone") == "phone"
    assert reloaded.verify("tok-tablet") == "tablet"
    assert reloaded.verify(laptop) == "laptop"
    assert {d["name"] for d in reloaded.list_devices()} == {"phone", "tablet", "laptop"}


def test_mutate_refuses_to_publish_over_corrupt_file(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    garbage = b"\xff\xfe not-utf8"
    path.write_bytes(garbage)
    store = PairStore(path)
    with pytest.raises(PairingCorrupt, match="corrupt pairing store"):
        store.add_device("phone")
    with pytest.raises(PairingCorrupt, match="corrupt pairing store"):
        store.remove_device("phone")
    assert path.read_bytes() == garbage
    assert len(store) == 0


@pytest.mark.parametrize(
    "raw",
    [
        b"",
        b"   \n",
        b"[]",
        b"null",
        b'"devices"',
        b"42",
        b'{"devices": null}',
        b'{"devices": "phone"}',
    ],
)
def test_invalid_pairing_shapes_are_not_replaced(tmp_path: Path, raw: bytes) -> None:
    path = tmp_path / "pairing.json"
    path.write_bytes(raw)
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert path.read_bytes() == raw
    assert len(store) == 0


def test_dangling_symlink_is_not_replaced(tmp_path: Path) -> None:
    target = tmp_path / "missing-target"
    path = tmp_path / "pairing.json"
    path.symlink_to(target)
    assert path.is_symlink()
    assert not path.exists()
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert path.is_symlink()
    assert os.readlink(path) == str(target)
    assert not target.exists()
    assert len(store) == 0
    with pytest.raises(PairingCorrupt, match="corrupt pairing store"):
        store.add_device("phone")
    assert path.is_symlink()
    assert not target.exists()


def test_pairing_directory_is_not_replaced(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    path.mkdir()
    marker = path / "keep"
    marker.write_text("stay", encoding="utf-8")
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert path.is_dir()
    assert marker.read_text(encoding="utf-8") == "stay"
    assert len(store) == 0
    with pytest.raises(PairingCorrupt, match="corrupt pairing store"):
        store.add_device("phone")
    assert path.is_dir()
    assert marker.read_text(encoding="utf-8") == "stay"


def test_valid_empty_store_still_mints_default(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    path.write_text('{"devices": {}}\n', encoding="utf-8")
    store = PairStore(path)
    store.ensure_default_device("phone")
    body = json.loads(path.read_text(encoding="utf-8"))
    assert list(body["devices"]) == ["phone"]
    assert body["devices"]["phone"]["token"]


def test_corrupt_store_hot_reloads_once_repaired(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    path.write_bytes(b"NOPE")
    store = PairStore(path)
    assert store.verify("t") is None
    path.write_text(
        json.dumps({"devices": {"phone": {"token": "t", "created": "c"}}}),
        encoding="utf-8",
    )
    assert store.verify("t") == "phone"


def test_all_nondict_device_values_are_not_replaced(tmp_path: Path) -> None:
    """Dropping every non-dict device must not look like an empty store and mint."""
    path = tmp_path / "pairing.json"
    raw = b'{"devices": {"phone": "not-a-dict", "tablet": null}}'
    path.write_bytes(raw)
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert path.read_bytes() == raw
    assert len(store) == 0
    assert store.list_devices() == []
    assert store.token_for("phone") is None
    with pytest.raises(PairingCorrupt):
        store.add_device("laptop")
    with pytest.raises(PairingCorrupt):
        store.remove_device("phone")
    assert path.read_bytes() == raw


def test_mixed_device_values_are_not_replaced(tmp_path: Path) -> None:
    """One non-dict value makes the whole file corrupt. Do not keep the dicts."""
    path = tmp_path / "pairing.json"
    raw = b'{"devices": {"phone": {"token": "tok-phone", "created": "c"}, "tablet": null}}'
    path.write_bytes(raw)
    store = PairStore(path)
    store.ensure_default_device("phone")
    assert path.read_bytes() == raw
    assert store.verify("tok-phone") is None
    with pytest.raises(PairingCorrupt):
        store.add_device("laptop")
    assert path.read_bytes() == raw


def test_object_without_devices_key_still_mints(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    path.write_text('{"version": 1}\n', encoding="utf-8")
    store = PairStore(path)
    store.ensure_default_device("phone")
    body = json.loads(path.read_text(encoding="utf-8"))
    assert list(body["devices"]) == ["phone"]
    assert body["devices"]["phone"]["token"]


def test_replace_between_read_and_stat_is_not_published(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A swap after the bytes are read must not be signed as those devices."""
    path = tmp_path / "pairing.json"
    valid = json.dumps(
        {"devices": {"phone": {"token": "tok-phone", "created": "c1"}}}
    ).encode()
    garbage = b'{"devices": "swapped-garbage-payload"}'
    path.write_bytes(valid)
    real_read_text = Path.read_text
    swapped = False

    def read_text(self: Path, *args: object, **kwargs: object) -> str:
        nonlocal swapped
        text = real_read_text(self, *args, **kwargs)
        if self == path and not swapped:
            path.write_bytes(garbage)
            swapped = True
        return text

    monkeypatch.setattr(Path, "read_text", read_text)
    store = PairStore(path)
    assert swapped
    assert path.read_bytes() == garbage
    with pytest.raises(PairingCorrupt):
        store.add_device("laptop")
    assert path.read_bytes() == garbage
    store.ensure_default_device("phone")
    assert path.read_bytes() == garbage
    assert len(store) == 0
    assert store.token_for("phone") is None


def test_symlink_to_valid_file_loads(tmp_path: Path) -> None:
    target = tmp_path / "real-pairing.json"
    target.write_text(
        json.dumps({"devices": {"phone": {"token": "tok-phone", "created": "t1"}}}),
        encoding="utf-8",
    )
    path = tmp_path / "pairing.json"
    path.symlink_to(target)
    store = PairStore(path)
    assert store.verify("tok-phone") == "phone"
    assert path.is_symlink()


def test_same_stat_swap_during_ensure_reload_is_not_minted(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Restoring mtime after a same-size swap must not look like a stable read.

    Init sees a missing path. The file is then a valid object with no devices.
    The read inside ensure_default_device's reload returns that object, then
    the bytes become same-sized garbage with the pre-read mtime restored.
    Post-read stat matches, so the empty parse is kept and ensure mints over
    the garbage. Those bytes must survive; raising PairingCorrupt is fine.
    """
    path = tmp_path / "pairing.json"
    store = PairStore(path)
    valid = b'{"version":1}'
    garbage = b"{" + b"G" * (len(valid) - 1)
    assert len(garbage) == len(valid)
    path.write_bytes(valid)
    real_read_text = Path.read_text
    swapped = False

    def read_text(self: Path, *args: object, **kwargs: object) -> str:
        nonlocal swapped
        text = real_read_text(self, *args, **kwargs)
        if self == path and not swapped:
            st = self.stat()
            path.write_bytes(garbage)
            os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns))
            swapped = True
        return text

    monkeypatch.setattr(Path, "read_text", read_text)
    try:
        store.ensure_default_device("phone")
    except PairingCorrupt:
        pass
    assert swapped
    assert path.read_bytes() == garbage


def test_same_stat_swap_during_pre_replace_read_is_not_published(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Garbage planted inside the pre-replace read must not be published over.

    Both load reads agree on an object with no devices key. The read that
    guards os.replace copies that text, then the file becomes same-sized
    garbage and the pre-read mtime is restored before the read returns.
    The string in hand still matches the accepted text; the bytes on disk
    do not, and those bytes must survive. Raising PairingCorrupt is fine.
    """
    path = tmp_path / "pairing.json"
    valid = b'{"version":1}'
    garbage = b"{" + b"H" * (len(valid) - 1)
    assert len(garbage) == len(valid)
    path.write_bytes(valid)
    store = PairStore(path)
    real_read_text = Path.read_text
    swapped = False

    def read_text(self: Path, *args: object, **kwargs: object) -> str:
        nonlocal swapped
        text = real_read_text(self, *args, **kwargs)
        if self == path and not swapped:
            st = self.stat()
            path.write_bytes(garbage)
            os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns))
            swapped = True
        return text

    monkeypatch.setattr(Path, "read_text", read_text)
    try:
        store.ensure_default_device("phone")
    except PairingCorrupt:
        pass
    assert swapped
    assert path.read_bytes() == garbage
