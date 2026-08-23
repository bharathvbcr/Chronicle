//! PARA knowledge path map (path_map.py): normalization, validation, tree.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use regex::Regex;

use crate::errors::ChronicleError;

pub const PARA_AREAS: [&str; 5] = ["00-Inbox", "10-Work", "20-Personal", "30-Knowledge", "90-Archive"];
pub const KB_AREA: &str = "30-Knowledge";
pub const NOTES_AREAS: [&str; 4] = ["00-Inbox", "10-Work", "20-Personal", "90-Archive"];
pub const SECTION_KB: &str = "kb";
pub const SECTION_NOTES: &str = "notes";
pub const LEGACY_KB_NOTES: &str = "kb/notes";
pub const CHROME_BASENAMES: [&str; 3] = ["CLAUDE.md", ".gitkeep", "README.md"];
pub const MACHINE_EXCLUDE_DIRS: [&str; 8] =
    ["index", "brain", "_capture", "_attachments", "entries", "img", "audio", ".stfolder"];

pub const MIGRATE_HINT: &str = "Legacy kb/notes/ dual-read is retired. Run `chronicle cutover-kb --apply --i-have-backup`, then use PARA paths (e.g. 30-Knowledge/…, 00-Inbox/…).";

const SAFE_REL: &str = r"^[A-Za-z0-9._\- /]+$";

pub fn is_chrome_basename(name: &str) -> bool {
    CHROME_BASENAMES.contains(&name)
}

pub fn _norm(path: &str) -> String {
    let cleaned = path.trim().trim_start_matches('/').replace('\\', "/");
    let mut collapsed = String::with_capacity(cleaned.len());
    let mut prev_slash = false;
    for ch in cleaned.chars() {
        if ch == '/' {
            if !prev_slash {
                collapsed.push(ch);
            }
            prev_slash = true;
        } else {
            collapsed.push(ch);
            prev_slash = false;
        }
    }
    collapsed
}

pub fn is_para_prefix(rel: &str) -> bool {
    PARA_AREAS.iter().any(|a| rel == *a || rel.starts_with(&format!("{a}/")))
}

pub fn is_legacy_kb_path(rel: &str) -> bool {
    rel == LEGACY_KB_NOTES || rel.starts_with("kb/notes/")
}

pub fn section_for(rel: &str) -> Option<&'static str> {
    if rel == KB_AREA || rel.starts_with(&format!("{KB_AREA}/")) {
        Some(SECTION_KB)
    } else if is_para_prefix(rel) {
        Some(SECTION_NOTES)
    } else {
        None
    }
}

pub fn default_create_area(section: &str) -> &'static str {
    if section == SECTION_KB { KB_AREA } else { "00-Inbox" }
}

pub fn validate_section(s: Option<&str>) -> Result<Option<String>, ChronicleError> {
    match s {
        None | Some("") => Ok(None),
        Some(v) if v == SECTION_KB || v == SECTION_NOTES => Ok(Some(v.to_string())),
        Some(other) => Err(ChronicleError::msg(format!(
            "section must be 'kb' or 'notes', got '{other}'"
        ))),
    }
}

/// normalize_api_path — passthrough rules + bare-path area assignment.
pub fn normalize_api_path(path: &str) -> Result<String, ChronicleError> {
    let p = _norm(path);
    if p.is_empty() {
        return Err(ChronicleError::msg("path must be a .md file"));
    }
    if is_legacy_kb_path(&p) {
        return Ok(p);
    }
    if is_para_prefix(&p) {
        return Ok(p);
    }
    if p == LEGACY_KB_NOTES {
        return Err(ChronicleError::msg("path must be a .md file"));
    }
    if p.starts_with("ResumePoints/") {
        return Ok(format!("10-Work/{p}"));
    }
    Ok(format!("00-Inbox/{p}"))
}

