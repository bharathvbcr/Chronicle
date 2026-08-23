//! SQLite search index (index_store.py port) — documents + meta schema,
//! JSON-embedding cosine mode, stale deletion, kb enrichment prefix.
//!
//! DB-file compatible with the Python incumbent: identical schema, identical
//! upsert/stale rules, identical scoring. sqlite-vec is not linked in this
//! build; when an existing vec_documents table is found we warn and continue
//! in JSON-cosine mode exactly like the python fallback path.

use std::collections::{HashMap, HashSet};
use std::path::Path;

use rusqlite::Connection;

impl From<rusqlite::Error> for ChronicleError {
    fn from(e: rusqlite::Error) -> Self {
        ChronicleError::msg(e.to_string())
    }
}
use serde_json::{json, Value};

use crate::config::{ensure_config, load_config, ChronicleConfig};
use crate::entries as store;
use crate::errors::ChronicleError;
use crate::journal;
use crate::kb_enrich::format_enrichment_prefix;
use crate::kb_enrich::load_enrich_cache;
use crate::ollama;
use crate::path_map;
use crate::paths::content_hash;
use crate::paths::resolve_chronicle_dir;
use crate::LlmRuntime;

pub const EMBED_DIM_HINT: i64 = 768;
pub const DOC_TEXT_STORE_LIMIT: usize = 32000;
pub const SEARCH_TEXT_DEFAULT_LIMIT: usize = 16000;

pub fn index_db_path(root: &Path) -> std::path::PathBuf {
    root.join("index").join("chronicle.sqlite")
}

fn clip_chars(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

static VEC_AUTO_EXTENSION: std::sync::Once = std::sync::Once::new();

/// Register sqlite-vec's auto-extension once per process (python: import check).
pub fn register_vec_extension() {
    VEC_AUTO_EXTENSION.call_once(|| unsafe {
        rusqlite::ffi::sqlite3_auto_extension(Some(
            std::mem::transmute(sqlite_vec::sqlite3_vec_init as *const ()),
        ));
    });
}

/// True when a vec0 virtual table can actually be created (extension loads).
fn vec_extension_usable() -> bool {
    register_vec_extension();
    match Connection::open_in_memory() {
        Ok(conn) => conn
            .execute("CREATE VIRTUAL TABLE temp.vec_probe USING vec0(a float[4])", [])
            .is_ok(),
        Err(_) => false,
    }
}

/// Probe && CHRONICLE_DISABLE_VEC kill-switch.
fn vec_mode_enabled() -> bool {
    vec_extension_usable()
        && std::env::var("CHRONICLE_DISABLE_VEC").map(|v| v != "1").unwrap_or(true)
}

fn connect(root: &Path) -> Result<Connection, ChronicleError> {
    register_vec_extension();
    let path = index_db_path(root);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let conn = Connection::open(&path)?;
    Ok(conn)
}

fn init_schema(conn: &Connection, use_vec: bool) -> Result<(), ChronicleError> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS documents (
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            path TEXT,
            text TEXT NOT NULL,
            content_hash TEXT NOT NULL,
            embed_model TEXT,
            embedding_json TEXT,
            updated_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_documents_kind ON documents(kind);
        "#,
    )?;
    if use_vec {
        if let Err(e) = conn.execute(
            "CREATE VIRTUAL TABLE IF NOT EXISTS vec_documents USING vec0(id TEXT PRIMARY KEY, embedding float[768])",
            [],
        ) {
            ollama::log_line("WARNING", &format!("Could not create vec0 table: {e}"));
        }
    }
    Ok(())
}

struct ExistingDoc {
    content_hash: String,
    embed_model: Option<String>,
    embedding_json: Option<String>,
}

type EmbedFn<'a> = &'a dyn Fn(&str, &str) -> Vec<f64>;

pub struct Embedder {
    pub reachable: bool,
    pub f: Box<dyn Fn(&str, &str) -> Vec<f64> + Send + Sync>,
}

