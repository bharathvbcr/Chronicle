//! Brain: enrich → tags → graph → insights → prompts (brain/ package port).

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use chrono::{Datelike, Duration as ChronoDuration, NaiveDate};
use regex::Regex;
use serde_json::{json, Map, Value};

use crate::config::{ensure_config, ChronicleConfig};
use crate::curation;
use crate::entries as store;
use crate::errors::ChronicleError;
use crate::lock::vault_lock;
use crate::models::Entry;
use crate::ollama;
use crate::paths::{atomic_write_json, resolve_chronicle_dir};
use crate::provider::{ChatOpts, ChatProvider, ContextLimits};
use crate::timeutil::{entry_day, now_iso};
use crate::LlmRuntime;

pub fn special_tag_re() -> Regex {
    Regex::new(r"^(#plan|future:\d{4}-\d{2}-\d{2}|prompt:.+)$").unwrap()
}

pub fn entity_re() -> Regex {
    Regex::new(r"\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)\b").unwrap()
}

pub fn word_re() -> Regex {
    Regex::new(r"[a-zA-Z][a-zA-Z0-9_/-]{2,}").unwrap()
}

pub const ENRICH_BATCH_SIZE: usize = 8;
pub const LINK_BATCH_SIZE: usize = 24;

/// summary_line(text, max_len): first line; ellipsis at max_len-1 + "…".
pub fn summary_line(text: &str, max_len: usize) -> String {
    let s = text.lines().next().unwrap_or_default().trim();
    if s.chars().count() > max_len {
        let cut: String = s.chars().take(max_len.saturating_sub(1)).collect();
        format!("{cut}…")
    } else {
        s.to_string()
    }
}

fn tag_key(t: &str) -> String {
    if t == "#plan" { "#plan".to_string() } else { t.trim_start_matches('#').to_lowercase() }
}

// ---------------------------------------------------------------------------
// Enrichment
// ---------------------------------------------------------------------------

const HINT_WORK: [&str; 5] = ["work", "meeting", "deadline", "project", "chronicle"];
const HINT_HEALTH: [&str; 5] = ["walk", "run", "gym", "sleep", "health"];
const HINT_DREAM: [&str; 3] = ["dream", "nightmare", "lucid"];
const HINT_IDEA: [&str; 3] = ["idea", "sketch", "prototype"];

pub fn heuristic_auto_tags(e: &Entry) -> Vec<String> {
    let mut candidates: Vec<String> = e.tags.clone();
    if e.kind == "dream" {
        candidates.push("dream".into());
    }
    if e.kind == "idea" {
        candidates.push("idea".into());
    }
    let lower = e.text.to_lowercase();
    let push_hint = |cands: &mut Vec<String>, hints: &[&str], tag: &str| {
        if hints.iter().any(|h| lower.contains(h)) {
            cands.push(tag.to_string());
        }
    };
    push_hint(&mut candidates, &HINT_WORK, "work");
    push_hint(&mut candidates, &HINT_HEALTH, "health");
    push_hint(&mut candidates, &HINT_DREAM, "dream");
    push_hint(&mut candidates, &HINT_IDEA, "idea");
    let special = special_tag_re();
    let mut out: Vec<String> = candidates
        .into_iter()
        .filter(|t| !special.is_match(t) && !e.tags.contains(t))
        .collect();
    out.sort();
    out.dedup();
    out
}

pub fn heuristic_entities(e: &Entry) -> Vec<Value> {
    let mut seen: std::collections::HashSet<String> = Default::default();
    let mut out = Vec::new();
    for m in entity_re().captures_iter(&e.text) {
        let name = m.get(1).unwrap().as_str().to_string();
        let key = name.to_lowercase();
        if seen.insert(key) {
            out.push(json!({"name": name, "kind": "person"}));
            if out.len() >= 8 {
                break;
            }
        }
    }
    out
}

fn heuristic_enrich(e: &Entry) -> Value {
    json!({
        "auto_tags": heuristic_auto_tags(e),
        "summary_line": summary_line(&e.text, 120),
        "entities": heuristic_entities(e),
    })
}

fn normalize_row(row: &Value, fallback: &Value) -> Value {
    let fb_auto = fallback
        .get("auto_tags")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut auto: Vec<String> = row
        .get("auto_tags")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|t| t.as_str())
                .map(|t| t.trim().to_lowercase())
                .filter(|t| !t.is_empty())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    auto.sort();
    auto.dedup();

    let summary = row
        .get("summary_line")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
        .chars()
        .take(200)
        .collect::<String>();

    let mut entities: Vec<Value> = Vec::new();
    if let Some(arr) = row.get("entities").and_then(Value::as_array) {
        for ent in arr {
            match ent {
                Value::String(s) => entities.push(json!({"name": s, "kind": "topic"})),
                Value::Object(o) => {
                    if let Some(name) = o.get("name").and_then(Value::as_str).filter(|n| !n.is_empty()) {
                        let kind = o.get("kind").and_then(Value::as_str).unwrap_or("topic");
                        entities.push(json!({"name": name, "kind": kind}));
                    }
                }
                _ => {}
            }
            if entities.len() >= 12 {
                break;
            }
        }
    }
    // Start from heuristic fallback; LLM values replace only when non-empty.
    let mut merged = match fallback.clone() {
        Value::Object(m) => m,
        _ => Map::new(),
    };
    if !auto.is_empty() {
        merged.insert("auto_tags".into(), json!(auto));
    }
    if !summary.is_empty() {
        merged.insert("summary_line".into(), json!(summary));
    }
    if !entities.is_empty() {
        merged.insert("entities".into(), json!(entities));
    }
    merged.entry("auto_tags".to_string()).or_insert_with(|| json!([]));
    merged.entry("summary_line".to_string()).or_insert_with(|| json!(""));
    merged.entry("entities".to_string()).or_insert_with(|| json!([]));
    Value::Object(merged)
}

