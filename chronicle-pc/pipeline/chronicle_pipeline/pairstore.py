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
            self._devices = {}
            self._corrupt = None
            self._file_sig = None
            return
        except OSError as e:
            self._mark_corrupt(f"pairing store unreadable ({type(e).__name__})")
            return
        try:
            self._devices = self._read_devices()
        except FileNotFoundError:
            # Vanished between the existence check and the read.
            self._devices = {}
            self._corrupt = None
            self._file_sig = None
            return
        except PairingCorrupt as e:
            self._mark_corrupt(str(e))
            return
        self._corrupt = None
        self._file_sig = self._stat_sig()

    def _mark_corrupt(self, reason: str) -> None:
        self._devices = {}
        self._corrupt = reason
        self._file_sig = self._stat_sig()
        _LOG.warning("corrupt pairing store; leaving it unchanged (%s)", reason)

    def _read_devices(self) -> dict[str, dict]:
        """Return devices from a valid pairing file, or raise PairingCorrupt."""
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
            text = self.path.read_text(encoding="utf-8")
        except UnicodeDecodeError as e:
            raise PairingCorrupt("pairing store is not valid UTF-8") from e
        except OSError as e:
            raise PairingCorrupt(f"pairing store unreadable ({type(e).__name__})") from e
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
        return {str(k): v for k, v in devices.items() if isinstance(v, dict)}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"devices": self._devices}
        fd, tmp = tempfile.mkstemp(
            prefix=f".{self.path.name}.", suffix=".tmp", dir=str(self.path.parent)
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(payload, f, indent=2, ensure_ascii=False)
                f.write("\n")
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.path)
        except Exception:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        self._file_sig = self._stat_sig()

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
