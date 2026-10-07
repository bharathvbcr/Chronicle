//! Incremental pipeline (process.py): transcribe → captions → flip processed →
//! file-once journal → brain → index → Upcoming.md, with self-heal of stuck
//! entries and per-stage exception policy.

use std::collections::{HashMap, HashSet};
use std::path::Path;

use chrono::NaiveDate;
use serde_json::{json, Value};

use crate::brain;
use crate::captions;
use crate::config::{ensure_config, require_layout_version, require_no_e2ee};
use crate::entries as store;
use crate::errors::ChronicleError;
use crate::index_store;
use crate::journal;
use crate::lock::vault_lock;
use crate::markdown_index;
use crate::media;
use crate::models::Entry;
use crate::notes;
use crate::ollama;
use crate::provider::ChatProvider;
use crate::transcribe;
use crate::upcoming;

pub struct ProcessDeps<'a> {
    pub rt: &'a crate::LlmRuntime,
}

#[allow(clippy::too_many_arguments)]
fn process_entry(
    root: &Path,
    entry: &mut Entry,
    image_captions: &mut HashMap<String, String>,
    provider: Option<&dyn ChatProvider>,
    vision_provider: Option<&dyn ChatProvider>,
    vision_model: &str,
    dry_run: bool,
) -> bool {
    let mut changed = false;

    // Transcription: audio present + empty text.
    if entry.text.trim().is_empty() && !entry.audio.is_empty() {
        let mut parts: Vec<String> = Vec::new();
        for aud in &entry.audio.clone() {
            let ap = match media::safe_media_path(root, aud) {
                Ok(p) => p,
                Err(e) => {
                    ollama::log_line("WARNING", &format!("Skipping bad audio path {aud}: {e}"));
                    continue;
                }
            };
            if dry_run {
                ollama::log_line("INFO", &format!("[dry-run] would transcribe {aud}"));
                continue;
            }
            if let Some(text) = transcribe::transcribe(&ap) {
                if !text.is_empty() {
                    parts.push(text);
                }
            }
        }
        if !parts.is_empty() && !dry_run {
            entry.text = parts.join("\n\n").trim().to_string();
            changed = true;
            ollama::log_line(
                "INFO",
                &format!("Filled text for {} from audio ({} chars)", entry.id, entry.text.len()),
            );
        }
        if parts.is_empty() && !dry_run {
            ollama::log_line(
                "INFO",
                &format!(
                    "No transcript for {} (whisper unavailable or empty)",
                    entry.audio.first().cloned().unwrap_or_default()
                ),
            );
        }
    }

    // Vision captions: cache hit even for empty strings (negative caching).
    for img in &entry.images.clone() {
        if image_captions.contains_key(img) {
            continue;
        }
        let ip = match media::safe_media_path(root, img) {
            Ok(p) => p,
            Err(e) => {
                ollama::log_line("WARNING", &format!("Skipping bad image path {img}: {e}"));
                image_captions.insert(img.clone(), String::new());
                continue;
            }
        };
        if dry_run {
            ollama::log_line("INFO", &format!("[dry-run] would vision-describe {img}"));
            continue;
        }
        if !ip.is_file() {
            ollama::log_line("WARNING", &format!("Image missing: {img}"));
            image_captions.insert(img.clone(), String::new());
            continue;
        }
        let desc = match vision_provider {
            Some(p) => crate::provider::try_chat_image(p, &ip),
            None => None,
        }
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
        if desc.is_empty() {
            image_captions.insert(img.clone(), String::new());
            ollama::log_line(
                "INFO",
                &format!("Skipped vision for {img} (provider unavailable or no consent)"),
            );
        } else {
            image_captions.insert(img.clone(), desc);
            ollama::log_line("INFO", &format!("Described image {img}"));
        }
    }
    let _ = vision_model;
    changed
}

