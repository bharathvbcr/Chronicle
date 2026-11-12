//! Chronicle local vault server — Rust port of `chronicle_pipeline`.
//!
//! Byte-compatible REST surface with the Python incumbent (CONTRACT.md v1.6):
//! same routes, JSON shapes, error bodies, fence formats, and file layouts so
//! the Android app, React SPA, and parity harness cannot tell them apart.





















































pub mod api;
pub mod brain;
pub mod captions;
pub mod backup;
pub mod doctor;
pub mod export;
pub mod rollup;
pub mod topics;
pub mod config;
pub mod curation;
pub mod entries;
pub mod errors;
pub mod formal_security;
pub mod frontmatter;
pub mod grok;
pub mod index_store;
pub mod journal;
pub mod kb;
pub mod kb_enrich;
pub mod link_repair;
pub mod lock;
pub mod markdown_index;
pub mod media;
pub mod models;
pub mod notes;
pub mod ollama;
pub mod path_map;
pub mod paths;
pub mod pipeline;
pub mod prompts;
pub mod provider;
pub mod rag;
pub mod serve;
pub mod timeutil;
pub mod tls;
pub mod transcribe;
pub mod upcoming;
pub mod vertex;

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Instant;

use serde::Serialize;

/// Runtime LLM connection settings mirrored from config.json (python's
/// module globals OLLAMA_BASE / DEFAULT_NUM_CTX / GLOBAL_TEMPERATURE).
#[derive(Debug, Clone)]
pub struct LlmRuntime {
    pub base_url: String,
    pub num_ctx: i64,
    pub global_temperature: Option<f64>,
}