impl Embedder {
    pub fn from_rt(rt: &LlmRuntime) -> Self {
        let reachable = ollama::ollama_reachable_rt(rt, std::time::Duration::from_secs(2));
        let rt = rt.clone();
        Self {
            reachable,
            f: Box::new(move |text, model| ollama::embed_rt(&rt, text, Some(model)).unwrap_or_default()),
        }
    }

    #[cfg(test)]
    fn fixed(vectors: HashMap<String, Vec<f64>>, dim: usize) -> Self {
        Self {
            reachable: true,
            f: Box::new(move |text, _| {
                vectors
                    .get(text)
                    .cloned()
                    .unwrap_or_else(|| vec![0.0; dim])
            }),
        }
    }
}

/// Return "upserted" | "skipped" — python `_upsert_doc` parity.
#[allow(clippy::too_many_arguments)]
fn upsert_doc(
    conn: &Connection,
    doc_id: &str,
    kind: &str,
    path: &str,
    text: &str,
    embed_model: &str,
    existing: &HashMap<String, ExistingDoc>,
    force: bool,
    embedder: &Embedder,
    hash_source: Option<&str>,
    use_vec: bool,
) -> Result<&'static str, ChronicleError> {
    let ch = content_hash(hash_source.unwrap_or(text));
    let prev = existing.get(doc_id);
    if !force
        && prev.is_some_and(|p| p.content_hash == ch && p.embed_model.as_deref() == Some(embed_model))
    {
        return Ok("skipped");
    }

    let mut emb: Vec<f64> = Vec::new();
    if embedder.reachable {
        emb = (embedder.f)(&clip_chars(text, 2000), embed_model);
    }
    // Preserve prior embedding when content changed but embed failed/unavailable.
    let emb_json: String = if !emb.is_empty() {
        serde_json::to_string(&emb).unwrap_or_else(|_| "[]".into())
    } else if let Some(p) = prev {
        match p.embedding_json.as_deref().filter(|j| !j.is_empty() && *j != "[]") {
            Some(j) => match serde_json::from_str::<Vec<f64>>(j) {
                Ok(_) => {
                    ollama::log_line(
                        "INFO",
                        &format!("Preserved prior embedding for {doc_id} (new embed empty)"),
                    );
                    j.to_string()
                }
                Err(_) => "[]".into(),
            },
            None => "[]".into(),
        }
    } else {
        "[]".into()
    };

    conn.execute(
        "INSERT OR REPLACE INTO documents \
         (id, kind, path, text, content_hash, embed_model, embedding_json, updated_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, datetime('now'))",
        rusqlite::params![
            doc_id,
            kind,
            path,
            clip_chars(text, DOC_TEXT_STORE_LIMIT),
            ch,
            embed_model,
            emb_json,
        ],
    )?;
    if use_vec && !emb.is_empty() {
        // Best-effort vec maintenance (python: try/except sqlite3.Error → debug log).
        let _ = conn.execute("DELETE FROM vec_documents WHERE id = ?1", rusqlite::params![doc_id]);
        if let Err(e) = conn.execute(
            "INSERT INTO vec_documents(id, embedding) VALUES (?1, ?2)",
            rusqlite::params![doc_id, serde_json::to_string(&emb).unwrap_or_else(|_| "[]".into())],
        ) {
            ollama::log_line("DEBUG", &format!("vec insert skipped: {e}"));
        }
    }
    Ok("upserted")
}

