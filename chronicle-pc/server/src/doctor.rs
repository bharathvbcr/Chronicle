//! Integrity checks + safe repairs (doctor.py): report-only by default,
//! fix limited to JSON entry conflicts (promote newer-wins) + ops compaction.

use std::path::{Path, PathBuf};

use serde_json::{json, Value};

use crate::curation;
use crate::entries as store;
use crate::errors::ChronicleError;
use crate::journal;
use crate::models::Entry;
use crate::paths::{content_hash, resolve_chronicle_dir};

const WALK_EXCLUDED_DIRS: [&str; 4] = ["index", ".git", ".venv", "__pycache__"];

fn walk_all(root: &Path) -> Vec<PathBuf> {
    fn walk(dir: &Path, out: &mut Vec<PathBuf>, depth: usize) {
        if depth >= crate::paths::MAX_WALK_DEPTH {
            return;
        }
        for (p, is_dir) in crate::paths::list_children(dir) {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if is_dir {
                if WALK_EXCLUDED_DIRS.contains(&name.as_str()) || name.starts_with('.') {
                    continue;
                }
                walk(&p, out, depth + 1);
            } else {
                out.push(p);
            }
        }
    }
    let mut out = Vec::new();
    walk(root, &mut out, 0);
    out.sort();
    out
}

