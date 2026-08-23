//! Derived notes (notes.py): stable renderers, idempotent writes, mirror guard.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use chrono::{Datelike, NaiveDate};

use crate::frontmatter::stable_frontmatter;
use crate::journal;
use crate::models::Entry;
use crate::paths::atomic_write_text;


pub fn content_hash(text: &str) -> String {
    crate::paths::content_hash(text)
}

/// write_if_changed — hash-compare gate; true when (would) write.
pub fn write_if_changed(path: &Path, content: &str, dry_run: bool) -> Result<bool, crate::errors::ChronicleError> {
    if path.is_file() {
        let existing = std::fs::read_to_string(path).unwrap_or_default();
        if content_hash(&existing) == content_hash(content) {
            return Ok(false);
        }
    }
    if !dry_run {
        atomic_write_text(path, content)?;
    }
    Ok(true)
}

/// mirror_note — deprecated one-way copy, hard-gated on env var == "1".
pub fn mirror_note(src: &Path, vault_mirror: Option<&str>, dry_run: bool) {
    let Some(mirror) = vault_mirror.filter(|m| !m.is_empty()) else {
        return;
    };
    if std::env::var("CHRONICLE_ALLOW_VAULT_MIRROR").map(|v| v.trim() != "1").unwrap_or(true) {
        eprintln!(
            "ERROR chronicle.notes: vault_mirror is set ({mirror}) but mirroring is disabled; set CHRONICLE_ALLOW_VAULT_MIRROR=1 to allow (deprecated)"
        );
        return;
    }
    let vault = PathBuf::from(shellexpand_home(mirror));
    let strs: Vec<String> = src
        .iter()
        .map(|s| s.to_string_lossy().to_string())
        .collect();
    let rel = if let Some(pos) = strs.iter().position(|p| p == "notes") {
        strs[pos..].join("/")
    } else if strs.contains(&"_system".to_string()) && strs.contains(&"derived".to_string()) {
        let pos = strs.iter().position(|p| p == "_system").unwrap();
        strs[pos..].join("/")
    } else {
        format!("notes/{}", src.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default())
    };
    let dest = vault.join(rel);
    if dry_run {
        eprintln!("INFO chronicle.notes: [dry-run] would mirror {} → {}", src.display(), dest.display());
        return;
    }
    if let Some(parent) = dest.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::copy(src, &dest);
}

fn shellexpand_home(path: &str) -> String {
    if let Some(rest) = path.strip_prefix('~') {
        if let Some(home) = std::env::var_os("HOME") {
            return format!("{}{}", home.to_string_lossy(), rest);
        }
    }
    path.to_string()
}

fn mood_avg(entries: &[&Entry]) -> Option<f64> {
    let moods: Vec<i64> = entries.iter().filter_map(|e| e.mood).collect();
    if moods.is_empty() { None } else { Some(moods.iter().sum::<i64>() as f64 / moods.len() as f64) }
}

fn fmt_avg2(v: Option<f64>) -> String {
    match v {
        Some(x) => format!("{x:.2}"),
        None => String::new(),
    }
}

fn first_line_preview(text: &str, cap: usize) -> String {
    let t = text.trim();
    match t.lines().next() {
        Some(l) => l.chars().take(cap).collect(),
        None => String::new(),
    }
}

fn sorted_by_ts_id(mut v: Vec<Entry>) -> Vec<Entry> {
    v.sort_by(|a, b| (&a.ts, &a.id).cmp(&(&b.ts, &b.id)));
    v
}

/// Daily chrome — literal template from notes.py.
pub fn render_daily_chrome(day: NaiveDate, day_entries_in: Vec<Entry>) -> String {
    let day_entries = sorted_by_ts_id(day_entries_in);
    let refs: Vec<&Entry> = day_entries.iter().collect();
    let tags: std::collections::BTreeSet<String> = day_entries
        .iter()
        .flat_map(|e| e.tags.iter().cloned())
        .collect();
    let fm = stable_frontmatter(&[
        ("date", day.format("%Y-%m-%d").to_string()),
        ("entries", day_entries.len().to_string()),
        ("mood_avg", fmt_avg2(mood_avg(&refs))),
        ("tags", tags.into_iter().collect::<Vec<_>>().join(", ")),
    ]);
    let mut parts: Vec<String> = vec![
        fm,
        String::new(),
        format!("# {} (derived)", day.format("%Y-%m-%d")),
        String::new(),
        "## Highlights".into(),
        String::new(),
    ];
    let mut highlights: Vec<String> = Vec::new();
    for e in &day_entries {
        let preview = first_line_preview(&e.text, 120);
        if !preview.is_empty() {
            highlights.push(format!("- {} ([[entry:{}]])", preview, e.id));
        }
    }
    if highlights.is_empty() {
        parts.pop(); // blank line
        parts.pop(); // heading
    } else {
        parts.extend(highlights);
        parts.push(String::new());
    }
    parts.push("## Entries".into());
    parts.push(String::new());
    for e in &day_entries {
        parts.push(format!("- [[entry:{}]] · {}", e.id, e.kind));
    }
    format!("{}\n", parts.join("\n").trim_end())
}

