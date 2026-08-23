//! Ollama HTTP client (chat / vision / embed) with think-strip + JSON ladder.

use std::path::Path;
use std::time::Duration;

use base64::Engine;
use regex::Regex;
use serde_json::{json, Value};

use crate::config::ChronicleConfig;
use crate::errors::ChronicleError;
use crate::provider::{ChatOpts, ChatProvider, Message};
use crate::LlmRuntime;

pub const DEFAULT_TIMEOUT_SECS: u64 = 300;
const CONNECT_TIMEOUT_SECS: u64 = 5;

const THINK_BLOCK_RE: &str = r"(?is)<think\b[^>]*>[\s\S]*?</think>";
const THINK_UNCLOSED_RE: &str = r"(?is)<think\b[^>]*>[\s\S]*\Z";

pub fn runtime_from_config(cfg: &ChronicleConfig) -> LlmRuntime {
    let mut rt = LlmRuntime::default();
    let _ = crate::provider::validate_ollama_base_url(&cfg.ollama.base_url)
        .map(|cleaned| rt.base_url = cleaned);
    if cfg.ollama.num_ctx > 0 {
        rt.num_ctx = cfg.ollama.num_ctx;
    }
    rt.global_temperature = cfg.ollama.temperature;
    rt
}

pub fn strip_think_blocks(text: &str) -> String {
    let cleaned = Regex::new(THINK_BLOCK_RE).unwrap().replace_all(text, "");
    let cleaned = Regex::new(THINK_UNCLOSED_RE).unwrap().replace_all(&cleaned, "");
    cleaned.trim().to_string()
}

fn blocking_client(read_timeout: Duration) -> reqwest::Result<reqwest::blocking::Client> {
    // Fast connect failure + bounded read — unreachable Ollama never hangs
    // for the full generation timeout (stall vs response separation).
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(read_timeout)
        .build()
}

/// One retry on connect-phase failures only (request never reached server).
fn post_with_retry(
    url: &str,
    payload: &Value,
    read_timeout: Duration,
) -> Result<reqwest::blocking::Response, ChronicleError> {
    let client = blocking_client(read_timeout).map_err(|e| ChronicleError::msg(e.to_string()))?;
    let mut last_err: Option<String> = None;
    for attempt in 0..2 {
        let res = client.post(url).json(payload).timeout(read_timeout).send();
        match res {
            Ok(r) => return Ok(r),
            Err(e) if e.is_connect() && attempt == 0 => {
                last_err = Some(format!("connection failed: {e}"));
                std::thread::sleep(Duration::from_millis(500));
            }
            Err(e) => {
                let msg = if e.is_connect() {
                    format!("Ollama not reachable at {url} ({e})")
                } else {
                    format!("request failed: {e}")
                };
                return Err(ChronicleError::OllamaUnreachable(msg));
            }
        }
    }
    Err(ChronicleError::OllamaUnreachable(last_err.unwrap_or_else(|| "connection failed".into())))
}

fn get_with_timeout(url: &str, timeout: Duration) -> Result<u16, ChronicleError> {
    let client = blocking_client(timeout).map_err(|e| ChronicleError::msg(e.to_string()))?;
    match client.get(url).timeout(timeout).send() {
        Ok(r) => Ok(r.status().as_u16()),
        Err(_) => Err(ChronicleError::OllamaUnreachable(format!("not reachable: {url}"))),
    }
}

pub fn ollama_reachable_rt(rt: &LlmRuntime, timeout: Duration) -> bool {
    matches!(get_with_timeout(&format!("{}/api/tags", rt.base_url), timeout), Ok(200))
}

pub fn list_available_models(rt: &LlmRuntime, timeout: Duration) -> Vec<String> {
    let client = match blocking_client(timeout) {
        Ok(c) => c,
        Err(_) => return vec![],
    };
    let resp = match client.get(format!("{}/api/tags", rt.base_url)).timeout(timeout).send() {
        Ok(r) if r.status().as_u16() < 500 => r,
        _ => return vec![],
    };
    let data: Value = match resp.json() {
        Ok(d) => d,
        Err(_) => return vec![],
    };
    let mut names: Vec<String> = data
        .get("models")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|m| {
                    m.get("name")
                        .or_else(|| m.get("model"))
                        .and_then(Value::as_str)
                        .map(|s| s.trim().to_string())
                        .filter(|s| !s.is_empty())
                })
                .collect()
        })
        .unwrap_or_default();
    names.sort_by_key(|a| a.to_lowercase());
    names.dedup_by(|a, b| a.eq_ignore_ascii_case(b));
    names
}

