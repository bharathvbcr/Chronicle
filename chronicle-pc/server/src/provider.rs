//! Provider seam: URL allowlists, secrets, consent, context caps, factory.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::Value;

use crate::config::{ChronicleConfig, LlmOptions};
use crate::errors::ChronicleError;

// ---------------------------------------------------------------------------
// Message / option shapes shared by all providers
// ---------------------------------------------------------------------------

pub type Message = Value;

#[derive(Debug, Clone)]
pub struct ChatOpts {
    pub model: Option<String>,
    pub temperature: f64,
    pub top_p: f64,
    pub top_k: i64,
    pub format_json: bool,
    pub num_predict: Option<i64>,
    pub num_ctx: i64,
    pub timeout: Duration,
}

impl Default for ChatOpts {
    fn default() -> Self {
        Self {
            model: None,
            temperature: 0.6,
            top_p: 0.95,
            top_k: 20,
            format_json: false,
            num_predict: None,
            num_ctx: 32768,
            timeout: Duration::from_secs(300),
        }
    }
}

pub trait ChatProvider: Send + Sync {
    fn name(&self) -> &'static str;
    fn reachable(&self, timeout: Duration) -> bool;
    fn chat(&self, messages: Vec<Message>, opts: &ChatOpts) -> Result<String, ChronicleError>;
    fn describe_image(&self, image_path: &Path, prompt: &str) -> Result<String, ChronicleError>;
}

/// try_chat degrade-to-None wrapper (protocol.py contract).
pub fn try_chat(
    provider: Option<&dyn ChatProvider>,
    messages: Vec<Message>,
    opts: &ChatOpts,
) -> Option<String> {
    let p = provider?;
    match p.chat(messages, opts) {
        Ok(s) => Some(s),
        Err(e) => {
            log_line("WARNING", &format!("chat skipped: {e}"));
            None
        }
    }
}

pub fn log_line(level: &str, msg: &str) {
    eprintln!("{level} chronicle: {msg}");
}

// ---------------------------------------------------------------------------
// URL allowlists (url_allowlist.py)
// ---------------------------------------------------------------------------

fn normalize_url(raw: &str) -> String {
    let mut s = raw.trim().to_string();
    if !s.contains("://") {
        s = format!("https://{s}");
    }
    s.trim_end_matches('/').to_string()
}

fn host_qualifies(host: &str) -> bool {
    if host == "localhost" {
        return true;
    }
    fn ip_ok(ip: std::net::IpAddr) -> bool {
        match ip {
            std::net::IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
            std::net::IpAddr::V6(v6) => v6.is_loopback(),
        }
    }
    // Literal IP?
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return ip_ok(ip);
    }
    // Hostname: resolve; every address must qualify.
    match std::net::ToSocketAddrs::to_socket_addrs(&(host, 80u16)) {
        Ok(mut addrs) => addrs.all(|a| ip_ok(a.ip())),
        Err(_) => false,
    }
}

pub fn validate_ollama_base_url(raw: &str) -> Result<String, ChronicleError> {
    let cleaned = normalize_url(raw);
    if cleaned.is_empty() {
        return Err(ChronicleError::msg("ollama base_url must be a non-empty URL"));
    }
    let (scheme, rest) = cleaned.split_once("://").unwrap_or(("https", cleaned.as_str()));
    if scheme != "http" && scheme != "https" {
        return Err(ChronicleError::msg(
            "ollama base_url must be a private or loopback address (localhost / 127.0.0.1 / RFC1918); public hosts are blocked",
        ));
    }
    let host = rest.split('/').next().unwrap_or_default();
    let host = host.split(':').next().unwrap_or(host);
    if !host_qualifies(host) {
        return Err(ChronicleError::msg(
            "ollama base_url must be a private or loopback address (localhost / 127.0.0.1 / RFC1918); public hosts are blocked",
        ));
    }
    Ok(cleaned)
}

