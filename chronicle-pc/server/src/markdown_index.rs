//! _system/index.md agent shortlist (markdown_index.py).

use std::path::Path;

use serde_json::{json, Value};

use crate::errors::ChronicleError;
use crate::frontmatter::parse_frontmatter;
use crate::path_map;
use crate::paths::atomic_write_text;

pub const INDEX_REL: &str = "_system/index.md";

const HEADER: &str = r"# Vault index

Agent shortlist (regenerated — do not hand-edit as SoT). Sqlite RAG lives under
`index/`. Rebuild: `chronicle rebuild-markdown-index` or `POST /vault/rebuild-index`.

Format: `title | type | tags | updated`";

struct Row {
    title: String,
    kind: String,
    tags: String,
    updated: String,
}

fn row_for_file(path: &std::path::Path, root: &Path, default_type: &str) -> Option<Row> {
    let bytes = std::fs::read(path).ok()?;
    let text = String::from_utf8_lossy(&bytes).to_string();
    let parsed = parse_frontmatter(&text);
    let fm = &parsed.fm;

    let get = |k: &str| fm.get(k).cloned();

    let title = get("title")
        .filter(|t| !t.is_empty())
        .or_else(|| {
            regex::Regex::new(r"(?m)^#\s+(.+)$")
                .ok()?
                .captures(&text)
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().trim().to_string())
        })
        .unwrap_or_else(|| {
            path.file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_default()
        });

    let kind = get("type").filter(|t| !t.is_empty()).unwrap_or_else(|| default_type.to_string());

    let tags_raw = get("tags").unwrap_or_default();
    let tags = if tags_raw.starts_with('[') && tags_raw.ends_with(']') {
        tags_raw
            .trim_start_matches('[')
            .trim_end_matches(']')
            .split(',')
            .map(|p| p.trim().trim_matches('"').trim_matches('\'').to_string())
            .filter(|p| !p.is_empty())
            .collect::<Vec<_>>()
            .join(", ")
    } else {
        tags_raw.clone()
    };

    let updated = get("updated")
        .filter(|u| !u.is_empty())
        .or_else(|| get("created"))
        .unwrap_or_else(|| {
            // File mtime as local date ISO.
            std::fs::metadata(path)
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| {
                    let dt: chrono::DateTime<chrono::Utc> = t.into();
                    Some(dt.with_timezone(&chrono::Local).format("%Y-%m-%d").to_string())
                })
                .unwrap_or_else(|| chrono::Local::now().format("%Y-%m-%d").to_string())
        });
    let _ = root;
    Some(Row { title, kind, tags, updated })
}

fn collect_rows(root: &Path) -> Vec<Row> {
    let mut rows: Vec<Row> = Vec::new();
    let mut seen: std::collections::HashSet<String> = Default::default();

    for (rel, path) in path_map::iter_knowledge_md(root) {
        if seen.insert(rel) {
            if let Some(r) = row_for_file(&path, root, "note") {
                rows.push(r);
            }
        }
    }
    let journal_root = root.join("40-Journal");
    if journal_root.is_dir() {
        let mut paths: Vec<std::path::PathBuf> = walk_md(&journal_root);
        paths.sort();
        for p in paths {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if name.starts_with('.') || name.contains(".sync-conflict") {
                continue;
            }
            let rel = p
                .strip_prefix(root)
                .map(|r| r.to_string_lossy().replace('\\', "/"))
                .unwrap_or_default();
            if seen.insert(rel) {
                if let Some(r) = row_for_file(&p, root, "journal") {
                    rows.push(r);
                }
            }
        }
    }
    rows.sort_by(|a, b| (&b.updated, b.title.to_lowercase()).cmp(&(&a.updated, a.title.to_lowercase())));
    rows
}

fn walk_md(dir: &Path) -> Vec<std::path::PathBuf> {
    let mut out = Vec::new();
    crate::paths::walk_files_filtered(dir, &mut out, 0, &|_p, name| name.ends_with(".md"));
    out
}

pub fn rebuild_markdown_index(root: &Path, dry_run: bool) -> Result<Value, ChronicleError> {
    let rows = collect_rows(root);
    let mut body = String::from(HEADER.trim_end());
    body.push_str("\n\n");
    for r in &rows {
        let tag_field = if r.tags.is_empty() { "—" } else { r.tags.as_str() };
        body.push_str(&format!("{} | {} | {} | {}\n", r.title, r.kind, tag_field, r.updated));
    }

    let path = root.join(INDEX_REL);
    if dry_run {
        return Ok(json!({
            "path": INDEX_REL,
            "rows": rows.len(),
            "dry_run": true,
            "would_write": true,
        }));
    }
    atomic_write_text(&path, &body)?;
    Ok(json!({
        "path": INDEX_REL,
        "rows": rows.len(),
        "dry_run": false,
        "ok": true,
    }))
}