/// run_process — full incremental pipeline under the vault lock.
pub fn run_process_with_deps(
    root: &Path,
    deps: Option<ProcessDeps<'_>>,
    dry_run: bool,
    run_brain: bool,
    regen_all_days: bool,
) -> Result<Value, ChronicleError> {
    let _guard = crate::lock::vault_lock(root, Some(std::time::Duration::from_secs(30)))?;
    run_process_inner(root, deps, dry_run, run_brain, regen_all_days)
}

fn run_process_inner(
    root: &Path,
    deps: Option<ProcessDeps<'_>>,
    dry_run: bool,
    run_brain: bool,
    regen_all_days: bool,
) -> Result<Value, ChronicleError> {
    let cfg = ensure_config(root)?;
    require_layout_version(&cfg)?;
    // Same gate `serve` applies at startup; the `process` CLI never passes
    // through it, and it cannot open sealed entries to file them.
    require_no_e2ee(&cfg)?;

    let rt_owned;
    let rt: &crate::LlmRuntime = match deps {
        Some(d) => d.rt,
        None => {
            rt_owned = ollama::runtime_from_config(&cfg);
            &rt_owned
        }
    };
    let built = crate::provider::build_provider(&cfg).ok();
    let provider: Option<&dyn ChatProvider> = built.as_ref().map(|(_, p)| p.as_ref());
    // Separate gate: cloud text consent does not authorize uploading photos.
    let built_vision = crate::provider::build_vision_provider(&cfg).ok();
    let vision_provider: Option<&dyn ChatProvider> = built_vision.as_ref().map(|(_, p)| p.as_ref());

    let mut unprocessed = store::load_unprocessed(root)?;
    let mut image_captions = captions::load_captions(root);

    let mut days: HashSet<NaiveDate> = HashSet::new();
    let mut processed_ids: Vec<String> = Vec::new();
    let mut pending_mark: Vec<Entry> = Vec::new();

    ollama::log_line(
        "INFO",
        &format!(
            "Processing {} unprocessed entr{} in {}{}",
            unprocessed.len(),
            if unprocessed.len() == 1 { "y" } else { "ies" },
            root.display(),
            if dry_run { " (dry-run)" } else { "" },
        ),
    );

    for entry in unprocessed.iter_mut() {
        // A sealed entry can arrive before the vault's e2ee config does;
        // transcribing or captioning it would write plaintext beside it.
        if journal::is_sealed(entry) {
            ollama::log_line(
                "WARNING",
                &format!("Skipping sealed e2ee entry {} (native runtime cannot open it)", entry.id),
            );
            continue;
        }
        let changed = process_entry(
            root,
            entry,
            &mut image_captions,
            provider,
            vision_provider,
            &cfg.models.vision,
            dry_run,
        );
        days.insert(crate::timeutil::entry_day(&entry.ts, &entry.id, &cfg.timezone));
        if changed && !dry_run {
            store::save_entry(root, entry)?;
        }
        if !journal::is_file_ready(entry) {
            ollama::log_line(
                "INFO",
                &format!("Leaving unprocessed (empty transcript with audio): {}", entry.id),
            );
            continue;
        }
        if dry_run {
            processed_ids.push(entry.id.clone());
            ollama::log_line("INFO", &format!("[dry-run] would mark processed: {}", entry.id));
        } else {
            pending_mark.push(entry.clone());
        }
    }

    if !image_captions.is_empty() && !dry_run {
        captions::save_captions(root, &image_captions)?;
    }

    // Flip-to-processed BEFORE filing (state machine captured → processed → filed).
    if !dry_run {
        for mut entry in pending_mark {
            entry.processed = true;
            store::save_entry(root, &entry)?;
            processed_ids.push(entry.id.clone());
            ollama::log_line("INFO", &format!("Marked processed: {}", entry.id));
        }
    }

    if regen_all_days {
        for e in store::load_all_entries(root)? {
            days.insert(crate::timeutil::entry_day(&e.ts, &e.id, &cfg.timezone));
        }
    }

    let all_for_notes = store::load_all_entries(root)?;
    let mut sorted_days: Vec<NaiveDate> = days.iter().copied().collect();
    sorted_days.sort();
    let written = notes::regenerate_daily_for_days(
        root,
        &sorted_days,
        all_for_notes.clone(),
        Some(&image_captions),
        cfg.vault_mirror.as_deref(),
        dry_run,
        &cfg.timezone,
    )?;

    // Self-heal stuck entries: processed && !filed && file-ready.
    let mut filed_results: Vec<Value> = Vec::new();
    if !dry_run {
        for mut e in all_for_notes {
            if e.processed && !e.get_filed() && journal::is_file_ready(&e) {
                match journal::file_entry(root, &mut e, Some(&image_captions), false, false) {
                    Ok(res) => {
                        ollama::log_line(
                            "INFO",
                            &format!(
                                "Filed stuck entry {} → {}",
                                e.id,
                                res.get("action").and_then(Value::as_str).unwrap_or("?")
                            ),
                        );
                        filed_results.push(res);
                    }
                    Err(err) => return Err(err),
                }
            }
        }
    }

    // Brain propagates failures (no swallow).
    let brain_result: Option<Value> = if run_brain && !dry_run {
        Some(brain::run_brain(root, Some(brain::BrainDeps { rt }), false)?)
    } else {
        if run_brain && dry_run {
            ollama::log_line("INFO", "[dry-run] would run chronicle brain");
        }
        None
    };

    // Index refresh swallowed.
    let index_result: Option<Value> = if !dry_run {
        match index_store::run_index_with_rt(root, &cfg, rt, false, false) {
            Ok(v) => Some(v),
            Err(e) => {
                ollama::log_line("ERROR", &format!("index refresh failed after process: {e}"));
                None
            }
        }
    } else {
        ollama::log_line("INFO", "[dry-run] would refresh search index");
        None
    };

    // Upcoming swallowed; runs in dry-run too.
    if let Err(e) = upcoming::regenerate_upcoming(root, dry_run) {
        ollama::log_line("ERROR", &format!("Upcoming.md regeneration failed after process: {e}"));
    }

    Ok(json!({
        "processed": processed_ids,
        "days": sorted_days.iter().map(|d| d.format("%Y-%m-%d").to_string()).collect::<Vec<_>>(),
        "notes_written": written.iter().map(|p| p.to_string_lossy()).collect::<Vec<_>>(),
        "filed": filed_results,
        "dry_run": dry_run,
        "brain": brain_result,
        "index": index_result,
    }))
}

