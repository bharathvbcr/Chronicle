//! Self-signed TLS material for LAN serving, plus the SPKI pin the phone uses.
//!
//! Parity with `chronicle_pipeline/tls_certs.py`: an EC P-256 cert/key is
//! generated once and persisted under `~/.config/chronicle/tls/`. The connect
//! payload carries `tls_fp` — base64 SHA-256 of the certificate's
//! SubjectPublicKeyInfo — which OkHttp's `CertificatePinner` pins directly
//! (`sha256/<b64>`).
//!
//! The cert is regenerated when the advertised LAN IP changes, because the IP
//! SAN must match the URL the phone dials or the handshake fails even with a
//! correct pin. Regeneration changes the pin, so devices must re-scan.

use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;

use base64::Engine as _;
use serde_json::json;
use sha2::{Digest, Sha256};

use crate::errors::ChronicleError;

/// Matches tls_certs.py CERT_VALIDITY_DAYS (10 years).
const CERT_VALIDITY_YEARS: i32 = 10;

pub struct TlsMaterial {
    pub cert_path: PathBuf,
    pub key_path: PathBuf,
    /// Base64 SHA-256 of SPKI DER — OkHttp `sha256/` pin syntax.
    pub fingerprint_b64: String,
}

pub fn config_home() -> PathBuf {
    match std::env::var("CHRONICLE_CONFIG_HOME") {
        Ok(v) if !v.trim().is_empty() => PathBuf::from(v.trim()),
        _ => {
            let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
            PathBuf::from(home).join(".config").join("chronicle")
        }
    }
}

pub fn tls_dir() -> PathBuf {
    config_home().join("tls")
}

