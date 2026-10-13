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

fn authority_of(url: &str) -> &str {
    let rest = url.split_once("://").map(|(_, rest)| rest).unwrap_or(url);
    rest.split(['/', '?', '#']).next().unwrap_or("")
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn percent_decode_once(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// `@` in the authority is userinfo, including `%40` and double-encoded `%2540`.
/// reqwest sends that origin; a colon split of the same text does not.
fn authority_has_userinfo(authority: &str) -> bool {
    if authority.contains('@') {
        return true;
    }
    let once = percent_decode_once(authority);
    if once.contains('@') || once.to_ascii_lowercase().contains("%40") {
        return true;
    }
    percent_decode_once(&once).contains('@')
}

/// `Url::host_str` keeps IPv6 brackets (`[::1]`). `host_qualifies` wants `::1`.
fn bare_host(url: &reqwest::Url) -> Option<String> {
    let host = url.host_str()?;
    Some(
        host.strip_prefix('[')
            .and_then(|inner| inner.strip_suffix(']'))
            .unwrap_or(host)
            .to_string(),
    )
}

struct CheckedBase {
    cleaned: String,
    url: reqwest::Url,
    host: String,
}

fn check_base(raw: &str, empty_msg: &str, blocked: &str) -> Result<CheckedBase, ChronicleError> {
    if raw.trim().is_empty() {
        return Err(ChronicleError::msg(empty_msg));
    }
    let cleaned = normalize_url(raw);
    if cleaned.is_empty() {
        return Err(ChronicleError::msg(empty_msg));
    }
    // `cleaned` is what callers already receive from normalize_url. Refuse it
    // when it still has userinfo instead of returning a rewritten origin.
    if authority_has_userinfo(authority_of(raw.trim())) || authority_has_userinfo(authority_of(&cleaned)) {
        return Err(ChronicleError::msg(blocked));
    }
    let url = reqwest::Url::parse(&cleaned).map_err(|_| ChronicleError::msg(blocked))?;
    if !url.username().is_empty() || url.password().is_some() {
        return Err(ChronicleError::msg(blocked));
    }
    if authority_has_userinfo(authority_of(url.as_str())) {
        return Err(ChronicleError::msg(blocked));
    }
    let host = bare_host(&url).ok_or_else(|| ChronicleError::msg(blocked))?;
    Ok(CheckedBase { cleaned, url, host })
}

pub fn validate_ollama_base_url(raw: &str) -> Result<String, ChronicleError> {
    const BLOCKED: &str = "ollama base_url must be a private or loopback address (localhost / 127.0.0.1 / RFC1918); public hosts are blocked";
    let checked = check_base(raw, "ollama base_url must be a non-empty URL", BLOCKED)?;
    if checked.url.scheme() != "http" && checked.url.scheme() != "https" {
        return Err(ChronicleError::msg(BLOCKED));
    }
    if !host_qualifies(&checked.host) {
        return Err(ChronicleError::msg(BLOCKED));
    }
    Ok(checked.cleaned)
}

pub fn validate_grok_base_url(raw: &str) -> Result<String, ChronicleError> {
    const BLOCKED: &str =
        "grok_base_url must be https://api.x.ai (exact host); other hosts are blocked";
    let checked = check_base(raw, BLOCKED, BLOCKED)?;
    if checked.url.scheme() != "https" || checked.url.host_str() != Some("api.x.ai") {
        return Err(ChronicleError::msg(BLOCKED));
    }
    // `Url::port` hides the https default, so explicit :443 is None and :444 is Some.
    if matches!(checked.url.port(), Some(port) if port != 443) {
        return Err(ChronicleError::msg(BLOCKED));
    }
    Ok(checked.cleaned)
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

/// Vision provider selection — enforces the SEPARATE cloud-vision consent.
///
/// Cloud *text* consent does not authorize uploading photo bytes. When the
/// active provider is a cloud one and `vision_cloud_consent` is absent, degrade
/// to local Ollama instead of sending the image (parity with the Python
/// `process._describe_image` gate). Every image caption path must build its
/// provider through here, never through `build_provider`.
/// Decision half of vision-provider selection. Pure, so the invariant can be
/// tested without reading the host's secrets file.
///
/// Local providers are always allowed. A cloud provider needs its own
/// `vision_cloud_consent` — `cloud_consent` alone covers text only.
pub fn cloud_vision_allowed(
    cfg: &ChronicleConfig,
    secrets: &serde_json::Map<String, Value>,
) -> bool {
    !is_cloud_provider(&provider_name(cfg)) || resolve_vision_cloud_consent(&cfg.llm, secrets)
}

pub fn build_vision_provider(
    cfg: &ChronicleConfig,
) -> Result<(String, Box<dyn ChatProvider>), ChronicleError> {
    let pname = provider_name(cfg);
    if !cloud_vision_allowed(cfg, &load_secrets()) {
        log_line(
            "WARNING",
            &format!(
                "Cloud vision blocked for provider '{pname}': llm.vision_cloud_consent is not set \
                 (photo bytes would leave this machine) — falling back to local Ollama."
            ),
        );
        let rt = crate::ollama::runtime_from_config(cfg);
        return Ok(("ollama".to_string(), Box::new(crate::ollama::OllamaProvider { rt })));
    }
    build_provider(cfg)
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

#[cfg(test)]
mod vision_consent_tests {
    use super::*;

    fn cfg_with(provider: &str, cloud: bool, vision: bool) -> ChronicleConfig {
        let mut cfg = ChronicleConfig::default();
        cfg.llm.provider = provider.into();
        cfg.llm.cloud_consent = cloud;
        cfg.llm.vision_cloud_consent = vision;
        cfg
    }

    fn secrets(pairs: &[(&str, bool)]) -> serde_json::Map<String, Value> {
        pairs.iter().map(|(k, v)| (k.to_string(), Value::Bool(*v))).collect()
    }

    /// The defect: cloud *text* consent was treated as authorizing photo upload.
    #[test]
    fn text_consent_alone_does_not_authorize_cloud_vision() {
        let cfg = cfg_with("grok", true, false);
        assert!(
            !cloud_vision_allowed(&cfg, &secrets(&[])),
            "cloud_consent=true must NOT permit sending photo bytes"
        );
    }

    #[test]
    fn explicit_vision_consent_authorizes_cloud_vision() {
        let cfg = cfg_with("grok", true, true);
        assert!(cloud_vision_allowed(&cfg, &secrets(&[])));
    }

    #[test]
    fn local_provider_needs_no_cloud_vision_consent() {
        let cfg = cfg_with("ollama", false, false);
        assert!(cloud_vision_allowed(&cfg, &secrets(&[])));
    }

    /// secrets.json wins over config.json, in both directions.
    #[test]
    fn secrets_override_config_for_vision_consent() {
        let granted = cfg_with("grok", true, false);
        assert!(cloud_vision_allowed(&granted, &secrets(&[("vision_cloud_consent", true)])));

        let revoked = cfg_with("grok", true, true);
        assert!(!cloud_vision_allowed(&revoked, &secrets(&[("vision_cloud_consent", false)])));
    }

    /// Blocked cloud vision degrades to local inference rather than failing the
    /// whole process run — and must never hand back the cloud provider.
    #[test]
    fn blocked_cloud_vision_falls_back_to_local_ollama() {
        let cfg = cfg_with("grok", true, false);
        let (name, _p) = build_vision_provider(&cfg).expect("degrades to local, not an error");
        assert_eq!(name, "ollama");
    }
}

#[cfg(test)]
mod base_url_allowlist_tests {
    use super::*;

    fn returned_host_is_loopback(url: &str) -> bool {
        let parsed = reqwest::Url::parse(url).expect("accepted url must parse");
        let host = parsed.host_str().unwrap_or("");
        assert_ne!(host, "[", "split host must not be the opening bracket of an IPv6 literal");
        assert!(!url.contains('@'), "returned url must not contain userinfo");
        let bare = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')).unwrap_or(host);
        bare.parse::<std::net::IpAddr>().is_ok_and(|ip| ip.is_loopback())
    }

    #[test]
    fn validate_ollama_base_url_rejects_userinfo() {
        for raw in [
            "http://127.0.0.1:11434@evil.example/",
            "http://user:pass@127.0.0.1:11434/",
            "http://127.0.0.1:11434%40evil.example/",
            "https://evil.example@127.0.0.1/",
            "http://127.0.0.1%2540evil.example/",
            "http://user%40x:pw%40y@127.0.0.1:11434/",
        ] {
            assert!(validate_ollama_base_url(raw).is_err(), "expected Err for {raw}");
        }
    }

    #[test]
    fn validate_ollama_base_url_accepts_ipv6_loopback() {
        let with_port = validate_ollama_base_url("http://[::1]:11434/").expect("ipv6 loopback with port");
        assert_eq!(with_port, "http://[::1]:11434");
        assert!(returned_host_is_loopback(&with_port), "{with_port}");

        let bare = validate_ollama_base_url("http://[::1]/").expect("ipv6 loopback");
        assert_eq!(bare, "http://[::1]");
        assert!(returned_host_is_loopback(&bare), "{bare}");
    }

    #[test]
    fn validate_grok_base_url_port_and_userinfo() {
        assert_eq!(validate_grok_base_url("https://api.x.ai").expect("host"), "https://api.x.ai");
        assert_eq!(validate_grok_base_url("https://api.x.ai/").expect("slash"), "https://api.x.ai");
        assert_eq!(
            validate_grok_base_url("https://api.x.ai:443").expect("port 443"),
            "https://api.x.ai:443"
        );
        assert_eq!(
            validate_grok_base_url("https://api.x.ai:443/").expect("port 443 slash"),
            "https://api.x.ai:443"
        );
        // Default config path must survive; do not rewrite it to the origin alone.
        assert_eq!(
            validate_grok_base_url("https://api.x.ai/v1").expect("v1 path"),
            "https://api.x.ai/v1"
        );
        assert!(validate_grok_base_url("https://api.x.ai:444/").is_err());
        assert!(validate_grok_base_url("https://api.x.ai:443@evil.example/").is_err());
        assert!(validate_grok_base_url("http://api.x.ai/").is_err());
        assert!(validate_grok_base_url("").is_err());
    }

    #[test]
    fn validate_ollama_base_url_blocks_public_allows_loopback() {
        assert!(validate_ollama_base_url("https://evil.example/").is_err());
        assert!(validate_ollama_base_url("").is_err());
        assert!(validate_ollama_base_url("   ").is_err());
        assert_eq!(
            validate_ollama_base_url("http://127.0.0.1:11434/").expect("loopback"),
            "http://127.0.0.1:11434"
        );
        assert_eq!(
            validate_ollama_base_url("http://127.0.0.1:11434").expect("loopback no slash"),
            "http://127.0.0.1:11434"
        );
        assert!(returned_host_is_loopback(
            &validate_ollama_base_url("http://127.0.0.1:11434/").unwrap()
        ));
        // Existing private and link-local literals stay allowed. No new public hosts.
        assert!(validate_ollama_base_url("http://10.1.2.3:11434/").is_ok());
        assert!(validate_ollama_base_url("http://192.168.1.9/").is_ok());
        assert!(validate_ollama_base_url("http://172.16.0.4/").is_ok());
        assert!(validate_ollama_base_url("http://169.254.1.1/").is_ok());
        assert!(validate_ollama_base_url("http://8.8.8.8/").is_err());
        assert!(validate_ollama_base_url("http://localhost:11434").is_ok());
    }
}