/// validate_knowledge_rel — full chain, exact error strings.
pub fn validate_knowledge_rel(rel: &str) -> Result<String, ChronicleError> {
    let p = normalize_api_path(rel)?;
    if is_legacy_kb_path(&p) {
        return Err(ChronicleError::msg(
            "legacy kb/notes/ path retired; run chronicle cutover-kb and use a PARA path",
        ));
    }
    if p.split('/').any(|c| c == "..") || p.contains('\0') || p.starts_with("../") || p.contains("/../") {
        return Err(ChronicleError::msg("invalid note path"));
    }
    let safe = Regex::new(SAFE_REL).unwrap();
    if !safe.is_match(&p) {
        return Err(ChronicleError::msg("note path has invalid characters"));
    }
    if !p.ends_with(".md") {
        return Err(ChronicleError::msg("note path must end with .md"));
    }
    if !is_para_prefix(&p) {
        return Err(ChronicleError::msg("path is not under a PARA knowledge area"));
    }
    Ok(p)
}

pub fn assert_path_allowed_for_section(rel: &str, section: Option<&str>) -> Result<(), ChronicleError> {
    let Some(section) = section else { return Ok(()) };
    let allowed = match section {
        SECTION_KB => rel == KB_AREA || rel.starts_with(&format!("{KB_AREA}/")),
        SECTION_NOTES => NOTES_AREAS.iter().any(|a| rel == *a || rel.starts_with(&format!("{a}/"))),
        _ => false,
    };
    if allowed {
        Ok(())
    } else {
        Err(ChronicleError::msg(format!(
            "path '{rel}' is outside section '{section}' (kb → 30-Knowledge/; notes → Inbox/Work/Personal/Archive)"
        )))
    }
}

/// preferred_write_rel: legacy kb/notes/SUFFIX → 10-Work (ResumePoints) or 00-Inbox.
pub fn preferred_write_rel(rel: &str) -> String {
    let p = match normalize_api_path(rel) {
        Ok(p) => p,
        Err(_) => return rel.to_string(),
    };
    if let Some(suffix) = p.strip_prefix("kb/notes/") {
        if suffix.starts_with("ResumePoints/") {
            return format!("10-Work/{suffix}");
        }
        return format!("00-Inbox/{suffix}");
    }
    p
}

fn abs_under_root(root: &Path, rel: &str) -> Result<PathBuf, ChronicleError> {
    let abs = root.join(rel);
    let root_resolved = root.canonicalize().unwrap_or_else(|_| root.to_path_buf());
    let resolved = abs.canonicalize().unwrap_or(abs.clone());
    if !resolved.starts_with(&root_resolved) && !abs.starts_with(&root_resolved) {
        return Err(ChronicleError::msg("note path escapes vault root"));
    }
    Ok(abs)
}

/// resolve_read_abs: existing file wins; legacy → None (caller emits 410).
pub fn resolve_read_abs(root: &Path, rel: &str) -> Result<Option<PathBuf>, ChronicleError> {
    let p = normalize_api_path(rel)?;
    if is_legacy_kb_path(&p) {
        return Ok(None);
    }
    let candidate = abs_under_root(root, &p)?;
    Ok(if candidate.is_file() { Some(candidate) } else { None })
}

/// resolve_write: existing file always wins; creates go to preferred rel.
pub fn resolve_write(root: &Path, rel: &str) -> Result<PathBuf, ChronicleError> {
    let p = normalize_api_path(rel)?;
    if let Some(existing) = resolve_read_abs(root, rel)? {
        return Ok(existing);
    }
    abs_under_root(root, &preferred_write_rel(&p))
}

fn skip_name(name: &str) -> bool {
    name.starts_with('.') || name.contains(".sync-conflict") || is_chrome_basename(name)
}

/// iter_knowledge_md: PARA areas in order, sorted rglob, dedup by rel.
pub fn iter_knowledge_md(root: &Path) -> Vec<(String, PathBuf)> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    for area in PARA_AREAS {
        let dir = root.join(area);
        if !dir.is_dir() {
            continue;
        }
        let mut files: Vec<PathBuf> = Vec::new();
        collect_md_recursive(&dir, &mut files);
        files.sort();
        for f in files {
            if let Ok(rel) = f.strip_prefix(root) {
                let rel_str = rel.to_string_lossy().replace('\\', "/");
                let name = f.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                if skip_name(&name) {
                    continue;
                }
                if seen.insert(rel_str.clone()) {
                    out.push((rel_str, f));
                }
            }
        }
    }
    out
}