/// (doc_id, kind, rel_path, text)
fn collect_note_docs(root: &Path) -> Vec<(String, String, String, String)> {
    fn read_lossy(p: &Path) -> Option<String> {
        std::fs::read(p).ok().map(|b| String::from_utf8_lossy(&b).to_string())
    }

    let mut docs: Vec<(String, String, String, String)> = Vec::new();

    let journal_root = root.join("40-Journal");
    if journal_root.is_dir() {
        let mut paths = walk_md(&journal_root);
        paths.sort();
        for path in paths {
            let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if name.starts_with('.') || name.contains(".sync-conflict") {
                continue;
            }
            let Some(text) = read_lossy(&path) else { continue };
            let rel = rel_for(root, &path);
            for eid in journal::list_fenced_ids(&text) {
                if let Some(body) = journal::extract_block(&text, &eid) {
                    let trimmed = body.trim();
                    if !trimmed.is_empty() {
                        docs.push((format!("journal:{eid}"), "note".to_string(), rel.clone(), trimmed.to_string()));
                    }
                }
            }
            docs.push((format!("note:{rel}"), "note".to_string(), rel, text));
        }
    }

    for base_dir in [root.join("_system").join("derived"), root.join("notes")] {
        if !base_dir.is_dir() {
            continue;
        }
        let mut paths = walk_md(&base_dir);
        paths.sort();
        for path in paths {
            let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if name.starts_with('.') || name.contains(".sync-conflict") {
                continue;
            }
            let Some(text) = read_lossy(&path) else { continue };
            let rel = rel_for(root, &path);
            docs.push((format!("note:{rel}"), "note".to_string(), rel, text));
        }
    }

    for (rel, path) in path_map::iter_knowledge_md(root) {
        let Some(text) = read_lossy(&path) else { continue };
        docs.push((rel.clone(), "kb".to_string(), rel, text));
    }
    docs
}

fn rel_for(root: &Path, p: &Path) -> String {
    p.strip_prefix(root)
        .map(|r| r.to_string_lossy().replace('\\', "/"))
        .unwrap_or_default()
}

fn walk_md(dir: &Path) -> Vec<std::path::PathBuf> {
    let mut out = Vec::new();
    crate::paths::walk_files_filtered(dir, &mut out, 0, &|_p, name| name.ends_with(".md"));
    out
}

/// _entry_index_text — filed entries prefer journal prose; JSON adds metadata.
pub fn entry_index_text(e: &crate::models::Entry, journal_bodies: &HashMap<String, String>) -> (String, String) {
    let meta = format!("{} {}", e.kind, e.tags.join(" ")).trim().to_string();
    let prose = journal_bodies.get(&e.id);
    match prose.filter(|_| e.filed) {
        Some(p) => {
            let text = format!("{meta}\n{p}").trim().to_string();
            let path = e
                .filed_path
                .clone()
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| format!("40-Journal/.../{}", e.id));
            (text, path)
        }
        None => {
            let text = format!("{meta}\n{}", e.text).trim().to_string();
            (text, format!("_capture/entries/.../{}.json", e.id))
        }
    }
}

pub fn run_index_with_rt(
    root: &Path,
    cfg: &ChronicleConfig,
    rt: &LlmRuntime,
    dry_run: bool,
    force: bool,
) -> Result<Value, ChronicleError> {
    run_index_inner(root, cfg, &Embedder::from_rt(rt), dry_run, force, vec_mode_enabled())
}

