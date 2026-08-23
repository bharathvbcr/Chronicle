"""Field-level E2EE for entry text (opt-in).

Passphrase-derived key (PBKDF2-HmacSHA256, 600k iterations) + AES-256-GCM.
KDF params and a GCM check blob live in ``config.json`` under ``e2ee`` —
both are non-secret; the passphrase is never stored. Phone and PC derive
the same key from the shared params, so either side can seal/open captures.

Entry format (contract v1.11): ``text_enc = {"v":1,"nonce":b64,"ct":b64}``
with ``text`` set to ``""``. Unknown-key round-tripping in both apps keeps
the blob intact on load/save.

Locked semantics: an entry with ``text_enc`` whose vault is not unlocked has
no usable plaintext. Pipeline skips transcription/vision/indexing for locked
entries rather than writing plaintext or junk vectors.
"""

from __future__ import annotations

import base64
import hashlib
import os
import threading
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .paths import atomic_write_json, read_json, resolve_chronicle_dir

E2EE_CHECK_PLAINTEXT = b"chronicle-e2ee-check-v1"
DEFAULT_KDF_ITERATIONS = 600_000
_KEY_LEN = 32
_NONCE_LEN = 12
_SALT_LEN = 16

# Vault root (resolved str) -> derived key bytes. Process-lifetime only.
_UNLOCKED: dict[str, bytes] = {}
_UNLOCK_LOCK = threading.Lock()


class E2eeError(Exception):
    pass


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _unb64(raw: str) -> bytes:
    if not isinstance(raw, (str, bytes, bytearray)):
        # Hostile/malformed blob (e.g. nonce as int) must surface as a clean
        # E2eeError from decrypt_text, not an AttributeError crash.
        raise ValueError("invalid base64 payload type")
    return base64.b64decode(raw)


def derive_key(passphrase: str, salt: bytes, iterations: int) -> bytes:
    if not passphrase:
        raise E2eeError("passphrase must not be empty")
    if iterations < 100_000:
        raise E2eeError("kdf iterations below 100_000 refused")
    return hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, iterations, dklen=_KEY_LEN)


def default_e2ee_block(passphrase: str) -> dict[str, Any]:
    """Fresh config.json ``e2ee`` block enabling protection."""
    salt = os.urandom(_SALT_LEN)
    key = derive_key(passphrase, salt, DEFAULT_KDF_ITERATIONS)
    nonce = os.urandom(_NONCE_LEN)
    ct = AESGCM(key).encrypt(nonce, E2EE_CHECK_PLAINTEXT, None)
    return {
        "enabled": True,
        "kdf": {"alg": "pbkdf2-sha256", "iter": DEFAULT_KDF_ITERATIONS, "salt": _b64(salt)},
        "check": {"nonce": _b64(nonce), "ct": _b64(ct)},
    }


def load_e2ee_config(root: Path | str | None = None) -> dict[str, Any] | None:
    """Return the ``e2ee`` config block, or None when absent/malformed."""
    cfg_path = resolve_chronicle_dir(root) / "config.json"
    if not cfg_path.exists():
        return None
    try:
        raw = read_json(cfg_path)
    except Exception:  # noqa: BLE001 — corrupt config means "not configured"
        return None
    block = raw.get("e2ee") if isinstance(raw, dict) else None
    if not isinstance(block, dict):
        return None
    kdf = block.get("kdf")
    check = block.get("check")
    if not isinstance(kdf, dict) or not isinstance(check, dict):
        return None
    if kdf.get("alg") != "pbkdf2-sha256":
        return None
    return block


def save_e2ee_config(block: dict[str, Any], root: Path | str | None = None) -> Path:
    cfg_path = resolve_chronicle_dir(root) / "config.json"
    raw = read_json(cfg_path) if cfg_path.exists() else {}
    raw["e2ee"] = block
    atomic_write_json(cfg_path, raw)
    return cfg_path


# --- passphrase / key lifecycle -------------------------------------------