fn parse_payload_rows(raw: &str) -> Option<HashMap<String, Value>> {
    use std::collections::HashMap;
    let data = ollama::extract_json(raw).ok()?;
    let obj = data.get("entries")?.as_object()?.clone();
    Some(obj.into_iter().collect())
}

#[allow(clippy::too_many_arguments)]
fn enrich_batch(
    provider: &dyn ChatProvider,
    model: &str,
    limits: ContextLimits,
    batch: &[&Entry],
) -> HashMap<String, Value> {
    let payload: Vec<Value> = batch
        .iter()
        .map(|e| {
            json!({
                "id": e.id,
                "text": e.text.chars().take(3500).collect::<String>(),
                "tags": e.tags,
                "type": e.kind,
            })
        })
        .collect();
    let opts = ChatOpts {
        model: Some(model.to_string()),
        temperature: 0.6,
        format_json: true,
        num_predict: Some(((400 * payload.len()) as i64).min(3200)),
        num_ctx: limits.num_ctx_brain,
        ..Default::default()
    };
    let messages = vec![
        json!({"role": "system", "content": crate::prompts::load_agent("brain_extract")}),
        json!({"role": "user", "content": serde_json::to_string(&payload).unwrap_or_default()}),
    ];
    match provider.chat(messages, &opts) {
        Ok(raw) => parse_payload_rows(&raw).unwrap_or_default(),
        Err(err) => {
            ollama::log_line("DEBUG", &format!("batch enrich parse failed: {err}"));
            HashMap::new()
        }
    }
}

fn enrich_single(
    provider: &dyn ChatProvider,
    model: &str,
    limits: ContextLimits,
    e: &Entry,
) -> Option<Value> {
    let payload = json!([{
        "id": e.id,
        "text": e.text.chars().take(4000).collect::<String>(),
        "tags": e.tags,
        "type": e.kind,
    }]);
    let opts = ChatOpts {
        model: Some(model.to_string()),
        temperature: 0.6,
        format_json: true,
        num_predict: Some(600),
        num_ctx: limits.num_ctx_enrich,
        ..Default::default()
    };
    let messages = vec![
        json!({"role": "system", "content": crate::prompts::load_agent("brain_extract")}),
        json!({"role": "user", "content": serde_json::to_string(&payload).unwrap_or_default()}),
    ];
    let raw = provider.chat(messages, &opts).ok()?;
    let data = ollama::extract_json(&raw).ok()?;
    let obj = data.as_object()?;
    let row = obj
        .get("entries")
        .and_then(|m| m.get(&e.id))
        .cloned()
        .or_else(|| {
            // Flat-object tolerance.
            if obj.contains_key("auto_tags")
                || obj.contains_key("summary_line")
                || obj.contains_key("entities")
            {
                Some(data.clone())
            } else {
                None
            }
        })?;
    Some(row)
}

pub type EnrichMap = HashMap<String, Value>;

pub fn build_enrich(
    root: &Path,
    cfg: &ChronicleConfig,
    rt: &LlmRuntime,
    entries: &[Entry],
    dry_run: bool,
) -> Result<(), ChronicleError> {
    let built = crate::provider::build_provider(cfg).ok();
    let provider: Option<&dyn ChatProvider> = built.as_ref().map(|(_, p)| p.as_ref());
    let pname = crate::provider::provider_name(cfg);
    let limits = crate::provider::limits_for(&pname);
    let model = cfg.models.llm.clone();

    let mut result: EnrichMap = entries.iter().map(|e| (e.id.clone(), heuristic_enrich(e))).collect();

    if let Some(provider) = provider {
        let with_text: Vec<&Entry> = entries.iter().filter(|e| !e.text.trim().is_empty()).collect();
        for chunk in with_text.chunks(ENRICH_BATCH_SIZE) {
            let rows = enrich_batch(provider, &model, limits, chunk);
            for e in chunk {
                if let Some(row) = rows.get(&e.id) {
                    result.insert(e.id.clone(), normalize_row(row, &result[e.id.as_str()]));
                }
            }
        }
    }

    // Month buckets by entry_day.
    let mut buckets: std::collections::BTreeMap<String, Vec<&Entry>> = Default::default();
    for e in entries {
        let day = entry_day(&e.ts, &e.id, &cfg.timezone);
        buckets
            .entry(format!("{:04}-{:02}", day.year(), day.month()))
            .or_default()
            .push(e);
    }
    let generated = now_iso();
    for (month, list) in buckets {
        let mut entries_obj = Map::new();
        for e in list {
            entries_obj.insert(e.id.clone(), result[e.id.as_str()].clone());
        }
        let payload = json!({
            "version": 1,
            "generated": generated,
            "month": month,
            "entries": entries_obj,
        });
        let path = root.join("brain").join("enrich").join(format!("{month}.json"));
        if !dry_run {
            atomic_write_json(&path, &payload)?;
            ollama::log_line("INFO", &format!("Wrote enrich {} ({} entries)", path.display(), payload["entries"].as_object().map(|m| m.len()).unwrap_or(0)));
        } else {
            ollama::log_line("INFO", &format!("[dry-run] would write enrich {}", path.display()));
        }
    }
    Ok(())
}