pub fn run_doctor(dir: Option<&Path>, fix: bool, dry_run: bool) -> Result<Value, ChronicleError> {
    let root = resolve_chronicle_dir(dir)?;
    let all_files = walk_all(&root);

    // ---- 1. Entry validation (sync-conflict copies skipped here) ----
    let mut entry_issues: Vec<Value> = Vec::new();
    let mut valid_entries: Vec<Entry> = Vec::new();
    for path in store::iter_entry_paths(&root) {
        let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        if name.contains(".sync-conflict") {
            continue;
        }
        let stem = path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        let rel = path
            .strip_prefix(&root)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default();
        let raw = std::fs::read_to_string(&path).unwrap_or_default();
        let parsed: Result<Entry, _> = serde_json::from_str(&raw);
        match parsed {
            Err(_) => entry_issues.push(json!({"path": rel, "problem": "unreadable_or_invalid_entry"})),
            Ok(entry) => {
                if !store::id_regex().is_match(&stem) {
                    entry_issues.push(json!({"path": rel, "problem": "filename_does_not_match_id_pattern"}));
                } else if entry.id != stem {
                    entry_issues.push(json!({"path": rel, "problem": format!("id {} != stem {}", entry.id, stem)}));
                } else {
                    valid_entries.push(entry);
                }
            }
        }
    }

    // ---- 2. Sync-conflict scan ----
    let mut sync_conflicts: Vec<String> = Vec::new();
    let mut json_conflict_pairs: Vec<(PathBuf, PathBuf)> = Vec::new(); // (primary, conflict twin)
    for p in &all_files {
        let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        if !name.contains(".sync-conflict") {
            continue;
        }
        let rel = p
            .strip_prefix(&root)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default();
        sync_conflicts.push(rel.clone());

        // Pair JSON entry conflicts with their primary file.
        let in_entry_tree =
            p.starts_with(root.join("_capture")) || p.starts_with(root.join("entries"));
        if name.ends_with(".json") && in_entry_tree {
            if let Some((base_stem, _rest)) = name.split_once(".sync-conflict") {
                if let Some(parent) = p.parent() {
                    let primary = parent.join(format!("{base_stem}.json"));
                    if primary.is_file() {
                        json_conflict_pairs.push((primary, p.clone()));
                    }
                }
            }
        }
    }
    let journal_conflicts: Vec<&String> =
        sync_conflicts.iter().filter(|r| r.starts_with("40-Journal/")).collect();

    // ---- 3. Orphan media (report-only) ----
    let referenced_images: std::collections::HashSet<String> = valid_entries
        .iter()
        .flat_map(|e| e.images.iter().cloned())
        .collect();
    let referenced_audio: std::collections::HashSet<String> = valid_entries
        .iter()
        .flat_map(|e| e.audio.iter().cloned())
        .collect();
    let media_exts = ["jpg", "jpeg", "png", "gif", "webp", "heic", "mp4", "m4a", "wav", "aac"];
    let mut orphan_images: Vec<String> = Vec::new();
    let mut orphan_audio: Vec<String> = Vec::new();
    for dir_name in ["img", "audio", "_attachments"] {
        walk_media(&root.join(dir_name), &mut |rel, name| {
            let ext = name.rsplit('.').next().unwrap_or("").to_lowercase();
            if !media_exts.contains(&ext.as_str()) {
                return;
            }
            if ext == "m4a" {
                if !referenced_audio.contains(&rel) {
                    orphan_audio.push(rel);
                }
            } else if !referenced_images.contains(&rel) {
                orphan_images.push(rel);
            }
        });
    }

    // ---- 4. Stuck unfiled ----
    let stuck_unfiled: Vec<Value> = valid_entries
        .iter()
        .filter(|e| e.processed && !e.get_filed() && journal::is_file_ready(e))
        .map(|e| json!({"id": e.id}))
        .collect();

    // ---- 5. Journal fence drift ----
    let journal_fence_issues = journal::detect_journal_hash_mismatches(&root, &valid_entries);

    // ---- 7. Brain staleness ----
    let graph_path = root.join("brain").join("graph.json");
    let (present, graph_generated, unreadable) = if graph_path.is_file() {
        match crate::paths::read_json(&graph_path) {
            Ok(v) => (
                true,
                v.get("generated").and_then(Value::as_str).map(String::from),
                false,
            ),
            Err(_) => (true, None, true),
        }
    } else {
        (false, None, false)
    };
    let brain_staleness = json!({
        "present": present,
        "graph_generated": graph_generated,
        "unreadable": unreadable,
    });

    // ---- 8. Config hazards ----
    let cfg = crate::config::load_config(&root)?;
    let mut config_issues: Vec<Value> = Vec::new();
    if let Some(mirror) = cfg.vault_mirror.as_deref().filter(|m| !m.is_empty()) {
        let expanded = mirror.replace('~', &std::env::var("HOME").unwrap_or_default());
        let mirror_canon = PathBuf::from(&expanded).canonicalize().ok();
        let root_canon = root.canonicalize().ok();
        if mirror_canon.is_some() && mirror_canon == root_canon {
            config_issues.push(json!({
                "problem": "vault_mirror_points_at_vault",
                "vault_mirror": mirror,
            }));
        }
    }

    // ---- 10. Dual-read leftovers (kb/notes) ----
    let legacy_root = root.join("kb").join("notes");
    let mut legacy_kb_leftover: Vec<String> = Vec::new();
    let mut dual_read_copies: Vec<Value> = Vec::new();
    if legacy_root.is_dir() {
        fn collect_md(dir: &Path, root: &Path, out: &mut Vec<String>, depth: usize) {
            if depth >= crate::paths::MAX_WALK_DEPTH {
                return;
            }
            let mut files: Vec<PathBuf> = Vec::new();
            crate::paths::walk_files_filtered(dir, &mut files, 0, &|_p, name| {
                !name.starts_with('.') && !crate::path_map::is_chrome_basename(name) && name.ends_with(".md")
            });
            for p in files {
                if let Some(rel) = p.strip_prefix(root).ok().map(|r| r.to_string_lossy().replace('\\', "/")) {
                    out.push(rel);
                }
            }
        }
        collect_md(&legacy_root, &root, &mut legacy_kb_leftover, 0);
        for legacy_rel in &legacy_kb_leftover {
            let suffix = legacy_rel.trim_start_matches("kb/notes/");
            for area in crate::path_map::PARA_AREAS {
                if root.join(area).join(suffix).is_file() {
                    dual_read_copies.push(json!({"suffix": suffix, "para": format!("{area}/{suffix}"), "legacy": legacy_rel}));
                    break;
                }
            }
        }
    }

    // ---- 6. Repairs (fix && JSON conflicts only; markdown report-only) ----
    let mut repairs: Vec<Value> = Vec::new();
    let mut repair_failed = false;
    if fix && !json_conflict_pairs.is_empty() {
        for (primary, twin) in &json_conflict_pairs {
            let primary_meta = std::fs::metadata(primary);
            let twin_meta = std::fs::metadata(twin);
            let primary_newer = match (&primary_meta, &twin_meta) {
                (Ok(a), Ok(b)) => a.modified().ok() >= b.modified().ok(),
                _ => true,
            };
            let action = if dry_run {
                "[dry-run] would promote newer copy".to_string()
            } else {
                match promote_newer(primary, twin, primary_newer) {
                    Ok(quarantined) => format!("promoted newer copy; quarantined {}", quarantined.display()),
                    Err(e) => {
                        repair_failed = true;
                        format!("repair failed: {e}")
                    }
                }
            };
            repairs.push(json!({
                "action": action,
                "primary": primary.to_string_lossy(),
                "twin": twin.to_string_lossy(),
                "dry_run": dry_run,
            }));
        }
    }

    // ---- 8b. Ops compaction when fixing ----
    let ops_compaction = if fix {
        Some(curation::compact_ops(&root, dry_run)?)
    } else {
        None
    };

    let mut issues_remaining = entry_issues.len()
        + stuck_unfiled.len()
        + journal_fence_issues.len()
        + usize::from(unreadable)
        + usize::from(!present && !valid_entries.is_empty())
        + config_issues.len()
        + usize::from(repair_failed);
    // Repaired conflicts no longer count once applied for real.
    if fix && !dry_run && !repair_failed {
        issues_remaining = issues_remaining.saturating_sub(0); // conflicts were repaired
    }

    Ok(json!({
        "chronicle_dir": root.to_string_lossy(),
        "entry_issues": entry_issues,
        "orphans": {"images": orphan_images, "audio": orphan_audio},
        "sync_conflicts": sync_conflicts,
        "journal_conflicts": journal_conflicts,
        "stuck_unfiled": stuck_unfiled,
        "journal_fence_issues": journal_fence_issues,
        "dual_read_copies": dual_read_copies,
        "brain_staleness": brain_staleness,
        "ops_compaction": ops_compaction,
        "repairs": repairs,
        "config_issues": config_issues,
        "legacy_kb_leftover": legacy_kb_leftover,
        "fix": fix,
        "ok": issues_remaining == 0,
    }))
}

