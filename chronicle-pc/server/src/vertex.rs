//! Google Vertex AI adapter — native generateContent with ADC auth.
//!
//! Auth mirrors google-auth's two local credential types:
//! - `service_account`: RS256 JWT-bearer grant (pure-Rust `rsa` signing)
//! - `authorized_user` (gcloud application-default login): refresh_token grant
//! Tokens are cached until 60s before expiry.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::errors::ChronicleError;
use crate::ollama::strip_think_blocks;
use crate::provider::{ChatOpts, ChatProvider, Message};

const DEFAULT_LOCATION: &str = "us-central1";
pub const DEFAULT_MODEL: &str = "gemini-2.0-flash-001";
const DEFAULT_TIMEOUT_SECS: u64 = 300;
const SCOPE: &str = "https://www.googleapis.com/auth/cloud-platform";
const TOKEN_URI: &str = "https://oauth2.googleapis.com/token";

fn err(m: impl Into<String>) -> ChronicleError {
    ChronicleError::Llm(m.into())
}

#[derive(Clone)]
pub struct VertexProvider {
    pub project: String,
    pub location: String,
    pub default_model: String,
    pub vision_model: String,
    /// Explicit token override (tests / pre-authenticated callers).
    pub access_token: Option<String>,
    cache: Arc<Mutex<Option<(String, Instant, u64)>>>,
}

impl VertexProvider {
    pub fn new(
        project: Option<String>,
        location: Option<String>,
        default_model: Option<String>,
        vision_model: Option<String>,
    ) -> Result<Self, ChronicleError> {
        let proj = project.unwrap_or_default().trim().to_string();
        if proj.is_empty() {
            return Err(err(
                "Vertex provider requires project (llm.vertex.project, VERTEX_PROJECT, or GOOGLE_CLOUD_PROJECT)",
            ));
        }
        let location = {
            let l = location.unwrap_or_default().trim().to_string();
            if l.is_empty() { DEFAULT_LOCATION.into() } else { l }
        };
        Ok(Self {
            project: proj,
            location,
            default_model: default_model
                .filter(|m| !m.is_empty())
                .unwrap_or_else(|| DEFAULT_MODEL.into()),
            vision_model: vision_model.filter(|m| !m.is_empty()).unwrap_or_default(),
            access_token: None,
            cache: Arc::new(Mutex::new(None)),
        })
    }

    pub fn from_config(cfg: &crate::config::ChronicleConfig) -> Result<Self, ChronicleError> {
        let secrets = crate::provider::load_secrets();
        let project =
            crate::provider::resolve_vertex_project(&cfg.llm, &secrets);
        Self::new(
            project,
            Some(cfg.llm.vertex.location.clone()),
            cfg.llm.vertex.model.clone(),
            None,
        )
    }

    fn endpoint(&self, model: &str) -> String {
        format!(
            "https://{}-aiplatform.googleapis.com/v1/projects/{}/locations/{}/publishers/google/models/{model}:generateContent",
            self.location, self.project, self.location
        )
    }

    fn adc_path() -> PathBuf {
        if let Ok(p) = std::env::var("GOOGLE_APPLICATION_CREDENTIALS") {
            return PathBuf::from(p);
        }
        let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
        home.join(".config/gcloud/application_default_credentials.json")
    }

