//! File-once journal fences (journal.py) — byte-compatible markdown blocks,
//! hash-gated amends, and the full upsert decision tree.

use std::path::{Path, PathBuf};

use chrono::{Datelike, NaiveDate};
use regex::Regex;
use serde_json::json;

use crate::entries as store;
use crate::errors::ChronicleError;
use crate::lock::vault_lock;
use crate::models::Entry;
use crate::paths::{atomic_write_bytes, atomic_write_text, content_hash};
use crate::timeutil::entry_day;

pub fn journal_rel(day: NaiveDate) -> String {
    format!("40-Journal/{}.md", day.format("%Y-%m-%d"))
}

/// Canonical pipeline-authored filing target: `40-Journal/YYYY-MM-DD.md`.
///
/// Entry JSON is user-editable and mirrored from phones, so `filed_path`
/// reaching `root.join(rel)` file I/O must be validated first. Returns
/// `JournalAmendNotFound` semantics via Err(String) — callers translate.
pub fn validate_filed_rel(rel: &str) -> Result<String, String> {
    let day_str = rel
        .strip_prefix("40-Journal/")
        .and_then(|s| s.strip_suffix(".md"))
        .ok_or_else(|| format!("invalid filed_path {rel:?}"))?;
    // Day names are pure ASCII dates; the length check keeps any multibyte
    // input away from byte slicing inside the date parse.
    if day_str.len() != 10 || chrono::NaiveDate::parse_from_str(day_str, "%Y-%m-%d").is_err() {
        return Err(format!("invalid filed_path {rel:?}"));
    }
    Ok(rel.to_string())
}

fn open_pat(entry_id: &str) -> Regex {
    Regex::new(&format!(r"<!--\s*entry:{}\s*-->\n?", regex::escape(entry_id))).unwrap()
}

fn close_pat(entry_id: &str) -> Regex {
    Regex::new(&format!(r"<!--\s*/entry:{entry_id}\s*-->\n?")).unwrap()
}

fn splice_pat(entry_id: &str) -> Regex {
    Regex::new(&format!(
        r"(?s)<!--\s*entry:{0}\s*-->.*?<!--\s*/entry:{0}\s*-->\n?",
        regex::escape(entry_id)
    ))
    .unwrap()
}

pub fn fence_open_scan() -> Regex {
    Regex::new(r"<!--\s*entry:(\d{4}-\d{2}-\d{2}_\d{6}-(?:an|pc)(?:_\d+)?)\s*-->").unwrap()
}

/// extract_block: inner body between fences (fences excluded).
pub fn extract_block(file_text: &str, entry_id: &str) -> Option<String> {
    let m_open = open_pat(entry_id).find(file_text)?;
    let after = m_open.end();
    let rest = &file_text[after..];
    let m_close = close_pat(entry_id).find(rest)?;
    Some(rest[..m_close.start()].to_string())
}

pub fn list_fenced_ids(file_text: &str) -> Vec<String> {
    fence_open_scan()
        .captures_iter(file_text)
        .map(|c| c.get(1).unwrap().as_str().to_string())
        .collect()
}

pub fn on_disk_block_hash(file_text: &str, entry_id: &str) -> Option<String> {
    extract_block(file_text, entry_id).map(|b| content_hash(&b))
}

/// Sealed (E2EE, CONTRACT v1.11): the prose lives only in `text_enc`, which
/// the native runtime has no key to open.
pub fn is_sealed(entry: &Entry) -> bool {
    entry.extra.get("text_enc").is_some_and(|v| !v.is_null())
}

/// is_file_ready: sealed → never (filing would write a permanent empty
/// fence, since an entry is filed only once); no audio → ready; audio →
/// needs non-empty stripped text.
pub fn is_file_ready(entry: &Entry) -> bool {
    if is_sealed(entry) {
        return false;
    }
    if entry.audio.is_empty() {
        return true;
    }
    !entry.text.trim().is_empty()
}

