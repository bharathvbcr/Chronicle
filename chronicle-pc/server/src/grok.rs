//! Grok (x.ai) OpenAI-compatible chat provider.

use std::path::Path;
use std::time::Duration;

use base64::Engine;
use serde_json::{json, Value};

use crate::config::ChronicleConfig;
use crate::errors::ChronicleError;
use crate::ollama::strip_think_blocks;
use crate::provider::{ChatOpts, ChatProvider, Message};

const CONNECT_TIMEOUT_SECS: u64 = 5;

#[derive(Clone)]
pub struct GrokProvider {
    pub base_url: String,
    pub api_key: String,
    pub cfg: ChronicleConfig,
}

/// Chat-completions body for one vision turn.
/// `content` is one parts array (text, then image_url), not an array of that array.
fn image_chat_payload(model: &str, prompt: &str, data_url: &str) -> Value {
    let content = json!([
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": data_url}},
    ]);
    json!({
        "model": model,
        "messages": [{"role": "user", "content": content}],
        "temperature": 0.1,
        "max_tokens": 200,
    })
}

fn client(read_timeout: Duration) -> Result<reqwest::blocking::Client, ChronicleError> {
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(read_timeout)
        .build()
        .map_err(|e| ChronicleError::msg(e.to_string()))
}

impl GrokProvider {
    fn default_model(&self) -> String {
        self.cfg
            .llm
            .grok
            .model
            .clone()
            .filter(|m| !m.is_empty())
            .or_else(|| Some(self.cfg.models.llm.clone()))
            .unwrap_or_else(|| "grok-2-latest".into())
    }

    fn vision_model(&self) -> String {
        let v = self.cfg.models.vision.clone();
        if v.is_empty() { self.default_model() } else { v }
    }

    fn post_chat(&self, payload: &Value, timeout: Duration) -> Result<String, ChronicleError> {
        let url = format!("{}/chat/completions", self.base_url);
        let cl = client(timeout)?;
        let mut last_connect: Option<String> = None;
        let resp = 'attempt: {
            for attempt in 0..2 {
                match cl
                    .post(&url)
                    .bearer_auth(&self.api_key)
                    .json(payload)
                    .timeout(timeout)
                    .send()
                {
                    Ok(r) => break 'attempt r,
                    Err(e) if e.is_connect() && attempt == 0 => {
                        last_connect = Some(format!("connection failed: {e}"));
                        std::thread::sleep(Duration::from_millis(500));
                    }
                    Err(e) => return Err(ChronicleError::Llm(format!("Grok chat failed: request failed: {e}"))),
                }
            }
            return Err(ChronicleError::Llm(format!(
                "Grok chat failed: {}",
                last_connect.unwrap_or_else(|| "connection failed".into())
            )));
        };
        let status = resp.status().as_u16();
        if !(200..300).contains(&status) {
            let body = resp.text().unwrap_or_default();
            let clipped: String = body.chars().take(200).collect();
            return Err(ChronicleError::Llm(format!("Grok chat failed: HTTP {status}: {clipped}")));
        }
        let data: Value = resp.json().map_err(|e| ChronicleError::msg(e.to_string()))?;
        match data
            .get("choices")
            .and_then(Value::as_array)
            .and_then(|c| c.first())
            .and_then(|c| c.get("message"))
            .and_then(|m| m.get("content"))
            .and_then(Value::as_str)
        {
            Some(s) => Ok(strip_think_blocks(s)),
            None => Err(ChronicleError::Llm("Grok chat response missing choices".into())),
        }
    }
}

impl ChatProvider for GrokProvider {
    fn name(&self) -> &'static str {
        "grok"
    }

    fn reachable(&self, timeout: Duration) -> bool {
        let Ok(cl) = client(timeout) else { return false };
        matches!(
            cl.get(format!("{}/models", self.base_url))
                .bearer_auth(&self.api_key)
                .timeout(timeout)
                .send(),
            Ok(r) if r.status().as_u16() < 500
        )
    }

    fn chat(&self, messages: Vec<Message>, opts: &ChatOpts) -> Result<String, ChronicleError> {
        let mut payload = json!({
            "model": opts.model.clone().unwrap_or_else(|| self.default_model()),
            "messages": messages,
            "temperature": opts.temperature,
            "top_p": opts.top_p,
            "stream": false,
        });
        if let Some(np) = opts.num_predict {
            payload["max_tokens"] = json!(np);
        }
        if opts.format_json {
            payload["response_format"] = json!({"type": "json_object"});
        }
        self.post_chat(&payload, opts.timeout)
    }

    fn describe_image(&self, image_path: &Path, prompt: &str) -> Result<String, ChronicleError> {
        let raw = std::fs::read(image_path)?;
        let b64 = base64::engine::general_purpose::STANDARD.encode(raw);
        let mime = if image_path
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| e.eq_ignore_ascii_case("png"))
        {
            "image/png"
        } else {
            "image/jpeg"
        };
        let data_url = format!("data:{mime};base64,{b64}");
        let payload = image_chat_payload(&self.vision_model(), prompt, &data_url);
        self.post_chat(&payload, Duration::from_secs(300))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grok_image_content_is_one_parts_array() {
        let data_url = "data:image/jpeg;base64,abcd";
        let payload = image_chat_payload("grok-2-latest", "Describe this journal photo.", data_url);
        let serialized = payload.to_string();
        let content = &payload["messages"][0]["content"];
        assert!(content.is_array(), "content is an array, got {content}");
        let parts = content.as_array().expect("content is an array");
        assert!(
            parts.first().is_some_and(|part| part.is_object() && !part.is_array()),
            "content[0] is the text object, not an array: {content}"
        );
        assert_eq!(parts[0]["type"], json!("text"));
        assert_eq!(parts[0]["text"], json!("Describe this journal photo."));
        assert_eq!(parts[1]["type"], json!("image_url"));
        assert!(
            !serialized.contains("[["),
            "serialized JSON must not wrap the parts array: {serialized}"
        );
    }
}
