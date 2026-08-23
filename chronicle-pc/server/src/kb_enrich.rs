//! KB enrichment cache (kb_enrich.py): load/save/index-prefix helpers.

use std::collections::HashMap;
use std::path::Path;

use serde_json::Value;

use crate::errors::ChronicleError;
use crate::paths::{atomic_write_json, content_hash};

pub fn enrich_cache_path(root: &Path) -> std::path::PathBuf {
    root.join("index").join("kb_enrich.json")
}

/// load_enrich_cache: missing/corrupt/non-dict → {"version":1,"notes":{}}.
pub fn load_enrich_cache(root: &Path) -> Value {
    let path = enrich_cache_path(root);
    let Ok(raw) = std::fs::read_to_string(&path) else {
        return serde_json::json!({"version": 1, "notes": {}});
    };
    match serde_json::from_str::<Value>(&raw) {
        Ok(v @ Value::Object(_)) => {
            let mut v = v;
            if v.get("notes").and_then(Value::as_object).is_none() {
                v["notes"] = serde_json::json!({});
            }
            if v.get("version").and_then(Value::as_i64).is_none() {
                v["version"] = serde_json::json!(1);
            }
            v
        }
        _ => serde_json::json!({"version": 1, "notes": {}}),
    }
}

pub fn save_enrich_cache(root: &Path, cache: &Value) -> Result<(), ChronicleError> {
    atomic_write_json(&enrich_cache_path(root), cache)
}

/// format_enrichment_prefix — retrieval prefix prepended at index time.
pub fn format_enrichment_prefix(entry: Option<&Value>) -> String {
    let Some(e) = entry.filter(|e| e.is_object()) else {
        return String::new();
    };
    let mut parts: Vec<String> = Vec::new();
    if let Some(summary) = e.get("summary").and_then(Value::as_str).filter(|s| !s.is_empty()) {
        parts.push(summary.to_string());
    }
    let skills: Vec<String> = e
        .get("skills")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default();
    if !skills.is_empty() {
        parts.push(format!("Skills: {}", skills.join(", ")));
    }
    let highlights: Vec<String> = e
        .get("highlights")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default();
    if !highlights.is_empty() {
        parts.push(format!(
            "Highlights:\n{}",
            highlights.iter().map(|h| format!("- {h}")).collect::<Vec<_>>().join("\n")
        ));
    }
    parts.join("\n")
}

#[derive(Debug, Clone)]
struct NormalizedEnrichment {
    summary: String,
    skills: Vec<String>,
    highlights: Vec<String>,
}

fn coerce_str_list(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str())
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

fn normalize_enrichment(data: &Value) -> Result<NormalizedEnrichment, ChronicleError> {
    let obj = data
        .as_object()
        .ok_or_else(|| ChronicleError::msg("enrichment must be a JSON object"))?;
    let summary = obj
        .get("summary")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
        .to_string();
    Ok(NormalizedEnrichment {
        summary,
        skills: coerce_str_list(obj.get("skills")),
        highlights: coerce_str_list(obj.get("highlights")),
    })
}

/// run_kb_enrich — LLM enrichment over PARA knowledge notes.
#[allow(clippy::too_many_arguments)]
pub fn run_kb_enrich(
    root: &Path,
    provider: Option<&dyn crate::provider::ChatProvider>,
    llm_model: &str,
    num_ctx_enrich: i64,
    force: bool,
) -> Result<serde_json::Value, ChronicleError> {
    use crate::provider::ChatOpts;

    let notes = crate::path_map::iter_knowledge_md(root);
    let total = notes.len();
    let pname = provider.map(crate::provider::ChatProvider::name).unwrap_or("");
    let Some(provider) = provider else {
        return Ok(serde_json::json!({
            "ok": true, "ollama": false, "provider": pname_or_empty(pname),
            "provider_ok": false, "enriched": 0, "skipped": total,
            "failed": 0, "total": total,
        }));
    };

    let mut cache = load_enrich_cache(root);
    let mut enriched = 0usize;
    let mut skipped = 0usize;
    let mut failed = 0usize;
    let mut live: Vec<String> = Vec::with_capacity(total);

    for (doc_id, path) in &notes {
        live.push(doc_id.clone());
        let Ok(raw_bytes) = std::fs::read(path) else { continue };
        let text = String::from_utf8_lossy(&raw_bytes).to_string();
        let ch = content_hash(&text);
        let prev = cache.get("notes").and_then(|n| n.get(doc_id)).cloned();
        if !force {
            if let Some(p) = prev.as_ref().filter(|p| p.is_object()) {
                let same_hash = p.get("content_hash").and_then(Value::as_str) == Some(ch.as_str());
                let has_summary = p.get("summary").map(Value::is_null) == Some(false);
                if same_hash && has_summary {
                    skipped += 1;
                    continue;
                }
            }
        }
        let clipped: String = text.chars().take(6000).collect();
        let messages = vec![
            serde_json::json!({"role": "system", "content": crate::prompts::KB_ENRICH_PROMPT}),
            serde_json::json!({"role": "user", "content": clipped}),
        ];
        let opts = ChatOpts {
            model: Some(llm_model.to_string()),
            temperature: 0.6,
            format_json: true,
            num_predict: Some(800),
            num_ctx: num_ctx_enrich,
            ..Default::default()
        };
        let result = provider.chat(messages, &opts).and_then(|raw| crate::ollama::extract_json(&raw));
        match result.and_then(|data| normalize_enrichment(&data)) {
            Ok(n) => {
                let payload = serde_json::json!({
                    "summary": n.summary,
                    "skills": n.skills,
                    "highlights": n.highlights,
                    "content_hash": ch,
                });
                cache["notes"][doc_id] = payload;
                enriched += 1;
            }
            Err(e) => {
                crate::provider::log_line(
                    "WARNING",
                    &format!("KB enrich failed for {doc_id}: {e}"),
                );
                failed += 1;
            }
        }
    }

    // Stale pruning.
    if let Some(notes_obj) = cache.get_mut("notes").and_then(Value::as_object_mut) {
        let keep: std::collections::HashSet<&String> = live.iter().collect();
        notes_obj.retain(|k, _| keep.contains(k));
    }
    save_enrich_cache(root, &cache)?;

    Ok(serde_json::json!({
        "ok": true,
        "ollama": pname == "ollama",
        "provider": pname_or_empty(pname),
        "provider_ok": true,
        "enriched": enriched,
        "skipped": skipped,
        "failed": failed,
        "total": total,
        "path": enrich_cache_path(root).to_string_lossy(),
    }))
}

fn pname_or_empty(p: &str) -> &str {
    if p.is_empty() { "unknown" } else { p }
}

pub type EnrichNotes = HashMap<String, Value>;