pub fn week_start(d: NaiveDate) -> NaiveDate {
    d - chrono::Duration::days(d.weekday().num_days_from_monday() as i64)
}

pub fn topic_slug(tag: &str) -> String {
    let s = tag.trim_start_matches('#').to_lowercase();
    let s = s.trim();
    let re = regex::Regex::new(r"[^a-z0-9]+").unwrap();
    let slug = re.replace_all(s, "-").trim_matches('-').to_string();
    if slug.is_empty() { "untagged".into() } else { slug }
}

/// Weekly/monthly/yearly renderers.
pub fn render_weekly_note(week: NaiveDate, entries_in: Vec<Entry>) -> String {
    let entries = sorted_by_ts_id(entries_in);
    let refs: Vec<&Entry> = entries.iter().collect();
    let end = week + chrono::Duration::days(6);
    let mut counts: HashMap<String, i64> = HashMap::new();
    for e in &entries {
        for t in &e.tags {
            *counts.entry(t.clone()).or_default() += 1;
        }
    }
    let mut themes: Vec<(String, i64)> = counts.into_iter().collect();
    themes.sort_by(|a, b| (-b.1, &a.0).cmp(&(-a.1, &b.0)));
    let themes: Vec<String> = themes.into_iter().take(12).map(|(t, c)| format!("{t} ({c})")).collect();
    let fm = stable_frontmatter(&[
        ("week_start", week.format("%Y-%m-%d").to_string()),
        ("week_end", end.format("%Y-%m-%d").to_string()),
        ("entries", entries.len().to_string()),
        ("mood_avg", fmt_avg2(mood_avg(&refs))),
        ("themes", themes.clone().join(", ")),
    ]);
    let mut parts: Vec<String> = vec![
        fm,
        String::new(),
        format!("# Week of {}", week.format("%Y-%m-%d")),
        String::new(),
        "## Themes".into(),
        String::new(),
    ];
    for t in &themes {
        parts.push(format!("- {t}"));
    }
    parts.push(String::new());
    parts.push("## Mood trend".into());
    parts.push(String::new());
    parts.push(format!("- average: {}", fmt_avg2(mood_avg(&refs))));
    let mut per_day: std::collections::BTreeMap<String, Vec<f64>> = Default::default();
    for e in &entries {
        if let Some(m) = e.mood {
            per_day.entry(e.ts.chars().take(10).collect()).or_default().push(m as f64);
        }
    }
    for (day, vals) in &per_day {
        let avg = vals.iter().sum::<f64>() / vals.len() as f64;
        parts.push(format!("- {day}: {avg:.1}"));
    }
    parts.push(String::new());
    parts.push("## Entries".into());
    parts.push(String::new());
    for e in &entries {
        let preview = first_line_preview(&e.text, 100);
        let preview = if preview.is_empty() { "(no text)".to_string() } else { preview };
        parts.push(format!("- {} · {}: {}", e.id, e.kind, preview));
    }
    format!("{}\n", parts.join("\n").trim_end())
}