/// render_entry_block_body — parts joined "\n\n", rstrip + trailing newline.
pub fn render_entry_block_body(entry: &Entry, image_captions: Option<&std::collections::HashMap<String, String>>) -> String {
    let mut parts: Vec<String> = Vec::new();
    let mut header = format!("### {} · {}", entry.id, entry.kind);
    if let Some(mood) = entry.mood {
        header.push_str(&format!(" · mood {mood}"));
    }
    parts.push(header);
    if !entry.tags.is_empty() {
        let mut tags = entry.tags.clone();
        tags.sort();
        parts.push(format!("tags: {}", tags.join(", ")));
    }
    let text = entry.text.trim().to_string();
    if !text.is_empty() {
        parts.push(text);
    }
    for img in &entry.images {
        match image_captions.and_then(|c| c.get(img)).map(String::as_str) {
            Some(cap) if !cap.is_empty() => parts.push(format!("![]({img})\n*{cap}*")),
            _ => parts.push(format!("![]({img})")),
        }
    }
    for aud in &entry.audio {
        parts.push(format!("[audio]({aud})"));
    }
    parts.push(format!("[[entry:{}]]", entry.id));
    let joined = parts.join("\n\n");
    format!("{}\n", joined.trim_end())
}

pub fn wrap_entry_fence(entry_id: &str, body: &str) -> String {
    let body = format!("{}\n", body.trim_end());
    format!("<!-- entry:{entry_id} -->\n{body}<!-- /entry:{entry_id} -->\n")
}

