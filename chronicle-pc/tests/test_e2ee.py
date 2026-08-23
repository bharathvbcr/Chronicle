"""E2EE module: key derivation, sealing, unlock lifecycle, pipeline gating."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from chronicle_pipeline import e2ee
from chronicle_pipeline.entries import load_all_entries, load_entry, save_entry
from chronicle_pipeline.models import Entry

PASS = "correct horse battery staple"
WRONG = "tricorn beacon"


def _setup_vault(root: Path) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), root)


def _entry(root: Path, text: str = "secret dream notes") -> Entry:
    return Entry(id="2026-08-01_101010-an", ts="2026-08-01T10:10:10+05:30", type="log", text=text)


def test_roundtrip_encrypt_decrypt(tmp_path: Path) -> None:
    _setup_vault(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    blob = e2ee.encrypt_text(tmp_path, "hello secret")
    assert set(blob) == {"v", "nonce", "ct"}
    assert e2ee.decrypt_text(blob, tmp_path) == "hello secret"
    e2ee.lock(tmp_path)
    with pytest.raises(e2ee.E2eeError):
        e2ee.decrypt_text(blob, tmp_path)


def test_unlock_wrong_passphrase_fails(tmp_path: Path) -> None:
    _setup_vault(tmp_path)
    with pytest.raises(e2ee.E2eeError):
        e2ee.unlock(tmp_path, WRONG)
    assert not e2ee.is_unlocked(tmp_path)


def test_unlock_not_configured_raises(tmp_path: Path) -> None:
    with pytest.raises(e2ee.E2eeError):
        e2ee.unlock(tmp_path, PASS)


def test_seal_clears_plaintext_and_reseals(tmp_path: Path) -> None:
    _setup_vault(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    entry = _entry(tmp_path)
    e2ee.seal_entry(entry, tmp_path)
    assert entry.text == ""
    assert isinstance(entry.text_enc, dict)

    # Pipeline fills text (transcription) then reseals — new nonce, still encrypted
    entry.text = "transcript body"
    assert e2ee.reseal_entry(entry, tmp_path) is True
    assert entry.text == ""
    e2ee.lock(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    plain = e2ee.open_entry_text(entry, tmp_path)
    assert plain == "transcript body"


def test_save_entry_never_persists_plaintext_locked(tmp_path: Path) -> None:
    _setup_vault(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    entry = _entry(tmp_path)
    e2ee.seal_entry(entry, tmp_path)
    save_entry(tmp_path, entry)
    path = next(iter((tmp_path / "_capture" / "entries").rglob("*.json")))
    on_disk = json.loads(path.read_text())
    assert on_disk["text"] == ""
    assert isinstance(on_disk["text_enc"], dict)

    e2ee.lock(tmp_path)
    loaded = load_entry(path, tmp_path)
    assert loaded is not None
    assert loaded.text == ""  # fail closed when locked

    # Someone sets plaintext while locked → save strips it, keeps blob
    loaded.text = "LEAK ATTEMPT"
    save_entry(tmp_path, loaded)
    on_disk = json.loads(path.read_text())
    assert on_disk["text"] == ""

    # Unlocked load returns plaintext
    e2ee.unlock(tmp_path, PASS)
    loaded2 = load_entry(path, tmp_path)
    assert loaded2 is not None
    assert loaded2.text == "secret dream notes"


def test_process_skips_locked_entries(tmp_path: Path, chronicle_dir: Path) -> None:
    """Locked entries are excluded from processing loop (no filing, no marking)."""
    from chronicle_pipeline.process import run_process

    _setup_vault(chronicle_dir)
    e2ee.unlock(chronicle_dir, PASS)
    entry = Entry(
        id="2026-08-02_111111-an",
        ts="2026-08-02T11:11:11+05:30",
        type="log",
        text="",
    )
    e2ee.seal_entry(entry, chronicle_dir, plaintext="locked reflection")
    save_entry(chronicle_dir, entry)
    e2ee.lock(chronicle_dir)
    result = run_process(chronicle_dir)
    assert entry.id not in [e for e in result["processed"]]


def test_kdf_iteration_floor(tmp_path: Path) -> None:
    with pytest.raises(e2ee.E2eeError):
        e2ee.derive_key(PASS, b"saltbytes12345678", 1000)


def test_load_all_entries_decrypts_when_unlocked(tmp_path: Path) -> None:
    _setup_vault(tmp_path)
    e2ee.unlock(tmp_path, PASS)
    entry = _entry(tmp_path)
    save_entry(tmp_path, e2ee.seal_entry(entry, tmp_path))
    e2ee.lock(tmp_path)
    locked_view = load_all_entries(tmp_path)
    assert locked_view[0].text == ""
    e2ee.unlock(tmp_path, PASS)
    unlocked_view = load_all_entries(tmp_path)
    assert unlocked_view[0].text == "secret dream notes"
