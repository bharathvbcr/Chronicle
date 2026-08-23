"""Self-signed TLS material for LAN serve + SPKI pinning fingerprint.

Generates (once) and persists an EC P-256 cert/key under
``~/.config/chronicle/tls/``. The QR/connect payload carries
``tls_fp`` — the base64 SHA-256 of the certificate's SubjectPublicKeyInfo —
which OkHttp ``CertificatePinner`` pins directly (``sha256/<b64>``).

The cert is regenerated when the advertised LAN IP changes (IP SANs must
match the URL host Android dials).
"""

from __future__ import annotations

import base64
import datetime
import hashlib
import ipaddress
import json
import logging
import os
import socket
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

log = logging.getLogger("chronicle.tls")

CERT_VALIDITY_DAYS = 3650


class TlsError(Exception):
    pass


@dataclass(frozen=True)
class TlsMaterial:
    cert_path: Path
    key_path: Path
    """Base64 SHA-256 of SPKI DER — matches OkHttp ``sha256/`` pin syntax."""
    fingerprint_b64: str


def config_home() -> Path:
    env = os.environ.get("CHRONICLE_CONFIG_HOME")
    base = Path(env).expanduser() if env else Path.home() / ".config" / "chronicle"
    return base


def tls_dir() -> Path:
    return config_home() / "tls"


def spki_fingerprint_b64(cert_path: Path) -> str:
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")


def _cert_covers_ip(cert_path: Path, lan_ip: str | None) -> bool:
    try:
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        sans = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName)
        ips = {str(v) for v in sans.value.get_values_for_type(x509.IPAddress)}
        if not lan_ip:
            return True
        return str(ipaddress.ip_address(lan_ip)) in ips
    except Exception:  # noqa: BLE001 — unreadable/expired cert forces regen
        return False


def ensure_tls_material(lan_ip: str | None = None) -> TlsMaterial:
    """Load or create the self-signed serve certificate."""
    out = tls_dir()
    # Private key lives here — 0700 regardless of umask.
    out.mkdir(parents=True, mode=0o700, exist_ok=True)
    try:
        os.chmod(out, 0o700)
    except OSError:
        pass
    cert_path = out / "cert.pem"
    key_path = out / "key.pem"

    if cert_path.is_file() and key_path.is_file():
        try:
            fp = spki_fingerprint_b64(cert_path)
            if _cert_covers_ip(cert_path, lan_ip):
                return TlsMaterial(cert_path, key_path, fp)
            log.info("LAN IP changed (%s); regenerating TLS cert", lan_ip or "-")
        except Exception as e:  # noqa: BLE001
            log.warning("Existing TLS material unusable (%s); regenerating", e)

    now = datetime.datetime.now(datetime.timezone.utc)
    key = ec.generate_private_key(ec.SECP256R1())
    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "chronicle-serve"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Chronicle"),
        ]
    )
    hostname = socket.gethostname().strip().lower()
    san_entries: list[x509.GeneralName] = [
        x509.DNSName("localhost"),
        x509.DNSName("chronicle.local"),
        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
    ]
    if hostname:
        # Clients may dial the mDNS-advertised host name; both forms must
        # verify or OkHttp rejects the handshake despite a valid pin.
        san_entries.append(x509.DNSName(hostname))
        san_entries.append(x509.DNSName(f"{hostname}.local"))
    if lan_ip:
        san_entries.append(x509.IPAddress(ipaddress.ip_address(lan_ip)))
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=5))
        .not_valid_after(now + datetime.timedelta(days=CERT_VALIDITY_DAYS))
        .add_extension(
            x509.SubjectAlternativeName(san_entries),
            critical=False,
        )
        .add_extension(
            x509.BasicConstraints(ca=False, path_length=None),
            critical=True,
        )
        .sign(key, hashes_sha256(), None)
    )

    key_bytes = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    cert_bytes = cert.public_bytes(serialization.Encoding.PEM)
    _write_private(key_path, key_bytes)
    _write_private(cert_path, cert_bytes)
    log.info("Generated self-signed TLS cert at %s", cert_path)
    return TlsMaterial(cert_path, key_path, spki_fingerprint_b64(cert_path))


def _write_private(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    # 0o600 only applies at creation — re-assert so an externally loosened
    # key file is tightened on every rewrite.
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def hashes_sha256():
    from cryptography.hazmat.primitives import hashes

    return hashes.SHA256()


def read_meta() -> dict | None:
    meta = tls_dir() / "meta.json"
    if not meta.is_file():
        return None
    try:
        return json.loads(meta.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return None