fn ensure_day_scaffold(day: NaiveDate, existing: Option<&str>) -> String {
    match existing {
        Some(t) if !t.trim().is_empty() => {
            if t.ends_with('\n') { t.to_string() } else { format!("{t}\n") }
        }
        _ => format!("# {}\n\n", day.format("%Y-%m-%d")),
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct UpsertOutcome {
    pub action: String,
    pub path: String,
    pub hash: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skipped_reason: Option<String>,
}

const DRY_PREFIX: &str = "would_";

/// Day bytes captured before a write so a failed `mark_filed` can put them back.
enum DayPrior {
    Missing,
    Bytes(Vec<u8>),
}

fn read_day_prior(path: &Path) -> Result<DayPrior, ChronicleError> {
    if path.is_file() {
        Ok(DayPrior::Bytes(std::fs::read(path)?))
    } else {
        Ok(DayPrior::Missing)
    }
}

fn restore_day_prior(path: &Path, prior: &DayPrior) -> Result<(), ChronicleError> {
    match prior {
        DayPrior::Missing => {
            if path.is_file() {
                std::fs::remove_file(path)?;
            }
            Ok(())
        }
        DayPrior::Bytes(bytes) => atomic_write_bytes(path, bytes),
    }
}

/// upsert_entry_block — exact gate order. A failed or non-UTF-8 read is an
/// error and does not rewrite the file. A fence whose body already hashes to
/// `new_hash` but has no filed hash is `repair` (no rewrite). A divergent
/// fence with no filed hash stays `missing_filed_content_hash`, force included.
/// Splice failure degrades to append "insert".
pub fn upsert_entry_block(
    root: &Path,
    entry: &Entry,
    day: Option<NaiveDate>,
    image_captions: Option<&std::collections::HashMap<String, String>>,
    dry_run: bool,
    force: bool,
) -> Result<UpsertOutcome, ChronicleError> {
    let day = day.unwrap_or_else(|| entry_day(&entry.ts, &entry.id, "UTC"));
    let rel = journal_rel(day);
    let path = crate::path_map::abs_under_root(root, &rel)?;
    let body = render_entry_block_body(entry, image_captions);
    let new_hash = content_hash(&body);
    let fence = wrap_entry_fence(&entry.id, &body);

    let existing: Option<String> = if path.is_file() {
        Some(std::fs::read_to_string(&path)?)
    } else {
        None
    };
    let mut text = ensure_day_scaffold(day, existing.as_deref());
    let disk_hash = existing
        .as_deref()
        .filter(|e| !e.is_empty())
        .and_then(|e| on_disk_block_hash(e, &entry.id));

    let disk_for_skip = disk_hash.clone();
    let (action, skipped): (String, Option<String>) = match disk_hash {
        None => {
            text = format!("{}\n\n{}", text.trim_end(), fence);
            ("insert".into(), None)
        }
        Some(disk_hash) => {
            let disk_hash = disk_hash.clone();
            let filed_hash = entry.get_filed_hash();
            if filed_hash.is_none() {
                if disk_hash == new_hash {
                    // Crash window: fence already matches. Stamp on the way out;
                    // do not rewrite the day file. Force does not change this.
                    return Ok(UpsertOutcome {
                        action: "repair".into(),
                        path: rel,
                        hash: new_hash,
                        skipped_reason: None,
                    });
                }
                (
                    "skip".into(),
                    Some("missing_filed_content_hash".into()),
                )
            } else if !force && entry.get_prose_edited() {
                ("skip".into(), Some("prose_edited".into()))
            } else if !force && disk_hash != filed_hash.unwrap_or_default() {
                ("skip".into(), Some("human_or_agent_edit".into()))
            } else if !force && disk_hash == new_hash && entry.get_filed() {
                // Python returns before the dry-run translation here: dry runs
                // report plain "unchanged", never "would_unchanged".
                return Ok(UpsertOutcome {
                    action: "unchanged".into(),
                    path: rel,
                    hash: new_hash,
                    skipped_reason: None,
                });
            } else {
                let replacement = fence.trim_end().to_string() + "\n";
                if splice_pat(&entry.id).is_match(&text) {
                    text = splice_pat(&entry.id).replace(&text, &replacement).to_string();
                    ("amend".into(), None)
                } else {
                    text = format!("{}\n\n{}", text.trim_end(), fence);
                    ("insert".into(), None)
                }
            }
        }
    };

    if action == "skip" {
        return Ok(UpsertOutcome {
            action,
            path: rel,
            hash: disk_for_skip.unwrap_or_else(|| new_hash.clone()),
            skipped_reason: skipped,
        });
    }

    let reported_action = if dry_run { format!("{DRY_PREFIX}{action}") } else { action };
    if !dry_run {
        if !text.ends_with('\n') {
            text.push('\n');
        }
        atomic_write_text(&path, &text)?;
    }
    Ok(UpsertOutcome {
        action: reported_action,
        path: rel,
        hash: new_hash,
        skipped_reason: None,
    })
}

/// mark_filed — set trio + pop extras + save.
pub fn mark_filed(root: &Path, entry: &mut Entry, block_hash: &str, filed_path: &str) -> Result<(), ChronicleError> {
    entry.filed = true;
    entry.filed_content_hash = Some(block_hash.to_string());
    entry.filed_path = Some(filed_path.to_string());
    entry.extra.remove("filed");
    entry.extra.remove("filed_content_hash");
    entry.extra.remove("filed_path");
    store::save_entry(root, entry)?;
    Ok(())
}

/// file_entry — readiness gate + skip translation + mark.
pub fn file_entry(
    root: &Path,
    entry: &mut Entry,
    image_captions: Option<&std::collections::HashMap<String, String>>,
    dry_run: bool,
    force: bool,
) -> Result<serde_json::Value, ChronicleError> {
    if !is_file_ready(entry) {
        return Ok(json!({
            "action": "skip",
            "skipped_reason": "not_file_ready",
            "id": entry.id,
        }));
    }
    let day = entry_day(&entry.ts, &entry.id, "UTC");
    let day_path = root.join(journal_rel(day));
    let prior = read_day_prior(&day_path)?;
    let result = upsert_entry_block(root, entry, None, image_captions, dry_run, force)?;
    if result.action == "skip" {
        return Ok(json!({
            "action": result.action,
            "path": result.path,
            "hash": result.hash,
            "skipped_reason": result.skipped_reason,
            "id": entry.id,
        }));
    }
    if dry_run {
        return Ok(json!({
            "action": result.action,
            "path": result.path,
            "hash": result.hash,
            "id": entry.id,
            "filed": false,
        }));
    }
    if result.action.starts_with(DRY_PREFIX) {
        return Ok(json!({
            "action": result.action,
            "path": result.path,
            "hash": result.hash,
            "id": entry.id,
        }));
    }
    let already_filed_unchanged = entry.get_filed() && result.action == "unchanged";
    if !already_filed_unchanged {
        if let Err(e) = mark_filed(root, entry, &result.hash, &result.path) {
            // A kill between the two writes is recovered next call as `repair`.
            // An error we can still see rolls the day file back to the bytes
            // captured before this call wrote them.
            if matches!(result.action.as_str(), "insert" | "amend") {
                if let Err(restore_err) = restore_day_prior(&day_path, &prior) {
                    return Err(ChronicleError::msg(format!(
                        "mark_filed failed ({e}); restoring the day file also failed ({restore_err})"
                    )));
                }
            }
            return Err(e);
        }
    }
    Ok(json!({
        "action": result.action,
        "path": result.path,
        "hash": result.hash,
        "id": entry.id,
        "filed": true,
    }))
}

/// amend_filed_block — hash-gated human-prose edit under the vault lock.
pub fn amend_filed_block(
    root: &Path,
    entry_id: &str,
    new_body: &str,
    base_hash: &str,
) -> Result<serde_json::Value, ChronicleError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30)))?;
    let not_found = |m: String| ChronicleError::JournalAmendNotFound(m);

    let path = store::entry_path(root, entry_id)
        .map_err(|_| not_found(format!("entry not found: {entry_id}")))?;
    let mut entry = store::load_entry(&path)
        .ok_or_else(|| not_found(format!("entry not found: {entry_id}")))?;

    let filed_rel = entry
        .get_filed_path()
        .ok_or_else(|| not_found(format!("entry not filed: {entry_id}")))?;
    if !entry.get_filed() {
        return Err(not_found(format!("entry not filed: {entry_id}")));
    }
    let filed_rel = validate_filed_rel(&filed_rel)
        .map_err(not_found)?;
    let day_path = crate::path_map::abs_under_root(root, &filed_rel)?;
    if !day_path.is_file() {
        return Err(not_found(format!("journal day file missing: {filed_rel}")));
    }
    let text = std::fs::read_to_string(&day_path).unwrap_or_default();
    let disk_hash = on_disk_block_hash(&text, entry_id)
        .ok_or_else(|| not_found(format!("fence missing for entry: {entry_id}")))?;

    let filed_hash = entry.get_filed_hash();
    let conflict = match &filed_hash {
        None => true,
        Some(fh) => disk_hash != base_hash || disk_hash != *fh,
    };
    if conflict {
        return Err(ChronicleError::JournalAmendConflict {
            on_disk_hash: Some(disk_hash),
            filed_content_hash: filed_hash,
        });
    }

    let body = format!("{}\n", new_body.trim_end());
    let new_hash = content_hash(&body);
    let fence = wrap_entry_fence(entry_id, &body);
    let replacement = fence.trim_end().to_string() + "\n";
    if !splice_pat(entry_id).is_match(&text) {
        return Err(not_found(format!("fence missing for entry: {entry_id}")));
    }
    let mut out_text = splice_pat(entry_id).replace(&text, &replacement).to_string();
    if !out_text.ends_with('\n') {
        out_text.push('\n');
    }
    atomic_write_text(&day_path, &out_text)?;

    entry.filed_content_hash = Some(new_hash.clone());
    entry.prose_edited = true;
    entry.extra.remove("filed_content_hash");
    entry.extra.remove("prose_edited");
    store::save_entry(root, &entry)?;

    Ok(json!({
        "id": entry_id,
        "path": filed_rel,
        "hash": new_hash,
        "prose_edited": true,
    }))
}