pub fn chat(
    rt: &LlmRuntime,
    messages: Vec<Message>,
    opts: &ChatOpts,
) -> Result<String, ChronicleError> {
    let model = opts.model.clone().unwrap_or_default();
    let effective_temp = rt.global_temperature.unwrap_or(opts.temperature);
    let mut options = json!({
        "temperature": effective_temp,
        "top_p": opts.top_p,
        "top_k": opts.top_k,
        "num_ctx": opts.num_ctx,
    });
    if let Some(np) = opts.num_predict {
        options["num_predict"] = json!(np);
    }
    let mut payload = json!({
        "model": model,
        "messages": messages,
        "stream": false,
        "options": options,
        "keep_alive": "10m",
    });
    if opts.format_json {
        payload["format"] = json!("json");
    }
    let url = format!("{}/api/chat", rt.base_url);
    let resp = post_with_retry(&url, &payload, opts.timeout)?;
    let status = resp.status().as_u16();
    if !(200..300).contains(&status) {
        let body = resp.text().unwrap_or_default();
        let clipped: String = body.chars().take(200).collect();
        return Err(if status == 404 {
            ChronicleError::Llm(format!(
                "Ollama model not found (404): is the model pulled? POST {url} → {clipped}"
            ))
        } else {
            ChronicleError::Llm(format!("Ollama chat failed: HTTP {status}: {clipped}"))
        });
    }
    let data: Value = resp.json().map_err(|e| ChronicleError::msg(format!("bad chat response: {e}")))?;
    let content = data
        .get("message")
        .and_then(|m| m.get("content"))
        .and_then(Value::as_str)
        .unwrap_or_default();
    Ok(strip_think_blocks(content))
}

pub fn embed_rt(rt: &LlmRuntime, text: &str, model: Option<&str>) -> Result<Vec<f64>, ChronicleError> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(vec![]);
    }
    let clipped: String = text.chars().take(2000).collect();
    let resolved = model.unwrap_or("nomic-embed-text");
    let payload = json!({"model": resolved, "prompt": clipped});
    let url = format!("{}/api/embeddings", rt.base_url);
    let resp = post_with_retry(&url, &payload, Duration::from_secs(60))?;
    let status = resp.status().as_u16();
    if !(200..300).contains(&status) {
        return Err(ChronicleError::Llm(format!("Ollama embed failed: HTTP {status}")));
    }
    let data: Value = resp.json().map_err(|e| ChronicleError::msg(e.to_string()))?;
    match data.get("embedding").and_then(Value::as_array) {
        Some(arr) => Ok(arr.iter().filter_map(|v| v.as_f64()).collect()),
        None => Err(ChronicleError::Llm("Ollama embed response missing embedding".into())),
    }
}

pub fn cosine(a: &[f64], b: &[f64]) -> f64 {
    if a.is_empty() || b.is_empty() || a.len() != b.len() {
        return 0.0;
    }
    let dot: f64 = a.iter().zip(b).map(|(x, y)| x * y).sum();
    let na: f64 = a.iter().map(|x| x * x).sum::<f64>().sqrt();
    let nb: f64 = b.iter().map(|y| y * y).sum::<f64>().sqrt();
    if na == 0.0 || nb == 0.0 { 0.0 } else { dot / (na * nb) }
}

/// extract_json tolerance ladder (think-strip → parse → fence → prefix scan).
pub fn extract_json(text: &str) -> Result<Value, ChronicleError> {
    let raw = strip_think_blocks(text);
    if raw.is_empty() {
        return Err(ChronicleError::msg("empty model output"));
    }
    if let Ok(v) = serde_json::from_str::<Value>(&raw) {
        return Ok(v);
    }
    let fence_re = Regex::new(r"(?s)```(?:json)?\s*([\s\S]*?)```").unwrap();
    if let Some(caps) = fence_re.captures(&raw) {
        if let Ok(v) = serde_json::from_str::<Value>(caps.get(1).unwrap().as_str().trim()) {
            return Ok(v);
        }
    }
    for opener in ['{', '['] {
        if let Some(start) = raw.find(opener) {
            if let Ok(v) = serde_json::from_str::<Value>(&raw[start..]) {
                return Ok(v);
            }
        }
    }
    let preview: String = raw.chars().take(200).collect();
    Err(ChronicleError::msg(format!(
        "could not parse JSON from model output: {preview:?}"
    )))
}

// ---------------------------------------------------------------------------
// Provider impl
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct OllamaProvider {
    pub rt: LlmRuntime,
}

impl ChatProvider for OllamaProvider {
    fn name(&self) -> &'static str {
        "ollama"
    }

    fn reachable(&self, timeout: Duration) -> bool {
        ollama_reachable_rt(&self.rt, timeout)
    }

    fn chat(&self, messages: Vec<Message>, opts: &ChatOpts) -> Result<String, ChronicleError> {
        chat(&self.rt, messages, opts)
    }

    fn describe_image(&self, image_path: &Path, prompt: &str) -> Result<String, ChronicleError> {
        describe_image(&self.rt, image_path, prompt, None)
    }
}

pub fn describe_image(
    rt: &LlmRuntime,
    image_path: &Path,
    prompt: &str,
    model: Option<&str>,
) -> Result<String, ChronicleError> {
    let raw = std::fs::read(image_path)?;
    let b64 = base64::engine::general_purpose::STANDARD.encode(raw);
    let resolved = model.unwrap_or("llama3.2-vision:11b").to_string();
    let messages = vec![json!({"role": "user", "content": prompt, "images": [b64]})];
    let opts = ChatOpts {
        model: Some(resolved),
        temperature: 0.1,
        num_predict: Some(200),
        num_ctx: 8192,
        ..Default::default()
    };
    chat(rt, messages, &opts)
}

pub use crate::log_line;