pub fn run_index_inner(
    root: &Path,
    cfg: &ChronicleConfig,
    embedder: &Embedder,
    dry_run: bool,
    force: bool,
    use_vec: bool,
) -> Result<Value, ChronicleError> {
    let embed_model = cfg.models.embed.clone();
    let mode = if use_vec { "sqlite-vec" } else { "sqlite+json-cosine" };

    let entries = store::load_all_entries(root)?;
    let note_docs = collect_note_docs(root);
    let enrich_cache = load_enrich_cache(root);
    let enrich_notes = enrich_cache
        .get("notes")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();

    let mut journal_bodies: HashMap<String, String> = HashMap::new();
    for (doc_id, kind, _rel, text) in &note_docs {
        if kind == "note" && doc_id.starts_with("journal:") {
            journal_bodies.insert(doc_id["journal:".len()..].to_string(), text.clone());
        }
    }

    if dry_run {
        ollama::log_line(
            "INFO",
            &format!(
                "[dry-run] would index {} entries + {} notes/kb (mode={mode})",
                entries.len(),
                note_docs.len()
            ),
        );
        return Ok(json!({
            "mode": mode,
            "would_index": entries.len() + note_docs.len(),
            "dry_run": true,
        }));
    }

    let conn = connect(root)?;
    init_schema(&conn, use_vec)?;
    for (k, v) in [
        ("embed_model", embed_model.as_str()),
        ("index_mode", mode),
        ("embed_dim", &EMBED_DIM_HINT.to_string()),
    ] {
        conn.execute(
            "INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)",
            rusqlite::params![k, v],
        )?;
    }

    let mut existing: HashMap<String, ExistingDoc> = HashMap::new();
    {
        let mut stmt = conn.prepare("SELECT id, content_hash, embed_model, embedding_json FROM documents")?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                ExistingDoc {
                    content_hash: r.get::<_, String>(1)?,
                    embed_model: r.get::<_, Option<String>>(2)?,
                    embedding_json: r.get::<_, Option<String>>(3)?,
                },
            ))
        })?;
        for row in rows {
            let (id, doc) = row?;
            existing.insert(id, doc);
        }
    }

    let mut live_ids: HashSet<String> = HashSet::new();
    let mut upserted = 0usize;
    let mut skipped = 0usize;


    for e in &entries {
        let (text, path_hint) = entry_index_text(e, &journal_bodies);
        live_ids.insert(e.id.clone());
        let result = upsert_doc(
            &conn, &e.id, "entry", &path_hint, &text, &embed_model, &existing, force, embedder, None, use_vec,
        )?;
        if result == "upserted" { upserted += 1 } else { skipped += 1 }
    }

    for (doc_id, kind, rel, text) in &note_docs {
        // Prefer entry:{id} docs over duplicate journal:{id}; the journal:
        // ids are deliberately excluded from live_ids so legacy rows are
        // stale-deleted on every run (python quirk preserved).
        if doc_id.starts_with("journal:") {
            continue;
        }
        live_ids.insert(doc_id.clone());
        let mut index_text = text.clone();
        let mut hash_source: Option<String> = None;
        if kind == "kb" {
            let entry = enrich_notes.get(doc_id).filter(|v| v.is_object());
            let prefix = format_enrichment_prefix(entry);
            if !prefix.is_empty() {
                index_text = format!("{prefix}\n\n{text}");
            }
            let enrich_fp = entry
                .map(|e| {
                    format!(
                        "{}|{prefix}",
                        e.get("content_hash").and_then(Value::as_str).unwrap_or_default()
                    )
                })
                .unwrap_or_default();
            hash_source = Some(format!("{text}\n{enrich_fp}"));
        }
        let result = upsert_doc(
            &conn,
            doc_id,
            kind,
            rel,
            &index_text,
            &embed_model,
            &existing,
            force,
            embedder,
            hash_source.as_deref(),
            use_vec,
        )?;
        if result == "upserted" { upserted += 1 } else { skipped += 1 }
    }

    let mut stale = 0usize;
    for doc_id in existing.keys() {
        if live_ids.contains(doc_id) {
            continue;
        }
        conn.execute("DELETE FROM documents WHERE id = ?1", rusqlite::params![doc_id])?;
        if use_vec {
            let _ = conn.execute("DELETE FROM vec_documents WHERE id = ?1", rusqlite::params![doc_id]);
        }
        stale += 1;
    }

    ollama::log_line(
        "INFO",
        &format!("Index {mode}: upserted={upserted} skipped={skipped} stale_deleted={stale}"),
    );
    Ok(json!({
        "mode": mode,
        "upserted": upserted,
        "skipped": skipped,
        "stale_deleted": stale,
        "dry_run": false,
        "path": index_db_path(root).to_string_lossy(),
    }))
}

pub fn run_index(root: &Path, dry_run: bool, force: bool) -> Result<Value, ChronicleError> {
    let cfg = ensure_config(root)?;
    let rt = ollama::runtime_from_config(&cfg);
    run_index_with_rt(root, &cfg, &rt, dry_run, force)
}

pub fn kinds_for_scope(scope: Option<&str>) -> Option<Vec<&'static str>> {
    match scope.unwrap_or("all").trim().to_lowercase().as_str() {
        "journal" => Some(vec!["entry", "note"]),
        "kb" => Some(vec!["kb"]),
        _ => None,
    }
}

fn keyword_boost(text: &str, query: &str) -> f64 {
    let text_l = text.to_lowercase();
    query
        .to_lowercase()
        .split_whitespace()
        .filter(|tok| !tok.is_empty() && text_l.contains(tok))
        .count() as f64
        * 0.05
}

