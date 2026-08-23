//! PARA knowledge CRUD + move/archive (api/kb.py logic as library functions).

use std::path::Path;

use serde_json::{json, Value};

use crate::errors::{ApiError, ChronicleError};
use crate::frontmatter;
use crate::link_repair;
use crate::lock::vault_lock;
use crate::models::Entry;
use crate::path_map;
use crate::paths::content_hash;

pub fn read_note_abs(root: &Path, rel: &str) -> Result<std::path::PathBuf, ApiError> {
    // Legacy 410.
    if path_map::is_legacy_kb_path(&path_map::_norm(rel)) {
        return Err(ApiError::new(
            axum::http::StatusCode::GONE,
            json!(path_map::MIGRATE_HINT),
        ));
    }
    match path_map::resolve_read_abs(root, rel) {
        Ok(Some(p)) => Ok(p),
        Ok(None) => Err(ApiError::not_found(format!("note not found: {rel}"))),
        Err(e) => Err(e.into()),
    }
}

pub fn get_note(root: &Path, rel_raw: &str) -> Result<Value, ApiError> {
    // Home.md hub exception at vault root.
    if path_map::_norm(rel_raw) == "Home.md" {
        let p = root.join("Home.md");
        if p.is_file() {
            let text = std::fs::read_to_string(&p).unwrap_or_default();
            return Ok(json!({
                "path": "Home.md",
                "content": text,
                "content_hash": content_hash(&text),
            }));
        }
    }
    let rel = validate_rel(rel_raw)?;
    let abs = read_note_abs(root, &rel)?;
    let bytes = std::fs::read(&abs).map_err(|_| ApiError::not_found(format!("note not found: {rel}")))?;
    let text = String::from_utf8_lossy(&bytes).to_string();
    Ok(json!({
        "path": rel,
        "content": text,
        "content_hash": content_hash(&text),
    }))
}

fn validate_rel(rel_raw: &str) -> Result<String, ApiError> {
    path_map::validate_knowledge_rel(rel_raw).map_err(|e| ApiError::bad_request(e.to_string()))
}

fn inject_frontmatter(body: &str, title: Option<&str>) -> String {
    let mut out = body.to_string();
    if !out.trim_start().starts_with("---") {
        let mut lines: Vec<String> = Vec::new();
        if let Some(t) = title.map(str::trim).filter(|t| !t.is_empty()) {
            lines.push(format!("title: {t}"));
        }
        if !lines.is_empty() {
            out = format!("---\n{}\n---\n{}", lines.join("\n"), out);
        }
    }
    frontmatter::ensure_create_frontmatter(&out, title, "note")
}

pub struct WriteNoteArgs<'a> {
    pub rel_raw: &'a str,
    pub content: &'a str,
    pub base_hash: Option<&'a str>,
    pub section: Option<&'a str>,
    pub create: bool,
}

