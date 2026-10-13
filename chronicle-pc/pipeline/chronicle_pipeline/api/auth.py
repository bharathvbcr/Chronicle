"""E2EE unlock lifecycle + device pairing management for serve.

``GET  /auth/e2ee/status`` — enabled + KDF params + check blob (non-secret),
so a paired phone can verify the passphrase locally without a round trip.
``POST /auth/e2ee/unlock`` — derive/verify server-side; key stays in memory.
``POST /auth/e2ee/lock``   — drop the in-memory key (+ purge sealed docs from
the search index so locked entries stay unsearchable).

Device management is loopback-only (Mac UI Settings → Paired phones):
``GET    /auth/devices``        — name + created for each pairing
``DELETE /auth/devices/{name}`` — revoke immediately (hot-reloads into the
running server via PairStore's mtime check)
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from .. import e2ee
from .deps import get_root
from .system import is_loopback_client

router = APIRouter(prefix="/auth/e2ee", tags=["auth"])

devices_router = APIRouter(prefix="/auth/devices", tags=["auth"])


def _require_loopback(request: Request) -> None:
    if not is_loopback_client(request):
        # Token holders are still LAN clients — revocation is a Mac-local,
        # physical-trust action and never reachable over the network.
        raise HTTPException(403, "device management requires loopback access")


class UnlockBody(BaseModel):
    passphrase: str


class RotateBody(BaseModel):
    old_passphrase: str
    new_passphrase: str


def _status_payload(root) -> dict[str, Any]:
    block = e2ee.load_e2ee_config(root)
    if block is None:
        return {"enabled": False}
    return {
        "enabled": bool(block.get("enabled")),
        "kdf": block.get("kdf"),
        "check": block.get("check"),
        "unlocked": e2ee.is_unlocked(root),
    }


@router.get("/status")
def get_status(root=Depends(get_root)) -> dict[str, Any]:
    return _status_payload(root)


def _configured_iterations(block: dict[str, Any] | None) -> object:
    """Iteration count from a config block, or a non-int when it is absent.

    Callers pass the result to ``validate_kdf_request`` before any KDF.
    """
    if not isinstance(block, dict):
        return None
    kdf = block.get("kdf")
    if not isinstance(kdf, dict) or "iter" not in kdf:
        return None
    return kdf["iter"]


@router.post("/unlock")
def post_unlock(body: UnlockBody, root=Depends(get_root)) -> dict[str, Any]:
    try:
        # No vault lock on this path. Reject before unlock so a hostile
        # config.json count never reaches PBKDF2.
        block = e2ee.load_e2ee_config(root)
        if block is None:
            raise e2ee.E2eeError("e2ee is not configured for this vault")
        e2ee.validate_kdf_request(body.passphrase, _configured_iterations(block))
        e2ee.unlock(root, body.passphrase)
    except e2ee.E2eeError as e:
        raise HTTPException(403, str(e)) from e
    return {"ok": True, **_status_payload(root)}


@router.post("/lock")
def post_lock(root=Depends(get_root)) -> dict[str, Any]:
    e2ee.lock(root)
    # Fail-closed search: drop sealed entries' documents from the index so
    # plaintext snippets captured while unlocked don't stay queryable.
    from ..index_store import purge_locked_entries

    purged = purge_locked_entries(root)
    return {"ok": True, "unlocked": False, "purged_index_docs": purged}


@router.post("/rotate")
def post_rotate(body: RotateBody, root: Path = Depends(get_root)) -> dict[str, Any]:
    """Rotate the vault passphrase and reseal every encrypted entry.

    Mac-UI convenience over ``chronicle e2ee-setup --rotate``; identical
    verify-all-then-rewrite semantics. The phone picks up new params by
    re-reading config.json on its next unlock.
    """
    from ..lock import vault_process_lock

    try:
        # Length is known without config. Refuse before the lock so a long
        # passphrase cannot sit on the vault lock at all.
        e2ee.validate_kdf_request(body.old_passphrase, e2ee.DEFAULT_KDF_ITERATIONS)
        e2ee.validate_kdf_request(body.new_passphrase, e2ee.DEFAULT_KDF_ITERATIONS)
    except e2ee.E2eeError as e:
        raise HTTPException(403, str(e)) from e

    with vault_process_lock(root):
        try:
            # The count lives in config.json. Read it under the same lock that
            # wraps rotate, and refuse before derive_key. Releasing the lock
            # between the check and the rotate would let two rotates interleave.
            block = e2ee.load_e2ee_config(root)
            if block is None or not block.get("enabled"):
                raise e2ee.E2eeError("e2ee is not enabled for this vault")
            e2ee.validate_kdf_request(body.old_passphrase, _configured_iterations(block))
            e2ee.validate_kdf_request(body.new_passphrase, e2ee.DEFAULT_KDF_ITERATIONS)
            stats = e2ee.rotate_passphrase(root, body.old_passphrase, body.new_passphrase)
        except e2ee.E2eeError as e:
            raise HTTPException(403, str(e)) from e
    return {"ok": True, **stats}


@devices_router.get("")
def list_devices(request: Request) -> dict[str, Any]:
    _require_loopback(request)
    from ..pairstore import PairStore

    return {"devices": PairStore(PairStore.default_path()).list_devices()}


@devices_router.delete("/{name}")
def revoke_device(name: str, request: Request) -> dict[str, Any]:
    _require_loopback(request)
    from ..pairstore import PairStore

    store = PairStore(PairStore.default_path())
    if not store.remove_device(name):
        raise HTTPException(404, f"no such device: {name}")
    # The running server's own PairStore instance hot-reloads on its next
    # verify() (mtime check), so revocation is immediate — no restart.
    return {"ok": True, "revoked": name}