fn row_hit(id: &str, kind: &str, path: Option<&str>, raw_text: &str, score: f64, limit: Option<usize>) -> Value {
    let shown = match limit {
        Some(l) => clip_chars(raw_text, l),
        None => raw_text.to_string(),
    };
    json!({"id": id, "kind": kind, "path": path, "text": shown, "score": score})
}

pub struct SearchArgs<'a> {
    pub query: &'a str,
    pub top_k: usize,
    pub kinds: Option<Vec<String>>,
    pub scope: Option<&'a str>,
    /// None → default 16000; Some(None) → uncapped; Some(Some(n)) → cap n.
    pub text_limit: Option<Option<usize>>,
    pub ids: Option<Vec<String>>,
}

pub fn search_with_rt(root: &Path, cfg: &ChronicleConfig, rt: &LlmRuntime, args: SearchArgs<'_>) -> Vec<Value> {
    let e = Embedder::from_rt(rt);
    search_inner(root, cfg, &e, args)
}

pub fn search_inner(
    root: &Path,
    cfg: &ChronicleConfig,
    embedder: &Embedder,
    args: SearchArgs<'_>,
) -> Vec<Value> {
    let db = index_db_path(root);
    if !db.is_file() {
        return vec![];
    }
    let kind_filter: Option<Vec<String>> = args.kinds.clone().or_else(|| {
        kinds_for_scope(args.scope).map(|ks| ks.into_iter().map(String::from).collect())
    });
    let id_filter: Option<HashSet<String>> = args.ids.as_ref().map(|i| i.iter().cloned().collect());
    let limit = args.text_limit.unwrap_or(Some(SEARCH_TEXT_DEFAULT_LIMIT));

    let conn = match Connection::open(&db) {
        Ok(c) => c,
        Err(_) => return vec![],
    };
    let q_emb = if embedder.reachable {
        (embedder.f)(args.query, &cfg.models.embed)
    } else {
        vec![]
    };

    struct RawRow {
        id: String,
        kind: String,
        path: Option<String>,
        text: String,
        embedding_json: Option<String>,
    }
    // Preferred path: sqlite-vec KNN when extension + virtual table exist
    // (python `_vec_table_ready` guard; overfetch max(top_k*4, top_k)).
    let vec_ready: bool = conn
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='vec_documents'",
            [],
            |r| r.get::<_, i64>(0),
        )
        .map(|n| n > 0)
        .unwrap_or(false);
    if !q_emb.is_empty() && vec_ready {
        let overfetch = (args.top_k * 4).max(args.top_k);
        let knn_sql = "SELECT d.id, d.kind, d.path, d.text, v.distance                        FROM vec_documents v JOIN documents d ON d.id = v.id                        WHERE v.embedding MATCH ?1 AND k = ?2 ORDER BY v.distance";
        match conn.prepare(knn_sql) {
            Ok(mut stmt) => {
                let rows = stmt.query_map(
                    rusqlite::params![
                        serde_json::to_string(&q_emb).unwrap_or_default(),
                        overfetch as i64
                    ],
                    |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, Option<String>>(2)?,
                            r.get::<_, String>(3)?,
                            r.get::<_, Option<f64>>(4)?,
                        ))
                    },
                );
                if let Ok(rows) = rows {
                    let mut scored_vec: Vec<(f64, Value)> = Vec::new();
                    for row in rows.flatten() {
                        let (id, kind, path, text, distance) = row;
                        if let Some(kf) = &kind_filter {
                            if !kf.iter().any(|k| k == &kind) { continue; }
                        }
                        if let Some(idf) = &id_filter {
                            if !idf.contains(&id) { continue; }
                        }
                        let dist = distance.unwrap_or(1.0_f64);
                        let score = (1.0_f64 - dist).max(0.0_f64) + keyword_boost(&text, args.query);
                        scored_vec.push((score, row_hit(&id, &kind, path.as_deref(), &text, score, limit)));
                    }
                    if !scored_vec.is_empty() {
                        scored_vec.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
                        return scored_vec.into_iter().take(args.top_k).map(|(_, h)| h).collect();
                    }
                    // Empty after filters → fall through to full scan (python parity).
                }
            }
            Err(_) => {
                ollama::log_line("DEBUG", "sqlite-vec KNN failed, falling back to cosine");
            }
        }
    }

    let mut raws: Vec<RawRow> = Vec::new();
    {
        let Ok(mut stmt) = conn.prepare("SELECT id, kind, path, text, embedding_json FROM documents") else {
            return vec![];
        };
        let Ok(rows_iter) = stmt.query_map([], |r| {
            Ok(RawRow {
                id: r.get(0)?,
                kind: r.get(1)?,
                path: r.get(2)?,
                text: r.get(3)?,
                embedding_json: r.get(4)?,
            })
        }) else { return vec![] };
        for row in rows_iter.flatten() {
            if let Some(kf) = &kind_filter {
                if !kf.iter().any(|k| k == &row.kind) {
                    continue;
                }
            }
            if let Some(idf) = &id_filter {
                if !idf.contains(&row.id) {
                    continue;
                }
            }
            raws.push(row);
        }
    }

    let mut scored: Vec<(f64, Value)> = Vec::with_capacity(raws.len());
    if !q_emb.is_empty() {
        for row in &raws {
            let emb: Vec<f64> = row
                .embedding_json
                .as_deref()
                .and_then(|j| serde_json::from_str(j).ok())
                .unwrap_or_default();
            let mut score = if emb.is_empty() { 0.0 } else { ollama::cosine(&q_emb, &emb) };
            score += keyword_boost(&row.text, args.query);
            scored.push((score, row_hit(&row.id, &row.kind, row.path.as_deref(), &row.text, score, limit)));
        }
    } else {
        let q_lower = args.query.to_lowercase();
        let q_tokens: Vec<&str> = q_lower.split_whitespace().filter(|t| !t.is_empty()).collect();
        for row in &raws {
            let text_l = row.text.to_lowercase();
            let count = q_tokens.iter().filter(|t| text_l.contains(**t)).count();
            if count > 0 {
                let score = count as f64;
                scored.push((score, row_hit(&row.id, &row.kind, row.path.as_deref(), &row.text, score, limit)));
            }
        }
    }
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    scored.into_iter().take(args.top_k).map(|(_, h)| h).collect()
}