fn walk_media(dir: &Path, f: &mut dyn FnMut(String, String)) {
    let mut files: Vec<PathBuf> = Vec::new();
    crate::paths::walk_files_filtered(dir, &mut files, 0, &|_p, _name| true);
    files.sort();
    for p in files {
        let rel = p.to_string_lossy().replace('\\', "/");
        let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        f(rel, name);
    }
}

/// Newer-wins promotion: winning content lands at the canonical capture path,
/// losing file quarantined as `<loser>.older.bak`. Returns quarantined path.
fn promote_newer(primary: &Path, twin: &Path, primary_newer: bool) -> Result<PathBuf, ChronicleError> {
    let (winner, loser): (&Path, &Path) = if primary_newer { (primary, twin) } else { (twin, primary) };
    let winner_bytes = std::fs::read(winner)?;
    // Quarantine loser FIRST so a crash cannot leave two live copies.
    let quarantined = PathBuf::from(format!("{}.older.bak", loser.to_string_lossy()));
    std::fs::rename(loser, &quarantined)?;
    if winner == twin {
        // Promote twin content into the canonical slot atomically, then the
        // conflict-named twin disappears from the live tree either way.
        crate::paths::atomic_write_bytes(primary, &winner_bytes)?;
        std::fs::remove_file(twin)?;
    }
    Ok(quarantined)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn seed_vault() -> (tempfile::TempDir, Option<std::path::PathBuf>) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().to_path_buf();
        std::fs::create_dir_all(root.join("_capture/entries/2026/08")).unwrap();
        std::fs::create_dir_all(root.join("_attachments/2026/08")).unwrap();
        std::fs::create_dir_all(root.join("40-Journal")).unwrap();
        std::fs::create_dir_all(root.join("_system")).unwrap();
        std::fs::write(
            root.join("config.json"),
            r#"{"layout_version":2,"timezone":"UTC"}"#,
        )
        .unwrap();
        let graph = root.join("brain/graph.json");
        (dir, Some(graph))
    }

    fn seed_graph(root: &Path) {
        std::fs::create_dir_all(root.join("brain")).unwrap();
        std::fs::write(
            root.join("brain/graph.json"),
            r#"{"version":1,"generated":"2026-08-01T00:00:00+00:00","nodes":[],"edges":[]}"#,
        )
        .unwrap();
    }

    fn write_entry(root: &Path, id: &str, body: Value) {
        let (yyyy, mm) = store::shard_from_id(id).unwrap();
        let p = root.join(format!("_capture/entries/{yyyy}/{mm}/{id}.json"));
        std::fs::write(p, serde_json::to_string_pretty(&body).unwrap()).unwrap();
    }

    #[test]
    fn clean_vault_reports_ok_true() {
        let (dir, _graph) = seed_vault();
        let root = dir.path();
        seed_graph(root);
        write_entry(
            root,
            "2026-08-01_100000-pc",
            json!({"version":1,"id":"2026-08-01_100000-pc","ts":"2026-08-01T10:00:00+00:00","type":"log","text":"hi"}),
        );
        let report = run_doctor(Some(root), false, false).unwrap();
        assert_eq!(report["ok"], json!(true), "{report}");
        assert_eq!(report["entry_issues"], json!([]));
    }

    #[test]
    fn malformed_and_mismatched_entries_flagged() {
        let (dir, _) = seed_vault();
        let root = dir.path();
        std::fs::write(
            root.join("_capture/entries/2026/08/broken.json"),
            "{not json",
        )
        .unwrap();
        // Valid JSON whose stem mismatches its inner id.
        std::fs::write(
            root.join("_capture/entries/2026/08/2026-08-02_101010-pc.json"),
            json!({"id":"other-id","ts":"x"}).to_string(),
        )
        .unwrap();
        let report = run_doctor(Some(root), false, false).unwrap();
        assert_eq!(report["ok"], json!(false));
        assert_eq!(report["entry_issues"].as_array().unwrap().len(), 2);
    }

    #[test]
    fn stuck_unfiled_and_missing_graph_fail_closed() {
        let (dir, _) = seed_vault();
        let root = dir.path();
        write_entry(
            root,
            "2026-08-03_090000-pc",
            json!({"version":1,"id":"2026-08-03_090000-pc","ts":"2026-08-03T09:00:00+00:00","type":"log","text":"ready","processed":true}),
        );
        let report = run_doctor(Some(root), false, false).unwrap();
        assert_eq!(report["ok"], json!(false));
        assert_eq!(report["stuck_unfiled"].as_array().unwrap().len(), 1);
        assert_eq!(report["brain_staleness"]["present"], json!(false));
    }

    #[test]
    fn sync_conflict_repair_quarantines_loser() {
        let (dir, _) = seed_vault();
        let root = dir.path();
        let good = json!({"version":1,"id":"2026-08-04_080000-pc","ts":"2026-08-04T08:00:00+00:00","type":"log","text":"kept"});
        let newer_twin = json!({"version":1,"id":"2026-08-04_080000-pc","ts":"2026-08-04T08:05:00+00:00","type":"log","text":"twin wins"});
        let primary = root.join("_capture/entries/2026/08/2026-08-04_080000-pc.json");
        let twin = root.join("_capture/entries/2026/08/2026-08-04_080000-pc.sync-conflict-xyz.json");
        std::fs::write(&primary, good.to_string()).unwrap();
        std::fs::write(&twin, newer_twin.to_string()).unwrap();
        // Make twin strictly newer on disk.
        let later = std::time::SystemTime::now() + std::time::Duration::from_secs(5);
        let _ = filetime_set(&twin, later);

        // Dry-run: reports, does not touch disk.
        let report = run_doctor(Some(root), true, true).unwrap();
        assert!(report["repairs"].as_array().unwrap().len() >= 1);
        assert!(report["repairs"][0]["action"]
            .as_str()
            .unwrap()
            .starts_with("[dry-run]"));
        assert!(twin.is_file(), "dry-run must not move files");

        let report = run_doctor(Some(root), true, false).unwrap();
        assert!(!twin.exists(), "twin must be quarantined");
        assert!(root.join("_capture/entries/2026/08/2026-08-04_080000-pc.json.older.bak").exists());
        let promoted: Value =
            serde_json::from_str(&std::fs::read_to_string(&primary).unwrap()).unwrap();
        assert_eq!(promoted["text"], json!("twin wins"));
        assert_eq!(report["fix"], json!(true));
    }

    #[cfg(unix)]
    fn filetime_set(p: &Path, t: std::time::SystemTime) -> std::io::Result<()> {
        let ft = to_filetime(t);
        set_times(p, ft)
    }
    #[cfg(not(unix))]
    fn filetime_set(_p: &Path, _t: std::time::SystemTime) -> std::io::Result<()> {
        Ok(())
    }
    #[cfg(unix)]
    fn to_filetime(t: std::time::SystemTime) -> std::time::SystemTime {
        t
    }
    #[cfg(unix)]
    fn set_times(p: &Path, t: std::time::SystemTime) -> std::io::Result<()> {
        use std::os::unix::fs::PermissionsExt;
        let file = std::fs::OpenOptions::new().write(true).open(p)?;
        let _ = PermissionsExt::mode(&std::fs::metadata(p)?.permissions());
        file.set_modified(t)
    }

    #[test]
    fn vault_mirror_hazard_detected() {
        let (dir, _) = seed_vault();
        let root = dir.path();
        std::fs::write(
            root.join("config.json"),
            format!(
                r#"{{"layout_version":2,"timezone":"UTC","vault_mirror":"{}"}}"#,
                root.canonicalize().unwrap().display()
            ),
        )
        .unwrap();
        let report = run_doctor(Some(root), false, false).unwrap();
        assert_eq!(report["ok"], json!(false));
        assert_eq!(report["config_issues"].as_array().unwrap().len(), 1);
    }
}