pub fn write_note(root: &Path, args: WriteNoteArgs<'_>) -> Result<Value, ApiError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30))).map_err(ApiError::from)?;

    let normalized = path_map::normalize_api_path(args.rel_raw).map_err(|e| ApiError::bad_request(e.to_string()))?;
    let is_bare_alias = !path_map::is_para_prefix(&normalized)
        && !path_map::is_legacy_kb_path(&normalized)
        && !normalized.starts_with("ResumePoints/")
        && !normalized.is_empty();

    let mut rel = validate_rel(args.rel_raw)?;
    if args.create && is_bare_alias {
        if let Some(section) = args.section {
            let area = path_map::default_create_area(section);
            let suffix = normalized.trim_start_matches('/');
            rel = format!("{area}/{suffix}");
        }
    }
    if let Some(section) = args.section {
        let sec = path_map::validate_section(Some(section)).map_err(|e| ApiError::bad_request(e.to_string()))?;
        path_map::assert_path_allowed_for_section(&rel, sec.as_deref()).map_err(|e| ApiError::bad_request(e.to_string()))?;
    }

    let abs = path_map::resolve_write(root, &rel).map_err(|e| ApiError::bad_request(e.to_string()))?;
    let existed = abs.is_file();

    if existed && !args.create {
        let disk_text = std::fs::read_to_string(&abs).unwrap_or_default();
        let Some(base) = args.base_hash else {
            return Err(ApiError::bad_request("base_hash required for overwrite (from GET content_hash)"));
        };
        if content_hash(&disk_text) != base {
            return Err(ApiError::conflict_object(json!({
                "detail": "knowledge note hash mismatch",
                "on_disk_hash": content_hash(&disk_text),
            })));
        }
        let mut text = args.content.to_string();
        if !text.ends_with('\n') {
            text.push('\n');
        }
        crate::paths::atomic_write_text(&abs, &text)?;
        return Ok(json!({
            "path": rel,
            "content": text,
            "content_hash": content_hash(&text),
        }));
    }
    if args.create && existed {
        return Err(ApiError::conflict(format!("note already exists: {rel}")));
    }

    // Create pipeline.
    let title = Path::new(&rel)
        .file_stem()
        .map(|s| s.to_string_lossy().to_string());
    let full = inject_frontmatter(args.content, title.as_deref());
    let mut text = full;
    if !text.ends_with('\n') {
        text.push('\n');
    }
    std::fs::create_dir_all(abs.parent().unwrap()).map_err(|e| ApiError::internal(e.to_string()))?;
    crate::paths::atomic_write_text(&abs, &text)?;
    Ok(json!({
        "path": rel,
        "content": text,
        "content_hash": content_hash(&text),
    }))
}

pub fn delete_note(root: &Path, rel_raw: &str) -> Result<Value, ApiError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30))).map_err(ApiError::from)?;
    let rel = validate_rel(rel_raw)?;
    let abs = read_note_abs(root, &rel)?;
    std::fs::remove_file(&abs).map_err(|e| ChronicleError::Io(e.to_string()))?;
    Ok(json!({"ok": true, "deleted": rel, "deleted_all": [rel]}))
}

fn archive_dest_rel(rel: &str) -> String {
    if let Some(inner) = rel.strip_prefix("90-Archive/") {
        if inner.starts_with("_legacy-kb/") {
            return format!("90-Archive/{inner}");
        }
    }
    if rel == "90-Archive" || rel.starts_with("90-Archive/") {
        return rel.to_string();
    }
    for area in path_map::PARA_AREAS {
        if let Some(suffix) = rel.strip_prefix(&format!("{area}/")) {
            return format!("90-Archive/{suffix}");
        }
        if rel == area {
            return "90-Archive".to_string();
        }
    }
    format!("90-Archive/{}", rel)
}

/// move/archive share the machinery; `archive=true` derives to_path.
pub fn move_note(
    root: &Path,
    from_raw: &str,
    to_raw: Option<&str>,
    archive: bool,
) -> Result<Value, ApiError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30))).map_err(ApiError::from)?;
    let from_rel = validate_rel(from_raw)?;
    let to_rel = if archive {
        let d = archive_dest_rel(&from_rel);
        if !d.ends_with(".md") {
            return Err(ApiError::bad_request("cannot archive a non-note"));
        }
        path_map::validate_knowledge_rel(&d).map_err(|e| ApiError::bad_request(e.to_string()))?
    } else {
        let raw = to_raw.ok_or_else(|| ApiError::bad_request("to_path is required"))?;
        let t = validate_rel(raw)?;
        if !path_map::is_para_prefix(&t) {
            return Err(ApiError::bad_request("to_path must be under a PARA area"));
        }
        let basename = Path::new(&t)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        if path_map::is_chrome_basename(&basename) {
            return Err(ApiError::bad_request("cannot move onto chrome basename"));
        }
        t
    };

    let src = read_note_abs(root, &from_rel)?;
    if to_rel == from_rel {
        // no-op move
    } else {
        let dst = root.join(&to_rel);
        if dst.is_file() {
            return Err(ApiError::conflict(format!("destination exists: {to_rel}")));
        }
    }

    let bytes = std::fs::read(&src).map_err(|_| ApiError::not_found(format!("note not found: {from_rel}")))?;
    let mut text = String::from_utf8_lossy(&bytes).to_string();
    if !text.ends_with('\n') {
        text.push('\n');
    }
    let dst = root.join(&to_rel);
    if let Some(parent) = dst.parent() {
        std::fs::create_dir_all(parent).map_err(|e| ApiError::internal(e.to_string()))?;
    }
    crate::paths::atomic_write_text(&dst, &text)?;
    std::fs::remove_file(&src).map_err(|e| ChronicleError::Io(e.to_string()))?;

    let (links, files) =
        link_repair::repair_links(root, &from_rel, &to_rel).map_err(ApiError::from)?;
    link_repair::append_changelog(root, &from_rel, &to_rel, links, files).map_err(ApiError::from)?;

    Ok(json!({
        "ok": true,
        "from_path": from_rel,
        "to_path": to_rel,
        "quarantined": [],
        "links_repaired": links,
        "files_updated": files,
        "changelog_appended": true,
    }))
}