    fn fetch_adc_token() -> Result<(String, u64), ChronicleError> {
        let path = Self::adc_path();
        let raw = std::fs::read_to_string(&path)
            .map_err(|_| err("Vertex ADC failed: no Application Default Credentials found. Run `gcloud auth application-default login` or set GOOGLE_APPLICATION_CREDENTIALS."))?;
        let creds: Value = serde_json::from_str(&raw)
            .map_err(|e| err(format!("Vertex ADC failed: unreadable credentials {}: {e}", path.display())))?;
        let ctype = creds.get("type").and_then(Value::as_str).unwrap_or_default();
        let client = reqwest::blocking::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|e| err(e.to_string()))?;
        match ctype {
            "authorized_user" => {
                let form = [
                    ("grant_type", "refresh_token"),
                    (
                        "refresh_token",
                        creds.get("refresh_token").and_then(Value::as_str).unwrap_or_default(),
                    ),
                    ("client_id", creds.get("client_id").and_then(Value::as_str).unwrap_or_default()),
                    ("client_secret", creds.get("client_secret").and_then(Value::as_str).unwrap_or_default()),
                ];
                let resp = client
                    .post(TOKEN_URI)
                    .form(&form)
                    .send()
                    .map_err(|e| err(format!("Vertex ADC failed: {e}")))?;
                Self::parse_token_response(resp, &path)
            }
            "service_account" => {
                let client_email = creds.get("client_email").and_then(Value::as_str).unwrap_or_default();
                let private_key_pem = creds.get("private_key").and_then(Value::as_str).unwrap_or_default();
                let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                let assertion = self_sign_jwt(client_email, SCOPE, TOKEN_URI, now + 3600, private_key_pem)?;
                let form = [
                    ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                    ("assertion", assertion.as_str()),
                ];
                let resp = client
                    .post(TOKEN_URI)
                    .form(&form)
                    .send()
                    .map_err(|e| err(format!("Vertex ADC failed: {e}")))?;
                Self::parse_token_response(resp, &path)
            }
            other => Err(err(format!(
                "Vertex ADC failed: unsupported credentials type '{other}' in {}",
                path.display()
            ))),
        }
    }

    fn parse_token_response(
        resp: reqwest::blocking::Response,
        path: &std::path::Path,
    ) -> Result<(String, u64), ChronicleError> {
        let status = resp.status().as_u16();
        let body: Value = resp.json().unwrap_or(Value::Null);
        if !(200..300).contains(&status) {
            let detail = body.get("error_description").and_then(Value::as_str)
                .or_else(|| body.get("error").and_then(Value::as_str))
                .unwrap_or("");
            return Err(err(format!(
                "Vertex ADC failed: token endpoint HTTP {status} {detail} (check {}) ",
                path.display()
            )));
        }
        let token = body
            .get("access_token")
            .and_then(Value::as_str)
            .ok_or_else(|| err("Vertex ADC returned no access token"))?
            .to_string();
        let expires_in = body.get("expires_in").and_then(Value::as_u64).unwrap_or(3600);
        Ok((token, expires_in))
    }

    fn token(&self) -> Result<String, ChronicleError> {
        if let Some(t) = self.access_token.clone().filter(|t| !t.is_empty()) {
            return Ok(t);
        }
        {
            let guard = self.cache.lock().unwrap();
            if let Some((tok, fetched_at, ttl)) = guard.as_ref() {
                if fetched_at.elapsed() < Duration::from_secs(*ttl) {
                    return Ok(tok.clone());
                }
            }
        }
        let (tok, expires_in) = Self::fetch_adc_token()?;
        let ttl = expires_in.saturating_sub(60).max(60);
        *self.cache.lock().unwrap() = Some((tok.clone(), Instant::now(), ttl));
        Ok(tok)
    }
}