/// load_all_enrich — flatten brain/enrich/*.json into eid→row.
pub fn load_all_enrich(root: &Path) -> EnrichMap {
    let dir = root.join("brain").join("enrich");
    let mut files: Vec<PathBuf> = walk_files(&dir);
    files.sort();
    let mut out = EnrichMap::new();
    for f in files {
        let Ok(bytes) = std::fs::read(&f) else { continue };
        let v: Value = match serde_json::from_slice(&bytes) {
            Ok(v) => v,
            Err(e) => {
                ollama::log_line("WARNING", &format!("Bad enrich file {}: {e}", f.display()));
                continue;
            }
        };
        if let Some(entries) = v.get("entries").and_then(Value::as_object) {
            for (k, val) in entries {
                out.insert(k.clone(), val.clone());
            }
        }
    }
    out
}

fn walk_files(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    crate::paths::walk_files_filtered(dir, &mut out, 0, &|_p, _name| true);
    out
}

// ---------------------------------------------------------------------------
// Tags
// ---------------------------------------------------------------------------

pub fn build_tags(
    root: &Path,
    entries: &[Entry],
    enrich: &EnrichMap,
    dry_run: bool,
) -> Result<PathBuf, ChronicleError> {
    use std::collections::BTreeMap;
    let special = special_tag_re();
    let mut aliases: BTreeMap<String, Vec<String>> = Default::default();
    for op in curation::read_ops(root) {
        if op.op == "merge" {
            if let (Some(f), Some(i)) = (&op.from_id, &op.into) {
                let src = f.strip_prefix("topic:").unwrap_or(f).to_string();
                let dst = i.strip_prefix("topic:").unwrap_or(i).to_string();
                if !src.is_empty() && !dst.is_empty() {
                    aliases.entry(dst).or_default().push(src);
                }
            }
        }
    }
    let mut counts: BTreeMap<String, i64> = BTreeMap::new();
    for e in entries {
        for t in &e.tags {
            if special.is_match(t) && t.starts_with("future:") {
                continue;
            }
            if t.starts_with("prompt:") {
                continue;
            }
            let mut canonical = tag_key(t);
            for (dst, srcs) in &aliases {
                if srcs.iter().any(|s| *s == canonical) {
                    canonical = dst.clone();
                    break;
                }
            }
            *counts.entry(canonical).or_default() += 1;
        }
        if let Some(en) = enrich.get(&e.id) {
            for t in en.get("auto_tags").and_then(Value::as_array).unwrap_or(&vec![]) {
                if let Some(ts) = t.as_str() {
                    *counts.entry(ts.to_lowercase()).or_default() += 1;
                }
            }
        }
    }
    let mut tags: Vec<Value> = Vec::new();
    for (canonical, count) in counts {
        let alias_list = aliases
            .get(&canonical)
            .map(|v| {
                let mut s = v.clone();
                s.sort();
                s.dedup();
                s
            })
            .unwrap_or_default();
        tags.push(json!({
            "canonical": canonical,
            "aliases": alias_list,
            "parent": canonical.split_once('/').map(|(p, _)| p.to_string()),
            "count": count,
        }));
    }
    let payload = json!({"version": 1, "generated": now_iso(), "tags": tags});
    let path = root.join("brain").join("tags.json");
    if !dry_run {
        atomic_write_json(&path, &payload)?;
    }
    ollama::log_line(
        "INFO",
        &format!(
            "{} tags.json ({} tags)",
            if dry_run { "[dry-run] would write" } else { "Wrote" },
            tags.len()
        ),
    );
    Ok(path)
}

// ---------------------------------------------------------------------------
// Graph
// ---------------------------------------------------------------------------

fn topic_id(tag: &str) -> String {
    format!("topic:{}", tag.trim_start_matches('#').to_lowercase())
}

fn entry_node(e: &Entry) -> Value {
    let label = summary_line(&e.text, 60);
    json!({
        "id": format!("entry:{}", e.id),
        "kind": "entry",
        "label": if label.is_empty() { e.id.clone() } else { label },
        "entry_id": e.id,
        "ts": e.ts,
        "weight": 1.0,
    })
}

struct LlmLinks {
    concepts: Vec<Value>,
    links: Vec<Value>,
}

