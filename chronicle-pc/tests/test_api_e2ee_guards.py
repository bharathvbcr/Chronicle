"""API guards for E2EE: locked vaults refuse plaintext writes and edits.

Complements tests/test_e2ee.py (module-level save_entry semantics) by pinning
the HTTP surface: 423 on fresh plaintext writes while locked, 409 on editing
encrypted entries while locked, and transparent sealing of fresh entries when
unlocked.
"""

from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from chronicle_pipeline import e2ee
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


def _enable(chronicle_dir: Path) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), chronicle_dir)


def _post_entry(client: TestClient, text: str) -> dict:
    res = client.post(
        "/entries",
        headers=HDR,
        json={"type": "log", "text": text, "tags": []},
    )
    assert res.status_code == 201, res.text
    return res.json()


def test_create_while_unlocked_seals_fresh_entry(chronicle_dir: Path) -> None:
    """Regression: fresh API-created entries stayed plaintext forever."""
    _enable(chronicle_dir)
    e2ee.unlock(chronicle_dir, PASS)
    client = _client(chronicle_dir)

    created = _post_entry(client, "typed from the mac ui")
    assert created["text"] == ""
    assert isinstance(created["text_enc"], dict)

    # On disk too — not just the response payload.
    entry_id = created["id"]
    matches = list((chronicle_dir / "_capture" / "entries").rglob(f"{entry_id}.json"))
    assert matches, f"entry file not found for {entry_id}"
    on_disk = json.loads(matches[0].read_text())
    assert on_disk["text"] == ""
    assert isinstance(on_disk["text_enc"], dict)

    # Round trip: unlocked reads return plaintext.
    fetched = client.get(f"/entries/{entry_id}", headers=HDR).json()
    assert fetched["text"] == "typed from the mac ui"


def test_create_while_locked_returns_423(chronicle_dir: Path) -> None:
    _enable(chronicle_dir)
    assert not e2ee.is_unlocked(chronicle_dir)
    client = _client(chronicle_dir)

    res = client.post("/entries", headers=HDR, json={"type": "log", "text": "no key yet"})
    assert res.status_code == 423
    assert "locked" in res.json()["detail"].lower()

    # Nothing plaintext landed on disk.
    for path in (chronicle_dir / "_capture" / "entries").rglob("*.json"):
        assert "no key yet" not in path.read_text()

    # Unlocking makes the same write succeed.
    e2ee.unlock(chronicle_dir, PASS)
    ok = _post_entry(client, "after unlock")
    assert isinstance(ok["text_enc"], dict)


def test_patch_encrypted_entry_while_locked_423(chronicle_dir: Path) -> None:
    _enable(chronicle_dir)
    e2ee.unlock(chronicle_dir, PASS)
    client = _client(chronicle_dir)
    created = _post_entry(client, "sealed body")
    entry_id = created["id"]

    e2ee.lock(chronicle_dir)
    res = client.patch(
        f"/entries/{entry_id}",
        headers=HDR,
        json={"text": "edit attempt while locked"},
    )
    # 423 everywhere: create, mirror, patch, delete — one condition, one code.
    assert res.status_code == 423
    assert "unlock" in res.json()["detail"].lower()

    # DELETE is guarded too: destroying unread ciphertext loses data forever.
    del_res = client.delete(f"/entries/{entry_id}", headers=HDR)
    assert del_res.status_code == 423
    # Ciphertext untouched by both refused calls.
    on_disk = json.loads(
        next((chronicle_dir / "_capture" / "entries").rglob(f"{entry_id}.json")).read_text()
    )
    assert isinstance(on_disk["text_enc"], dict)
    assert on_disk["text"] == ""

    # Unlocked vault allows deletion of the encrypted entry.
    e2ee.unlock(chronicle_dir, PASS)
    ok_del = client.delete(f"/entries/{entry_id}", headers=HDR)
    assert ok_del.status_code == 200
    assert not list((chronicle_dir / "_capture" / "entries").rglob(f"{entry_id}.json"))



def test_plaintext_vault_unaffected(chronicle_dir: Path) -> None:
    """No e2ee block → create/patch behave exactly as before."""
    client = _client(chronicle_dir)
    created = _post_entry(client, "plain old note")
    assert created["text"] == "plain old note"
    patched = client.patch(
        f"/entries/{created['id']}", headers=HDR, json={"text": "still plain"}
    )
    assert patched.status_code == 200
    assert patched.json()["text"] == "still plain"