fn b64url(data: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

/// RS256-signed JWT for the service-account flow.
pub fn self_sign_jwt(
    issuer: &str,
    scope: &str,
    aud: &str,
    exp_unix: u64,
    private_key_pem: &str,
) -> Result<String, ChronicleError> {
    use rsa::pkcs8::DecodePrivateKey;
    use rsa::{Pkcs1v15Sign, RsaPrivateKey};

    let key = RsaPrivateKey::from_pkcs8_pem(private_key_pem)
        .or_else(|_| {
            use rsa::pkcs1::DecodeRsaPrivateKey;
            RsaPrivateKey::from_pkcs1_pem(private_key_pem)
        })
        .map_err(|e| err(format!("Vertex ADC failed: bad service account private_key: {e}")))?;

    let header = b64url(br#"{"alg":"RS256","typ":"JWT"}"#);
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
    let claims = json!({
        "iss": issuer,
        "scope": scope,
        "aud": aud,
        "exp": exp_unix.max(now),
        "iat": now,
    });
    let claims_b64 = b64url(claims.to_string().as_bytes());
    let signing_input = format!("{header}.{claims_b64}");
    let digest: Vec<u8> = Sha256::digest(signing_input.as_bytes()).to_vec();
    let sig = key
        .sign(Pkcs1v15Sign::new::<Sha256>(), &digest)
        .map_err(|e| err(format!("Vertex ADC failed: JWT signing error: {e}")))?;
    Ok(format!("{signing_input}.{}", b64url(&sig)))
}

fn contents_from_messages(messages: &[Message]) -> Result<(Option<String>, Vec<Value>), ChronicleError> {
    let mut system_parts: Vec<String> = Vec::new();
    let mut contents: Vec<Value> = Vec::new();
    for msg in messages {
        let role = msg.get("role").and_then(Value::as_str).unwrap_or("user").to_lowercase();
        let content = msg.get("content");
        if role == "system" {
            if let Some(s) = content.and_then(Value::as_str).filter(|c| !c.trim().is_empty()) {
                system_parts.push(s.trim().to_string());
            }
            continue;
        }
        let gemini_role = if role == "assistant" { "model" } else { "user" };
        let mut parts: Vec<Value> = Vec::new();
        match content {
            Some(Value::String(text)) => parts.push(json!({"text": text})),
            Some(Value::Array(items)) => {
                for part in items {
                    let Some(obj) = part.as_object() else { continue };
                    match obj.get("type").and_then(Value::as_str) {
                        Some("text") => {
                            if let Some(t) = obj.get("text").and_then(Value::as_str).filter(|t| !t.is_empty()) {
                                parts.push(json!({"text": t}));
                            }
                        }
                        Some("image_url") => {
                            let url = obj
                                .get("image_url")
                                .and_then(|i| i.get("url"))
                                .and_then(Value::as_str)
                                .unwrap_or_default();
                            if let Some(rest) = url.strip_prefix("data:") {
                                if let Some((header, b64)) = rest.split_once(',') {
                                    if let Some(mime) = header.split(';').next().and_then(|h| h.split(':').nth(1)) {
                                        parts.push(json!({"inlineData": {"mimeType": mime, "data": b64}}));
                                    }
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            _ => {}
        }
        if !parts.is_empty() {
            contents.push(json!({"role": gemini_role, "parts": parts}));
        }
    }
    let system = if system_parts.is_empty() {
        None
    } else {
        Some(system_parts.join("\n\n"))
    };
    Ok((system, contents))
}

fn extract_vertex_text(data: &Value) -> Result<String, ChronicleError> {
    let Some(cands) = data.get("candidates").and_then(Value::as_array) else {
        return Err(err("Vertex response missing candidates"));
    };
    let first = cands.first().ok_or_else(|| err("Vertex response missing candidates"))?;
    let parts = first
        .get("content")
        .and_then(|c| c.get("parts"))
        .and_then(Value::as_array)
        .ok_or_else(|| err("Vertex response missing content.parts"))?;
    let texts: Vec<&str> = parts
        .iter()
        .filter_map(|p| p.get("text").and_then(Value::as_str))
        .collect();
    if texts.is_empty() {
        return Err(err("Vertex response contained no text parts"));
    }
    Ok(texts.join("\n"))
}

impl ChatProvider for VertexProvider {
    fn name(&self) -> &'static str {
        "vertex"
    }

    fn reachable(&self, timeout: Duration) -> bool {
        let Ok(token) = self.token() else { return false };
        let url = format!(
            "https://{}-aiplatform.googleapis.com/v1/projects/{}/locations/{}/publishers/google/models/{}",
            self.location, self.project, self.location, self.default_model
        );
        let client = match reqwest::blocking::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(timeout)
            .build()
        {
            Ok(c) => c,
            Err(_) => return false,
        };
        matches!(
            client.get(url).bearer_auth(token).timeout(timeout).send(),
            Ok(r) if r.status().as_u16() < 500
        )
    }

    fn chat(&self, messages: Vec<Message>, opts: &ChatOpts) -> Result<String, ChronicleError> {
        let resolved = opts.model.clone().filter(|m| !m.is_empty()).unwrap_or_else(|| self.default_model.clone());
        let (system, contents) = contents_from_messages(&messages)?;
        if contents.is_empty() {
            return Err(err("Vertex chat requires at least one user/assistant message"));
        }
        let mut generation = json!({
            "temperature": opts.temperature,
            "topP": opts.top_p,
            "topK": opts.top_k,
        });
        if let Some(np) = opts.num_predict {
            generation["maxOutputTokens"] = json!(np);
        }
        if opts.format_json {
            generation["responseMimeType"] = json!("application/json");
        }
        let mut payload = json!({"contents": contents, "generationConfig": generation});
        if let Some(sys) = system {
            payload["systemInstruction"] = json!({"parts": [{"text": sys}]});
        }
        let client = reqwest::blocking::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(opts.timeout)
            .build()
            .map_err(|e| err(e.to_string()))?;
        let resp = client
            .post(self.endpoint(&resolved))
            .bearer_auth(self.token()?)
            .json(&payload)
            .timeout(opts.timeout)
            .send()
            .map_err(|e| err(format!("Vertex chat failed: request failed: {e}")))?;
        let status = resp.status().as_u16();
        if !(200..300).contains(&status) {
            let body = resp.text().unwrap_or_default();
            let clipped: String = body.chars().take(200).collect();
            return Err(err(format!("Vertex chat failed: HTTP {status}: {clipped}")));
        }
        let data: Value = resp.json().map_err(|e| err(e.to_string()))?;
        let text = extract_vertex_text(&data)?;
        Ok(strip_think_blocks(text.trim()))
    }

    fn describe_image(&self, image_path: &std::path::Path, prompt: &str) -> Result<String, ChronicleError> {
        let raw = std::fs::read(image_path)?;
        let mime = match image_path
            .extension()
            .and_then(|e| e.to_str())
            .map(str::to_ascii_lowercase)
            .as_deref()
        {
            Some("png") => "image/png",
            Some("webp") => "image/webp",
            Some("gif") => "image/gif",
            _ => "image/jpeg",
        };
        let b64 = base64::engine::general_purpose::STANDARD.encode(raw);
        let parts = vec![
            json!({"type": "text", "text": prompt}),
            json!({"type": "image_url", "image_url": {"url": format!("data:{mime};base64,{b64}")}}),
        ];
        let messages = vec![json!({"role": "user", "content": parts})];
        let opts = ChatOpts {
            model: Some(if self.vision_model.is_empty() { self.default_model.clone() } else { self.vision_model.clone() }),
            temperature: 0.1,
            num_predict: Some(200),
            ..Default::default()
        };
        self.chat(messages, &opts)
    }
}


impl VertexProvider {
    fn vision_model_missing(&self) -> String {
        self.default_model.clone()
    }
}