pub fn templates(root: &Path) -> Value {
    let dir = root.join("_templates");
    let mut files: Vec<std::path::PathBuf> = std::fs::read_dir(&dir)
        .map(|rd| {
            rd.flatten()
                .map(|e| e.path())
                .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("md"))
                .collect()
        })
        .unwrap_or_default();
    files.sort();
    let out: Vec<Value> = files
        .iter()
        .filter_map(|p| {
            let name = p.file_stem()?.to_string_lossy().to_string();
            let rel_name = p.file_name()?.to_string_lossy().to_string();
            let bytes = std::fs::read(p).ok()?;
            Some(json!({
                "name": name,
                "path": format!("_templates/{rel_name}"),
                "content": String::from_utf8_lossy(&bytes),
            }))
        })
        .collect();
    json!({ "files": out })
}

/// Entry helper reused by api::entries — parse ts or generate now.
pub(crate) fn entry_from_create(
    root: &Path,
    id_in: Option<String>,
    ts_in: Option<String>,
    kind: &str,
    text: &str,
    tags: Vec<String>,
    mood: Option<i64>,
    images: Vec<String>,
    audio: Vec<String>,
) -> Result<Entry, ApiError> {
    use chrono::{DateTime, FixedOffset, Local};
    let _ = Local;
    let ts = match ts_in.as_deref().filter(|s| !s.trim().is_empty()) {
        Some(s) => match crate::timeutil::parse_iso_aware(s) {
            Some((dt, false)) => dt.to_rfc3339_opts(chrono::SecondsFormat::Secs, false),
            Some((_dt, true)) => s.trim().to_string(),
            None => return Err(ApiError::bad_request(format!("invalid ts: {s:?}"))),
        },
        None => {
            let now: DateTime<FixedOffset> = Local::now().into();
            now.to_rfc3339_opts(chrono::SecondsFormat::Secs, false)
        }
    };
    let id = match id_in {
        Some(id) => {
            crate::entries::validate_id(&id).map_err(ApiError::from)?;
            if !id.ends_with("-pc") && !id.contains("-pc_") {
                return Err(ApiError::bad_request("API writes must use -pc entry ids"));
            }
            if crate::entries::entry_path(root, &id).is_ok_and(|p| p.is_file()) {
                return Err(ApiError::conflict(format!("entry already exists: {id}")));
            }
            id
        }
        None => crate::entries::next_pc_id(root, chrono::Local::now().fixed_offset()),
    };
    if let Some(m) = mood {
        if !(1..=5).contains(&m) {
            return Err(ApiError::bad_request("mood must be 1–5 or null"));
        }
    }
    Ok(Entry {
        version: 1,
        id,
        ts,
        kind: kind.to_string(),
        text: text.to_string(),
        tags,
        images,
        audio,
        mood,
        processed: false,
        filed: false,
        filed_content_hash: None,
        filed_path: None,
        prose_edited: false,
        extra: Default::default(),
    })
}
