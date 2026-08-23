//! Weekly/monthly/yearly rollups (rollup.py) using notes renderers.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use chrono::{Datelike, NaiveDate};
use serde_json::{json, Value};

use crate::entries as store;
use crate::models::Entry;
use crate::errors::ChronicleError;
use crate::notes;
use crate::paths::resolve_chronicle_dir;
use crate::timeutil::entry_day;

fn week_start(d: NaiveDate) -> NaiveDate {
    d - chrono::Duration::days(d.weekday().num_days_from_monday() as i64)
}

pub fn run_rollup(dir: Option<&Path>, dry_run: bool) -> Result<Value, ChronicleError> {
    let root = resolve_chronicle_dir(dir)?;
    let entries = store::load_all_entries(&root)?;

    let mut weekly: HashMap<NaiveDate, Vec<Entry>> = HashMap::new();
    let mut monthly: HashMap<(i32, u32), Vec<Entry>> = HashMap::new();
    let mut yearly: HashMap<i32, Vec<Entry>> = HashMap::new();
    for e in entries {
        let d = entry_day(&e.ts, &e.id, "UTC");
        let (y, m) = (d.year(), d.month());
        weekly.entry(week_start(d)).or_default().push(e.clone());
        monthly.entry((y, m)).or_default().push(e.clone());
        yearly.entry(y).or_default().push(e);
    }

    let mut written: Vec<String> = Vec::new();
    let base = root.join("_system").join("derived");
    let mut record =
        |path: PathBuf, content: String, written: &mut Vec<String>| -> Result<(), ChronicleError> {
            if notes::write_if_changed(&path, &content, dry_run)? {
                written.push(path.to_string_lossy().to_string());
            }
            Ok(())
        };

    for (w, list) in weekly {
        let path = base.join("weekly").join(format!("{}.md", w.format("%Y-%m-%d")));
        record(path, notes::render_weekly_note(w, list), &mut written)?;
    }
    for ((y, m), list) in monthly {
        let path = base.join("monthly").join(format!("{y:04}-{m:02}.md"));
        record(path, notes::render_monthly_note(y, m, list), &mut written)?;
    }
    for (y, list) in yearly {
        let path = base.join("yearly").join(format!("{y:04}.md"));
        record(path, notes::render_yearly_note(y, list), &mut written)?;
    }
    Ok(json!({ "written": written, "dry_run": dry_run }))
}
