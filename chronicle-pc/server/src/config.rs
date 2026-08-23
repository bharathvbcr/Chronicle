//! config.json — serde mirror of ChronicleConfig with python-identical
//! defaults, deep-merge rules, layout gate, and exclude-none save semantics.

use std::path::Path;

use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::errors::ChronicleError;
use crate::paths::{atomic_write_json, resolve_chronicle_dir};

pub const CURRENT_LAYOUT_VERSION: i64 = 2;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConfigModels {
    #[serde(default = "def_llm_model")]
    pub llm: String,
    #[serde(default = "def_embed_model")]
    pub embed: String,
    #[serde(default = "def_vision_model")]
    pub vision: String,
    #[serde(default = "def_whisper")]
    pub whisper: String,
}

fn def_llm_model() -> String { "maxwell1500/ornith-35b:Q4_K_M".into() }
fn def_embed_model() -> String { "nomic-embed-text".into() }
fn def_vision_model() -> String { "llama3.2-vision:11b".into() }
fn def_whisper() -> String { "whisper".into() }

impl Default for ConfigModels {
    fn default() -> Self {
        Self {
            llm: def_llm_model(),
            embed: def_embed_model(),
            vision: def_vision_model(),
            whisper: def_whisper(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct OllamaOptions {
    #[serde(default = "def_ollama_base")]
    pub base_url: String,
    #[serde(default = "def_num_ctx")]
    pub num_ctx: i64,
    #[serde(default)]
    pub temperature: Option<f64>,
}

fn def_ollama_base() -> String { "http://localhost:11434".into() }
fn def_num_ctx() -> i64 { 32768 }

impl Default for OllamaOptions {
    fn default() -> Self {
        Self { base_url: def_ollama_base(), num_ctx: def_num_ctx(), temperature: None }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct GrokOptions {
    #[serde(default = "def_grok_base")]
    pub base_url: String,
    #[serde(default)]
    pub model: Option<String>,
}

fn def_grok_base() -> String { "https://api.x.ai/v1".into() }

impl Default for GrokOptions {
    fn default() -> Self {
        Self { base_url: def_grok_base(), model: None }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VertexOptions {
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default = "def_vertex_location")]
    pub location: String,
    #[serde(default)]
    pub model: Option<String>,
}

fn def_vertex_location() -> String { "us-central1".into() }

impl Default for VertexOptions {
    fn default() -> Self {
        Self { project: None, location: def_vertex_location(), model: None }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LlmOptions {
    #[serde(default = "def_provider")]
    pub provider: String,
    #[serde(default)]
    pub cloud_consent: bool,
    #[serde(default)]
    pub vision_cloud_consent: bool,
    #[serde(default)]
    pub grok: GrokOptions,
    #[serde(default)]
    pub vertex: VertexOptions,
}

fn def_provider() -> String { "ollama".into() }

impl Default for LlmOptions {
    fn default() -> Self {
        Self {
            provider: def_provider(),
            cloud_consent: false,
            vision_cloud_consent: false,
            grok: GrokOptions::default(),
            vertex: VertexOptions::default(),
        }
    }
}

/// extra="allow" everywhere → unknown keys survive round-trips in `extra`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChronicleConfig {
    #[serde(default = "def_version")]
    pub version: i64,
    #[serde(default = "def_layout_version")]
    pub layout_version: i64,
    #[serde(default = "def_timezone")]
    pub timezone: String,
    #[serde(default)]
    pub vault_mirror: Option<String>,
    #[serde(default)]
    pub models: ConfigModels,
    #[serde(default)]
    pub ollama: OllamaOptions,
    #[serde(default)]
    pub llm: LlmOptions,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

fn def_version() -> i64 { 1 }
fn def_layout_version() -> i64 { CURRENT_LAYOUT_VERSION }
fn def_timezone() -> String { "UTC".into() }

impl Default for ChronicleConfig {
    fn default() -> Self {
        Self {
            version: 1,
            layout_version: CURRENT_LAYOUT_VERSION,
            timezone: "UTC".into(),
            vault_mirror: None,
            models: ConfigModels::default(),
            ollama: OllamaOptions::default(),
            llm: LlmOptions::default(),
            extra: Default::default(),
        }
    }
}

const MISSING_LAYOUT_MSG: &str = "config.json is missing required layout_version. New installs: add \"layout_version\": 2. Legacy vaults: add \"layout_version\": 1, then `chronicle backup` + `migrate-journal-v2 --apply --i-have-backup` (which bumps to 2). Or restore a known-good config.json.";

impl ChronicleConfig {
    /// Defaults with layout_version=2 (missing file case).
    pub fn defaults() -> Self {
        Self::default()
    }

    /// Deep-merge raw JSON onto defaults exactly like load_config:
    /// models/ollama shallow-update; llm scalars replace; grok/vertex sub-merged.
    fn merged_from(raw: &Map<String, Value>) -> Result<Self, ChronicleError> {
        if !raw.contains_key("layout_version") {
            return Err(ChronicleError::Layout(MISSING_LAYOUT_MSG.to_string()));
        }
        let mut cfg = Self::defaults();
        if let Some(m) = raw.get("models").and_then(Value::as_object) {
            let obj = serde_json::to_value(&cfg.models).unwrap();
            let mut merged = obj.as_object().cloned().unwrap_or_default();
            for (k, v) in m {
                merged.insert(k.clone(), v.clone());
            }
            cfg.models = serde_json::from_value(Value::Object(merged))
                .map_err(|e| ChronicleError::msg(format!("models: {e}")))?;
        }
        if let Some(m) = raw.get("ollama").and_then(Value::as_object) {
            let obj = serde_json::to_value(&cfg.ollama).unwrap();
            let mut merged = obj.as_object().cloned().unwrap_or_default();
            for (k, v) in m {
                merged.insert(k.clone(), v.clone());
            }
            cfg.ollama = serde_json::from_value(Value::Object(merged))
                .map_err(|e| ChronicleError::msg(format!("ollama: {e}")))?;
        }
        if let Some(l) = raw.get("llm").and_then(Value::as_object) {
            // Scalars replace; grok/vertex deep-merge onto defaults.
            let mut grok = serde_json::to_value(&cfg.llm.grok).unwrap();
            if let Some(g) = l.get("grok").and_then(Value::as_object) {
                merge_objects(grok.as_object_mut().unwrap(), g);
            }
            let mut vertex = serde_json::to_value(&cfg.llm.vertex).unwrap();
            if let Some(v) = l.get("vertex").and_then(Value::as_object) {
                merge_objects(vertex.as_object_mut().unwrap(), v);
            }
            cfg.llm.grok = serde_json::from_value(grok).map_err(|e| ChronicleError::msg(e.to_string()))?;
            cfg.llm.vertex = serde_json::from_value(vertex).map_err(|e| ChronicleError::msg(e.to_string()))?;
            for (k, v) in l {
                match k.as_str() {
                    "grok" | "vertex" => {}
                    _ => set_field_by_name(&mut cfg.llm, k, v),
                }
            }
        }
        // Remaining top-level fields.
        let mut map = raw.clone();
        map.remove("models");
        map.remove("ollama");
        map.remove("llm");
        let base = serde_json::to_value(&cfg).unwrap();
        let mut merged = base.as_object().cloned().unwrap_or_default();
        for (k, v) in &map {
            merged.insert(k.clone(), v.clone());
        }
        cfg = serde_json::from_value(Value::Object(merged))
            .map_err(|e| ChronicleError::msg(format!("config: {e}")))?;
        Ok(cfg)
    }
}

fn merge_objects(dst: &mut Map<String, Value>, src: &Map<String, Value>) {
    for (k, v) in src {
        dst.insert(k.clone(), v.clone());
    }
}

fn set_field_by_name(llm: &mut LlmOptions, key: &str, value: &Value) {
    match key {
        "provider" => {
            if let Some(s) = value.as_str() {
                llm.provider = s.to_string();
            }
        }
        "cloud_consent" => {
            if let Some(b) = value.as_bool() {
                llm.cloud_consent = b;
            }
        }
        "vision_cloud_consent" => {
            if let Some(b) = value.as_bool() {
                llm.vision_cloud_consent = b;
            }
        }
        _ => {}
    }
}

pub fn config_path(root: &Path) -> std::path::PathBuf {
    root.join("config.json")
}

/// load_config: missing file → defaults; missing layout_version key → hard error.
pub fn load_config(root: &Path) -> Result<ChronicleConfig, ChronicleError> {
    let path = config_path(root);
    if !path.is_file() {
        return Ok(ChronicleConfig::defaults());
    }
    let value = crate::paths::read_json(&path)?;
    let obj = value
        .as_object()
        .ok_or_else(|| ChronicleError::msg("config.json must be an object"))?
        .clone();
    ChronicleConfig::merged_from(&obj)
}

/// ensure_config: load + persist when the file is absent.
pub fn ensure_config(root: &Path) -> Result<ChronicleConfig, ChronicleError> {
    let path = config_path(root);
    if !path.is_file() {
        let cfg = ChronicleConfig::defaults();
        save_config_at(&path, &cfg)?;
        return Ok(cfg);
    }
    load_config(root)
}

/// save_config writes model_dump(exclude_none=True): nulls vanish from disk.
pub fn save_config(root: &Path, cfg: &ChronicleConfig) -> Result<(), ChronicleError> {
    save_config_at(&config_path(root), cfg)
}

fn save_config_at(path: &Path, cfg: &ChronicleConfig) -> Result<(), ChronicleError> {
    let mut value = serde_json::to_value(cfg).map_err(|e| ChronicleError::msg(e.to_string()))?;
    strip_nulls(value.as_object_mut().expect("config object"));
    atomic_write_json(path, &value)
}

fn strip_nulls(obj: &mut Map<String, Value>) {
    obj.retain(|_, v| !v.is_null());
    for (_, v) in obj.iter_mut() {
        if let Some(inner) = v.as_object_mut() {
            strip_nulls(inner);
        }
    }
}

/// require_layout_version — exact-equality gate with the verbatim message.
pub fn require_layout_version(cfg: &ChronicleConfig) -> Result<i64, ChronicleError> {
    let version = if cfg.layout_version == 0 { 1 } else { cfg.layout_version };
    if version != CURRENT_LAYOUT_VERSION {
        return Err(ChronicleError::Layout(format!(
            "Vault layout_version={version} is incompatible with this Chronicle build (requires layout_version={CURRENT_LAYOUT_VERSION} for file-once paths: _capture/, _attachments/, 40-Journal/). Copy the vault if needed, run `chronicle backup` (zip outside Syncthing), then `chronicle migrate-journal-v2 --apply --i-have-backup`. Co-release APK + CLI so phone and Mac agree on paths."
        )));
    }
    Ok(version)
}

/// Convenience used by CLI paths where only the dir is known.
pub fn load_config_resolved(dir: Option<&Path>) -> Result<(std::path::PathBuf, ChronicleConfig), ChronicleError> {
    let root = resolve_chronicle_dir(dir)?;
    let cfg = load_config(&root)?;
    Ok((root, cfg))
}

pub fn default_config_json() -> Value {
    json!({
        "version": 1,
        "layout_version": CURRENT_LAYOUT_VERSION,
        "timezone": "UTC",
        "vault_mirror": null,
        "models": ConfigModels::default(),
        "ollama": OllamaOptions::default(),
        "llm": LlmOptions::default(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_file_gives_defaults_with_layout_2() {
        let dir = tempfile::tempdir().unwrap();
        let cfg = load_config(dir.path()).unwrap();
        assert_eq!(cfg.layout_version, 2);
        assert_eq!(cfg.models.llm, "maxwell1500/ornith-35b:Q4_K_M");
        assert_eq!(cfg.llm.provider, "ollama");
    }

    #[test]
    fn missing_layout_key_is_hard_error() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("config.json"), "{}\n").unwrap();
        let err = load_config(dir.path()).unwrap_err();
        assert!(matches!(err, ChronicleError::Layout(_)));
        assert!(err.to_string().contains("layout_version"));
    }

    #[test]
    fn deep_merge_llm_subobjects_and_save_drops_nulls() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path().join("config.json"),
            r#"{"layout_version":2,"timezone":"Asia/Kolkata","llm":{"provider":"grok","grok":{"model":"grok-3"}}}"#,
        )
        .unwrap();
        let cfg = load_config(dir.path()).unwrap();
        assert_eq!(cfg.timezone, "Asia/Kolkata");
        assert_eq!(cfg.llm.provider, "grok");
        assert_eq!(cfg.llm.grok.model.as_deref(), Some("grok-3"));
        assert_eq!(cfg.llm.grok.base_url, "https://api.x.ai/v1");
        assert_eq!(cfg.models.embed, "nomic-embed-text");

        save_config(dir.path(), &cfg).unwrap();
        let on_disk = crate::paths::read_json(&dir.path().join("config.json")).unwrap();
        assert!(on_disk.get("vault_mirror").is_none(), "nulls dropped on save");
        assert_eq!(on_disk["llm"]["provider"], "grok");
    }

    #[test]
    fn layout_gate_message_exact_prefix() {
        let mut cfg = ChronicleConfig::defaults();
        cfg.layout_version = 1;
        let err = require_layout_version(&cfg).unwrap_err();
        assert!(err.to_string().starts_with("Vault layout_version=1 is incompatible"));
    }
}