#[allow(clippy::too_many_arguments)]
fn llm_concept_links(
    cfg: &ChronicleConfig,
    provider: Option<&dyn ChatProvider>,
    limits: ContextLimits,
    entries: &[Entry],
    enrich: &EnrichMap,
    seed_concepts: &[Value],
) -> LlmLinks {
    let empty = LlmLinks { concepts: vec![], links: vec![] };
    let Some(provider) = provider else { return empty };
    if entries.is_empty() || crate::prompts::load_agent("brain_link").is_empty() {
        return empty;
    }
    let mut concept_seed: Vec<Value> = seed_concepts
        .iter()
        .filter_map(|n| {
            Some(json!({
                "id": n.get("id")?,
                "label": n.get("label")?,
                "kind": n.get("kind")?,
            }))
        })
        .collect();
    let mut new_concepts: Vec<Value> = Vec::new();
    let mut links: Vec<Value> = Vec::new();
    let rel_ok = ["about", "related", "continues", "mentions", "manual"];

    for chunk in entries.chunks(LINK_BATCH_SIZE) {
        let payload_entries: Vec<Value> = chunk
            .iter()
            .map(|e| {
                let summary = enrich
                    .get(&e.id)
                    .and_then(|r| r.get("summary_line"))
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                    .map(str::to_string)
                    .unwrap_or_else(|| summary_line(&e.text, 160));
                json!({
                    "id": e.id,
                    "summary": summary,
                    "tags": e.tags,
                    "entities": enrich.get(&e.id).and_then(|r| r.get("entities")).cloned().unwrap_or(json!([])),
                })
            })
            .collect();
        let payload = json!({
            "entries": payload_entries,
            "concepts": concept_seed.iter().take(80).collect::<Vec<_>>(),
        });
        let opts = ChatOpts {
            model: Some(cfg.models.llm.clone()),
            temperature: 0.6,
            format_json: true,
            num_predict: Some(1800),
            num_ctx: limits.num_ctx_brain,
            ..Default::default()
        };
        let messages = vec![
            json!({"role": "system", "content": crate::prompts::load_agent("brain_link")}),
            json!({"role": "user", "content": serde_json::to_string(&payload).unwrap_or_default()}),
        ];
        let raw = match provider.chat(messages, &opts) {
            Ok(r) => r,
            Err(_) => continue,
        };
        let Ok(data) = ollama::extract_json(&raw) else { continue };
        for c in data.get("concepts").and_then(Value::as_array).unwrap_or(&vec![]).clone() {
            let id = c.get("id").and_then(Value::as_str).unwrap_or_default().to_string();
            let label = c.get("label").and_then(Value::as_str).unwrap_or_default().to_string();
            if id.is_empty() || label.is_empty() {
                continue;
            }
            let kind_raw = c.get("kind").and_then(Value::as_str).unwrap_or("concept");
            let kind = if rel_ok_no(kind_raw) { kind_raw } else { "concept" };
            let node = json!({
                "id": id,
                "kind": if kind == "topic" { "topic" } else { kind },
                "label": label,
                "weight": c.get("weight").and_then(Value::as_f64).unwrap_or(1.0),
            });
            concept_seed.push(json!({"id": id, "label": label, "kind": node["kind"]}));
            new_concepts.push(node);
        }
        for link in data.get("links").and_then(Value::as_array).unwrap_or(&vec![]).clone() {
            let from_id = link.get("from").and_then(Value::as_str).unwrap_or_default().trim().to_string();
            let to = link.get("to").and_then(Value::as_str).unwrap_or_default().trim().to_string();
            if from_id.is_empty() || to.is_empty() {
                continue;
            }
            let mut rel = link.get("rel").and_then(Value::as_str).unwrap_or("related").trim().to_string();
            if !rel_ok.contains(&rel.as_str()) {
                rel = "related".into();
            }
            let score = link
                .get("score")
                .and_then(Value::as_f64)
                .unwrap_or(0.6);
            let score = if score.is_nan() { 0.6 } else { score };
            if score < 0.5 {
                continue;
            }
            links.push(json!({"from": from_id, "to": to, "rel": rel, "score": score.min(1.0)}));
        }
    }
    LlmLinks { concepts: new_concepts, links }
}

fn rel_ok_no(kind: &str) -> bool {
    matches!(kind, "person" | "place" | "project" | "concept" | "topic")
}

pub struct GraphOutcome {
    pub path: PathBuf,
    pub archive_paths: Vec<PathBuf>,
}