def unlock(root: Path | str | None, passphrase: str) -> bool:
    """Derive + verify against the check blob; cache the key. True when ok."""
    resolved = str(resolve_chronicle_dir(root))
    block = load_e2ee_config(resolved)
    if block is None:
        raise E2eeError("e2ee is not configured for this vault")
    kdf = block["kdf"]
    check = block["check"]
    try:
        key = derive_key(passphrase, _unb64(kdf["salt"]), int(kdf.get("iter", 0)))
        plain = AESGCM(key).decrypt(_unb64(check["nonce"]), _unb64(check["ct"]), None)
    except (KeyError, ValueError, TypeError, InvalidTag) as e:
        raise E2eeError(f"unlock failed: {type(e).__name__}") from e
    if plain != E2EE_CHECK_PLAINTEXT:
        raise E2eeError("unlock failed: check mismatch")
    with _UNLOCK_LOCK:
        _UNLOCKED[resolved] = key
    return True


def lock(root: Path | str | None) -> None:
    with _UNLOCK_LOCK:
        _UNLOCKED.pop(str(resolve_chronicle_dir(root)), None)


def is_unlocked(root: Path | str | None) -> bool:
    with _UNLOCK_LOCK:
        return str(resolve_chronicle_dir(root)) in _UNLOCKED


def _key_for(root: Path | str | None) -> bytes:
    resolved = str(resolve_chronicle_dir(root))
    with _UNLOCK_LOCK:
        key = _UNLOCKED.get(resolved)
    if key is None:
        raise E2eeError("vault is locked")
    return key


# --- entry field sealing ----------------------------------------------------


def entry_is_encrypted(entry: Any) -> bool:
    """True when the entry carries a text_enc blob."""
    return isinstance(getattr(entry, "text_enc", None) or None, dict) or (
        isinstance(entry, dict) and isinstance(entry.get("text_enc"), dict)
    )


def entry_locked(entry: Any, root: Path | str | None = None) -> bool:
    """True when the entry is encrypted and its vault is not unlocked."""
    if not entry_is_encrypted(entry):
        return False
    return not is_unlocked(root)


def encrypt_text(root: Path | str | None, plaintext: str) -> dict[str, Any]:
    key = _key_for(root)
    nonce = os.urandom(_NONCE_LEN)
    ct = AESGCM(key).encrypt(nonce, plaintext.encode("utf-8"), None)
    return {"v": 1, "nonce": _b64(nonce), "ct": _b64(ct)}


_BLOB_KEYS = {"v", "nonce", "ct"}


def decrypt_text(blob: dict[str, Any], root: Path | str | None = None) -> str:
    if not isinstance(blob, dict):
        raise E2eeError("text_enc blob missing")
    # Contract shape is exactly {v:1, nonce, ct}; extra/missing keys or a
    # foreign version are hostile or foreign data — refuse rather than
    # best-effort decrypt.
    if set(blob) != _BLOB_KEYS or blob.get("v") != 1:
        raise E2eeError("text_enc blob malformed")
    key = _key_for(root)
    try:
        plain = AESGCM(key).decrypt(
            _unb64(blob["nonce"]), _unb64(blob["ct"]), None
        )
    except (KeyError, ValueError, TypeError, InvalidTag) as e:
        raise E2eeError(f"decrypt failed: {type(e).__name__}") from e
    return plain.decode("utf-8")


def open_entry_text(entry: Any, root: Path | str | None = None) -> str | None:
    """Plaintext of an entry, or None when locked. Plain entries pass through."""
    if not entry_is_encrypted(entry):
        return getattr(entry, "text", "") if not isinstance(entry, dict) else entry.get("text", "")
    if entry_locked(entry, root):
        return None
    blob = (
        entry.text_enc
        if not isinstance(entry, dict)
        else entry.get("text_enc")
    )
    return decrypt_text(blob, root)


def seal_entry(entry: Any, root: Path | str | None, *, plaintext: str | None = None) -> Any:
    """Encrypt entry text into text_enc (mutates pydantic model in place).

    Call only while unlocked. Clears ``text`` so plaintext never persists.
    """
    source = plaintext if plaintext is not None else (getattr(entry, "text", "") or "")
    blob = encrypt_text(root, source)
    entry.text = ""
    entry.text_enc = blob
    return entry


