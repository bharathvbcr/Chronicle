//! Topic pages + dream clustering (topics.py).

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use regex::Regex;
use serde_json::{json, Value};

use crate::entries as store;
use crate::models::Entry;
use crate::errors::ChronicleError;
use crate::notes::{topic_slug, write_if_changed};
use crate::paths::resolve_chronicle_dir;

const STOPWORDS: [&str; 26] = [
    "that", "this", "with", "from", "have", "were", "been", "they", "them", "then",
    "when", "what", "your", "about", "into", "just", "like", "there", "their",
    "would", "could", "should", "dream", "dreams", "dreamt", "dreamed",
];

pub fn topic_path(root: &Path, tag: &str) -> PathBuf {
    root.join("_system")
        .join("derived")
        .join("topics")
        .join(format!("{}.md", topic_slug(tag)))
}

pub fn run_topics(dir: Option<&Path>, dry_run: bool) -> Result<Value, ChronicleError> {
    let root = resolve_chronicle_dir(dir)?;
    let entries = store::load_all_entries(&root)?;

    // Tag buckets exclude future:/prompt:.
    let mut buckets: HashMap<String, Vec<&Entry>> = HashMap::new();
    for e in &entries {
        for t in &e.tags {
            if t.starts_with("future:") || t.starts_with("prompt:") {
                continue;
            }
            buckets.entry(t.clone()).or_default().push(e);
        }
    }
    let mut keys: Vec<String> = buckets.keys().cloned().collect();
    keys.sort();

    let mut written: Vec<String> = Vec::new();
    for key in &keys {
        let mut list = buckets.remove(key).unwrap_or_default();
        list.sort_by(|a, b| (&a.ts, &a.id).cmp(&(&b.ts, &b.id)));
        let preview = |e: &Entry| -> String {
            let first = e.text.lines().next().unwrap_or("").trim();
            let cut: String = first.chars().take(120).collect();
            if cut.is_empty() { "(no text)".into() } else { cut }
        };
        let mut body = format!(
            "---\ntopic: {}\nentries: {}\n---\n\n# {}\n\n",
            key,
            list.len(),
            key
        );
        for e in &list {
            body.push_str(&format!("- [[{}]] · {}: {}\n", e.id, e.kind, preview(e)));
        }
        let content = format!("{}\n", body.trim_end());
        let path = topic_path(&root, key);
        if write_if_changed(&path, &content, dry_run)? {
            written.push(path.to_string_lossy().to_string());
        }
    }

    // Dream clustering.
    let word_re = Regex::new(r"[a-zA-Z][a-zA-Z0-9']{3,}").unwrap();
    let mut symbols: HashMap<String, i64> = HashMap::new();
    let dreams: Vec<&Entry> = entries.iter().filter(|e| e.kind == "dream").collect();
    for e in &dreams {
        let lower = e.text.to_lowercase();
        for m in word_re.find_iter(&lower) {
            let w = m.as_str();
            if !STOPWORDS.contains(&w) {
                *symbols.entry(w.to_string()).or_default() += 1;
            }
        }
    }
    let mut top: Vec<(String, i64)> = symbols.into_iter().collect();
    top.sort_by(|a, b| (-b.1, &a.0).cmp(&(-a.1, &b.0)));
    top.truncate(40);

    if !dreams.is_empty() {
        let mut body = format!(
            "---\ntopic: dreams\nentries: {}\n---\n\n# Dreams\n\n## Symbols\n\n",
            dreams.len()
        );
        for (sym, n) in &top {
            body.push_str(&format!("- {sym} ({n})\n"));
        }
        body.push_str("\n## Entries\n\n");
        for e in &dreams {
            let first = e.text.lines().next().unwrap_or("").trim();
            let cut: String = first.chars().take(120).collect();
            let preview = if cut.is_empty() { "(voice / empty)".to_string() } else { cut };
            body.push_str(&format!("- [[{}]]: {preview}\n", e.id));
        }
        let content = format!("{}\n", body.trim_end());
        let path = topic_path(&root, "dreams");
        if write_if_changed(&path, &content, dry_run)? {
            written.push(path.to_string_lossy().to_string());
        }
    }
    Ok(json!({ "written": written, "dry_run": dry_run }))
}
