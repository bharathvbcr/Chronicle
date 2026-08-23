//! Image caption cache (captions.py) — index/image_captions.json storage.

use std::collections::HashMap;
use std::path::Path;

use serde_json::Value;

use crate::errors::ChronicleError;
use crate::paths::atomic_write_json;

pub fn captions_path(root: &Path) -> std::path::PathBuf {
    root.join("index").join("image_captions.json")
}

/// load_captions: tolerant; accepts wrapped {"captions": {…}} or flat map;
/// keeps only str→str pairs.
pub fn load_captions(root: &Path) -> HashMap<String, String> {
    let path = captions_path(root);
    let raw = match std::fs::read_to_string(&path) {
        Ok(r) => r,
        Err(_) => return HashMap::new(),
    };
    let data: Value = match serde_json::from_str(&raw) {
        Ok(d) => d,
        Err(e) => {
            eprintln!(
                "WARNING chronicle.captions: Failed to load image captions {}: {}",
                path.display(),
                e
            );
            return HashMap::new();
        }
    };
    let map_ref = data
        .get("captions")
        .and_then(Value::as_object)
        .map(|m| m.clone())
        .or_else(|| data.as_object().cloned());
    let Some(obj) = map_ref else { return HashMap::new() };
    obj.into_iter()
        .filter_map(|(k, v)| v.as_str().map(|s| (k, s.to_string())))
        .collect()
}

pub fn save_captions(root: &Path, captions: &HashMap<String, String>) -> Result<(), ChronicleError> {
    let mut inner = serde_json::Map::new();
    for (k, v) in captions {
        inner.insert(k.clone(), Value::String(v.clone()));
    }
    let payload = serde_json::json!({"version": 1, "captions": inner});
    atomic_write_json(&captions_path(root), &payload)
}