fn hostname() -> Option<String> {
    let mut buf = vec![0u8; 256];
    let rc = unsafe { libc::gethostname(buf.as_mut_ptr() as *mut libc::c_char, buf.len()) };
    if rc != 0 {
        return None;
    }
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    let s = String::from_utf8_lossy(&buf[..end]).trim().to_lowercase();
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

/// SANs the phone may dial: loopback, the mDNS names, and the LAN IP.
fn san_list(lan_ip: Option<&str>) -> Vec<String> {
    let mut sans = vec![
        "localhost".to_string(),
        "chronicle.local".to_string(),
        "127.0.0.1".to_string(),
    ];
    if let Some(h) = hostname() {
        let bare = h.strip_suffix(".local").unwrap_or(&h).to_string();
        if !bare.is_empty() {
            sans.push(bare.clone());
            sans.push(format!("{bare}.local"));
        }
    }
    if let Some(ip) = lan_ip {
        let ip = ip.trim();
        if !ip.is_empty() && ip.parse::<IpAddr>().is_ok() {
            sans.push(ip.to_string());
        }
    }
    sans.sort();
    sans.dedup();
    sans
}

fn spki_fingerprint_b64(key_pem: &str) -> Result<String, ChronicleError> {
    // The cert embeds this same SubjectPublicKeyInfo, so deriving the pin from
    // the key file matches what a client sees on the wire.
    let kp = rcgen::KeyPair::from_pem(key_pem)
        .map_err(|e| ChronicleError::msg(format!("unreadable TLS key: {e}")))?;
    let digest = Sha256::digest(kp.public_key_der());
    Ok(base64::engine::general_purpose::STANDARD.encode(digest))
}

/// 0600 on create AND on rewrite — creation mode alone leaves an externally
/// loosened key file loose.
fn write_private(path: &std::path::Path, data: &[u8]) -> Result<(), ChronicleError> {
    use std::io::Write as _;
    use std::os::unix::fs::OpenOptionsExt as _;
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    f.write_all(data)?;
    drop(f);
    let _ = std::fs::set_permissions(path, std::os::unix::fs::PermissionsExt::from_mode(0o600));
    Ok(())
}

/// Load existing TLS material, or generate it.
pub fn ensure_tls_material(lan_ip: Option<&str>) -> Result<TlsMaterial, ChronicleError> {
    ensure_tls_material_in(&tls_dir(), lan_ip)
}

/// Same, against an explicit directory — the seam tests use, so they never
/// touch the developer's real `~/.config/chronicle/tls`.
pub fn ensure_tls_material_in(
    dir: &std::path::Path,
    lan_ip: Option<&str>,
) -> Result<TlsMaterial, ChronicleError> {
    std::fs::create_dir_all(dir)?;
    let _ = std::fs::set_permissions(dir, std::os::unix::fs::PermissionsExt::from_mode(0o700));

    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");
    let meta_path = dir.join("meta.json");
    let sans = san_list(lan_ip);

    if cert_path.is_file() && key_path.is_file() {
        match reuse_existing(&cert_path, &key_path, &meta_path, &sans) {
            Ok(Some(m)) => return Ok(m),
            Ok(None) => crate::log_line(
                "WARNING",
                "LAN address or host name changed; regenerating the TLS certificate. \
                 Paired devices must re-scan the pairing QR (the pin changes).",
            ),
            Err(e) => crate::log_line(
                "WARNING",
                &format!("Existing TLS material unusable ({e}); regenerating."),
            ),
        }
    }

    let key_pair = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256)
        .map_err(|e| ChronicleError::msg(format!("TLS key generation failed: {e}")))?;
    let mut params = rcgen::CertificateParams::new(sans.clone())
        .map_err(|e| ChronicleError::msg(format!("TLS SAN rejected: {e}")))?;
    params.distinguished_name = {
        let mut dn = rcgen::DistinguishedName::new();
        dn.push(rcgen::DnType::CommonName, "chronicle-serve");
        dn.push(rcgen::DnType::OrganizationName, "Chronicle");
        dn
    };
    let today = chrono::Utc::now().date_naive();
    params.not_before = rcgen::date_time_ymd(
        today.format("%Y").to_string().parse::<i32>().unwrap_or(2026) - 1,
        1,
        1,
    );
    params.not_after = rcgen::date_time_ymd(
        today.format("%Y").to_string().parse::<i32>().unwrap_or(2026) + CERT_VALIDITY_YEARS,
        1,
        1,
    );

    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| ChronicleError::msg(format!("TLS self-sign failed: {e}")))?;
    let key_pem = key_pair.serialize_pem();
    let fingerprint_b64 = spki_fingerprint_b64(&key_pem)?;

    write_private(&key_path, key_pem.as_bytes())?;
    write_private(&cert_path, cert.pem().as_bytes())?;
    let _ = crate::paths::atomic_write_json(
        &meta_path,
        &json!({ "sans": sans, "fingerprint": fingerprint_b64 }),
    );
    crate::log_line("INFO", &format!("Generated self-signed TLS cert at {}", cert_path.display()));

    Ok(TlsMaterial { cert_path, key_path, fingerprint_b64 })
}

/// Some(material) when the on-disk cert still covers the SANs we need.
fn reuse_existing(
    cert_path: &std::path::Path,
    key_path: &std::path::Path,
    meta_path: &std::path::Path,
    want_sans: &[String],
) -> Result<Option<TlsMaterial>, ChronicleError> {
    let key_pem = std::fs::read_to_string(key_path)?;
    let fingerprint_b64 = spki_fingerprint_b64(&key_pem)?;

    // The SAN set is recorded beside the cert rather than parsed back out of
    // it, so no X.509 parser is needed to answer "does this still cover us".
    let covered = std::fs::read_to_string(meta_path)
        .ok()
        .and_then(|raw| serde_json::from_str::<serde_json::Value>(&raw).ok())
        .and_then(|v| {
            let have: Vec<String> = v
                .get("sans")?
                .as_array()?
                .iter()
                .filter_map(|s| s.as_str().map(str::to_string))
                .collect();
            Some(want_sans.iter().all(|s| have.contains(s)))
        })
        .unwrap_or(false);

    if !covered {
        return Ok(None);
    }
    Ok(Some(TlsMaterial {
        cert_path: cert_path.to_path_buf(),
        key_path: key_path.to_path_buf(),
        fingerprint_b64,
    }))
}