pub fn build_graph(
    root: &Path,
    cfg: &ChronicleConfig,
    rt: &LlmRuntime,
    provider: Option<&dyn ChatProvider>,
    limits: ContextLimits,
    entries: &[Entry],
    enrich: &EnrichMap,
    dry_run: bool,
) -> Result<GraphOutcome, ChronicleError> {
    let cutoff = chrono::Utc::now().date_naive() - ChronoDuration::days(365);

    let mut nodes: std::collections::HashMap<String, Value> = Default::default();
    let mut edges: Vec<Value> = Vec::new();
    let mut archive_nodes: std::collections::BTreeMap<i32, std::collections::BTreeMap<String, Value>> = Default::default();

    // Topic weights + entity nodes.
    let mut tag_weights: std::collections::HashMap<String, f64> = Default::default();
    for e in entries {
        for t in &e.tags {
            if t.starts_with("future:") || t.starts_with("prompt:") {
                continue;
            }
            *tag_weights.entry(tag_key(t)).or_default() += 1.0;
        }
        if let Some(en) = enrich.get(&e.id) {
            for t in en.get("auto_tags").and_then(Value::as_array).unwrap_or(&vec![]) {
                if let Some(ts) = t.as_str() {
                    *tag_weights.entry(ts.to_lowercase()).or_default() += 0.5;
                }
            }
            for ent in en.get("entities").and_then(Value::as_array).unwrap_or(&vec![]) {
                let Some(name) = ent.get("name").and_then(Value::as_str).filter(|n| !n.is_empty()) else { continue };
                let mut kind = ent.get("kind").and_then(Value::as_str).unwrap_or("concept").to_string();
                if !rel_ok_no(&kind) {
                    kind = "concept".into();
                }
                let eid = format!("{}:{}", kind, name.to_lowercase().replace(' ', "-"));
                let prev_w = nodes.get(&eid).and_then(|n| n.get("weight")).and_then(Value::as_f64).unwrap_or(0.0);
                nodes.insert(
                    eid.clone(),
                    json!({
                        "id": eid,
                        "kind": kind,
                        "label": name,
                        "weight": prev_w + 1.0,
                    }),
                );
                edges.push(json!({
                    "from": format!("entry:{}", e.id),
                    "to": eid,
                    "rel": "mentions",
                    "score": 0.7,
                }));
            }
        }
    }
    for (tag, w) in &tag_weights {
        let tid = topic_id(tag);
        nodes.insert(tid.clone(), json!({"id": tid, "kind": "topic", "label": tag, "weight": w}));
    }

    // Entry nodes + about edges + plan tracking.
    let mut plan_by_tag: std::collections::HashMap<String, Vec<&Entry>> = Default::default();
    for e in entries {
        let day = entry_day(&e.ts, &e.id, &cfg.timezone);
        let node = entry_node(e);
        if day >= cutoff || e.tags.contains(&"#plan".to_string()) {
            nodes.insert(node["id"].as_str().unwrap().to_string(), node);
        } else {
            archive_nodes
                .entry(day.year())
                .or_default()
                .insert(node["id"].as_str().unwrap().to_string(), node);
        }
        for t in &e.tags {
            if t.starts_with("future:") || t.starts_with("prompt:") {
                continue;
            }
            edges.push(json!({
                "from": format!("entry:{}", e.id),
                "to": topic_id(&tag_key(t)),
                "rel": "about",
                "score": 1.0,
            }));
            if t == "#plan" {
                for other in &e.tags {
                    if other != "#plan" && !other.starts_with("future:") && !other.starts_with("prompt:") {
                        plan_by_tag
                            .entry(other.trim_start_matches('#').to_lowercase())
                            .or_default()
                            .push(e);
                    }
                }
            }
        }
        if let Some(en) = enrich.get(&e.id) {
            for t in en.get("auto_tags").and_then(Value::as_array).unwrap_or(&vec![]) {
                if let Some(ts) = t.as_str() {
                    edges.push(json!({
                        "from": format!("entry:{}", e.id),
                        "to": topic_id(&ts.to_lowercase()),
                        "rel": "about",
                        "score": 0.5,
                    }));
                }
            }
        }
    }

    // continues edges: #plan → later same-tag progress.
    for e in entries {
        if e.tags.contains(&"#plan".to_string()) {
            continue;
        }
        for t in &e.tags {
            let key = t.trim_start_matches('#').to_lowercase();
            for plan in plan_by_tag.get(&key).map(|v| v.as_slice()).unwrap_or(&[]) {
                if plan.ts < e.ts {
                    edges.push(json!({
                        "from": format!("entry:{}", plan.id),
                        "to": format!("entry:{}", e.id),
                        "rel": "continues",
                        "score": 0.8,
                    }));
                }
            }
        }
    }

    // LLM linking; offline shared-tag related fallback when it yields nothing.
    let seed_concepts: Vec<Value> = nodes
        .values()
        .filter(|n| matches!(n.get("kind").and_then(Value::as_str), Some("concept" | "project" | "person" | "place")))
        .cloned()
        .collect();
    let llm = llm_concept_links(cfg, provider, limits, entries, enrich, &seed_concepts);
    if !llm.links.is_empty() {
        let n_concepts = llm.concepts.len();
        let n_links = llm.links.len();
        for c in llm.concepts.clone() {
            let cid = c["id"].as_str().unwrap_or_default().to_string();
            if let Some(existing) = nodes.get_mut(&cid) {
                let w = existing.get("weight").and_then(Value::as_f64).unwrap_or(0.0)
                    + c.get("weight").and_then(Value::as_f64).unwrap_or(1.0);
                existing["weight"] = json!(w);
                if let Some(l) = c.get("label").and_then(Value::as_str).filter(|l| !l.is_empty()) {
                    existing["label"] = json!(l);
                }
            } else {
                nodes.insert(cid, c);
            }
        }
        edges.extend(llm.links);
        ollama::log_line(
            "INFO",
            &format!("LLM graph linking added {n_concepts} concepts, {n_links} links"),
        );
    } else {
        let mut by_tag: std::collections::HashMap<String, Vec<String>> = Default::default();
        for e in entries {
            for t in &e.tags {
                if t.starts_with("future:") || t.starts_with("prompt:") || t == "#plan" {
                    continue;
                }
                by_tag
                    .entry(t.trim_start_matches('#').to_lowercase())
                    .or_default()
                    .push(e.id.clone());
            }
        }
        let mut related_seen: HashSet<(String, String)> = Default::default();
        for ids in by_tag.values_mut() {
            ids.sort();
            ids.dedup();
            for i in 0..ids.len() {
                for b in ids.iter().skip(i + 1).take(3) {
                    let a = &ids[i];
                    let pair = if a < b { (a.clone(), b.clone()) } else { (b.clone(), a.clone()) };
                    if !related_seen.insert(pair) {
                        continue;
                    }
                    edges.push(json!({
                        "from": format!("entry:{a}"),
                        "to": format!("entry:{b}"),
                        "rel": "related",
                        "score": 0.4,
                    }));
                }
            }
        }
    }

    // Deduplicate edges keeping strictly-higher score.
    let mut edge_map: std::collections::HashMap<(String, String, String), Value> = Default::default();
    let mut node_ids: HashSet<String> = nodes.keys().cloned().collect();
    for ed in edges {
        let from_id = ed["from"].as_str().unwrap_or_default().to_string();
        let to = ed["to"].as_str().unwrap_or_default().to_string();
        if from_id.starts_with("entry:") && !node_ids.contains(&from_id) {
            if !node_ids.contains(&to) && !to.starts_with("topic:") {
                continue;
            }
        }
        let key = (
            from_id,
            to,
            ed["rel"].as_str().unwrap_or_default().to_string(),
        );
        let better = edge_map.get(&key).is_none_or(|prev| {
            ed.get("score").and_then(Value::as_f64).unwrap_or(0.0)
                > prev.get("score").and_then(Value::as_f64).unwrap_or(0.0)
        });
        if better {
            edge_map.insert(key, ed);
        }
    }

    let mut nodes_vec: Vec<Value> = nodes.values().cloned().collect();
    nodes_vec.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
    let mut edges_vec: Vec<Value> = edge_map.into_values().collect();
    edges_vec.sort_by(|a, b| {
        let ka = (a["from"].as_str().unwrap_or_default(), a["to"].as_str().unwrap_or_default(), a["rel"].as_str().unwrap_or_default());
        let kb = (b["from"].as_str().unwrap_or_default(), b["to"].as_str().unwrap_or_default(), b["rel"].as_str().unwrap_or_default());
        ka.cmp(&kb)
    });

    let mut graph = json!({
        "version": 1,
        "generated": now_iso(),
        "nodes": nodes_vec,
        "edges": edges_vec,
    });

    // Preserve prior non-empty groups dict.
    let graph_path = root.join("brain").join("graph.json");
    if graph_path.is_file() {
        if let Ok(prev) = crate::paths::read_json(&graph_path) {
            if let Some(groups) = prev.get("groups").filter(|g| g.as_object().is_some_and(|o| !o.is_empty())) {
                graph["groups"] = groups.clone();
            }
        }
    }

    // Curation replay LAST.
    let ops = curation::read_ops(root);
    if !ops.is_empty() {
        curation::apply_ops_to_graph(&mut graph, &ops);
        ollama::log_line("INFO", &format!("Replayed {} curation ops", ops.len()));
    }

    // kb_meta stamp is migrate-tooling territory (python-retired); best-effort skip.
    let kb_meta = root.join("brain").join("kb_meta.json");
    if kb_meta.is_file() {
        ollama::log_line("WARNING", "kb_meta stamp skipped: migrate_kb tooling is not available in the native server");
    }

    // Archive writes (per-year JSON).
    let mut archive_paths: Vec<PathBuf> = Vec::new();
    for (year, nodes_by_year) in &archive_nodes {
        let mut archived: Vec<Value> = nodes_by_year.values().cloned().collect();
        archived.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
        let payload = json!({
            "version": 1,
            "generated": now_iso(),
            "nodes": archived,
            "edges": [],
        });
        let path = root.join("brain").join("graph-archive").join(format!("{year}.json"));
        if !dry_run {
            atomic_write_json(&path, &payload)?;
        }
        archive_paths.push(path);
    }

    if !dry_run {
        atomic_write_json(&graph_path, &graph)?;
    }
    ollama::log_line(
        "INFO",
        &format!(
            "{} graph.json ({} nodes, {} edges)",
            if dry_run { "[dry-run] would write" } else { "Wrote" },
            graph["nodes"].as_array().map(Vec::len).unwrap_or(0),
            graph["edges"].as_array().map(Vec::len).unwrap_or(0),
        ),
    );
    Ok(GraphOutcome { path: graph_path, archive_paths })
}

