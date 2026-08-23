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
        let token = connect_info.token.clone();
        let auth_required = connect_info.auth_required;
        Self {
            root,
            connect_info,
            token,
            auth_required,
            llm: std::sync::RwLock::new(LlmRuntime::default()),
            rate: Mutex::new(RateWindow::default()),
            health_cache: Mutex::new(None),
        }
    }

    pub fn apply_ollama_settings(&self, base_url: &str, num_ctx: i64, temperature: Option<f64>) {
        let mut rt = self.llm.write().unwrap();
        match crate::provider::validate_ollama_base_url(base_url) {
            Err(_) => {
                eprintln!(
                    "WARNING chronicle: Rejecting non-private ollama.base_url {base_url:?}; keeping {}",
                    rt.base_url
                );
            }
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