def reseal_entry(entry: Any, root: Path | str | None) -> bool:
    """Re-seal after pipeline filled ``entry.text`` (transcription/captions).

    Returns False (and leaves the entry untouched) when the vault is locked;
    callers must skip work that would write plaintext instead.
    """
    if not entry_is_encrypted(entry):
        return False
    if not is_unlocked(root):
        return False
    seal_entry(entry, root)
    return True


# --- passphrase rotation -----------------------------------------------------


def rotate_passphrase(
    root: Path | str | None,
    old_passphrase: str,
    new_passphrase: str,
) -> dict[str, Any]:
    """Rotate the vault passphrase, resealing every encrypted entry.

    Safety order (fail-closed):
      1. Verify the old passphrase against the check blob.
      2. Decrypt EVERY text_enc blob into memory — any unreadable entry
         aborts the rotation with nothing written.
      3. Only then mint fresh params and rewrite blobs + config atomically.

    Callers must hold ``vault_process_lock`` so the pipeline cannot write
    plaintext entries mid-swap. Returns stats:
    ``{resealed, skipped_corrupt, failed_ids}``.
    """
    if not new_passphrase:
        raise E2eeError("new passphrase must not be empty")
    if new_passphrase == old_passphrase:
        raise E2eeError("new passphrase must differ from the current one")

    resolved = resolve_chronicle_dir(root)
    block = load_e2ee_config(resolved)
    if block is None or not block.get("enabled"):
        raise E2eeError("e2ee is not enabled for this vault")

    # 1) Verify the old passphrase (caches the key decrypt_text relies on).
    unlock(resolved, old_passphrase)

    # 2) Verify-decrypt everything first (nothing written on failure).
    from .entries import iter_entry_paths
    from .paths import atomic_write_json, read_json

    plan: list[tuple[Path, dict[str, Any], str]] = []
    failed_ids: list[str] = []
    skipped_corrupt = 0
    for path in iter_entry_paths(resolved):
        try:
            raw = read_json(path)
        except Exception:  # noqa: BLE001
            skipped_corrupt += 1
            continue
        if not isinstance(raw.get("text_enc"), dict):
            continue
        try:
            plain = decrypt_text(raw["text_enc"], resolved)
        except E2eeError:
            failed_ids.append(path.stem)
            continue
        plan.append((path, raw, plain))

    if failed_ids:
        raise E2eeError(
            "rotation aborted — "
            f"{len(failed_ids)} entr(y/ies) cannot be opened with the current "
            f"passphrase: {', '.join(sorted(failed_ids)[:10])}"
        )

    # 3) Mint new params; reseal; persist per-file atomically.
    new_salt = os.urandom(_SALT_LEN)
    new_iter = DEFAULT_KDF_ITERATIONS
    new_key = derive_key(new_passphrase, new_salt, new_iter)
    nonce = os.urandom(_NONCE_LEN)
    check_ct = AESGCM(new_key).encrypt(nonce, E2EE_CHECK_PLAINTEXT, None)  # already bytes
    new_block: dict[str, Any] = {
        "enabled": True,
        "kdf": {"alg": "pbkdf2-sha256", "iter": new_iter, "salt": _b64(new_salt)},
        "check": {"nonce": _b64(nonce), "ct": _b64(check_ct)},
    }

    resealed = 0
    for path, raw, plain in plan:
        real_nonce = os.urandom(_NONCE_LEN)
        real_ct = AESGCM(new_key).encrypt(real_nonce, plain.encode("utf-8"), None)
        raw["text"] = ""
        raw["text_enc"] = {"v": 1, "nonce": _b64(real_nonce), "ct": _b64(real_ct)}
        atomic_write_json(path, raw)
        resealed += 1

    save_e2ee_config(new_block, resolved)
    with _UNLOCK_LOCK:
        _UNLOCKED[str(resolved)] = new_key
    return {
        "resealed": resealed,
        "skipped_corrupt": skipped_corrupt,
        "failed_ids": [],
    }