// ---------------------------------------------------------------------------
// Insights
// ---------------------------------------------------------------------------

pub fn build_insights(
    root: &Path,
    cfg: &ChronicleConfig,
    entries: &[Entry],
    enrich: &EnrichMap,
    dry_run: bool,
) -> Result<Vec<PathBuf>, ChronicleError> {
    let mut by_day: std::collections::BTreeMap<NaiveDate, Vec<&Entry>> = Default::default();
    for e in entries {
        let d = entry_day(&e.ts, &e.id, &cfg.timezone);
        by_day.entry(d).or_default().push(e);
    }
    for list in by_day.values_mut() {
        list.sort_by(|a, b| (&a.ts, &a.id).cmp(&(&b.ts, &b.id)));
    }

    // Time capsules grouped by due date.
    let mut capsules_by_due: std::collections::HashMap<String, Vec<Value>> = Default::default();
    for e in entries {
        for t in &e.tags {
            if let Some(due) = t.strip_prefix("future:") {
                capsules_by_due.entry(due.to_string()).or_default().push(json!({
                    "entry_id": e.id,
                    "due": due,
                    "text": summary_line(&e.text, 100),
                }));
            }
        }
    }

    let token_set = |e: &Entry| -> HashSet<String> {
        word_re()
            .find_iter(&e.text)
            .map(|m| m.as_str().to_lowercase())
            .chain(e.tags.iter().map(|t| t.to_lowercase()))
            .collect()
    };

    let all_tokenized: Vec<(String, HashSet<String>, HashSet<String>)> = entries
        .iter()
        .map(|e| (e.id.clone(), token_set(e), e.tags.iter().map(|t| t.to_lowercase()).collect()))
        .collect();

    let mut written: Vec<PathBuf> = Vec::new();
    for (day, day_entries) in &by_day {
        let moods: Vec<f64> = day_entries.iter().filter_map(|e| e.mood.map(|m| m as f64)).collect();
        let mood_avg = if moods.is_empty() { None } else { Some(moods.iter().sum::<f64>() / moods.len() as f64) };

        // Themes: ordered unique.
        let mut themes: Vec<String> = Vec::new();
        for e in day_entries {
            for t in &e.tags {
                if t.starts_with("future:") || t.starts_with("prompt:") {
                    continue;
                }
                let key = tag_key(t);
                if !themes.contains(&key) {
                    themes.push(key);
                }
            }
            if let Some(auto) = enrich.get(&e.id).and_then(|r| r.get("auto_tags")).and_then(Value::as_array) {
                for t in auto.iter().filter_map(Value::as_str) {
                    if !themes.contains(&t.to_string()) {
                        themes.push(t.to_string());
                    }
                }
            }
        }

        let summaries: Vec<String> = day_entries
            .iter()
            .filter_map(|e| {
                let s = enrich
                    .get(&e.id)
                    .and_then(|r| r.get("summary_line"))
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                    .map(str::to_string)
                    .unwrap_or_else(|| summary_line(&e.text, 120));
                if s.is_empty() { None } else { Some(s) }
            })
            .collect();
        let summary = if summaries.is_empty() {
            format!("{} entries", day_entries.len())
        } else {
            summaries.iter().take(3).cloned().collect::<Vec<_>>().join("; ")
        };

        // Related entries per day entry.
        let mut connections: Vec<Value> = Vec::new();
        let mut related_map: Vec<(String, Vec<String>)> = Vec::new();
        for e in day_entries {
            let (_, etoks, etags) = all_tokenized
                .iter()
                .find(|(id, _, _)| id == &e.id)
                .cloned()
                .unwrap_or_default();
            let mut scored: Vec<(f64, String)> = Vec::new();
            for (oid, otoks, otags) in &all_tokenized {
                if oid == &e.id {
                    continue;
                }
                let inter = etoks.intersection(otoks).count() as f64;
                let union = etoks.union(otoks).count() as f64;
                let jaccard = if union == 0.0 { 0.0 } else { inter / union };
                let shared = etags.intersection(otags).count();
                let score = jaccard + 0.15 * shared as f64;
                if score > 0.05 {
                    scored.push((score, oid.clone()));
                }
            }
            scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal).then(a.1.cmp(&b.1)));
            let tops: Vec<String> = scored.iter().take(5).map(|(_, id)| id.clone()).collect();
            for rid in tops.iter().take(2) {
                connections.push(json!({"from": e.id, "to": rid, "reason": "related"}));
            }
            related_map.push((e.id.clone(), tops));
        }
        connections.truncate(10);

        // on_this_day.
        let candidates = [
            *day - ChronoDuration::days(30),
            *day - ChronoDuration::days(365),
            NaiveDate::from_ymd_opt(day.year() - 1, day.month(), day.day()).unwrap_or(*day - ChronoDuration::days(365)),
        ];
        let mut on_this_day: Vec<String> = Vec::new();
        for cand in candidates {
            if let Some(list) = by_day.get(&cand) {
                for e in list {
                    if !on_this_day.contains(&e.id) {
                        on_this_day.push(e.id.clone());
                    }
                }
            }
        }
        for e in entries {
            if let Some(ts_head) = e.ts.get(..10) {
                if let Ok(ts_date) = chrono::NaiveDate::parse_from_str(ts_head, "%Y-%m-%d") {
                    if ts_date.month() == day.month() && ts_date.day() == day.day() && ts_date.year() < day.year() {
                        if !on_this_day.contains(&e.id) {
                            on_this_day.push(e.id.clone());
                        }
                    }
                }
            }
        }
        on_this_day.truncate(10);

        let payload = json!({
            "version": 1,
            "date": day.format("%Y-%m-%d").to_string(),
            "generated": now_iso(),
            "summary": summary,
            "mood_avg": mood_avg,
            "themes": themes,
            "connections": connections,
            "related_entries": related_map.into_iter()
                .map(|(k, v)| (k, json!(v)))
                .collect::<serde_json::Map<String, Value>>(),
            "on_this_day": on_this_day,
            "time_capsules": capsules_by_due.get(&day.format("%Y-%m-%d").to_string()).cloned().unwrap_or_default(),
        });
        let path = root
            .join("brain")
            .join("insights")
            .join(format!("{:04}", day.year()))
            .join(format!("{}.json", day.format("%Y-%m-%d")));
        if !dry_run {
            atomic_write_json(&path, &payload)?;
        }
        written.push(path);
    }
    ollama::log_line(
        "INFO",
        &format!(
            "{} {} insight files",
            if dry_run { "[dry-run] would write" } else { "Wrote" },
            written.len()
        ),
    );
    Ok(written)
}

