"""TLS material: self-signed cert generation + SPKI pinning fingerprint."""

from __future__ import annotations

import base64
import hashlib
import os
import stat

import pytest

from chronicle_pipeline import tls_certs


@pytest.fixture(autouse=True)
def _tls_home(tmp_path, monkeypatch):
    monkeypatch.setenv("CHRONICLE_CONFIG_HOME", str(tmp_path / "conf"))
    return tmp_path / "conf" / "tls"


def test_generates_cert_with_fingerprint(_tls_home) -> None:
    mat = tls_certs.ensure_tls_material(lan_ip="192.168.1.20")
    assert mat.cert_path.is_file() and mat.key_path.is_file()
    assert len(mat.fingerprint_b64) == 44  # sha256 b64 (no padding char issue: has =)
    # Fingerprint matches an independent SPKI hash of the cert file
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    cert = x509.load_pem_x509_certificate(mat.cert_path.read_bytes())
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    expected = base64.b64encode(hashlib.sha256(spki).digest()).decode()
    assert mat.fingerprint_b64 == expected


def test_reuses_existing_cert_when_ip_unchanged(_tls_home) -> None:
    first = tls_certs.ensure_tls_material(lan_ip="192.168.1.20")
    second = tls_certs.ensure_tls_material(lan_ip="192.168.1.20")
    assert first.fingerprint_b64 == second.fingerprint_b64


def test_regenerates_when_lan_ip_changes(_tls_home) -> None:
    first = tls_certs.ensure_tls_material(lan_ip="192.168.1.20")
    second = tls_certs.ensure_tls_material(lan_ip="10.0.0.2")
    assert first.fingerprint_b64 != second.fingerprint_b64


def test_key_permissions_0600(_tls_home) -> None:
    tls_certs.ensure_tls_material(lan_ip=None)
    mode = stat.S_IMODE(os.stat(_tls_home / "key.pem").st_mode)
    assert mode & 0o077 == 0
