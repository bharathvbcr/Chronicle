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
    // Same ancestor rule as path_map::abs_under_root: a lexical path inside the
    // vault is not enough when the path or a parent is a symlink to the outside.
    crate::path_map::abs_under_root(root, &cleaned).map_err(|_| {
        ChronicleError::MediaPath(format!("{kind} path escapes vault: {rel}"))
    })?;
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
    // A missing leaf does not canonicalize. Walk the nearest existing ancestor
    // so a parent directory symlink that points outside is rejected.
    crate::path_map::abs_under_root(root, &candidates[0]).map_err(|_| {
        ChronicleError::msg(format!("media path escapes vault: {rel}"))
    })?;
    Ok(root.join(&candidates[0]))
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

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn containment_media_symlink_outside_rejected() {
        let vault = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        // Temp roots live under /var → /private/var. Pass the canonical root so
        // the lexical path starts with root_resolved; otherwise the old AND
        // check rejects for the wrong reason and the test passes without a fix.
        let root = vault.path().canonicalize().unwrap();
        let secret = outside.path().canonicalize().unwrap().join("secret.jpg");
        std::fs::write(&secret, b"outside-bytes").unwrap();
        let dir = root.join("_attachments/2026/07");
        std::fs::create_dir_all(&dir).unwrap();
        let link = dir.join("photo.jpg");
        std::os::unix::fs::symlink(&secret, &link).unwrap();
        let rel = "_attachments/2026/07/photo.jpg";

        let validated = validate_media_rel(&root, rel, "img");
        assert!(
            validated.is_err(),
            "in-vault symlink to an outside file must not be returned, got {validated:?}"
        );
        let safe = safe_media_path(&root, rel);
        assert!(safe.is_err(), "safe_media_path returned {safe:?}");
        assert_eq!(std::fs::read(&secret).unwrap(), b"outside-bytes");
    }

    #[test]
    fn containment_media_parent_symlink_rejected() {
        let vault = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let root = vault.path().canonicalize().unwrap();
        std::os::unix::fs::symlink(outside.path().canonicalize().unwrap(), root.join("_attachments")).unwrap();
        let rel = "_attachments/2026/07/missing.jpg";
        let resolved = resolve_media_abs(&root, rel);
        assert!(
            resolved.is_err(),
            "missing leaf under an outside parent symlink must be rejected, got {resolved:?}"
        );
        let validated = validate_media_rel(&root, rel, "img");
        assert!(validated.is_err(), "validate_media_rel returned {validated:?}");
    }
}
