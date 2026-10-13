"""Persistent per-device pairing tokens for LAN serve.

Tokens live in ``~/.config/chronicle/pairing.json`` (mode 0600) so the phone
survives serve restarts without re-scanning a QR. Each device gets its own
token; revoking one device never disturbs the others.

The store hot-reloads when the file changes underneath it (mtime/size check
on every :meth:`verify`), so ``chronicle pair`` / ``chronicle unpair`` from
another process take effect on a running server immediately — revocation of
a lost device must not wait for a restart. Cross-process read-modify-write
is serialized with an advisory lock file.

A missing path is a fresh install and may be created. A path that exists but
is not valid pairing data (including a dangling symlink) is left byte-for-byte
unchanged: the store does not mint a device over it.

``CHRONICLE_PAIRING_FILE`` (or ``CHRONICLE_CONFIG_HOME``) overrides the
location — tests use it to stay off real user config.
"""

from __future__ import annotations

import fcntl
import json
import logging
import os
import secrets
import stat
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path

_LOG = logging.getLogger("chronicle.pair")
# Not a real st_mtime_ns/st_size pair (size cannot be negative). A load that
# observed the file change mid-read stores this so the next call must re-read
# instead of trusting devices that were never paired with the bytes on disk.
_FORCE_RELOAD_SIG = (-1, -1)


class PairingCorrupt(Exception):
    """The pairing path exists but is not valid pairing data.

    Mutators raise this instead of publishing a new file over the path.
    """


class PairStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        # RLock: ensure_default_device holds it across add_device.
        self._lock = threading.RLock()
        self._devices: dict[str, dict] = {}
        self._corrupt: str | None = None
        self._accepted_text: str | None = None
        self._file_sig: tuple[int, int] | None = None  # (st_mtime_ns, st_size)
        self._load()

    # -- persistence ---------------------------------------------------------

    @classmethod
    def default_path(cls) -> Path:
        env_file = os.environ.get("CHRONICLE_PAIRING_FILE")
        if env_file:
            return Path(env_file).expanduser()
        env_home = os.environ.get("CHRONICLE_CONFIG_HOME")
        base = (
            Path(env_home).expanduser()
            if env_home
            else Path.home() / ".config" / "chronicle"
        )
        return base / "pairing.json"

    def _stat_sig(self) -> tuple[int, int] | None:
        """mtime/size of the path. ``stat`` follows links, so a dangling symlink uses ``lstat``."""
        try:
            st = self.path.stat()
        except OSError:
            try:
                st = self.path.lstat()
            except OSError:
                return None
        return (st.st_mtime_ns, st.st_size)

    def _reload_if_changed(self) -> None:
        """Hot-reload when another process rewrote the file. Call under lock."""
        sig = self._stat_sig()
        if sig != self._file_sig:
            self._load()

    def _load(self) -> None:
        """Load devices, or remember that the path is missing or corrupt.

        Missing (no directory entry) is a fresh install: empty, not corrupt.
        Anything else we cannot accept as pairing data is corrupt. The corrupt
        reason is a fixed phrase — never the file body or a token.
        """
        try:
            self.path.lstat()
        except FileNotFoundError:
            self._set_missing()
            return
        except OSError as e:
            self._mark_corrupt(
                f"pairing store unreadable ({type(e).__name__})", self._stat_sig()
            )
            return
        sig_before = self._stat_sig()
        try:
            text = self._read_text()
        except FileNotFoundError:
            if self._stat_sig() is not None:
                self._note_torn_read()
                return
            self._set_missing()
            return
        except PairingCorrupt as e:
            if not self._read_error_is_stable(sig_before):
                self._note_torn_read()
                return
            self._mark_corrupt(str(e), sig_before)
            return
        try:
            devices = self._parse_devices(text)
        except PairingCorrupt as e:
            sig = self._stable_sig(text, sig_before)
            if sig is None:
                self._note_torn_read()
                return
            self._mark_corrupt(str(e), sig)
            return
        sig = self._stable_sig(text, sig_before)
        if sig is None:
            # mtime and size are not content identity. A second read that
            # disagrees, or a stat that moved, is a torn read: do not keep
            # this parse and do not pin _file_sig to bytes we did not accept.
            self._note_torn_read()
            return
        self._devices = devices
        self._corrupt = None
        self._accepted_text = text
        self._file_sig = sig

    def _stable_sig(
        self, text: str, sig_before: tuple[int, int] | None
    ) -> tuple[int, int] | None:
        """Signature of `text` only if a second read returns it and the stat matches.

        Equal (mtime, size) alone is not proof the bytes are unchanged.
        """
        if sig_before is None:
            return None
        try:
            again = self._read_text()
        except (OSError, UnicodeDecodeError, PairingCorrupt):
            return None
        if again != text:
            return None
        sig_after = self._stat_sig()
        if sig_after != sig_before:
            return None
        return sig_after

    def _read_error_is_stable(self, sig_before: tuple[int, int] | None) -> bool:
        """True when a non-text path still fails the same way and the stat matches."""
        if sig_before is None or self._stat_sig() != sig_before:
            return False
        try:
            self._read_text()
        except PairingCorrupt:
            return self._stat_sig() == sig_before
        except (OSError, UnicodeDecodeError):
            return False
        return False

    def _set_missing(self) -> None:
        self._devices = {}
        self._corrupt = None
        self._accepted_text = None
        self._file_sig = None

    def _note_torn_read(self) -> None:
        self._devices = {}
        self._corrupt = None
        self._accepted_text = None
        self._file_sig = _FORCE_RELOAD_SIG
        _LOG.warning("pairing store changed while reading; will reload")

    def _mark_corrupt(self, reason: str, sig: tuple[int, int] | None) -> None:
        self._devices = {}
        self._corrupt = reason
        self._accepted_text = None
        self._file_sig = sig
        _LOG.warning("corrupt pairing store; leaving it unchanged (%s)", reason)

    def _read_text(self) -> str:
        """Return the file text, or raise PairingCorrupt for a path that is not one."""
        listed = self.path.lstat()
        if stat.S_ISLNK(listed.st_mode):
            try:
                followed = self.path.stat()
            except OSError as e:
                raise PairingCorrupt(
                    f"pairing store is a dangling symlink ({type(e).__name__})"
                ) from e
            mode = followed.st_mode
        else:
            mode = listed.st_mode
        if stat.S_ISDIR(mode):
            raise PairingCorrupt("pairing store path is a directory")
        if not stat.S_ISREG(mode):
            raise PairingCorrupt("pairing store path is not a regular file")
        try:
            return self.path.read_text(encoding="utf-8")
        except UnicodeDecodeError as e:
            raise PairingCorrupt("pairing store is not valid UTF-8") from e
        except OSError as e:
            raise PairingCorrupt(f"pairing store unreadable ({type(e).__name__})") from e

    def _parse_devices(self, text: str) -> dict[str, dict]:
        """Return devices from pairing text, or raise PairingCorrupt."""
        if not text.strip():
            raise PairingCorrupt("pairing store is empty")
        try:
            raw = json.loads(text)
        except json.JSONDecodeError as e:
            raise PairingCorrupt("pairing store is not valid JSON") from e
        if not isinstance(raw, dict):
            raise PairingCorrupt("pairing store JSON is not an object")
        if "devices" in raw and not isinstance(raw["devices"], dict):
            raise PairingCorrupt("pairing store devices is not an object")
        devices = raw.get("devices", {})
        if not isinstance(devices, dict):
            raise PairingCorrupt("pairing store devices is not an object")
        parsed: dict[str, dict] = {}
        for key, value in devices.items():
            if not isinstance(value, dict):
                raise PairingCorrupt("pairing store device entry is not an object")
            parsed[str(key)] = value
        return parsed

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"devices": self._devices}
        body = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
        fd, tmp = tempfile.mkstemp(
            prefix=f".{self.path.name}.", suffix=".tmp", dir=str(self.path.parent)
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(body)
            os.chmod(tmp, 0o600)
            self._refuse_stale_publish()
            os.replace(tmp, self.path)
        except Exception:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        self._accepted_text = body
        self._file_sig = self._stat_sig()

    def _refuse_stale_publish(self) -> None:
        """Do not replace bytes this store did not last accept. A missing path may be created."""
        try:
            self.path.lstat()
        except FileNotFoundError:
            return
        except OSError as e:
            raise PairingCorrupt(
                f"refusing to replace an unreadable pairing store ({type(e).__name__})"
            ) from e
        try:
            current = self._read_text()
        except PairingCorrupt as e:
            raise PairingCorrupt(
                "refusing to replace a pairing store that is not the accepted text"
            ) from e
        if current != self._accepted_text:
            raise PairingCorrupt(
                "refusing to replace a pairing store that is not the accepted text"
            )

    def _locked_mutate(self, mutate) -> None:
        """Read-modify-write under a cross-process advisory lock.

        Reload-then-mutate-then-save all happen while holding the flock, so a
        stale snapshot can never clobber devices added or revoked by the CLI
        (or another serve instance) since we last looked.
        """
        lock_path = self.path.with_suffix(self.path.suffix + ".lock")
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        with open(lock_path, "w") as lock_f:
            fcntl.flock(lock_f.fileno(), fcntl.LOCK_EX)
            try:
                self._reload_if_changed()
                if self._file_sig == _FORCE_RELOAD_SIG:
                    raise PairingCorrupt(
                        "refusing to modify a pairing store that changed while reading"
                    )
                if self._corrupt:
                    raise PairingCorrupt(
                        "refusing to modify a corrupt pairing store "
                        f"({self._corrupt})"
                    )
                mutate()
                self._save()
            finally:
                fcntl.flock(lock_f.fileno(), fcntl.LOCK_UN)

    # -- device management ---------------------------------------------------

    def ensure_default_device(self, name: str = "phone") -> None:
        """Create the first pairing when the store file is missing.

        A corrupt path is not a fresh install: log (via load) and return
        without minting a device or calling ``_save``.
        """
        with self._lock:
            self._reload_if_changed()
            if self._corrupt or self._devices:
                return
            self.add_device(name)

    def add_device(self, name: str, token: str | None = None) -> str:
        name = name.strip()
        if not name or any(c in name for c in "/\\\r\n"):
            raise ValueError("invalid device name")
        token = token or secrets.token_urlsafe(24)
        created = datetime.now(timezone.utc).isoformat(timespec="seconds")

        def _mutate() -> None:
            self._devices[name] = {"token": token, "created": created}

        with self._lock:
            self._locked_mutate(_mutate)
        return token

    def remove_device(self, name: str) -> bool:
        found = False

        def _mutate() -> None:
            nonlocal found
            if name in self._devices:
                del self._devices[name]
                found = True

        with self._lock:
            self._locked_mutate(_mutate)
        return found

    def list_devices(self) -> list[dict]:
        with self._lock:
            self._reload_if_changed()
            return [
                {"name": n, "created": d.get("created")}
                for n, d in sorted(self._devices.items())
            ]

    def token_for(self, name: str) -> str | None:
        with self._lock:
            self._reload_if_changed()
            dev = self._devices.get(name)
        return dev.get("token") if dev else None

    def verify(self, presented: str | None) -> str | None:
        """Device name for a presented token, else None. Constant-time per entry."""
        if not presented:
            return None
        with self._lock:
            # Hot path: one stat() per request keeps revocation immediate even
            # though the CLI mutates this file out-of-process.
            self._reload_if_changed()
            devices = dict(self._devices)
        for name, dev in devices.items():
            stored = dev.get("token")
            if stored and secrets.compare_digest(stored, presented):
                return name
        return None

    def __len__(self) -> int:
        with self._lock:
            self._reload_if_changed()
            return len(self._devices)
