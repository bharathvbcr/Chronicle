//! Link repair after note moves (link_repair.py) + vault changelog append.

use std::path::{Path, PathBuf};

use regex::Regex;
use serde_json::Value;

const EXCLUDED_DIRS: [&str; 5] = [".git", ".venv", "node_modules", "__pycache__", "index"];

fn collect_vault_md(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    fn walk(root: &Path, dir: &Path, out: &mut Vec<PathBuf>, depth: usize) {
        if depth >= crate::paths::MAX_WALK_DEPTH {
            return;
        }
        for (p, is_dir) in crate::paths::list_children(dir) {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if name.starts_with('.') || name.contains(".sync-conflict") {
                continue;
            }
            if is_dir {
                if EXCLUDED_DIRS.contains(&name.as_str()) {
                    continue;
                }
                walk(root, &p, out, depth + 1);
            } else if name.ends_with(".md") {
                // Changelog itself is excluded (appended below).
                if p.ends_with("_system/changelog.md") {
                    continue;
                }
                out.push(p);
            }
        }
    }
    walk(root, root, &mut out, 0);
    out.sort();
    out
}

struct LinkForms {
    rel: String,
    stem: String,
    basename: String,
}

/// Rewrite wikilinks + markdown links pointing at the moved note.
pub fn repair_links(root: &Path, old_rel: &str, new_rel: &str) -> Result<(usize, usize), crate::errors::ChronicleError> {
    let old_path = PathBuf::from(old_rel);
    let forms = LinkForms {
        rel: old_rel.to_string(),
        stem: old_path
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default(),
        basename: old_path
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default(),
    };

    let wiki_re = Regex::new(r"(!?)\[\[([^\]|#]+)(#[^\]|]*)?(?:\|([^\]]*))?\]\]").unwrap();
    let md_re = Regex::new(r#"(?i)(!?)\]\((<[^>]*>|[^)\s]+)(?:\s+"[^"]*")?\)"#).unwrap();

    let mut files_updated = 0usize;
    let links_replaced = std::cell::Cell::new(0usize);
    for path in collect_vault_md(root) {
        let Ok(bytes) = std::fs::read(&path) else { continue };
        let text = String::from_utf8_lossy(&bytes).to_string();
        let mut changed = false;

        let new_text = wiki_re
            .replace_all(&text, |caps: &regex::Captures| {
                let bang = caps.get(1).map(|m| m.as_str()).unwrap_or("");
                let target_raw = caps.get(2).map(|m| m.as_str()).unwrap_or("");
                let anchor = caps.get(3).map(|m| m.as_str()).unwrap_or("");
                let alias = caps.get(4).map(|m| m.as_str());
                let target = target_raw.trim();
                let is_rel = target == forms.rel;
                let is_stem = !forms.stem.is_empty()
                    && (target == forms.stem
                        || target == format!("{}.md", forms.stem)
                        || (!forms.basename.is_empty() && target == forms.basename));
                if !(is_rel || is_stem) {
                    return caps.get(0).unwrap().as_str().to_string();
                }
                changed = true;
                links_replaced.set(links_replaced.get() + 1);
                let new_target = if is_rel {
                    new_rel.to_string()
                } else {
                    // Basename-form links stay basename-form.
                    let nb = PathBuf::from(new_rel);
                    nb.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| new_rel.to_string())
                };
                match alias {
                    Some(a) => format!("{bang}[[{new_target}{anchor}|{a}]]"),
                    None => format!("{bang}[[{new_target}{anchor}]]"),
                }
            })
            .to_string();

        let new_text = md_re
            .replace_all(&new_text, |caps: &regex::Captures| {
                let bang = caps.get(1).map(|m| m.as_str()).unwrap_or("]");
                let href_raw = caps.get(2).map(|m| m.as_str()).unwrap_or("");
                let href = href_raw.trim_start_matches('<').trim_end_matches('>');
                let decoded = urldecode(href);
                if decoded.starts_with("http://")
                    || decoded.starts_with("https://")
                    || decoded.starts_with("mailto:")
                    || decoded.starts_with('#')
                {
                    return caps.get(0).unwrap().as_str().to_string();
                }
                let stripped = decoded.trim_end_matches(".md");
                let old_stem_no_ext = forms.rel.trim_end_matches(".md");
                let matches_old = decoded.eq_ignore_ascii_case(&forms.rel)
                    || decoded.eq_ignore_ascii_case(&old_stem_no_ext)
                    || (!forms.stem.is_empty() && stripped.eq_ignore_ascii_case(&forms.stem))
                    || (!forms.basename.is_empty() && decoded.eq_ignore_ascii_case(&forms.basename));
                if !matches_old {
                    return caps.get(0).unwrap().as_str().to_string();
                }
                changed = true;
                links_replaced.set(links_replaced.get() + 1);
                let angle = href_raw.starts_with('<');
                let new_href = if angle {
                    format!("<{}>", new_rel)
                } else {
                    new_rel.to_string()
                };
                format!("{bang}({new_href})")
            })
            .to_string();

        if changed && new_text != text {
            crate::paths::atomic_write_text(&path, &new_text)?;
            files_updated += 1;
        }
    }
    Ok((links_replaced.get(), files_updated))
}

fn urldecode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(b) = u8::from_str_radix(std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or(""), 16) {
                out.push(b as char);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i] as char);
        i += 1;
    }
    out.replace("%20", " ")
}

/// Append a changelog line (header seeded when new).
pub fn append_changelog(root: &Path, old_rel: &str, new_rel: &str, links: usize, files: usize) -> Result<(), crate::errors::ChronicleError> {
    let path = root.join("_system").join("changelog.md");
    let mut text = if path.is_file() {
        std::fs::read_to_string(&path)?
    } else {
        "# Vault changelog\n".to_string()
    };
    let today = chrono::Local::now().format("%Y-%m-%d");
    text.push_str(&format!(
        "{today}: moved {old_rel} → {new_rel} ({links} links in {files} files)\n"
    ));
    crate::paths::atomic_write_text(&path, &text)
}

pub fn changelog_appended_marker() -> Value {
    Value::Bool(true)
}