/// accept_disk_as_base — resync stored hash to on-disk fence; md untouched.
pub fn accept_disk_as_base(root: &Path, entry_id: &str) -> Result<serde_json::Value, ChronicleError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30)))?;
    let not_found = |m: String| ChronicleError::JournalAmendNotFound(m);

    let path = store::entry_path(root, entry_id)
        .map_err(|_| not_found(format!("entry not found: {entry_id}")))?;
    let mut entry = store::load_entry(&path)
        .ok_or_else(|| not_found(format!("entry not found: {entry_id}")))?;
    let filed_rel = entry
        .get_filed_path()
        .ok_or_else(|| not_found(format!("entry not filed: {entry_id}")))?;
    if !entry.get_filed() {
        return Err(not_found(format!("entry not filed: {entry_id}")));
    }
    let filed_rel = validate_filed_rel(&filed_rel).map_err(not_found)?;
    let day_path = crate::path_map::abs_under_root(root, &filed_rel)?;
    if !day_path.is_file() {
        return Err(not_found(format!("journal day file missing: {filed_rel}")));
    }
    let text = std::fs::read_to_string(&day_path).unwrap_or_default();
    let disk_hash = on_disk_block_hash(&text, entry_id)
        .ok_or_else(|| not_found(format!("fence missing for entry: {entry_id}")))?;

    entry.filed_content_hash = Some(disk_hash.clone());
    entry.prose_edited = true;
    entry.extra.remove("filed_content_hash");
    entry.extra.remove("prose_edited");
    store::save_entry(root, &entry)?;

    Ok(json!({
        "id": entry_id,
        "path": filed_rel,
        "hash": disk_hash,
        "prose_edited": true,
        "accepted_disk": true,
    }))
}

