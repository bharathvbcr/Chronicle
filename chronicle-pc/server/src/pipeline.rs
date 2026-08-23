//! Incremental pipeline (process.py): transcribe → captions → flip processed →
//! file-once journal → brain → index → Upcoming.md, with self-heal of stuck
//! entries and per-stage exception policy.

use std::collections::{HashMap, HashSet};
use std::path::Path;

use chrono::NaiveDate;
use serde_json::{json, Value};

use crate::brain;
use crate::captions;
use crate::config::{ensure_config, require_layout_version};
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
        let desc = match provider {
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
        let changed = process_entry(
            root,
            entry,
            &mut image_captions,
            provider,
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