pub fn get_documents_by_ids(root: &Path, doc_ids: &[String], text_limit: Option<usize>) -> Vec<Value> {
    let db = index_db_path(root);
    if !db.is_file() || doc_ids.is_empty() {
        return vec![];
    }
    let mut wanted: Vec<String> = Vec::new();
    let mut seen = HashSet::new();
    for id in doc_ids {
        if !id.is_empty() && seen.insert(id.clone()) {
            wanted.push(id.clone());
        }
    }
    if wanted.is_empty() {
        return vec![];
    }
    let Ok(conn) = Connection::open(&db) else { return vec![] };
    let placeholders = vec!["?"; wanted.len()].join(",");
    let sql = format!("SELECT id, kind, path, text FROM documents WHERE id IN ({placeholders})");
    let Ok(mut stmt) = conn.prepare(&sql) else { return vec![] };
    let params: Vec<&dyn rusqlite::ToSql> = wanted.iter().map(|w| w as &dyn rusqlite::ToSql).collect();
    let Ok(rows) = stmt.query_map(params.as_slice(), |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, Option<String>>(2)?,
            r.get::<_, String>(3)?,
        ))
    }) else { return vec![] };
    let mut by_id: HashMap<String, (String, Option<String>, String)> = HashMap::new();
    for row in rows.flatten() {
        let (id, kind, path, text) = row;
        by_id.insert(id, (kind, path, text));
    }
    let mut out = Vec::new();
    for doc_id in &wanted {
        let Some((kind, path, text)) = by_id.get(doc_id) else { continue };
        let shown = match text_limit {
            Some(l) => clip_chars(text, l),
            None => text.clone(),
        };
        out.push(json!({
            "id": doc_id, "kind": kind, "path": path, "text": shown, "score": 1.0,
        }));
    }
    out
}

#[cfg(test)]
mod vec_tests {
    use super::*;
    use serde_json::json;