/// CLI-facing wrapper resolving config itself.
pub fn run_process(
    dir: Option<&Path>,
    dry_run: bool,
    run_brain: bool,
) -> Result<Value, ChronicleError> {
    let root = crate::paths::resolve_chronicle_dir(dir)?;
    run_process_with_deps(&root, None, dry_run, run_brain, false)
}

/// Sealed (E2EE) captures must never become empty journal fences. The native
/// runtime cannot open `text_enc`, and an entry is filed only once, so filing
/// one without its prose loses that prose from `40-Journal/` for good.
#[cfg(test)]
mod e2ee_fail_closed_tests {
    use super::*;

    const SEALED_ID: &str = "2026-07-09_120000-an";
    const PLAIN_ID: &str = "2026-07-09_130000-pc";

    fn vault(e2ee: bool) -> tempfile::TempDir {
        let d = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(d.path().join("_capture/entries/2026/07")).unwrap();
        let cfg = if e2ee {
            r#"{"version":1,"layout_version":2,"timezone":"UTC",
                "e2ee":{"enabled":true,"kdf":{"alg":"pbkdf2-sha256","iter":600000,"salt":"c2FsdA=="}}}"#
        } else {
            r#"{"version":1,"layout_version":2,"timezone":"UTC"}"#
        };
        std::fs::write(d.path().join("config.json"), cfg).unwrap();
        d
    }

    fn write_entry(root: &Path, id: &str, ts: &str, text: &str, sealed: bool) -> std::path::PathBuf {
        let mut v = json!({
            "version": 1, "id": id, "ts": ts, "type": "log", "text": text,
            "tags": [], "images": [], "audio": [], "processed": false,
        });
        if sealed {
            v["text_enc"] = json!({"v": 1, "nonce": "bm9uY2Vub25jZTEy", "ct": "Y2lwaGVydGV4dA=="});
        }
        let p = root.join(format!("_capture/entries/2026/07/{id}.json"));
        std::fs::write(&p, serde_json::to_string_pretty(&v).unwrap()).unwrap();
        p
    }

    fn on_disk(p: &Path) -> Value {
        serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
    }

    fn not_filed(v: &Value) -> bool {
        v.get("filed").is_none_or(|f| f == &json!(false))
    }

    fn journal_text(root: &Path) -> String {
        std::fs::read_to_string(root.join("40-Journal/2026-07-09.md")).unwrap_or_default()
    }

    #[test]
    fn process_refuses_an_encrypted_vault_and_leaves_sealed_entries_unprocessed() {
        let d = vault(true);
        let p = write_entry(d.path(), SEALED_ID, "2026-07-09T12:00:00+00:00", "", true);

        let err = run_process_with_deps(d.path(), None, false, false, false).unwrap_err();
        assert!(err.to_string().contains("end-to-end encryption"), "wrong refusal: {err}");

        let e = on_disk(&p);
        assert_eq!(e["processed"], json!(false), "sealed entry was marked processed");
        assert!(not_filed(&e), "sealed entry was filed");
        assert!(!journal_text(d.path()).contains(SEALED_ID), "empty fence written for sealed entry");
    }

    /// Android mirrors the e2ee block into config.json create-only, and
    /// Syncthing can deliver sealed entries before that config arrives. The
    /// entry itself is the evidence: `text_enc` means "not ours to file".
    #[test]
    fn sealed_entry_is_skipped_even_when_config_does_not_say_e2ee() {
        let d = vault(false);
        let sealed = write_entry(d.path(), SEALED_ID, "2026-07-09T12:00:00+00:00", "", true);
        let plain = write_entry(d.path(), PLAIN_ID, "2026-07-09T13:00:00+00:00", "a plain note", false);

        let res = run_process_with_deps(d.path(), None, false, false, false).expect("plain vault processes");

        let processed: Vec<&str> = res["processed"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(Value::as_str)
            .collect();
        assert_eq!(processed, vec![PLAIN_ID], "only the plaintext entry may be processed");

        let s = on_disk(&sealed);
        assert_eq!(s["processed"], json!(false), "sealed entry was marked processed");
        assert!(not_filed(&s), "sealed entry was filed");
        assert!(s.get("text_enc").is_some(), "ciphertext must be preserved");

        assert_eq!(on_disk(&plain)["processed"], json!(true));
        let journal = journal_text(d.path());
        assert!(journal.contains(PLAIN_ID), "plain entry should be filed: {journal}");
        assert!(!journal.contains(SEALED_ID), "empty fence written for sealed entry: {journal}");
    }

    /// A sealed entry already marked processed (e.g. by an older build) must
    /// not be picked up by the stuck-entry self-heal either.
    #[test]
    fn processed_sealed_entry_is_not_filed_by_self_heal() {
        let d = vault(false);
        let p = write_entry(d.path(), SEALED_ID, "2026-07-09T12:00:00+00:00", "", true);
        let mut v = on_disk(&p);
        v["processed"] = json!(true);
        std::fs::write(&p, serde_json::to_string_pretty(&v).unwrap()).unwrap();

        let res = run_process_with_deps(d.path(), None, false, false, false).unwrap();

        assert_eq!(res["filed"], json!([]), "self-heal filed a sealed entry: {res}");
        assert!(not_filed(&on_disk(&p)));
        assert!(!journal_text(d.path()).contains(SEALED_ID));
    }
}
