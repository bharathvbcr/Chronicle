"""Optional Bonjour/NSD advertisement of ``_chronicle._tcp``.

Lets the Android app discover the Mac without typing IPs (Settings →
Discover). Depends on the optional ``zeroconf`` package
(``pip install -e ".[mdns]"``); when absent this degrades to a no-op and
serve logs one line — QR pairing remains fully functional.
"""

from __future__ import annotations

import logging
import socket
from typing import Any

log = logging.getLogger("chronicle.mdns")

SERVICE_TYPE = "_chronicle._tcp.local."


class MdnsAdvertiser:
    """Register/unregister the serve instance; safe no-op without zeroconf."""

    def __init__(self) -> None:
        self._zc: Any = None
        self._info: Any = None

    @property
    def available(self) -> bool:
        try:
            import zeroconf  # noqa: F401
        except ImportError:
            return False
        return True

    def start(
        self,
        *,
        name: str,
        port: int,
        tls_fp: str | None,
        lan_ip: str | None,
        tls: bool = True,
    ) -> bool:
        if not self.available:
            log.info("zeroconf not installed; skipping mDNS advertise (pip install '.[mdns]')")
            return False
        try:
            from zeroconf import ServiceInfo, Zeroconf

            host = lan_ip or socket.gethostbyname(socket.gethostname())
            props: dict[str, str | bytes] = {"v": "2", "tls": "1" if tls else "0"}
            if tls_fp:
                props["fp"] = tls_fp
            # Server (A-record host) must be a TLS SAN: ensure_tls_material
            # includes <hostname> and <hostname>.local.
            mdns_host = f"{socket.gethostname().strip().lower() or 'chronicle'}.local."
            self._info = ServiceInfo(
                SERVICE_TYPE,
                f"{name}.{SERVICE_TYPE}",
                addresses=[socket.inet_aton(host)],
                port=port,
                properties=props,
                server=mdns_host,
            )
            self._zc = Zeroconf()
            self._zc.register_service(self._info, ttl=60)
            log.info("Advertising mDNS %s on %s:%d", name, host, port)
            return True
        except Exception as e:  # noqa: BLE001 — mDNS is best-effort
            log.warning("mDNS advertise failed (%s); continuing without", e)
            self.stop()
            return False

    def stop(self) -> None:
        try:
            if self._zc and self._info:
                self._zc.unregister_service(self._info)
        except Exception:  # noqa: BLE001
            pass
        try:
            if self._zc:
                self._zc.close()
        except Exception:  # noqa: BLE001
            pass
        self._zc = None
        self._info = None
