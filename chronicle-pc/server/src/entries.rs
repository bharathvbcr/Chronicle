//! Sharded entry store with legacy dual-read (entries.py).

use std::path::{Path, PathBuf};

use regex::Regex;

use crate::errors::ChronicleError;
use crate::models::Entry;
use crate::paths::{atomic_write_json, resolve_chronicle_dir};

pub fn id_regex() -> Regex {
    Regex::new(r"^(\d{4}-\d{2}-\d{2})_(\d{6})-(an|pc)(_[0-9]+)?$").unwrap()
}

pub fn validate_id(entry_id: &str) -> Result<(), ChronicleError> {
    if id_regex().is_match(entry_id) {
        Ok(())
    } else {
        Err(ChronicleError::InvalidEntryId(entry_id.to_string()))
    }
}

pub fn shard_from_id(entry_id: &str) -> Result<(String, String), ChronicleError> {
    let caps = id_regex()
        .captures(entry_id)
        .ok_or_else(|| ChronicleError::msg(format!("invalid entry id: {entry_id}")))?;
    let date = caps.get(1).unwrap().as_str();
    let mut parts = date.split('-');
    let yyyy = parts.next().unwrap_or_default().to_string();
    let mm = parts.next().unwrap_or_default().to_string();
    Ok((yyyy, mm))
}

fn candidate_rels(entry_id: &str, yyyy: &str, mm: &str) -> [String; 2] {
    [
        format!("_capture/entries/{yyyy}/{mm}/{entry_id}.json"),
        format!("entries/{yyyy}/{mm}/{entry_id}.json"),
    ]
}

/// entry_path(prefer_existing=True): first existing candidate else capture path.
pub fn entry_path(root: &Path, entry_id: &str) -> Result<PathBuf, ChronicleError> {
    entry_path_opts(root, entry_id, true)
}

pub fn entry_path_opts(root: &Path, entry_id: &str, prefer_existing: bool) -> Result<PathBuf, ChronicleError> {
    validate_id(entry_id)?;
    let (yyyy, mm) = shard_from_id(entry_id)?;
    let cands = candidate_rels(entry_id, &yyyy, &mm);
    if prefer_existing {
        for rel in &cands {
            let p = root.join(rel);
            if p.is_file() {
                return Ok(p);
            }
        }
    }
    Ok(root.join(&cands[0]))
}

/// load_entry: any parse/validation failure → None (logged upstream).
pub fn load_entry(path: &Path) -> Option<Entry> {
    let raw = std::fs::read_to_string(path).ok()?;
    let value: serde_json::Value = serde_json::from_str(&raw).ok()?;
    let entry: Entry = serde_json::from_value(value).ok()?;
    if !Entry::valid_type(&entry.kind) {
        return None;
    }
    Some(entry)
}

pub fn save_entry(root: &Path, entry: &Entry) -> Result<PathBuf, ChronicleError> {
    let mut path = entry_path(root, &entry.id)?;
    if !path.is_file() {
        path = entry_path_opts(root, &entry.id, false)?;
    }
    atomic_write_json(&path, &entry.to_disk_value())?;
    Ok(path)
}

/// iter_entry_paths: capture-first dedupe by stem, global POSIX-path sort.
pub fn iter_entry_paths(root: &Path) -> Vec<PathBuf> {
    let bases = [root.join("_capture/entries"), root.join("entries")];
    let mut seen: std::collections::HashSet<String> = Default::default();
    let mut out: Vec<PathBuf> = Vec::new();
    for base in &bases {
        if !base.is_dir() {
            continue;
        }
        let mut files: Vec<PathBuf> = Vec::new();
        collect_json_recursive(base, &mut files);
        files.sort();
        for f in files {
            let name = f.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if name.contains(".sync-conflict") {
                continue;
            }
            let stem = f.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
            if seen.insert(stem) {
                out.push(f);
            }
        }
    }
    out.sort_by_key(|p| p.to_string_lossy().replace('\\', "/"));
    out
}

fn collect_json_recursive(dir: &Path, out: &mut Vec<PathBuf>) {
    crate::paths::walk_files_filtered(dir, out, 0, &|_p, name| name.ends_with(".json"));
}

/// load_all_entries sorted by (ts, id) — raw string ts compare.
pub fn load_all_entries(root: &Path) -> Result<Vec<Entry>, ChronicleError> {
    let root = resolve_chronicle_dir(Some(root))?;
    let mut entries: Vec<Entry> = iter_entry_paths(&root)
        .iter()
        .filter_map(|p| load_entry(p))
        .collect();
    entries.sort_by(|a, b| (&a.ts, &a.id).cmp(&(&b.ts, &b.id)));
    Ok(entries)
}

pub fn load_unprocessed(root: &Path) -> Result<Vec<Entry>, ChronicleError> {
    Ok(load_all_entries(root)?.into_iter().filter(|e| !e.processed).collect())
}

pub fn next_pc_id(root: &Path, when: chrono::DateTime<chrono::FixedOffset>) -> String {
    let base = when.format("%Y-%m-%d_%H%M%S").to_string();
    let base = format!("{base}-pc");
    let mut candidate = base.clone();
    let mut n = 2;
    while entry_path(root, &candidate).is_ok_and(|p| p.exists()) {
        candidate = format!("{base}_{n}");
        n += 1;
    }
    candidate
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn iter_entry_paths_terminates_on_symlink_cycle() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        std::fs::create_dir_all(root.join("_capture/entries/2026/08")).unwrap();
        std::fs::write(
            root.join("_capture/entries/2026/08/2026-08-20_101500-an.json"),
            r#"{"version":1,"id":"2026-08-20_101500-an","ts":"2026-08-20T10:15:00+00:00","kind":"note","text":"hi"}"#,
        )
        .unwrap();
        // Cycle: dir → itself. Regression: is_dir() follows symlinks ⇒ stack overflow.
        #[cfg(unix)]
        std::os::unix::fs::symlink(root.join("_capture"), root.join("_capture/entries/loop")).unwrap();
        std::os::unix::fs::symlink(root.join("_capture"), root.join("_capture/loop2")).unwrap();

        let files = iter_entry_paths(root);
        assert_eq!(files.len(), 1, "{files:?}");
        let all = load_all_entries(root).unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].id, "2026-08-20_101500-an");
    }
}