fn collect_md_recursive(dir: &Path, out: &mut Vec<PathBuf>) {
    crate::paths::walk_files_filtered(dir, out, 0, &|_p, name| {
        !skip_name(name) && name.ends_with(".md")
    });
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct TreeNode {
    pub path: String,
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub children: Option<Vec<TreeNode>>,
}

/// build_knowledge_tree — dirs-first sort, childless-dir pruning, chrome hidden.
pub fn build_knowledge_tree(root: &Path, section: Option<&str>) -> TreeNode {
    let mut children = Vec::new();
    for area in PARA_AREAS {
        let dir = root.join(area);
        if !dir.is_dir() {
            continue;
        }
        if let Some(node) = build_dir_node(root, &dir, section) {
            children.push(node);
        }
    }
    TreeNode {
        path: "knowledge".into(),
        kind: "dir".into(),
        name: None,
        children: Some(children),
    }
}

fn build_dir_node(root: &Path, dir: &Path, section: Option<&str>) -> Option<TreeNode> {
    build_dir_node_depth(root, dir, section, 0)
}

fn build_dir_node_depth(root: &Path, dir: &Path, section: Option<&str>, depth: usize) -> Option<TreeNode> {
    if depth >= crate::paths::MAX_WALK_DEPTH {
        return None;
    }
    let mut dirs: Vec<TreeNode> = Vec::new();
    let mut files: Vec<TreeNode> = Vec::new();
    for (path, is_dir) in crate::paths::list_children(dir) {
        let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        if skip_name(&name) {
            continue;
        }
        let rel = path
            .strip_prefix(root)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default();
        if is_dir {
            if let Some(child) = build_dir_node_depth(root, &path, section, depth + 1) {
                dirs.push(child);
            }
        } else if name.ends_with(".md") {
            if let Some(sec) = section_for(&rel) {
                if section.map(|s| s != sec).unwrap_or(false) {
                    continue;
                }
                files.push(TreeNode { path: rel, kind: "file".into(), name: Some(name), children: None });
            }
        }
    }
    dirs.sort_by(|a, b| a.path.to_lowercase().cmp(&b.path.to_lowercase()));
    files.sort_by(|a, b| a.name.as_deref().unwrap_or("").to_lowercase().cmp(&b.name.as_deref().unwrap_or("").to_lowercase()));
    let mut kids = dirs;
    kids.extend(files);
    if kids.is_empty() {
        // Existing-but-childless areas still appear; deeper empty dirs are pruned.
        let rel = dir.strip_prefix(root).map(|r| r.to_string_lossy().replace('\\', "/")).unwrap_or_default();
        if PARA_AREAS.contains(&rel.as_str()) {
            return Some(TreeNode { path: rel, kind: "dir".into(), name: None, children: Some(vec![]) });
        }
        return None;
    }
    let rel = dir.strip_prefix(root).map(|r| r.to_string_lossy().replace('\\', "/")).unwrap_or_default();
    Some(TreeNode { path: rel, kind: "dir".into(), name: None, children: Some(kids) })
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn iter_knowledge_md_skips_symlinked_dirs() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let inbox = root.join("00-Inbox");
        std::fs::create_dir_all(&inbox).unwrap();
        std::fs::write(inbox.join("note.md"), "# note").unwrap();
        // Self-cycle and ancestor-cycle: walkers must not descend into either,
        // and must not emit duplicate rel paths for files reachable through them.
        std::os::unix::fs::symlink(&inbox, inbox.join("self")).unwrap();
        std::os::unix::fs::symlink(root, inbox.join("rootlink")).unwrap();

        let files = iter_knowledge_md(root);
        let rels: Vec<&str> = files.iter().map(|(r, _)| r.as_str()).collect();
        assert_eq!(rels, vec!["00-Inbox/note.md"], "unexpected: {rels:?}");
    }
}