pub fn render_monthly_note(year: i32, month: u32, entries_in: Vec<Entry>) -> String {
    let entries = sorted_by_ts_id(entries_in);
    let refs: Vec<&Entry> = entries.iter().collect();
    let label = format!("{year:04}-{month:02}");
    let mut counts: HashMap<String, i64> = HashMap::new();
    for e in &entries {
        for t in &e.tags {
            *counts.entry(t.clone()).or_default() += 1;
        }
    }
    let mut themes: Vec<(String, i64)> = counts.into_iter().collect();
    themes.sort_by(|a, b| (-b.1, &a.0).cmp(&(-a.1, &b.0)));
    let themes: Vec<String> = themes.into_iter().take(20).map(|(t, c)| format!("{t} ({c})")).collect();
    let fm = stable_frontmatter(&[
        ("month", label.clone()),
        ("entries", entries.len().to_string()),
        ("mood_avg", fmt_avg2(mood_avg(&refs))),
        ("themes", themes.clone().join(", ")),
    ]);
    let mut parts: Vec<String> = vec![fm, String::new(), format!("# {label}"), String::new(), "## Themes".into(), String::new()];
    for t in &themes {
        parts.push(format!("- {t}"));
    }
    parts.push(String::new());
    parts.push("## Mood".into());
    parts.push(String::new());
    parts.push(format!("average: {}", fmt_avg2(mood_avg(&refs))));
    parts.push(String::new());
    parts.push(format!("{} entries this month.", entries.len()));
    format!("{}\n", parts.join("\n").trim_end())
}

pub fn render_yearly_note(year: i32, entries_in: Vec<Entry>) -> String {
    let entries = sorted_by_ts_id(entries_in);
    let refs: Vec<&Entry> = entries.iter().collect();
    let fm = stable_frontmatter(&[
        ("year", year.to_string()),
        ("entries", entries.len().to_string()),
        ("mood_avg", fmt_avg2(mood_avg(&refs))),
    ]);
    let mut parts: Vec<String> = vec![fm, String::new(), format!("# {year}"), String::new(), "## By type".into(), String::new()];
    let mut type_counts: std::collections::BTreeMap<String, i64> = Default::default();
    for e in &entries {
        *type_counts.entry(e.kind.clone()).or_default() += 1;
    }
    for (t, c) in &type_counts {
        parts.push(format!("- {t}: {c}"));
    }
    let mut counts: HashMap<String, i64> = HashMap::new();
    for e in &entries {
        for t in &e.tags {
            *counts.entry(t.clone()).or_default() += 1;
        }
    }
    let mut themes: Vec<(String, i64)> = counts.into_iter().collect();
    themes.sort_by(|a, b| (-b.1, &a.0).cmp(&(-a.1, &b.0)));
    parts.push(String::new());
    parts.push("## Top themes".into());
    parts.push(String::new());
    for (t, c) in themes.into_iter().take(30) {
        parts.push(format!("- {t} ({c})"));
    }
    format!("{}\n", parts.join("\n").trim_end())
}

pub fn daily_note_path(root: &Path, day: NaiveDate) -> PathBuf {
    root.join("_system/derived/daily").join(format!("{}.md", day.format("%Y-%m-%d")))
}

/// regenerate_daily_for_days — journal blocks first, then derived chrome.
#[allow(clippy::too_many_arguments)]
pub fn regenerate_daily_for_days(
    root: &Path,
    days: &[NaiveDate],
    all_entries: Vec<Entry>,
    image_captions: Option<&HashMap<String, String>>,
    vault_mirror: Option<&str>,
    dry_run: bool,
    fallback_tz: &str,
) -> Result<Vec<PathBuf>, crate::errors::ChronicleError> {
    use crate::timeutil::entry_day as eday;
    let mut by_day: HashMap<NaiveDate, Vec<Entry>> = HashMap::new();
    for e in all_entries {
        by_day.entry(eday(&e.ts, &e.id, fallback_tz)).or_default().push(e);
    }

    // Journal prose first (source of truth).
    let _ = journal::file_entries_for_days(root, &mut by_day, days, image_captions, dry_run)?;

    let mut written = Vec::new();
    for day in days {
        let path = daily_note_path(root, *day);
        let empty: Vec<Entry> = Vec::new();
        let content = render_daily_chrome(*day, by_day.get(day).cloned().unwrap_or(empty));
        let changed = write_if_changed(&path, &content, dry_run)?;
        if changed {
            written.push(path.clone());
            eprintln!(
                "INFO chronicle.notes: {} {}",
                if dry_run { "[dry-run] would write" } else { "Wrote" },
                path.display()
            );
        }
        if dry_run {
            mirror_note(&path, vault_mirror, true);
        } else if path.is_file() {
            mirror_note(&path, vault_mirror, false);
        }
    }
    Ok(written)
}