pub fn validate_grok_base_url(raw: &str) -> Result<String, ChronicleError> {
    let cleaned = normalize_url(raw);
    let (scheme, rest) = cleaned.split_once("://").ok_or_else(|| {
        ChronicleError::msg("grok_base_url must be https://api.x.ai (exact host); other hosts are blocked")
    })?;
    let host = rest.split('/').next().unwrap_or_default();
    if scheme != "https" || host != "api.x.ai" {
        return Err(ChronicleError::msg(
            "grok_base_url must be https://api.x.ai (exact host); other hosts are blocked",
        ));
    }
    Ok(cleaned)
}

// ---------------------------------------------------------------------------
// Secrets (~/.config/chronicle/secrets.json, env wins)
// ---------------------------------------------------------------------------

pub fn secrets_path() -> PathBuf {
    if let Ok(p) = std::env::var("CHRONICLE_SECRETS") {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("/"));
    home.join(".config/chronicle/secrets.json")
}

pub fn load_secrets() -> serde_json::Map<String, Value> {
    let path = secrets_path();
    let Ok(raw) = std::fs::read_to_string(&path) else {
        return Default::default();
    };
    match serde_json::from_str::<Value>(&raw) {
        Ok(Value::Object(m)) => m,
        _ => {
            log_line("WARNING", &format!("secrets file unreadable: {}", path.display()));
            Default::default()
        }
    }
}

pub fn resolve_grok_key(secrets: &serde_json::Map<String, Value>) -> Option<String> {
    if let Ok(env) = std::env::var("GROK_API_KEY") {
        if !env.trim().is_empty() {
            return Some(env.trim().to_string());
        }
    }
    for key in ["grok_api_key", "GROK_API_KEY"] {
        if let Some(s) = secrets.get(key).and_then(Value::as_str) {
            let t = s.trim();
            if !t.is_empty() {
                return Some(t.to_string());
            }
        }
    }
    None
}

pub fn resolve_vertex_project(cfg: &LlmOptions, secrets: &serde_json::Map<String, Value>) -> Option<String> {
    for env in ["GOOGLE_CLOUD_PROJECT", "GCLOUD_PROJECT", "VERTEX_PROJECT"] {
        if let Ok(v) = std::env::var(env) {
            if !v.trim().is_empty() {
                return Some(v.trim().to_string());
            }
        }
    }
    if let Some(p) = cfg.vertex.project.clone().filter(|p| !p.is_empty()) {
        return Some(p);
    }
    for key in ["vertex_project", "google_cloud_project", "project"] {
        if let Some(s) = secrets.get(key).and_then(Value::as_str) {
            let t = s.trim();
            if !t.is_empty() {
                return Some(t.to_string());
            }
        }
    }
    None
}

/// Consent: secrets file presence-wins over config flag.
pub fn resolve_cloud_consent(cfg_consent: bool, secrets: &serde_json::Map<String, Value>) -> bool {
    if let Some(v) = secrets.get("cloud_consent") {
        return v.as_bool().unwrap_or(false);
    }
    cfg_consent
}

pub fn resolve_vision_cloud_consent(cfg: &LlmOptions, secrets: &serde_json::Map<String, Value>) -> bool {
    if let Some(v) = secrets.get("vision_cloud_consent") {
        return v.as_bool().unwrap_or(false);
    }
    cfg.vision_cloud_consent
}

// ---------------------------------------------------------------------------
// Context caps (llm/context.py)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
pub struct ContextLimits {
    pub hit_text_limit: usize,
    pub recall_top_k: usize,
    pub ask_top_k: usize,
    pub resume_top_k: usize,
    pub rollup_max_chars: usize,
    pub rollup_max_notes: usize,
    pub num_ctx_ask: i64,
    pub num_ctx_resume: i64,
    pub num_ctx_recall: i64,
    pub num_ctx_enrich: i64,
    pub num_ctx_brain: i64,
}