/// file_entries_for_days — prose SoT pass over the given days (sorted).
pub fn file_entries_for_days(
    root: &Path,
    by_day: &mut std::collections::HashMap<NaiveDate, Vec<Entry>>,
    days: &[NaiveDate],
    image_captions: Option<&std::collections::HashMap<String, String>>,
    dry_run: bool,
) -> Result<Vec<serde_json::Value>, ChronicleError> {
    let mut out = Vec::new();
    let mut sorted_days = days.to_vec();
    sorted_days.sort();
    for day in sorted_days {
        if let Some(list) = by_day.get_mut(&day) {
            list.sort_by(|a, b| (&a.ts, &a.id).cmp(&(&b.ts, &b.id)));
            for entry in list {
                out.push(file_entry(root, entry, image_captions, dry_run, false)?);
            }
        }
    }
    Ok(out)
}

/// detect_journal_hash_mismatches — per-call rel cache; missing file → "".
pub fn detect_journal_hash_mismatches(root: &Path, entries: &[Entry]) -> Vec<serde_json::Value> {    let mut issues = Vec::new();
    let mut cache: std::collections::HashMap<String, String> = Default::default();
    for entry in entries {
        if !entry.get_filed() {
            continue;
        }
        let rel = entry.get_filed_path().unwrap_or_else(|| {
            journal_rel(entry_day(&entry.ts, &entry.id, "UTC"))
        });
        let text = cache.entry(rel.clone()).or_insert_with(|| {
            std::fs::read_to_string(root.join(&rel)).unwrap_or_default()
        });
        let disk = if text.is_empty() { None } else { on_disk_block_hash(text, &entry.id) };
        let expected = entry.get_filed_hash();
        match disk {
            None => issues.push(json!({
                "id": entry.id, "issue": "missing_fence", "path": rel,
                "prose_edited": entry.get_prose_edited(),
            })),
            Some(d) => {
                if let Some(exp) = expected {
                    if d != exp {
                        issues.push(json!({
                            "id": entry.id, "issue": "hash_mismatch", "path": rel,
                            "filed_content_hash": exp, "on_disk_hash": d,
                            "prose_edited": entry.get_prose_edited(),
                        }));
                    }
                }
            }
        }
    }
    issues
}