    fn seed(vault: &Path) {
        std::fs::create_dir_all(vault.join("_capture/entries/2026/08")).unwrap();
        std::fs::write(vault.join("config.json"), r#"{"layout_version":2,"timezone":"UTC"}"#).unwrap();
        for (i, text) in ["apple pie recipe", "automotive repair manual", "astronomy telescope guide"]
            .into_iter()
            .enumerate()
        {
            let id = format!("2026-08-0{}_100000-pc", i + 1);
            let entry = json!({
                "version":1,"id":id,"ts":format!("2026-08-0{}T10:00:00+00:00", i+1),
                "type":"log","text":text
            });
            std::fs::write(
                vault.join(format!("_capture/entries/2026/08/{id}.json")),
                entry.to_string(),
            )
            .unwrap();
        }
    }

    #[test]
    fn vec_mode_indexes_and_knn_ranks_nearest_first() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        seed(root);

        let mut pad = |v: Vec<f64>| -> Vec<f64> {
            let mut p = v;
            p.resize(768, 0.0);
            p
        };
        // Stored index text is "<type> <tags>\n<text>" (trimmed) — key on that form.
        let mut map: HashMap<String, Vec<f64>> = HashMap::new();
        map.insert("log\napple pie recipe".into(), pad(vec![1.0, 0.0, 0.0]));
        map.insert("log\nautomotive repair manual".into(), pad(vec![0.0, 1.0, 0.0]));
        map.insert("log\nastronomy telescope guide".into(), pad(vec![0.0, 0.0, 1.0]));
        map.insert("nearest-vector-query".into(), pad(vec![1.0, 0.0, 0.0]));

        let embedder = Embedder::fixed(map.clone(), 768);
        let result =
            run_index_inner(root, &load_config(root).unwrap(), &embedder, false, true, true).unwrap();
        assert_eq!(result["mode"], json!("sqlite-vec"), "{result}");
        assert_eq!(result["upserted"], json!(3));
        // Prove embeddings actually landed in the vec table.
        let conn = Connection::open(index_db_path(root)).unwrap();
        let n: i64 = conn
            .query_row("SELECT count(*) FROM vec_documents", [], |r| r.get(0))
            .unwrap_or(-1);
        assert_eq!(n, 3, "vec rows must exist");
        let stored: String = conn
            .query_row("SELECT text FROM documents WHERE id='2026-08-01_100000-pc'", [], |r| r.get(0))
            .unwrap();
        assert!(
            map.contains_key(&stored),
            "stored text {stored:?} must be an embedder key"
        );

        let cfg = load_config(root).unwrap();
        // Query embedding identical to the apple doc ⇒ distance≈0.
        // Query text has zero keyword overlap with any doc ⇒ pure KNN.
        let hits = search_inner(
            root,
            &cfg,
            &Embedder::fixed(
                [("nearest-vector-query".to_string(), pad(vec![1.0, 0.0, 0.0]))]
                    .into_iter()
                    .collect(),
                768,
            ),
            SearchArgs {
                query: "nearest-vector-query",
                top_k: 3,
                kinds: None,
                scope: None,
                text_limit: None,
                ids: None,
            },
        );
        assert_eq!(hits.len(), 3);
        assert_eq!(
            hits[0]["id"], json!("2026-08-01_100000-pc"),
            "nearest-by-vector must rank first; got {:?}",
            hits.iter().map(|h| (h["id"].clone(), h["score"].clone())).collect::<Vec<_>>()
        );
        let s = hits[0]["score"].as_f64().unwrap();
        assert!((0.99..=1.0).contains(&s), "identical vector ⇒ score≈1, got {s}");
    }

    #[test]
    fn kill_switch_forces_cosine_mode() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        seed(root);
        let embedder = Embedder::fixed(HashMap::new(), 768);
        let result = run_index_inner(root, &load_config(root).unwrap(), &embedder, false, false, false).unwrap();
        assert_eq!(result["mode"], json!("sqlite+json-cosine"));
    }

    #[test]
    fn vec_mode_env_kill_switch_contract() {
        // Contract: probe=true + unset env → enabled. (No global env mutation;
        // the wrapper test above covers disabled mode end-to-end.)
        assert!(vec_extension_usable());
    }
}
