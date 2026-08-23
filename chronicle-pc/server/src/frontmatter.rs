//! Frontmatter parsing/completion (note_frontmatter.py) + stable renderer.

use std::collections::BTreeMap;

const DEFAULT_KEY_ORDER: [&str; 6] = ["title", "created", "updated", "type", "tags", "aliases"];

pub struct ParsedFrontmatter {
    pub fm: BTreeMap<String, String>,
    pub body: String,
}

/// \A---\r?\n(.*?)\r?\n---\r?\n? — DOTALL, non-greedy.
pub fn parse_frontmatter(text: &str) -> ParsedFrontmatter {
    let bytes = text.as_bytes();
    if !text.starts_with("---") {
        return ParsedFrontmatter { fm: BTreeMap::new(), body: text.to_string() };
    }
    // Find end of first line.
    let after_open = match find_line_end(bytes, 3) {
        Some(idx) => idx,
        None => return ParsedFrontmatter { fm: BTreeMap::new(), body: text.to_string() },
    };
    // Find closing fence line "---".
    let mut search = after_open;
    while let Some(rel) = text[search..].find("\n---") {
        let abs = search + rel;
        let line_start = abs + 1;
        let rest = &text[line_start..];
        let is_fence = rest.starts_with("---")
            && rest[3..].starts_with(|c: char| c == '\r' || c == '\n' || c == '\0')
            || rest == "---";
        if is_fence {
            let after_fence = line_start + 3;
            let body_start = skip_one_newline(bytes, after_fence);
            let inner = &text[after_open..abs];
            let mut fm = BTreeMap::new();
            for line in inner.lines() {
                if let Some((k, v)) = line.split_once(':') {
                    fm.insert(k.trim().to_string(), v.trim().to_string());
                }
            }
            return ParsedFrontmatter { fm, body: text[body_start..].to_string() };
        }
        search = abs + 1;
    }
    ParsedFrontmatter { fm: BTreeMap::new(), body: text.to_string() }
}

fn find_line_end(bytes: &[u8], from: usize) -> Option<usize> {
    let mut i = from;
    while i < bytes.len() {
        if bytes[i] == b'\n' {
            return Some(i + 1);
        }
        i += 1;
    }
    None
}

fn skip_one_newline(bytes: &[u8], at: usize) -> usize {
    if at < bytes.len() && bytes[at] == b'\r' && at + 1 < bytes.len() && bytes[at + 1] == b'\n' {
        at + 2
    } else if at < bytes.len() && bytes[at] == b'\n' {
        at + 1
    } else {
        at
    }
}

fn set_key(fm: &mut BTreeMap<String, String>, key: &str, value: &str, overwrite: bool) {
    let existing = fm
        .keys()
        .find(|k| k.eq_ignore_ascii_case(key))
        .cloned();
    if let Some(cased) = existing {
        let non_empty = fm.get(&cased).map(|v| !v.trim().is_empty()).unwrap_or(false);
        if !overwrite && non_empty {
            return;
        }
        fm.remove(&cased);
    }
    fm.insert(key.to_string(), value.to_string());
}

pub fn format_frontmatter(fm: &BTreeMap<String, String>) -> String {
    let mut lines: Vec<String> = Vec::new();
    for key in DEFAULT_KEY_ORDER {
        if let Some(v) = fm.get(key) {
            lines.push(format!("{key}: {v}"));
        }
    }
    for (k, v) in fm {
        if !DEFAULT_KEY_ORDER.contains(&k.as_str()) {
            lines.push(format!("{k}: {v}"));
        }
    }
    format!("---\n{}\n---", lines.join("\n"))
}

pub fn today_iso() -> String {
    chrono::Local::now().format("%Y-%m-%d").to_string()
}

/// ensure_create_frontmatter — create-time completion contract.
pub fn ensure_create_frontmatter(
    content: &str,
    title: Option<&str>,
    note_type: &str,
) -> String {
    let parsed = parse_frontmatter(content);
    let mut fm = parsed.fm;
    if let Some(t) = title.map(str::trim).filter(|t| !t.is_empty()) {
        set_key(&mut fm, "title", t, false);
    }
    set_key(&mut fm, "created", &today_iso(), false);
    set_key(&mut fm, "updated", &today_iso(), true);
    set_key(&mut fm, "type", note_type, false);
    set_key(&mut fm, "tags", "[]", false);
    let mut body = parsed.body;
    if !body.is_empty() && !body.starts_with('\n') {
        body.insert(0, '\n');
    }
    if body.is_empty() {
        body.push('\n');
    }
    format!("{}{}", format_frontmatter(&fm), body)
}

/// _stable_frontmatter (notes.py): keys sorted alphabetically, newlines→spaces.
pub fn stable_frontmatter(pairs: &[(&str, String)]) -> String {
    let mut sorted: Vec<(String, String)> = pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.replace('\n', " ").trim().to_string()))
        .collect();
    sorted.sort_by(|a, b| a.0.cmp(&b.0));
    let lines: Vec<String> = sorted.iter().map(|(k, v)| format!("{k}: {v}")).collect();
    format!("---\n{}\n---", lines.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn completes_create_frontmatter_in_order() {
        let out = ensure_create_frontmatter("Hello world", Some("My Note"), "note");
        assert!(out.starts_with("---\ntitle: My Note\ncreated: "));
        assert!(out.contains("\nupdated: "));
        assert!(out.contains("\ntype: note\ntags: []\n---\nHello world"));
    }

    #[test]
    fn respects_existing_nonempty_keys_case_insensitively() {
        let src = "---\nTitle: Kept\n---\nBody";
        let out = ensure_create_frontmatter(src, Some("New"), "note");
        assert!(out.contains("Title: Kept"), "{out}");
        assert!(!out.contains("title: New"));
    }

    #[test]
    fn stable_frontmatter_sorts_and_flattens_newlines() {
        let out = stable_frontmatter(&[("zeta", "1".into()), ("alpha", "a\nb".into())]);
        assert_eq!(out, "---\nalpha: a b\nzeta: 1\n---");
    }
}