impl Default for LlmRuntime {
    fn default() -> Self {
        Self {
            base_url: "http://localhost:11434".into(),
            num_ctx: 32768,
            global_temperature: None,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct ConnectInfo {
    pub host: String,
    pub port: u16,
    pub bind_host: String,
    pub lan_ip: Option<String>,
    pub base: String,
    pub kb_proxied: bool,
    #[serde(rename = "v")]
    pub version: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    pub auth_required: bool,
    /// True when the listener terminates TLS (LAN mode).
    #[serde(default)]
    pub tls: bool,
    /// base64 SHA-256 of the cert SPKI — the phone pins this.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tls_fp: Option<String>,
}

/// Sliding-window cloud rate limiter state (20 req / 60 s across ask/recall/resume).
#[derive(Default)]
pub struct RateWindow {
    stamps: Vec<Instant>,
}

impl RateWindow {
    pub fn check(&mut self, max: usize, window_secs: u64) -> bool {
        let now = Instant::now();
        let cutoff = now - std::time::Duration::from_secs(window_secs);
        self.stamps.retain(|t| *t >= cutoff);
        if self.stamps.len() >= max {
            false
        } else {
            self.stamps.push(now);
            true
        }
    }
}

pub struct App {
    pub root: PathBuf,
    pub connect_info: ConnectInfo,
    pub token: Option<String>,
    pub auth_required: bool,
    pub session_authority: Mutex<Option<crate::formal_security::PinBoundSessionAuthority>>,
    active_lan_credentials: std::sync::RwLock<(Option<String>, Option<String>)>,
    pub llm: std::sync::RwLock<LlmRuntime>,
    pub rate: Mutex<RateWindow>,
    /// TTL cache for the /health provider probe (Ollama reachable + provider ok).
    pub health_cache: Mutex<Option<(Instant, ProviderProbe)>>,
}

#[derive(Debug, Clone)]
pub struct ProviderProbe {
    pub ollama_ok: bool,
    pub provider: String,
    pub provider_ok: bool,
    pub provider_error: Option<String>,
}

pub const HEALTH_CACHE_TTL: std::time::Duration = std::time::Duration::from_secs(5);

impl App {
    pub fn new(root: PathBuf, connect_info: ConnectInfo) -> Self {
        Self::new_with_session_authority(root, connect_info, None)
    }

    pub fn new_with_session_authority(
        root: PathBuf,
        connect_info: ConnectInfo,
        authority: Option<crate::formal_security::PinBoundSessionAuthority>,
    ) -> Self {
        let token = connect_info.token.clone();
        let tls_fp = connect_info.tls_fp.clone();
        let auth_required = connect_info.auth_required;
        Self {
            root,
            connect_info,
            token: token.clone(),
            auth_required,
            session_authority: Mutex::new(authority),
            active_lan_credentials: std::sync::RwLock::new((token, tls_fp)),
            llm: std::sync::RwLock::new(LlmRuntime::default()),
            rate: Mutex::new(RateWindow::default()),
            health_cache: Mutex::new(None),
        }
    }

    pub fn has_lan_credentials(&self) -> bool {
        if self.session_authority.lock().unwrap().is_some() {
            return true;
        }
        self.active_lan_credentials.read().unwrap().0.is_some()
    }

    pub fn current_pairing_token(&self) -> Option<String> {
        self.active_lan_credentials.read().unwrap().0.clone()
    }

    pub fn current_tls_fp(&self) -> Option<String> {
        self.active_lan_credentials.read().unwrap().1.clone()
    }

    /// Mints an independent per-device token bound to the current `(epoch, tls_fp)`
    /// when `PinBoundSessionAuthority` is active; otherwise returns the static token.
    pub fn issue_lan_device_token(&self) -> Option<String> {
        if let Some(ref auth) = *self.session_authority.lock().unwrap() {
            return Some(auth.issue_token());
        }
        self.current_pairing_token()
    }

    /// Rotates the active LAN TLS SPKI fingerprint (`new_tls_fp`), invalidating all
    /// tokens from prior epochs (including under `fp1 -> fp2 -> fp1` ABA rollback)
    /// and minting a fresh pairing token for the new epoch.
    pub fn rotate_lan_tls_pin(
        &self,
        new_tls_fp: &str,
    ) -> Result<(u64, String), crate::formal_security::SecurityViolation> {
        let mut guard = self.session_authority.lock().unwrap();
        let auth = guard
            .as_mut()
            .ok_or(crate::formal_security::SecurityViolation::EmptyTlsFingerprint)?;
        let epoch = auth.rotate_tls_pin(new_tls_fp)?;
        let fresh_token = auth.issue_token();
        let fp = auth.active_tls_fp().to_string();
        let mut creds = self.active_lan_credentials.write().unwrap();
        *creds = (Some(fresh_token.clone()), Some(fp));
        Ok((epoch, fresh_token))
    }

    /// Revokes a single paired device's token without rotating the server TLS cert.
    pub fn revoke_lan_token(
        &self,
        revoked_token: &str,
    ) -> Result<(), crate::formal_security::SecurityViolation> {
        let mut guard = self.session_authority.lock().unwrap();
        let auth = guard
            .as_mut()
            .ok_or(crate::formal_security::SecurityViolation::MalformedToken)?;
        auth.revoke_token(revoked_token)?;
        let mut creds = self.active_lan_credentials.write().unwrap();
        if creds.0.as_deref() == Some(revoked_token) {
            creds.0 = Some(auth.issue_token());
        }
        Ok(())
    }

    pub fn apply_ollama_settings(&self, base_url: &str, num_ctx: i64, temperature: Option<f64>) {
        let mut rt = self.llm.write().unwrap();
        match crate::provider::validate_ollama_base_url(base_url) {
            Err(_) => warn_rejected_ollama_base(),
            Ok(cleaned) => {
                if !cleaned.is_empty() {
                    rt.base_url = cleaned;
                }
            }
        }
        rt.num_ctx = if num_ctx > 0 { num_ctx } else { 32768 };
        rt.global_temperature = temperature;
    }

    pub fn llm_runtime(&self) -> LlmRuntime {
        self.llm.read().unwrap().clone()
    }
}

pub fn log_line(level: &str, msg: &str) {
    eprintln!("{level} chronicle: {msg}");
}

/// One literal, no format args. The rejected URL and the previous base can both carry userinfo.
macro_rules! ollama_reject_warning {
    ($text:literal) => {
        #[cfg(test)]
        fn rejected_ollama_base_warning() -> &'static str {
            $text
        }
        fn warn_rejected_ollama_base() {
            eprintln!($text);
        }
    };
}

ollama_reject_warning!(
    "WARNING chronicle: Rejecting non-private ollama.base_url; keeping the previous base"
);

#[cfg(test)]
mod rejected_base_url_log_tests {
    use super::*;

    const REJECTED: &str = "https://user:secret-token-zz@evil.example";
    const PREVIOUS: &str = "http://127.0.0.1:11434";

    fn test_app() -> App {
        App::new(
            std::path::PathBuf::from("/tmp"),
            ConnectInfo {
                host: "127.0.0.1".into(),
                port: 8741,
                bind_host: "127.0.0.1".into(),
                lan_ip: None,
                base: "http://127.0.0.1:8741".into(),
                kb_proxied: false,
                version: 1,
                token: None,
                auth_required: false,
                tls: false,
                tls_fp: None,
            },
        )
    }

    #[test]
    fn rejected_ollama_base_url_is_not_logged() {
        assert!(
            crate::provider::validate_ollama_base_url(REJECTED).is_err(),
            "main validator accepted the probe URL; refusing to weaken it"
        );
        let app = test_app();
        {
            let mut rt = app.llm.write().unwrap();
            rt.base_url = PREVIOUS.into();
        }
        app.apply_ollama_settings(REJECTED, 0, None);
        assert_eq!(app.llm_runtime().base_url, PREVIOUS);

        let warning = rejected_ollama_base_warning();
        assert_eq!(
            warning,
            "WARNING chronicle: Rejecting non-private ollama.base_url; keeping the previous base"
        );
        for forbidden in ["secret-token-zz", "@", "user", "evil.example", "http"] {
            assert!(!warning.contains(forbidden), "{forbidden} in warning {warning}");
        }
    }
}