/// Days that have at least one journal file (for /journal/days listing).
pub fn list_day_files(root: &Path) -> Vec<(NaiveDate, PathBuf)> {
    let dir = root.join("40-Journal");
    let re = Regex::new(r"^\d{4}-\d{2}-\d{2}$").unwrap();
    let mut days = Vec::new();
    if let Ok(entries) = std::fs::read_dir(&dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) != Some("md") {
                continue;
            }
            if let Some(stem) = p.file_stem().and_then(|s| s.to_str()) {
                if re.is_match(stem) {
                    if let Ok(d) = NaiveDate::parse_from_str(stem, "%Y-%m-%d") {
                        days.push((d, p));
                    }
                }
            }
        }
    }
    days.sort_by(|a, b| b.0.cmp(&a.0));
    days
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_filed_rel_accepts_only_canonical_day_files() {
        assert!(validate_filed_rel("40-Journal/2026-07-09.md").is_ok());
        for bad in [
            "",
            "00-Inbox/2026-07-09.md",
            "40-Journal/note.md",
            "40-Journal/2026-7-9.md",
            "../secret.txt",
            "/etc/passwd",
            "40-Journal/2026-07-09.md/x",
            "40-Journal/日本.md",
        ] {
            assert!(validate_filed_rel(bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn amend_rejects_traversal_filed_path() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let entry_id = "2026-07-09_213045-pc";
        let epath = root.join("_capture/entries/2026/07").join(format!("{entry_id}.json"));
        std::fs::create_dir_all(epath.parent().unwrap()).unwrap();
        std::fs::write(
            &epath,
            format!(
                r#"{{"version":1,"id":"{entry_id}","ts":"2026-07-09T21:30:45+05:30","type":"log","text":"hello","processed":true,"filed":true,"filed_content_hash":"{}","filed_path":"../secret.txt"}}"#,
                "0".repeat(64)
            ),
        )
        .unwrap();
        // Fence lives OUTSIDE the vault — pre-fix this file would be read
        // (existence oracle / cross-file fence redirect).
        std::fs::write(
            tmp.path().parent().unwrap().join("secret.txt"),
            format!("<!-- entry:{entry_id} -->\nSECRET BODY\n<!-- /entry:{entry_id} -->\n"),
        )
        .unwrap();

        let err = amend_filed_block(root, entry_id, "owned", "0").unwrap_err();
        assert!(
            matches!(err, ChronicleError::JournalAmendNotFound(ref m) if m.contains("invalid filed_path")),
            "{err:?}"
        );
    }

    fn sample_entry() -> Entry {
        serde_json::from_value(json!({
            "version": 1,
            "id": "2026-07-09_213045-pc",
            "ts": "2026-07-09T21:30:45+05:30",
            "type": "log",
            "text": "hello world",
            "tags": ["a"],
            "mood": 3,
            "processed": true,
            "filed": false,
        }))
        .unwrap()
    }

    #[test]
    fn upsert_does_not_clobber_non_utf8_day_file() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let path = root.join("40-Journal/2026-07-09.md");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let marker = b"# day\n\xff\xfeKEEP-MARKER";
        std::fs::write(&path, marker).unwrap();
        let entry = sample_entry();

        let err = upsert_entry_block(root, &entry, None, None, false, false).unwrap_err();
        assert!(matches!(err, ChronicleError::Io(_)), "{err:?}");
        assert_eq!(std::fs::read(&path).unwrap(), marker);
    }

    #[test]
    fn upsert_does_not_clobber_unreadable_day_file() {
        use std::os::unix::fs::PermissionsExt;
        // Parent mode 0o000 also denies create/rename, and is_file() is false,
        // so that setup never reaches the failed-read clobber. File mode 0o000
        // still lets the directory replace the inode.
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let path = root.join("40-Journal/2026-07-09.md");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let marker = b"KEEP-UNREADABLE-MARKER\n";
        std::fs::write(&path, marker).unwrap();
        let mut perms = std::fs::metadata(&path).unwrap().permissions();
        perms.set_mode(0o000);
        std::fs::set_permissions(&path, perms).unwrap();

        struct Restore(std::path::PathBuf);
        impl Drop for Restore {
            fn drop(&mut self) {
                let mut perms = std::fs::metadata(&self.0).unwrap().permissions();
                perms.set_mode(0o644);
                std::fs::set_permissions(&self.0, perms).unwrap();
            }
        }
        let restore = Restore(path.clone());

        let result = upsert_entry_block(root, &sample_entry(), None, None, false, false);
        drop(restore);
        let err = result.expect_err("mode 0o000 day file must not be read as empty");
        assert!(matches!(err, ChronicleError::Io(_)), "{err:?}");
        assert_eq!(std::fs::read(&path).unwrap(), marker);
    }

    fn seed_day(root: &std::path::Path, entry: &Entry, body: &str) -> (std::path::PathBuf, Vec<u8>) {
        let path = root.join("40-Journal/2026-07-09.md");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let text = format!("# 2026-07-09\n\n{}", wrap_entry_fence(&entry.id, body));
        std::fs::write(&path, &text).unwrap();
        let bytes = std::fs::read(&path).unwrap();
        (path, bytes)
    }

    #[test]
    fn matching_fence_without_filed_hash_repairs_stamp() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let mut entry = sample_entry();
        let body = render_entry_block_body(&entry, None);
        let (path, before) = seed_day(root, &entry, &body);

        let res = file_entry(root, &mut entry, None, false, false).unwrap();
        assert_eq!(res["action"], "repair");
        assert_eq!(res["filed"], true);
        assert!(entry.get_filed());
        assert_eq!(entry.get_filed_hash().as_deref(), Some(content_hash(&body).as_str()));
        assert_eq!(std::fs::read(&path).unwrap(), before);
    }

    #[test]
    fn matching_fence_without_filed_hash_repairs_stamp_when_forced() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let mut entry = sample_entry();
        let body = render_entry_block_body(&entry, None);
        let (path, before) = seed_day(root, &entry, &body);

        let res = file_entry(root, &mut entry, None, false, true).unwrap();
        assert_eq!(res["action"], "repair");
        assert!(entry.get_filed());
        assert_eq!(std::fs::read(&path).unwrap(), before);
    }

    #[test]
    fn divergent_fence_without_filed_hash_is_not_overwritten() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        let mut entry = sample_entry();
        let (path, before) = seed_day(root, &entry, "HUMAN PROSE KEEP ME\n");

        let res = file_entry(root, &mut entry, None, false, true).unwrap();
        assert_eq!(res["action"], "skip");
        assert_eq!(res["skipped_reason"], "missing_filed_content_hash");
        assert!(!entry.get_filed());
        assert_eq!(std::fs::read(&path).unwrap(), before);
    }
    #[cfg(unix)]
    #[test]
    fn containment_journal_upsert_parent_symlink_rejected() {
        let vault = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let root = vault.path();
        std::os::unix::fs::symlink(outside.path(), root.join("40-Journal")).unwrap();
        let mut entry = Entry::default();
        entry.id = "2026-07-09_120000-pc".into();
        entry.ts = "2026-07-09T12:00:00+00:00".into();
        entry.kind = "log".into();
        entry.text = "must-not-escape".into();

        let upserted = upsert_entry_block(root, &entry, None, None, false, false);
        assert!(upserted.is_err(), "journal upsert followed a parent symlink: {upserted:?}");
        let mut leaked = Vec::new();
        let mut stack = vec![outside.path().to_path_buf()];
        while let Some(d) = stack.pop() {
            let Ok(rd) = std::fs::read_dir(&d) else { continue };
            for ent in rd.flatten() {
                let p = ent.path();
                if ent.file_type().ok().is_some_and(|t| t.is_dir()) {
                    stack.push(p);
                } else if let Some(name) = p.file_name() {
                    leaked.push(name.to_string_lossy().to_string());
                }
            }
        }
        assert!(leaked.is_empty(), "journal day file landed outside the vault: {leaked:?}");
    }
}