pub const LOCAL_LIMITS: ContextLimits = ContextLimits {
    hit_text_limit: 16000,
    recall_top_k: 12,
    ask_top_k: 10,
    resume_top_k: 14,
    rollup_max_chars: 12000,
    rollup_max_notes: 4,
    num_ctx_ask: 65536,
    num_ctx_resume: 65536,
    num_ctx_recall: 131072,
    num_ctx_enrich: 16384,
    num_ctx_brain: 32768,
};

pub const CLOUD_LIMITS: ContextLimits = ContextLimits {
    hit_text_limit: 2000,
    recall_top_k: 6,
    ask_top_k: 5,
    resume_top_k: 6,
    rollup_max_chars: 3000,
    rollup_max_notes: 2,
    num_ctx_ask: 8192,
    num_ctx_resume: 8192,
    num_ctx_recall: 16384,
    num_ctx_enrich: 8192,
    num_ctx_brain: 8192,
};

pub fn is_cloud_provider(name: &str) -> bool {
    matches!(name.trim().to_lowercase().as_str(), "grok" | "vertex")
}

pub fn limits_for(name: &str) -> ContextLimits {
    if is_cloud_provider(name) { CLOUD_LIMITS } else { LOCAL_LIMITS }
}

pub fn provider_name(cfg: &ChronicleConfig) -> String {
    let raw = cfg.llm.provider.trim();
    if raw.is_empty() { "ollama".into() } else { raw.to_lowercase() }
}

pub const RATE_LIMIT_MSG: &str =
    "Cloud LLM rate limit exceeded (20 requests / 60s). Retry shortly or switch llm.provider to ollama.";

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

pub fn build_provider(cfg: &ChronicleConfig) -> Result<(String, Box<dyn ChatProvider>), ChronicleError> {
    let pname = provider_name(cfg);
    let secrets = load_secrets();
    match pname.as_str() {
        "ollama" => {
            let rt = crate::ollama::runtime_from_config(cfg);
            Ok((pname, Box::new(crate::ollama::OllamaProvider { rt })))
        }
        "grok" => {
            if !resolve_cloud_consent(cfg.llm.cloud_consent, &secrets) {
                return Err(ChronicleError::Llm(format!(
                    "Cloud LLM provider 'grok' requires opt-in consent. Set llm.cloud_consent=true in config.json, or {{\"cloud_consent\": true}} in ~/.config/chronicle/secrets.json (journal/KB text will leave this machine)."
                )));
            }
            let key = resolve_grok_key(&secrets).ok_or_else(|| {
                ChronicleError::Llm(
                    "Grok selected but no API key. Set GROK_API_KEY or grok_api_key in ~/.config/chronicle/secrets.json".into(),
                )
            })?;
            let base = validate_grok_base_url(&cfg.llm.grok.base_url).map_err(|_| {
                ChronicleError::Llm(format!(
                    "Grok base_url must be https://api.x.ai only; refused '{}'",
                    cfg.llm.grok.base_url
                ))
            })?;
            Ok((pname, Box::new(crate::grok::GrokProvider { base_url: base, api_key: key, cfg: cfg.clone() })))
        }
        "vertex" => Err(ChronicleError::Llm(
            "Vertex provider is not available in the native Rust server yet; switch llm.provider to 'ollama' or 'grok'.".into(),
        )),
        other => Err(ChronicleError::Llm(format!("unknown provider: {other}"))),
    }
}

pub fn runtime_from_config_pub(cfg: &ChronicleConfig) -> crate::LlmRuntime {
    crate::ollama::runtime_from_config(cfg)
}

/// try_describe_image degrade-to-None wrapper.
pub fn try_chat_image(provider: &dyn ChatProvider, image_path: &Path) -> Option<String> {
    match provider.describe_image(image_path, "Describe this journal photo in 1-2 factual sentences.") {
        Ok(s) => Some(s),
        Err(e) => {
            log_line("WARNING", &format!("vision skipped: {e}"));
            None
        }
    }
}