/// rustls server config for the generated material.
pub fn server_config(material: &TlsMaterial) -> Result<Arc<rustls::ServerConfig>, ChronicleError> {
    use rustls::pki_types::pem::PemObject as _;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};

    let certs: Vec<CertificateDer<'static>> = CertificateDer::pem_file_iter(&material.cert_path)
        .map_err(|e| ChronicleError::msg(format!("cannot read TLS cert: {e}")))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| ChronicleError::msg(format!("malformed TLS cert: {e}")))?;
    if certs.is_empty() {
        return Err(ChronicleError::msg("TLS cert file contains no certificate"));
    }
    let key = PrivateKeyDer::from_pem_file(&material.key_path)
        .map_err(|e| ChronicleError::msg(format!("cannot read TLS key: {e}")))?;

    let cfg = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|e| ChronicleError::msg(format!("TLS config rejected cert/key: {e}")))?;
    Ok(Arc::new(cfg))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt as _;

    #[test]
    fn generates_reusable_material_with_a_stable_pin() {
        let d = tempfile::tempdir().unwrap();
        let a = ensure_tls_material_in(d.path(), Some("192.168.1.50")).unwrap();
        assert!(a.cert_path.is_file() && a.key_path.is_file());
        assert!(!a.fingerprint_b64.is_empty());
        // base64 of a SHA-256 digest is always 44 chars with padding.
        assert_eq!(a.fingerprint_b64.len(), 44, "pin is not a base64 SHA-256");

        // Second call with the same LAN IP must reuse, not rotate the pin —
        // rotating would silently break every paired phone.
        let b = ensure_tls_material_in(d.path(), Some("192.168.1.50")).unwrap();
        assert_eq!(a.fingerprint_b64, b.fingerprint_b64, "pin rotated on reuse");
    }

    #[test]
    fn regenerates_when_the_lan_ip_changes() {
        let d = tempfile::tempdir().unwrap();
        let a = ensure_tls_material_in(d.path(), Some("192.168.1.50")).unwrap();
        let b = ensure_tls_material_in(d.path(), Some("10.0.0.7")).unwrap();
        assert_ne!(
            a.fingerprint_b64, b.fingerprint_b64,
            "cert must be reissued so the new IP is in the SANs"
        );
    }

    #[test]
    fn private_key_is_not_world_readable() {
        let d = tempfile::tempdir().unwrap();
        let m = ensure_tls_material_in(d.path(), None).unwrap();
        let mode = std::fs::metadata(&m.key_path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "TLS private key mode is {mode:o}, want 600");
        let dmode = std::fs::metadata(d.path()).unwrap().permissions().mode() & 0o777;
        assert_eq!(dmode, 0o700, "TLS dir mode is {dmode:o}, want 700");
    }

    /// A loosened key file is re-tightened rather than left as found.
    #[test]
    fn rewrite_retightens_a_loosened_key() {
        let d = tempfile::tempdir().unwrap();
        let m = ensure_tls_material_in(d.path(), Some("192.168.1.50")).unwrap();
        std::fs::set_permissions(&m.key_path, std::fs::Permissions::from_mode(0o644)).unwrap();
        // Force a regeneration by changing the LAN IP.
        let m2 = ensure_tls_material_in(d.path(), Some("10.0.0.7")).unwrap();
        let mode = std::fs::metadata(&m2.key_path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn rustls_accepts_the_generated_pair() {
        let d = tempfile::tempdir().unwrap();
        let m = ensure_tls_material_in(d.path(), Some("192.168.1.50")).unwrap();
        // with_single_cert verifies the key actually matches the certificate.
        server_config(&m).expect("rustls rejected the generated cert/key");
    }

    #[test]
    fn sans_cover_loopback_and_the_lan_ip() {
        let sans = san_list(Some("192.168.1.50"));
        for want in ["localhost", "127.0.0.1", "192.168.1.50", "chronicle.local"] {
            assert!(sans.contains(&want.to_string()), "missing SAN {want}: {sans:?}");
        }
    }
}

/// End-to-end: the generated material really terminates TLS, and a client that
/// pins the advertised certificate succeeds while a wrong pin is rejected.
/// This is the regression guard for "LAN served in cleartext".
#[cfg(test)]
mod handshake_tests {
    use super::*;
    use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};

    /// Mirrors what the phone does: trust exactly one certificate, ignore the
    /// CA chain entirely.
    #[derive(Debug)]
    struct PinnedVerifier {
        expected: CertificateDer<'static>,
    }

    impl rustls::client::danger::ServerCertVerifier for PinnedVerifier {
        fn verify_server_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            _intermediates: &[CertificateDer<'_>],
            _server_name: &ServerName<'_>,
            _ocsp: &[u8],
            _now: UnixTime,
        ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
            if end_entity.as_ref() == self.expected.as_ref() {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            } else {
                Err(rustls::Error::General("certificate pin mismatch".into()))
            }
        }
        fn verify_tls12_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &rustls::DigitallySignedStruct,
        ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls12_signature(
                message,
                cert,
                dss,
                &rustls::crypto::ring::default_provider().signature_verification_algorithms,
            )
        }
        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &rustls::DigitallySignedStruct,
        ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls13_signature(
                message,
                cert,
                dss,
                &rustls::crypto::ring::default_provider().signature_verification_algorithms,
            )
        }
        fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
            rustls::crypto::ring::default_provider()
                .signature_verification_algorithms
                .supported_schemes()
        }
    }

    fn cert_der(material: &TlsMaterial) -> CertificateDer<'static> {
        use rustls::pki_types::pem::PemObject as _;
        CertificateDer::pem_file_iter(&material.cert_path)
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
    }

    async fn echo_once(listener: tokio::net::TcpListener, cfg: Arc<rustls::ServerConfig>) {
        let acceptor = tokio_rustls::TlsAcceptor::from(cfg);
        if let Ok((stream, _)) = listener.accept().await {
            if let Ok(mut tls) = acceptor.accept(stream).await {
                let mut buf = [0u8; 1024];
                let _ = tls.read(&mut buf).await;
                let _ = tls
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nok\r\n")
                    .await;
                let _ = tls.flush().await;
            }
        }
    }

    fn client(verifier: Arc<PinnedVerifier>) -> tokio_rustls::TlsConnector {
        let cfg = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        tokio_rustls::TlsConnector::from(Arc::new(cfg))
    }

    #[tokio::test]
    async fn correct_pin_completes_the_handshake_and_serves() {
        let d = tempfile::tempdir().unwrap();
        let m = ensure_tls_material_in(d.path(), Some("127.0.0.1")).unwrap();
        let cfg = server_config(&m).unwrap();

        let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(echo_once(listener, cfg));

        let connector = client(Arc::new(PinnedVerifier { expected: cert_der(&m) }));
        let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let name = ServerName::try_from("localhost").unwrap();
        let mut tls = connector.connect(name, tcp).await.expect("pinned handshake failed");

        tls.write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
        let mut buf = vec![0u8; 128];
        let n = tls.read(&mut buf).await.unwrap();
        let body = String::from_utf8_lossy(&buf[..n]);
        assert!(body.contains("200 OK"), "no HTTP response over TLS: {body}");
    }

    #[tokio::test]
    async fn a_different_certificate_is_rejected() {
        let served = tempfile::tempdir().unwrap();
        let other = tempfile::tempdir().unwrap();
        let m = ensure_tls_material_in(served.path(), Some("127.0.0.1")).unwrap();
        let impostor = ensure_tls_material_in(other.path(), Some("127.0.0.1")).unwrap();
        assert_ne!(m.fingerprint_b64, impostor.fingerprint_b64);

        let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(echo_once(listener, server_config(&m).unwrap()));

        // Client pins the impostor's cert; the real server must not be trusted.
        let connector = client(Arc::new(PinnedVerifier { expected: cert_der(&impostor) }));
        let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let name = ServerName::try_from("localhost").unwrap();
        assert!(
            connector.connect(name, tcp).await.is_err(),
            "a mismatched certificate pin was accepted"
        );
    }
}
