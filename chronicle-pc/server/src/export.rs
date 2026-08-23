//! chronosflow export bundle (export.py).

use std::path::Path;

use serde_json::{json, Value};

use crate::entries as store;
use crate::errors::ChronicleError;
use crate::paths::{atomic_write_json, resolve_chronicle_dir};

pub const SUPPORTED_FORMATS: [&str; 1] = ["chronosflow"];

pub fn run_export(dir: Option<&Path>, format: Option<&str>, out_path: Option<&Path>) -> Result<Value, ChronicleError> {
    let fmt = format.unwrap_or("chronosflow").to_lowercase();
    if !SUPPORTED_FORMATS.contains(&fmt.as_str()) {
        return Err(ChronicleError::msg(format!(
            "unsupported export format: {fmt} (supported: chronosflow)"
        )));
    }
    let root = resolve_chronicle_dir(dir)?;
    let entries: Vec<Value> = store::load_all_entries(&root)?
        .iter()
        .map(|e| e.to_api_value())
        .collect();
    let mut brain = serde_json::Map::new();
    for name in ["graph.json", "tags.json", "prompts.json"] {
        let p = root.join("brain").join(name);
        if let Ok(v) = crate::paths::read_json(&p) {
            brain.insert(name.to_string(), v);
        }
    }
    let payload = json!({
        "format": "chronosflow",
        "version": 1,
        "exported": crate::timeutil::now_iso(),
        "entries": entries,
        "brain": Value::Object(brain),
    });
    let target = out_path.map(Path::to_path_buf).unwrap_or_else(|| {
        root.join(format!(
            "chronicle-export-{}.chronosflow.json",
            chrono::Local::now().format("%Y%m%d")
        ))
    });
    atomic_write_json(&target, &payload)?;
    Ok(json!({ "format": fmt, "path": target.to_string_lossy() }))
}
