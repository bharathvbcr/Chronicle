//! Media path validation + dual-read resolution (media_paths.py / vault_paths.py).

use std::path::{Path, PathBuf};

use regex::Regex;
use serde_json::Value;

use crate::errors::ChronicleError;

const ATTACH_RE: &str = r"^_attachments/\d{4}/\d{2}/[^/]+$";
const LEGACY_IMG_RE: &str = r"^img/\d{4}/\d{2}/[^/]+$";
const LEGACY_AUDIO_RE: &str = r"^audio/\d{4}/\d{2}/[^/]+\.m4a$";
const ATTACH_AUDIO_RE: &str = r"^_attachments/\d{4}/\d{2}/[^/]+\.m4a$";

pub fn normalize_media_rel(rel: &str) -> Result<String, ChronicleError> {
    let cleaned = rel.trim().replace('\\', "/");
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
    let cleaned = collapsed.trim_start_matches('/').to_string();
    if cleaned.is_empty() || cleaned.split('/').any(|c| c == "..") || cleaned.contains('\0') {
        return Err(ChronicleError::MediaPath(format!("invalid media path: {rel:?}")));
    }
    Ok(cleaned)
}

fn matches_any(rel: &str, patterns: &[&str]) -> bool {
    patterns.iter().any(|p| Regex::new(p).unwrap().is_match(rel))
}

/// validate_media_rel — kind dispatch + pattern + containment.
pub fn validate_media_rel(root: &Path, rel: &str, kind: &str) -> Result<PathBuf, ChronicleError> {
    let cleaned = normalize_media_rel(rel)?;
    let ok = match kind {
        "img" => matches_any(&cleaned, &[ATTACH_RE, LEGACY_IMG_RE]),
        "audio" => matches_any(&cleaned, &[ATTACH_AUDIO_RE, LEGACY_AUDIO_RE]),
        other => return Err(ChronicleError::msg(format!("unknown media kind: {other}"))),
    };
    if !ok {
        let msg = if kind == "img" { format!("invalid image path: {rel}") } else { format!("invalid audio path: {rel}") };
        return Err(ChronicleError::MediaPath(msg));
    }
    let abs = root.join(&cleaned);
    let root_resolved = root
        .canonicalize()
        .unwrap_or_else(|_| root.to_path_buf());
    let abs_resolved = abs
        .canonicalize()
        .unwrap_or_else(|_| abs.clone());
    if !abs_resolved.starts_with(&root_resolved) && !abs.starts_with(&root_resolved) {
        return Err(ChronicleError::MediaPath(format!("{kind} path escapes vault: {rel}")));
    }
    Ok(root.join(cleaned))
}

/// safe_media_path dispatch (audio/, .m4a attachments → audio; img/_attachments → img).
pub fn safe_media_path(root: &Path, rel: &str) -> Result<PathBuf, ChronicleError> {
    let cleaned = normalize_media_rel(rel)?;
    if cleaned.starts_with("audio/") || (cleaned.starts_with("_attachments/") && cleaned.ends_with(".m4a")) {
        validate_media_rel(root, &cleaned, "audio")
    } else if cleaned.starts_with("img/") || cleaned.starts_with("_attachments/") {
        validate_media_rel(root, &cleaned, "img")
    } else {
        Err(ChronicleError::MediaPath(format!(
            "media path must be under _attachments/, img/, or audio/: {rel}"
        )))
    }
}

/// resolve_media_abs dual-read: first existing candidate inside the vault.
pub fn resolve_media_abs(root: &Path, rel: &str) -> Result<PathBuf, ChronicleError> {
    let cleaned = normalize_media_rel(rel)?;
    let root_resolved = root.canonicalize().unwrap_or_else(|_| root.to_path_buf());
    let mut candidates: Vec<String> = vec![cleaned.clone()];
    if let Some(rest) = cleaned.strip_prefix("img/") {
        candidates.push(format!("_attachments/{rest}"));
    }
    if let Some(rest) = cleaned.strip_prefix("audio/") {
        candidates.push(format!("_attachments/{rest}"));
    }
    if let Some(rest) = cleaned.strip_prefix("_attachments/") {
        candidates.push(format!("img/{rest}"));
        if rest.ends_with(".m4a") {
            candidates.push(format!("audio/{rest}"));
        }
    }
    for cand in &candidates {
        let abs = root.join(cand);
        if abs.is_file() {
            let resolved = abs.canonicalize().unwrap_or(abs);
            if resolved.starts_with(&root_resolved) {
                return Ok(resolved);
            }
        }
    }
    let preferred = root.join(&candidates[0]);
    let preferred_resolved = preferred.canonicalize().unwrap_or(preferred);
    if !preferred_resolved.starts_with(&root_resolved) {
        return Err(ChronicleError::msg(format!("media path escapes vault: {rel}")));
    }
    Ok(preferred_resolved)
}

/// Upload magic-byte checks (api/entries.py).
pub fn is_jpeg(data: &[u8]) -> bool {
    data.len() >= 3 && data[0] == 0xff && data[1] == 0xd8
}

pub fn is_mp4_container(data: &[u8]) -> bool {
    data.len() >= 12 && &data[4..8] == b"ftyp"
}

#[derive(serde::Deserialize)]
struct MediaRef(pub String);

pub fn validate_media_ref_list(root: &Path, refs: &[Value], kind: &str) -> Result<(), ChronicleError> {
    for r in refs {
        let s = r.as_str().unwrap_or_default();
        validate_media_rel(root, s, kind)?;
    }
    Ok(())
}