// ---------------------------------------------------------------------------
// Prompts (reflection suggestions)
// ---------------------------------------------------------------------------

pub fn build_prompts(
    root: &Path,
    entries: &[Entry],
    enrich: &EnrichMap,
    dry_run: bool,
) -> Result<PathBuf, ChronicleError> {
    let mut tag_counts: std::collections::HashMap<String, i64> = Default::default();
    for e in entries {
        for t in &e.tags {
            if t.starts_with("future:") || t.starts_with("prompt:") || t == "#plan" {
                continue;
            }
            *tag_counts.entry(t.trim_start_matches('#').to_lowercase()).or_default() += 1;
        }
        if let Some(en) = enrich.get(&e.id) {
            for t in en.get("auto_tags").and_then(Value::as_array).unwrap_or(&vec![]) {
                if let Some(ts) = t.as_str() {
                    *tag_counts.entry(ts.to_lowercase()).or_default() += 1;
                }
            }
        }
    }
    let mut top: Vec<(i64, String)> = tag_counts.into_iter().map(|(t, c)| (c, t)).collect();
    top.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    let top: Vec<String> = top.into_iter().take(8).map(|(_, t)| t).collect();
    let mut prompts: Vec<Value> = Vec::new();
    for tag in &top {
        prompts.push(json!({
            "id": format!("reflect-{}", tag.replace('/', "-")),
            "text": format!("What have you learned about {tag} lately?"),
            "tag": tag,
        }));
        prompts.push(json!({
            "id": format!("next-{}", tag.replace('/', "-")),
            "text": format!("What is one small next step for {tag}?"),
            "tag": tag,
        }));
    }
    for p in [
        json!({"id": "gratitude", "text": "What are you grateful for today?", "tag": Value::Null}),
        json!({"id": "energy", "text": "What gave you energy this week?", "tag": Value::Null}),
        json!({"id": "open-loop", "text": "What open loop is weighing on you?", "tag": Value::Null}),
    ] {
        let pid = p["id"].as_str().unwrap();
        if !prompts.iter().any(|x| x["id"] == pid) {
            prompts.push(p);
        }
    }
    let path = root.join("brain").join("prompts.json");
    if !dry_run {
        atomic_write_json(&path, &json!({"version": 1, "generated": now_iso(), "prompts": prompts}))?;
    }
    Ok(path)
}

// ---------------------------------------------------------------------------
// run_brain orchestration
// ---------------------------------------------------------------------------

pub struct BrainDeps<'a> {
    pub rt: &'a LlmRuntime,
}

pub fn run_brain(root: &Path, deps: Option<BrainDeps<'_>>, dry_run: bool) -> Result<Value, ChronicleError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30)))?;
    let cfg = ensure_config(root)?;
    let entries = store::load_all_entries(root)?;
    let rt_owned;
    let rt: &LlmRuntime = match deps {
        Some(d) => d.rt,
        None => {
            rt_owned = ollama::runtime_from_config(&cfg);
            &rt_owned
        }
    };
    let built = crate::provider::build_provider(&cfg).ok();
    let provider: Option<&dyn ChatProvider> = built.as_ref().map(|(_, p)| p.as_ref());
    let pname = crate::provider::provider_name(&cfg);
    let limits = crate::provider::limits_for(&pname);

    build_enrich(root, &cfg, rt, &entries, dry_run)?;
    let enrich = load_all_enrich(root);
    build_tags(root, &entries, &enrich, dry_run)?;
    build_graph(root, &cfg, rt, provider, limits, &entries, &enrich, dry_run)?;
    let insight_files = build_insights(root, &cfg, &entries, &enrich, dry_run)?;
    build_prompts(root, &entries, &enrich, dry_run)?;
    Ok(json!({
        "entries": entries.len(),
        "insights": insight_files.len(),
        "dry_run": dry_run,
    }))
}

/// CLI convenience: resolve dir + config, run brain.
pub fn run_brain_resolved(dir: Option<&Path>, dry_run: bool) -> Result<Value, ChronicleError> {
    let root = resolve_chronicle_dir(dir)?;
    run_brain(&root, None, dry_run)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::Entry;

    fn entry(id: &str, ts: &str) -> Entry {
        Entry {
            version: 1,
            id: id.to_string(),
            ts: ts.to_string(),
            kind: "note".to_string(),
            text: "hello".to_string(),
            tags: vec![],
            images: vec![],
            audio: vec![],
            mood: None,
            processed: true,
            filed: false,
            filed_content_hash: None,
            filed_path: None,
            prose_edited: false,
            extra: Default::default(),
        }
    }

    #[test]
    fn insights_survive_multibyte_ts_and_id() {
        // Regression: `&e.ts[..10]` panicked when byte 10 was mid-character.
        let entries = vec![
            entry("aaaaaaaaa日本", "garbage"),
            entry("2026-08-20_101500-an", "日本語のタイムスタンプ"),
        ];
        let tmp = tempfile::tempdir().unwrap();
        let cfg = ChronicleConfig { timezone: "UTC".into(), ..Default::default() };
        let enrich: EnrichMap = HashMap::new();
        // Must not panic; returns written insight paths (empty graph is fine).
        let res = build_insights(tmp.path(), &cfg, &entries, &enrich, true);
        assert!(res.is_ok(), "{res:?}");
    }
}
