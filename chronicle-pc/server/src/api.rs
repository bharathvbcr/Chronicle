//! REST surface (api/*.py port): exact wire shapes, error bodies, ordering.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use axum::extract::{ConnectInfo, Multipart, Path as AxumPath, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{delete, get, patch, post, put};
use axum::{Json, Router};
use serde_json::{json, Map, Value};

use crate::errors::{ApiError, ApiResult};
use crate::models::Entry;
use crate::serve::TOKEN_HEADER;
use crate::{config, kb_enrich, ollama, pipeline, rag, App};

pub const MAX_UPLOAD_BYTES: usize = 50 * 1024 * 1024;
const RATE_MAX: usize = 20;
const RATE_WINDOW_SECS: u64 = 60;

// ---------------------------------------------------------------------------
// Router assembly
// ---------------------------------------------------------------------------

pub fn build_router(state: Arc<App>) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/", get(get_index))
        .route("/legacy", get(get_legacy))
        .route("/connect", get(get_connect))
        .route("/connect/qr.svg", get(get_connect_qr))
        .route("/models", get(get_models).post(post_models))
        .route("/enrich/kb", post(post_enrich_kb))
        .route(
            "/entries",
            get(list_entries).post(create_entry),
        )
        .route(
            "/entries/{id}",
            get(get_entry).patch(patch_entry).delete(delete_entry),
        )
        .route("/entries/{id}/images", post(upload_image))
        .route("/entries/{id}/audio", post(upload_audio))
        .route("/journal/days", get(journal_days))
        .route("/journal/entries/{id}", get(journal_entry).patch(journal_amend))
        .route("/journal/entries/{id}/accept-disk", post(journal_accept_disk))
        .route("/notes", get(list_notes))
        .route("/notes/{*path}", get(get_note_file))
        .route("/kb/templates", get(kb_templates))
        .route("/kb/tree", get(kb_tree))
        .route(
            "/kb/notes/{*path}",
            get(kb_get_note)
                .put(kb_put_note)
                .post(kb_post_note)
                .delete(kb_delete_note),
        )
        .route("/kb/move", post(kb_move))
        .route("/kb/archive", post(kb_archive))
        .route("/brain/graph", get(brain_graph))
        .route("/brain/insights", get(brain_insights))
        .route("/curation/ops", post(post_curation_op))
        .route("/search", post(post_search))
        .route("/recall", post(post_recall))
        .route("/ask", post(post_ask))
        .route("/resume", post(post_resume))
        .route("/process", post(post_process))
        .route("/vault/rebuild-index", post(post_rebuild_index))
        .route("/{*spa_path}", get(spa_catch_all))
        .fallback(not_found)
        // axum's default request-body cap is 2 MB, which would 413 photo /
        // voice-memo uploads before the handler's own 50 MB accounting runs.
        // Applied after route registration so it wraps every route.
        .layer(axum::extract::DefaultBodyLimit::max(MAX_UPLOAD_BYTES))
        .with_state(state)
}

async fn not_found() -> ApiError {
    ApiError::not_found("Not Found")
}

/// Heavy/sync work runs on the blocking pool (FastAPI threadpool analogue).
async fn blocking<T, F>(f: F) -> Result<T, ApiError>
where
    F: FnOnce() -> Result<T, ApiError> + Send + 'static,
    T: Send + 'static,
{
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| ApiError::internal(format!("join error: {e}")))?
}

fn ok_json(v: Value) -> Response {
    Json(v).into_response()
}

// ---------------------------------------------------------------------------
// system: health / connect / models / enrich-kb / SPA roots
// ---------------------------------------------------------------------------

fn compute_provider_probe(state: &App, cfg: &config::ChronicleConfig) -> crate::ProviderProbe {
    let pname = crate::provider::provider_name(cfg);
    let rt = state.llm_runtime();
    let ollama_ok = ollama::ollama_reachable_rt(&rt, std::time::Duration::from_secs(2));
    let mut provider_ok = false;
    let mut provider_error: Option<String> = None;
    match crate::provider::build_provider(cfg) {
        Ok((_, p)) => {
            provider_ok = p.reachable(std::time::Duration::from_secs(2));
            let secrets = crate::provider::load_secrets();
            if crate::provider::is_cloud_provider(&pname)
                && !crate::provider::resolve_cloud_consent(cfg.llm.cloud_consent, &secrets)
            {
                provider_error = Some("cloud_consent required".into());
                provider_ok = false;
            }
        }
        Err(e) => {
            provider_error = Some(e.to_string());
        }
    }
    crate::ProviderProbe { ollama_ok, provider: pname, provider_ok, provider_error }
}

async fn health(
    State(state): State<Arc<App>>,
) -> Result<Response, ApiError> {
    blocking(move || {
        let cfg = config::ensure_config(&state.root).map_err(|e| match e {
            crate::errors::ChronicleError::Layout(m) => ApiError::internal(m),
            other => other.into(),
        })?;
        let cached = {
            let guard = state.health_cache.lock().unwrap();
            guard
                .as_ref()
                .filter(|(t, _)| t.elapsed() < crate::HEALTH_CACHE_TTL)
                .map(|(_, p)| p.clone())
        };
        let probe = match cached {
            Some(p) => p,
            None => {
                let p = compute_provider_probe(&state, &cfg);
                *state.health_cache.lock().unwrap() =
                    Some((std::time::Instant::now(), p.clone()));
                p
            }
        };
        let models = json!({
            "llm": cfg.models.llm, "embed": cfg.models.embed,
            "vision": cfg.models.vision, "whisper": cfg.models.whisper,
        });
        Ok(ok_json(json!({
            "ok": true,
            "chronicle": {
                "ok": true,
                "chronicle_dir": state.root.to_string_lossy(),
                "ollama": probe.ollama_ok,
                "provider": probe.provider,
                "provider_ok": probe.provider_ok,
                "embed_ok": probe.ollama_ok,
                "models": models,
                "ask_resume": "native",
            },
            "chronicle_dir": state.root.to_string_lossy(),
            "ollama": probe.ollama_ok,
            "provider": probe.provider,
            "provider_ok": probe.provider_ok,
            "models": models,
        })))
    })
    .await
}

fn pc_root() -> Option<PathBuf> {
    fn looks_like_pc(p: &Path) -> bool {
        p.is_dir()
            && (p.join("frontend/dist/index.html").is_file() || p.join("dashboard").is_dir())
    }
    if let Ok(env) = std::env::var("CHRONICLE_PC_ROOT") {
        let p = PathBuf::from(env.trim());
        if looks_like_pc(&p) {
            return Some(p);
        }
    }
    // Persisted hint written by Start Chronicle.command / installs.
    let support = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
        .join("Library/Application Support/Chronicle/pc_root");
    if let Ok(raw) = std::fs::read_to_string(&support) {
        if let Some(line) = raw.lines().next().map(str::trim).filter(|l| !l.is_empty()) {
            let p = PathBuf::from(line);
            if looks_like_pc(&p) {
                return Some(p);
            }
        }
    }
    // Repo-checkout convention.
    if let Some(home) = std::env::var_os("HOME").map(PathBuf::from) {
        let candidate = home.join("Code/Chronicle/chronicle-pc");
        if looks_like_pc(&candidate) {
            return Some(candidate);
        }
    }
    let mut cur = std::env::current_exe().ok()?.parent().map(Path::to_path_buf)?;
    for _ in 0..10 {
        if looks_like_pc(&cur) {
            return Some(cur);
        }
        cur = cur.parent()?.to_path_buf();
    }
    None
}

fn frontend_dist() -> Option<PathBuf> {
    if let Ok(env) = std::env::var("CHRONICLE_FRONTEND_DIST") {
        let p = PathBuf::from(env);
        if p.join("index.html").is_file() {
            return Some(p);
        }
    }
    let dist = pc_root()?.join("frontend").join("dist");
    if dist.join("index.html").is_file() {
        Some(dist)
    } else {
        None
    }
}

async fn get_index() -> Response {
    if let Some(dist) = frontend_dist() {
        if let Ok(bytes) = std::fs::read(dist.join("index.html")) {
            return (
                [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
                bytes,
            )
                .into_response();
        }
    }
    if let Some(root) = pc_root() {
        let dash = root.join("dashboard").join("dashboard.html");
        if dash.is_file() {
            if let Ok(bytes) = std::fs::read(dash) {
                return (
                    [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
                    bytes,
                )
                    .into_response();
            }
        }
    }
    ApiError::not_found(format!(
        "no UI found (build frontend/ or missing {})",
        pc_root()
            .map(|r| r.join("dashboard/dashboard.html").to_string_lossy().to_string())
            .unwrap_or_else(|| "dashboard/dashboard.html".into())
    ))
    .into_response()
}

async fn get_legacy() -> Response {
    let layout = config::load_config(&crate::paths::resolve_chronicle_dir(None).unwrap_or_default())
        .map(|c| c.layout_version)
        .unwrap_or(2);
    if layout >= 2 {
        return Redirect::temporary("/").into_response();
    }
    if let Some(root) = pc_root() {
        let dash = root.join("dashboard").join("dashboard.html");
        if dash.is_file() {
            if let Ok(bytes) = std::fs::read(dash) {
                return (
                    [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
                    bytes,
                )
                    .into_response();
            }
        }
    }
    ApiError::not_found(format!(
        "dashboard not found at {}",
        pc_root().map(|r| r.join("dashboard/dashboard.html").to_string_lossy().to_string()).unwrap_or_default()
    ))
    .into_response()
}

fn connect_body(state: &App, include_token: bool) -> Value {
    let base = &state.connect_info.base;
    let token = if include_token { state.token.clone() } else { None };
    let mut qr = json!({"v": 1, "base": base});
    if let Some(t) = &token {
        qr["token"] = json!(t);
    }
    json!({
        "v": 1,
        "host": state.connect_info.host,
        "port": state.connect_info.port,
        "bind_host": state.connect_info.bind_host,
        "lan_ip": state.connect_info.lan_ip,
        "base": base,
        "kb_proxied": false,
        "token": token,
        "auth_required": state.auth_required,
        "qr": qr,
    })
}

async fn get_connect(
    State(state): State<Arc<App>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
) -> Response {
    ok_json(connect_body(&state, peer.ip().is_loopback()))
}

fn qr_svg(payload: &str) -> Result<String, ApiError> {
    use qrcode::EcLevel;
    let code = qrcode::QrCode::with_error_correction_level(payload.as_bytes(), EcLevel::M)
        .map_err(|e| ApiError::internal(e.to_string()))?;
    let width = code.width();
    let colors = code.to_colors();
    let quiet = 4usize;
    let scale = 4usize;
    let total = (width + quiet * 2) * scale;
    let mut svg = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{total}\" height=\"{total}\" viewBox=\"0 0 {total} {total}\">\
         <rect width=\"{total}\" height=\"{total}\" fill=\"#ffffff\"/>"
    );
    for y in 0..width {
        for x in 0..width {
            if colors[y * width + x] == qrcode::Color::Dark {
                svg.push_str(&format!(
                    "<rect x=\"{}\" y=\"{}\" width=\"{scale}\" height=\"{scale}\" fill=\"#2A0E12\"/>",
                    (x + quiet) * scale,
                    (y + quiet) * scale
                ));
            }
        }
    }
    svg.push_str("</svg>");
    Ok(svg)
}

async fn get_connect_qr(
    State(state): State<Arc<App>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
) -> Result<Response, ApiError> {
    let body = connect_body(&state, peer.ip().is_loopback());
    let payload = body["qr"].to_string(); // compact JSON of the QR payload object
    let svg = qr_svg(&payload)?;
    Ok((
        [(header::CONTENT_TYPE, "image/svg+xml")],
        svg,
    )
        .into_response())
}

fn models_state(state: &App) -> Value {
    let cfg = match config::ensure_config(&state.root) {
        Ok(c) => c,
        Err(_) => return fallback_models_state(state),
    };
    let pname = crate::provider::provider_name(&cfg);
    let probe = compute_provider_probe(state, &cfg);
    let available = if probe.ollama_ok {
        ollama::list_available_models(&state.llm_runtime(), std::time::Duration::from_secs(3))
    } else {
        vec![]
    };
    json!({
        "llm": cfg.models.llm,
        "embed": cfg.models.embed,
        "vision": cfg.models.vision,
        "base_url": cfg.ollama.base_url,
        "num_ctx": cfg.ollama.num_ctx,
        "temperature": cfg.ollama.temperature,
        "available": available,
        "ollama_ok": probe.ollama_ok,
        "provider": pname,
        "provider_ok": probe.provider_ok,
        "provider_error": probe.provider_error,
        "cloud_consent": cfg.llm.cloud_consent,
        "vision_cloud_consent": cfg.llm.vision_cloud_consent,
        "grok_base_url": cfg.llm.grok.base_url,
        "grok_model": cfg.llm.grok.model,
        "vertex_project": cfg.llm.vertex.project,
        "vertex_location": cfg.llm.vertex.location,
        "vertex_model": cfg.llm.vertex.model,
        "embed_note": "Embeddings always use local Ollama nomic-embed-text @ 768",
    })
}

fn fallback_models_state(_state: &App) -> Value {
    let cfg = config::ChronicleConfig::default();
    json!({
        "llm": cfg.models.llm, "embed": cfg.models.embed, "vision": cfg.models.vision,
        "base_url": cfg.ollama.base_url, "num_ctx": cfg.ollama.num_ctx,
        "temperature": Value::Null, "available": [], "ollama_ok": false,
        "provider": crate::provider::provider_name(&cfg), "provider_ok": false,
        "provider_error": Value::Null,
        "cloud_consent": false, "vision_cloud_consent": false,
        "grok_base_url": cfg.llm.grok.base_url, "grok_model": Value::Null,
        "vertex_project": Value::Null, "vertex_location": cfg.llm.vertex.location,
        "vertex_model": Value::Null,
        "embed_note": "Embeddings always use local Ollama nomic-embed-text @ 768",
    })
}

async fn get_models(State(state): State<Arc<App>>) -> Response {
    ok_json(models_state(&state))
}

async fn post_models(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be a JSON object"))?;
    let obj = body
        .as_object()
        .cloned()
        .ok_or_else(|| ApiError::bad_request("body must be a JSON object"))?;
    blocking(move || {
        if obj.is_empty() {
            return Err(ApiError::bad_request("provide at least one field to update"));
        }
        let mut cfg = config::ensure_config(&state.root)?;
        let available = if ollama::ollama_reachable_rt(&state.llm_runtime(), std::time::Duration::from_secs(2)) {
            ollama::list_available_models(&state.llm_runtime(), std::time::Duration::from_secs(3))
        } else {
            vec![]
        };

        let get_s = |k: &str| obj.get(k).and_then(Value::as_str);
        let has = |k: &str| obj.contains_key(k);

        if has("provider") {
            match get_s("provider") {
                Some(p) if matches!(p, "ollama" | "grok" | "vertex") => cfg.llm.provider = p.into(),
                _ => return Err(ApiError::bad_request("provider must be one of ollama, grok, vertex")),
            }
        }
        let provider_is_ollama = cfg.llm.provider == "ollama";

        let check_model = |name: Option<&str>, field: &str| -> Result<String, ApiError> {
            let name = name.unwrap_or_default().trim();
            if name.is_empty() {
                return Err(ApiError::bad_request(format!("{field} must be a non-empty model name")));
            }
            if field == "embed" || provider_is_ollama {
                if !available.is_empty() && !available.iter().any(|a| a == name) {
                    return Err(ApiError::bad_request(format!(
                        "{field} '{name}' is not in Ollama tags. Available: {}",
                        available.join(", ")
                    )));
                }
            }
            Ok(name.to_string())
        };

        if has("llm") {
            cfg.models.llm = check_model(get_s("llm"), "llm")?;
        }
        if has("embed") {
            cfg.models.embed = check_model(get_s("embed"), "embed")?;
        }
        if has("vision") {
            cfg.models.vision = check_model(get_s("vision"), "vision")?;
        }
        if has("cloud_consent") {
            cfg.llm.cloud_consent = obj.get("cloud_consent").and_then(Value::as_bool).unwrap_or(false);
        }
        if has("vision_cloud_consent") {
            cfg.llm.vision_cloud_consent = obj.get("vision_cloud_consent").and_then(Value::as_bool).unwrap_or(false);
        }
        if has("base_url") {
            let url = get_s("base_url").unwrap_or_default();
            cfg.ollama.base_url = crate::provider::validate_ollama_base_url(url)
                .map_err(|e| ApiError::bad_request(e.to_string()))?;
        }
        if has("num_ctx") {
            let n = obj.get("num_ctx").and_then(Value::as_i64).unwrap_or(0);
            if n <= 0 {
                return Err(ApiError::bad_request("num_ctx must be a positive integer"));
            }
            cfg.ollama.num_ctx = n;
        }
        if has("temperature") {
            match obj.get("temperature") {
                Some(Value::Null) | None => cfg.ollama.temperature = None,
                Some(v) => match v.as_f64() {
                    Some(t) if (0.0..=2.0).contains(&t) => cfg.ollama.temperature = Some(t),
                    _ => return Err(ApiError::bad_request("temperature must be between 0 and 2")),
                },
            }
        }
        if has("grok_base_url") {
            let url = get_s("grok_base_url").unwrap_or_default();
            cfg.llm.grok.base_url = crate::provider::validate_grok_base_url(url)
                .map_err(|e| ApiError::bad_request(e.to_string()))?;
        }
        if has("grok_model") {
            cfg.llm.grok.model = get_s("grok_model")
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);
        }
        if has("vertex_project") {
            cfg.llm.vertex.project = get_s("vertex_project")
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);
        }
        if has("vertex_location") {
            if obj.get("vertex_location").is_some_and(Value::is_null) {
                // present-and-null skipped (python quirk)
            } else {
                let loc = get_s("vertex_location").unwrap_or("").trim().to_string();
                if loc.is_empty() {
                    return Err(ApiError::bad_request("vertex_location must be non-empty"));
                }
                cfg.llm.vertex.location = loc;
            }
        }
        if has("vertex_model") {
            cfg.llm.vertex.model = get_s("vertex_model")
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);
        }

        config::save_config(&state.root, &cfg)?;
        state.apply_ollama_settings(&cfg.ollama.base_url, cfg.ollama.num_ctx, cfg.ollama.temperature);
        Ok(ok_json(models_state(&state)))
    })
    .await
}

async fn post_enrich_kb(State(state): State<Arc<App>>) -> Result<Response, ApiError> {
    blocking(move || {
        let cfg = config::ensure_config(&state.root)?;
        let built = crate::provider::build_provider(&cfg).ok();
        let provider = built.as_ref().map(|(_, p)| p.as_ref());
        let limits = crate::provider::limits_for(&crate::provider::provider_name(&cfg));
        let mut result = kb_enrich::run_kb_enrich(
            &state.root,
            provider,
            &cfg.models.llm,
            limits.num_ctx_enrich,
            false,
        )?;
        let idx = crate::index_store::run_index_with_rt(
            &state.root,
            &cfg,
            &state.llm_runtime(),
            false,
            false,
        )?;
        result["index"] = json!({
            "upserted": idx.get("upserted"),
            "skipped": idx.get("skipped"),
        });
        Ok(ok_json(result))
    })
    .await
}

// ---------------------------------------------------------------------------
// entries
// ---------------------------------------------------------------------------

#[derive(serde::Deserialize)]
struct ListParams {
    limit: Option<String>,
    offset: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>,
    processed: Option<String>,
    from: Option<String>,
    to: Option<String>,
}

fn parse_date_param(name: &str, raw: &Option<String>) -> Result<Option<chrono::NaiveDate>, ApiError> {
    match raw {
        None => Ok(None),
        Some(s) if s.trim().is_empty() => Ok(None),
        Some(s) => chrono::NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d")
            .map(Some)
            .map_err(|_| ApiError::bad_request(format!("invalid {name}: expected YYYY-MM-DD"))),
    }
}

async fn list_entries(
    State(state): State<Arc<App>>,
    Query(params): Query<ListParams>,
) -> Result<Response, ApiError> {
    blocking(move || {
        let limit: i64 = params
            .limit
            .as_deref()
            .map(str::parse)
            .transpose()
            .map_err(|_| ApiError::bad_request("invalid limit"))?
            .unwrap_or(100);
        if !(1..=1000).contains(&limit) {
            return Err(ApiError::bad_request("limit must be between 1 and 1000"));
        }
        let offset: i64 = params
            .offset
            .as_deref()
            .map(str::parse)
            .transpose()
            .map_err(|_| ApiError::bad_request("invalid offset"))?
            .unwrap_or(0);
        if offset < 0 {
            return Err(ApiError::bad_request("invalid offset"));
        }
        if let Some(t) = &params.kind {
            if !Entry::valid_type(t) {
                return Err(ApiError::bad_request(format!("invalid type filter: {t}")));
            }
        }
        let processed_filter: Option<bool> = match params.processed.as_deref() {
            None | Some("") => None,
            Some("true") | Some("1") => Some(true),
            Some("false") | Some("0") => Some(false),
            Some(other) => {
                return Err(ApiError::bad_request(format!("invalid processed: {other}")));
            }
        };
        let from_d = parse_date_param("from", &params.from)?;
        let to_d = parse_date_param("to", &params.to)?;
        if let (Some(f), Some(t)) = (from_d, to_d) {
            if f > t {
                return Err(ApiError::bad_request("from must be on or before to"));
            }
        }
        let cfg = config::load_config(&state.root)?;

        let mut all = crate::entries::load_all_entries(&state.root)?;
        all.reverse(); // newest first
        let filtered: Vec<&Entry> = all
            .iter()
            .filter(|e| params.kind.as_ref().map(|t| e.kind == *t).unwrap_or(true))
            .filter(|e| processed_filter.map(|p| e.processed == p).unwrap_or(true))
            .filter(|e| {
                let day = crate::timeutil::entry_day(&e.ts, &e.id, &cfg.timezone);
                from_d.map(|f| day >= f).unwrap_or(true) && to_d.map(|t| day <= t).unwrap_or(true)
            })
            .collect();
        let total = filtered.len() as i64;
        let page: Vec<Value> = filtered
            .iter()
            .skip(offset as usize)
            .take(limit as usize)
            .map(|e| e.to_api_value())
            .collect();
        Ok(ok_json(json!({
            "total": total, "offset": offset, "limit": limit, "entries": page,
        })))
    })
    .await
}

fn load_entry_checked(state: &App, id: &str) -> Result<Entry, ApiError> {
    crate::entries::validate_id(id).map_err(ApiError::from)?;
    let path = crate::entries::entry_path(&state.root, id).map_err(ApiError::from)?;
    if !path.is_file() {
        return Err(ApiError::not_found(format!("entry not found: {id}")));
    }
    crate::entries::load_entry(&path)
        .ok_or_else(|| ApiError::internal(format!("failed to load entry: {id}")))
}

async fn get_entry(
    State(state): State<Arc<App>>,
    AxumPath(id): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || {
        let entry = load_entry_checked(&state, &id)?;
        Ok(ok_json(entry.to_api_value()))
    })
    .await
}

const MOOD_MSG: &str = "mood must be 1\u{2013}5 or null";

async fn create_entry(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    blocking(move || {
        let _guard = crate::lock::vault_lock(&state.root, Some(std::time::Duration::from_secs(30)))?;
        let obj = body.as_object().cloned().unwrap_or_default();
        let get_s = |k: &str| obj.get(k).and_then(Value::as_str);

        let kind = get_s("type").unwrap_or("log");
        if !Entry::valid_type(kind) {
            return Err(ApiError::bad_request("type must be one of log, idea, dream, reflection"));
        }
        let mood = match obj.get("mood") {
            None | Some(Value::Null) => None,
            Some(v) => match v.as_i64() {
                Some(m) => Some(m),
                None => return Err(ApiError::bad_request(MOOD_MSG)),
            },
        };
        if let Some(m) = mood {
            if !(1..=5).contains(&m) {
                return Err(ApiError::bad_request(MOOD_MSG));
            }
        }
        let tags: Vec<String> = obj
            .get("tags")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
            .unwrap_or_default();

        // ts handling: aware stored verbatim (trimmed); naive localized.
        let ts = match get_s("ts").filter(|s| !s.trim().is_empty()) {
            Some(raw) => match crate::timeutil::parse_iso_aware(raw) {
                Some((_dt, false)) => raw.trim().to_string(),
                Some((dt, true)) => dt.format("%Y-%m-%dT%H:%M:%S%:z").to_string(),
                None => {
                    return Err(ApiError::bad_request(format!(
                        "invalid ts: Invalid isoformat string: {raw:?}"
                    )))
                }
            },
            None => chrono::Local::now().format("%Y-%m-%dT%H:%M:%S%:z").to_string(),
        };

        let id = match get_s("id") {
            Some(given) => {
                crate::entries::validate_id(given).map_err(ApiError::from)?;
                if !(given.ends_with("-pc") || given.contains("-pc_")) {
                    return Err(ApiError::bad_request("API writes must use -pc entry ids"));
                }
                if crate::entries::entry_path(&state.root, given)
                    .map(|p| p.is_file())
                    .unwrap_or(false)
                {
                    return Err(ApiError::conflict(format!("entry already exists: {given}")));
                }
                given.to_string()
            }
            None => crate::entries::next_pc_id(&state.root, chrono::Local::now().fixed_offset()),
        };

        let images = validate_refs(&state.root, &obj, "images", "img")?;
        let audio = validate_refs(&state.root, &obj, "audio", "audio")?;

        let entry = Entry {
            version: 1,
            id,
            ts,
            kind: kind.to_string(),
            text: get_s("text").unwrap_or_default().to_string(),
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
        };
        crate::entries::save_entry(&state.root, &entry)?;
        Ok((StatusCode::CREATED, Json(entry.to_api_value())).into_response())
    })
    .await
}

fn validate_refs(root: &Path, obj: &Map<String, Value>, key: &str, kind: &str) -> Result<Vec<String>, ApiError> {
    let arr = match obj.get(key) {
        None => return Ok(vec![]),
        Some(Value::Array(a)) => a.clone(),
        Some(_) => return Err(ApiError::bad_request(format!("{key} must be an array"))),
    };
    let mut out = Vec::with_capacity(arr.len());
    for r in arr {
        let s = r.as_str().unwrap_or_default();
        crate::media::validate_media_rel(root, s, kind).map_err(ApiError::from)?;
        out.push(s.to_string());
    }
    Ok(out)
}

async fn patch_entry(
    State(state): State<Arc<App>>,
    AxumPath(id): AxumPath<String>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    blocking(move || {
        crate::entries::validate_id(&id).map_err(ApiError::from)?;
        let path = crate::entries::entry_path(&state.root, &id)?;
        if !path.is_file() {
            return Err(ApiError::not_found(format!("entry not found: {id}")));
        }
        let mut entry = crate::entries::load_entry(&path)
            .ok_or_else(|| ApiError::internal(format!("failed to load entry: {id}")))?;
        if entry.processed {
            return Err(ApiError::conflict(format!(
                "entry {id} is processed=true and cannot be edited or deleted"
            )));
        }
        let obj = body.as_object().cloned().unwrap_or_default();
        if obj.is_empty() {
            return Err(ApiError::bad_request("provide at least one field to update"));
        }
        if let Some(t) = obj.get("type").and_then(Value::as_str) {
            if !Entry::valid_type(t) {
                return Err(ApiError::bad_request("type must be one of log, idea, dream, reflection"));
            }
            entry.kind = t.to_string();
        }
        if let Some(t) = obj.get("text").and_then(Value::as_str) {
            entry.text = t.to_string();
        }
        if let Some(tags) = obj.get("tags").filter(|v| !v.is_null()) {
            entry.tags = tags
                .as_array()
                .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
                .unwrap_or_default();
        }
        if obj.contains_key("mood") {
            match obj.get("mood") {
                Some(Value::Null) | None => entry.mood = None,
                Some(v) => match v.as_i64() {
                    Some(m) if (1..=5).contains(&m) => entry.mood = Some(m),
                    _ => return Err(ApiError::bad_request(MOOD_MSG)),
                },
            }
        }
        if let Some(imgs) = obj.get("images").filter(|v| !v.is_null()) {
            entry.images = imgs
                .as_array()
                .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
                .unwrap_or_default();
            for r in &entry.images {
                crate::media::validate_media_rel(&state.root, r, "img").map_err(ApiError::from)?;
            }
        }
        if let Some(ads) = obj.get("audio").filter(|v| !v.is_null()) {
            entry.audio = ads
                .as_array()
                .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
                .unwrap_or_default();
            for r in &entry.audio {
                crate::media::validate_media_rel(&state.root, r, "audio").map_err(ApiError::from)?;
            }
        }
        crate::entries::save_entry(&state.root, &entry)?;
        Ok(ok_json(entry.to_api_value()))
    })
    .await
}

async fn delete_entry(
    State(state): State<Arc<App>>,
    AxumPath(id): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || {
        crate::entries::validate_id(&id).map_err(ApiError::from)?;
        let path = crate::entries::entry_path(&state.root, &id)?;
        if !path.is_file() {
            return Err(ApiError::not_found(format!("entry not found: {id}")));
        }
        let entry = crate::entries::load_entry(&path)
            .ok_or_else(|| ApiError::internal(format!("failed to load entry: {id}")))?;
        if entry.processed {
            return Err(ApiError::conflict(format!(
                "entry {id} is processed=true and cannot be edited or deleted"
            )));
        }
        let _guard = crate::lock::vault_lock(&state.root, Some(std::time::Duration::from_secs(30)))?;
        std::fs::remove_file(&path).map_err(|e| crate::errors::ChronicleError::Io(e.to_string()))?;
        Ok(ok_json(json!({"ok": true, "deleted": id})))
    })
    .await
}

async fn read_upload(mut mp: Multipart) -> Result<(Option<String>, Vec<u8>), ApiError> {
    let mut filename: Option<String> = None;
    let mut data: Vec<u8> = Vec::new();
    while let Some(field) = mp
        .next_field()
        .await
        .map_err(|e| ApiError::bad_request(format!("multipart error: {e}")))?
    {
        if field.name() != Some("file") {
            continue;
        }
        filename = field.file_name().map(String::from);
        let mut chunk_stream = field;
        loop {
            let chunk = chunk_stream
                .chunk()
                .await
                .map_err(|e| ApiError::bad_request(format!("multipart read error: {e}")))?;
            let Some(bytes) = chunk else { break };
            if data.len() + bytes.len() > MAX_UPLOAD_BYTES {
                return Err(ApiError::payload_too_large(format!(
                    "upload exceeds {MAX_UPLOAD_BYTES} bytes"
                )));
            }
            data.extend_from_slice(&bytes);
        }
        break;
    }
    Ok((filename, data))
}

fn next_media_index(root: &Path, folder: &str, entry_id: &str) -> u32 {
    let (yyyy, mm) = crate::entries::shard_from_id(entry_id).unwrap_or(("0000".into(), "00".into()));
    let dirs = [root.join("_attachments").join(&yyyy).join(&mm), root.join(folder).join(&yyyy).join(&mm)];
    let prefix = format!("{entry_id}_");
    let mut used = std::collections::HashSet::new();
    for d in dirs {
        if let Ok(rd) = std::fs::read_dir(&d) {
            for f in rd.flatten() {
                let name = f.file_name().to_string_lossy().to_string();
                if let Some(rest) = name.strip_prefix(&prefix) {
                    let stem = rest.split('.').next().unwrap_or("");
                    if !stem.is_empty() && stem.chars().all(|c| c.is_ascii_digit()) {
                        if let Ok(n) = stem.parse::<u32>() {
                            used.insert(n);
                        }
                    }
                }
            }
        }
    }
    (1..).find(|n| !used.contains(n)).unwrap_or(1)
}

macro_rules! upload_handler {
    ($fname:ident, $kind:expr, $ext:expr) => {
        async fn $fname(
            State(state): State<Arc<App>>,
            AxumPath(id): AxumPath<String>,
            mp: Multipart,
        ) -> Result<Response, ApiError> {
            let (filename, data) = read_upload(mp).await?;
            blocking(move || {
                if $kind == "audio" {
                    let fname = filename.clone().unwrap_or_default().to_lowercase();
                    if filename.is_none() {
                        return Err(ApiError::bad_request("audio upload requires a filename"));
                    }
                    if !fname.ends_with(".m4a") {
                        return Err(ApiError::bad_request("audio must be .m4a"));
                    }
                    if data.is_empty() {
                        return Err(ApiError::bad_request("empty audio upload"));
                    }
                    if !crate::media::is_mp4_container(&data) {
                        return Err(ApiError::bad_request(
                            "audio must be an MP4 (.m4a) container",
                        ));
                    }
                } else if !crate::media::is_jpeg(&data) {
                    return Err(ApiError::bad_request("images must be JPEG"));
                }
                let _guard = crate::lock::vault_lock(&state.root, Some(std::time::Duration::from_secs(30)))?;
                crate::entries::validate_id(&id).map_err(ApiError::from)?;
                let path = crate::entries::entry_path(&state.root, &id)?;
                if !path.is_file() {
                    return Err(ApiError::not_found(format!("entry not found: {id}")));
                }
                let mut entry = crate::entries::load_entry(&path)
                    .ok_or_else(|| ApiError::internal(format!("failed to load entry: {id}")))?;
                if entry.processed {
                    return Err(ApiError::conflict(format!(
                        "entry {id} is processed=true and cannot be edited or deleted"
                    )));
                }
                let (yyyy, mm) = crate::entries::shard_from_id(&id)?;
                let n = next_media_index(&state.root, $kind, &id);
                let rel = format!("_attachments/{yyyy}/{mm}/{id}_{n}.{}", $ext);
                crate::paths::atomic_write_bytes(&state.root.join(&rel), &data)?;
                let kind_s: &str = $kind;
                if kind_s == "img" {
                    entry.images.push(rel.clone());
                } else if kind_s == "audio" {
                    entry.audio.push(rel.clone());
                }
                crate::entries::save_entry(&state.root, &entry)?;
                Ok((
                    StatusCode::CREATED,
                    Json(json!({"path": rel, "entry": entry.to_api_value()})),
                )
                    .into_response())
            })
            .await
        }
    };
}

upload_handler!(upload_image, "img", "jpg");
upload_handler!(upload_audio, "audio", "m4a");

// ---------------------------------------------------------------------------
// journal
// ---------------------------------------------------------------------------

async fn journal_days(State(state): State<Arc<App>>) -> Result<Response, ApiError> {
    blocking(move || {
        let files = crate::journal::list_day_files(&state.root);
        let days: Vec<Value> = files
            .iter()
            .map(|(d, p)| {
                let text = std::fs::read_to_string(p).unwrap_or_default();
                json!({
                    "date": d.format("%Y-%m-%d").to_string(),
                    "path": format!("40-Journal/{}.md", d.format("%Y-%m-%d")),
                    "entry_ids": crate::journal::list_fenced_ids(&text),
                })
            })
            .collect();
        Ok(ok_json(json!({ "days": days })))
    })
    .await
}

async fn journal_entry(
    State(state): State<Arc<App>>,
    AxumPath(id): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || {
        crate::entries::validate_id(&id).map_err(ApiError::from)?;
        let path = crate::entries::entry_path(&state.root, &id)?;
        if !path.is_file() {
            return Err(ApiError::not_found(format!("entry not found: {id}")));
        }
        let entry = crate::entries::load_entry(&path)
            .ok_or_else(|| ApiError::internal(format!("failed to load entry: {id}")))?;
        let filed_rel = entry
            .get_filed_path()
            .ok_or_else(|| ApiError::not_found(format!("entry not filed: {id}")))?;
        let filed_rel = crate::journal::validate_filed_rel(&filed_rel)
            .map_err(ApiError::not_found)?;
        let day_path = state.root.join(&filed_rel);
        let text = std::fs::read_to_string(&day_path).map_err(|_| {
            ApiError::not_found(format!("journal day file missing: {filed_rel}"))
        })?;
        let body = crate::journal::extract_block(&text, &id)
            .ok_or_else(|| ApiError::not_found(format!("fence missing for entry: {id}")))?;
        let body_hash = crate::paths::content_hash(&body);
        let filed_hash = entry.get_filed_hash();
        Ok(ok_json(json!({
            "id": id,
            "date": PathBuf::from(&filed_rel)
                .file_stem()
                .map(|s| s.to_string_lossy().to_string()),
            "path": filed_rel,
            "body": body,
            "body_hash": body_hash,
            "filed_content_hash": filed_hash,
            "editable": filed_hash.as_deref() == Some(body_hash.as_str()),
        })))
    })
    .await
}

async fn journal_amend(
    State(state): State<Arc<App>>,
    AxumPath(id): AxumPath<String>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| {
        ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, json!([{"msg": "valid JSON body required"}]))
    })?;
    let new_body = body
        .get("body")
        .and_then(Value::as_str)
        .filter(|b| !b.is_empty())
        .ok_or_else(|| {
            ApiError::new(
                StatusCode::UNPROCESSABLE_ENTITY,
                json!([{"loc": ["body"], "msg": "String should have at least 1 character"}]),
            )
        })?
        .to_string();
    let base_hash = body
        .get("base_hash")
        .and_then(Value::as_str)
        .filter(|h| regex::Regex::new(r"^[0-9a-f]{64}$").unwrap().is_match(h))
        .ok_or_else(|| {
            ApiError::new(
                StatusCode::UNPROCESSABLE_ENTITY,
                json!([{"loc": ["base_hash"], "msg": "pattern mismatch"}]),
            )
        })?
        .to_string();
    blocking(move || {
        crate::journal::amend_filed_block(&state.root, &id, &new_body, &base_hash)
            .map_err(ApiError::from)
    })
    .await
    .map(ok_json)
}

async fn journal_accept_disk(
    State(state): State<Arc<App>>,
    AxumPath(id): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || crate::journal::accept_disk_as_base(&state.root, &id).map_err(ApiError::from))
        .await
        .map(ok_json)
}

// ---------------------------------------------------------------------------
// notes browse
// ---------------------------------------------------------------------------

async fn list_notes(State(state): State<Arc<App>>) -> Result<Response, ApiError> {
    blocking(move || {
        let mut files: Vec<Value> = Vec::new();
        let push = |p: PathBuf, files: &mut Vec<Value>| {
            let rel = p
                .strip_prefix(&state.root)
                .map(|r| r.to_string_lossy().replace('\\', "/"))
                .unwrap_or_default();
            let name = p
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            if name.starts_with('.') || name.contains(".sync-conflict") {
                return;
            }
            files.push(json!({"path": rel, "name": name}));
        };
        let upcoming = state.root.join("Upcoming.md");
        if upcoming.is_file() {
            push(upcoming, &mut files);
        }
        for dir in ["40-Journal", "_system/derived", "notes"] {
            collect_md(&state.root.join(dir), &state.root, &mut files);
        }
        Ok(ok_json(json!({ "files": files })))
    })
    .await
}

fn collect_md(dir: &Path, root: &Path, files: &mut Vec<Value>) {
    let mut out: Vec<PathBuf> = Vec::new();
    crate::paths::walk_files_filtered(dir, &mut out, 0, &|_p, name| {
        !name.starts_with('.') && !name.contains(".sync-conflict") && name.ends_with(".md")
    });
    out.sort();
    for p in out {
        let rel = p
            .strip_prefix(root)
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default();
        let name = p
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        files.push(json!({"path": rel, "name": name}));
    }
}

async fn get_note_file(
    State(state): State<Arc<App>>,
    AxumPath(rel_raw): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || {
        let norm = crate::path_map::_norm(&rel_raw);
        if norm.split('/').any(|c| c == "..") || norm.contains('\0') {
            return Err(ApiError::bad_request("invalid note path"));
        }
        let safe = regex::Regex::new(r"^[A-Za-z0-9._\- /]+$").unwrap();
        if !safe.is_match(&norm) {
            return Err(ApiError::bad_request("note path has invalid characters"));
        }
        if !norm.ends_with(".md") {
            return Err(ApiError::bad_request("note path must end with .md"));
        }
        let allowed_prefixes = ["notes/", "40-Journal/", "_system/derived/"];
        let rel = if norm == "Upcoming.md" {
            norm
        } else if allowed_prefixes.iter().any(|p| norm.starts_with(p)) {
            norm
        } else {
            format!("notes/{norm}")
        };
        if !allowed_prefixes.contains(&rel.as_str()) && !allowed_prefixes.iter().any(|p| rel.starts_with(p)) && rel != "Upcoming.md" {
            return Err(ApiError::bad_request(
                "path must be under notes/, 40-Journal/, or _system/derived/",
            ));
        }
        let abs = state.root.join(&rel);
        let resolved = abs.canonicalize().unwrap_or(abs.clone());
        let root_resolved = state.root.canonicalize().unwrap_or_else(|_| state.root.clone());
        if !resolved.starts_with(&root_resolved) {
            return Err(ApiError::bad_request("path escapes vault"));
        }
        if !resolved.is_file() {
            return Err(ApiError::not_found(format!("note not found: {rel}")));
        }
        let content = std::fs::read_to_string(&resolved).map_err(|e| crate::errors::ChronicleError::Io(e.to_string()))?;
        Ok(ok_json(json!({"path": rel, "content": content})))
    })
    .await
}

// ---------------------------------------------------------------------------
// knowledge base
// ---------------------------------------------------------------------------

async fn kb_templates(State(state): State<Arc<App>>) -> Response {
    ok_json(crate::kb::templates(&state.root))
}

#[derive(serde::Deserialize)]
struct TreeParams {
    section: Option<String>,
}

async fn kb_tree(
    State(state): State<Arc<App>>,
    Query(params): Query<TreeParams>,
) -> Result<Response, ApiError> {
    blocking(move || {
        let section = crate::path_map::validate_section(params.section.as_deref())
            .map_err(|e| ApiError::bad_request(e.to_string()))?;
        let tree = crate::path_map::build_knowledge_tree(&state.root, section.as_deref());
        let files: Vec<String> = crate::path_map::iter_knowledge_md(&state.root)
            .into_iter()
            .filter(|(rel, _)| {
                section
                    .as_deref()
                    .and_then(|s| crate::path_map::section_for(rel).map(|sec| sec == s))
                    .unwrap_or(true)
            })
            .map(|(rel, _)| rel)
            .collect();
        Ok(ok_json(json!({ "tree": tree, "files": files })))
    })
    .await
}

async fn kb_get_note(
    State(state): State<Arc<App>>,
    AxumPath(rel): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || crate::kb::get_note(&state.root, &rel)).await.map_err(ApiError::from).map(ok_json)
}

async fn kb_put_note(
    State(state): State<Arc<App>>,
    AxumPath(rel): AxumPath<String>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let content = body.get("content").and_then(Value::as_str).unwrap_or_default().to_string();
    let base_hash = body.get("base_hash").and_then(Value::as_str).map(String::from);
    let section = body.get("section").and_then(Value::as_str).map(String::from);
    blocking(move || {
        crate::kb::write_note(
            &state.root,
            crate::kb::WriteNoteArgs {
                rel_raw: &rel,
                content: &content,
                base_hash: base_hash.as_deref(),
                section: section.as_deref(),
                create: false,
            },
        )
    })
    .await
    .map(ok_json)
}

async fn kb_post_note(
    State(state): State<Arc<App>>,
    AxumPath(rel): AxumPath<String>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let content = body.get("content").and_then(Value::as_str).unwrap_or_default().to_string();
    let section = body.get("section").and_then(Value::as_str).map(String::from);
    blocking(move || {
        crate::kb::write_note(
            &state.root,
            crate::kb::WriteNoteArgs {
                rel_raw: &rel,
                content: &content,
                base_hash: None,
                section: section.as_deref(),
                create: true,
            },
        )
    })
    .await
    .map(ok_json)
}

async fn kb_delete_note(
    State(state): State<Arc<App>>,
    AxumPath(rel): AxumPath<String>,
) -> Result<Response, ApiError> {
    blocking(move || crate::kb::delete_note(&state.root, &rel)).await.map_err(ApiError::from).map(ok_json)
}

async fn kb_move(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let from_path = body.get("from_path").and_then(Value::as_str).unwrap_or_default().to_string();
    let to_path = body.get("to_path").and_then(Value::as_str).unwrap_or_default().to_string();
    if from_path.is_empty() || to_path.is_empty() {
        return Err(ApiError::bad_request("both from_path and to_path are required"));
    }
    blocking(move || crate::kb::move_note(&state.root, &from_path, Some(&to_path), false)).await.map_err(ApiError::from).map(ok_json)
}

async fn kb_archive(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let path = body.get("path").and_then(Value::as_str).unwrap_or_default().to_string();
    if path.is_empty() {
        return Err(ApiError::bad_request("path is required"));
    }
    blocking(move || crate::kb::move_note(&state.root, &path, None, true)).await.map_err(ApiError::from).map(ok_json)
}

// ---------------------------------------------------------------------------
// brain
// ---------------------------------------------------------------------------

async fn brain_graph(State(state): State<Arc<App>>) -> Result<Response, ApiError> {
    blocking(move || {
        let path = state.root.join("brain").join("graph.json");
        if !path.is_file() {
            return Ok(ok_json(json!({
                "version": 1, "generated": crate::timeutil::now_iso(),
                "nodes": [], "edges": [],
            })));
        }
        let raw = std::fs::read_to_string(&path)
            .map_err(|e| ApiError::internal(format!("failed to read graph.json: {e}")))?;
        let parsed: Value = serde_json::from_str(&raw)
            .map_err(|e| ApiError::internal(format!("failed to read graph.json: {e}")))?;
        if !parsed.is_object() {
            return Ok(ok_json(json!({
                "version": 1, "generated": crate::timeutil::now_iso(),
                "nodes": [], "edges": [],
            })));
        }
        let mut g = parsed;
        let obj = g.as_object_mut().unwrap();
        obj.entry("version".to_string()).or_insert(json!(1));
        obj.entry("nodes".to_string()).or_insert(json!([]));
        obj.entry("edges".to_string()).or_insert(json!([]));
        Ok(ok_json(g))
    })
    .await
}

async fn brain_insights(
    State(state): State<Arc<App>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Response, ApiError> {
    blocking(move || {
        let date_raw = params.get("date").map(String::as_str).unwrap_or("");
        let limit: usize = match params.get("limit") {
            None => 30,
            Some(l) => l
                .parse()
                .map_err(|_| ApiError::bad_request("limit must be an integer"))?,
        };
        if !(1..=365).contains(&limit) {
            return Err(ApiError::bad_request("limit must be between 1 and 365"));
        }
        let insights_root = state.root.join("brain").join("insights");
        if !date_raw.is_empty() {
            let d = chrono::NaiveDate::parse_from_str(date_raw, "%Y-%m-%d")
                .map_err(|_| ApiError::bad_request("date must be YYYY-MM-DD"))?;
            let p = insights_root
                .join(format!("{:04}", d.year()))
                .join(format!("{}.json", d.format("%Y-%m-%d")));
            use chrono::Datelike;
            if !p.is_file() {
                return Err(ApiError::not_found(format!("insight not found for {date_raw}")));
            }
            let raw = crate::paths::read_json(&p)?;
            return Ok(ok_json(json!({ "insight": raw })));
        }
        let mut paths: Vec<PathBuf> = Vec::new();
        collect_files(&insights_root, &mut paths);
        paths.sort();
        paths.reverse();
        let mut insights: Vec<Value> = Vec::new();
        let mut dates: Vec<Value> = Vec::new();
        for p in paths.into_iter().take(limit) {
            if let Ok(v) = crate::paths::read_json(&p) {
                dates.push(v.get("date").cloned().unwrap_or(Value::Null));
                insights.push(v);
            }
        }
        Ok(ok_json(json!({ "insights": insights, "dates": dates })))
    })
    .await
}

fn collect_files(dir: &Path, out: &mut Vec<PathBuf>) {
    crate::paths::walk_files_filtered(dir, out, 0, &|_p, _name| true);
}

async fn post_curation_op(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    blocking(move || {
        let op: crate::models::CurationOp = serde_json::from_value(body.clone())
            .map_err(|e| ApiError::bad_request(format!("invalid curation op: {e}")))?;
        let result = crate::curation::append_op(&state.root, op).map_err(ApiError::from)?;
        Ok((StatusCode::CREATED, Json(result)).into_response())
    })
    .await
}

// ---------------------------------------------------------------------------
// search / recall / ask / resume
// ---------------------------------------------------------------------------

fn active_provider(state: &App) -> (Option<Box<dyn crate::provider::ChatProvider>>, String) {
    let cfg = config::load_config(&state.root).unwrap_or_default();
    let pname = crate::provider::provider_name(&cfg);
    let built = crate::provider::build_provider(&cfg).ok();
    (built.map(|(_, p)| p), pname)
}

fn enforce_cloud_rate(state: &App, pname: &str) -> Result<(), ApiError> {
    if crate::provider::is_cloud_provider(pname) {
        let mut win = state.rate.lock().unwrap();
        if !win.check(RATE_MAX, RATE_WINDOW_SECS) {
            return Err(ApiError::too_many(crate::provider::RATE_LIMIT_MSG));
        }
    }
    Ok(())
}

async fn post_search(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let query = body
        .get("query")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::bad_request("query is required"))?
        .chars()
        .take(8000)
        .collect::<String>();
    let top_k = body.get("top_k").and_then(Value::as_u64).unwrap_or(8).clamp(1, 50) as usize;
    let scope = body.get("scope").and_then(Value::as_str).unwrap_or("all").to_string();
    blocking(move || {
        let cfg = config::load_config(&state.root)?;
        let (provider, pname) = active_provider(&state);
        let hits = crate::index_store::search_with_rt(
            &state.root,
            &cfg,
            &state.llm_runtime(),
            crate::index_store::SearchArgs {
                query: &query,
                top_k,
                kinds: None,
                scope: Some(&scope),
                text_limit: None,
                ids: None,
            },
        );
        let reachable = provider
            .as_ref()
            .map(|p| p.reachable(std::time::Duration::from_secs(2)))
            .unwrap_or(false);
        Ok(ok_json(json!({
            "query": query,
            "hits": hits,
            "ollama": reachable,
            "provider": pname,
            "provider_ok": reachable,
        })))
    })
    .await
}

async fn post_recall(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let message = body
        .get("message")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::bad_request("message is required"))?
        .chars()
        .take(8001)
        .collect::<String>();
    if message.chars().count() > 8000 {
        return Err(ApiError::payload_too_large("message too long (max 8000 chars)"));
    }

    let history: Vec<(String, String)> = body
        .get("history")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .take(60)
                .filter_map(|h| {
                    Some((
                        h.get("role")?.as_str()?.chars().take(32).collect(),
                        h.get("content")?.as_str()?.chars().take(16000).collect(),
                    ))
                })
                .collect()
        })
        .unwrap_or_default();
    let scope = body.get("scope").and_then(Value::as_str).unwrap_or("all").to_string();
    let node_ids: Vec<String> = body
        .get("node_ids")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
        .unwrap_or_default();

    blocking(move || {
        let cfg = config::load_config(&state.root)?;
        let (provider_box, pname) = active_provider(&state);
        enforce_cloud_rate(&state, &pname)?;
        let limits = crate::provider::limits_for(&pname);
        let result = rag::recall(
            &state.root,
            &cfg,
            &state.llm_runtime(),
            provider_box.as_deref(),
            &pname,
            limits,
            rag::RecallArgs {
                message: &message,
                history: &history,
                scope: &scope,
                node_ids: &node_ids,
            },
        );

        if result.get("error").is_some_and(|e| !e.is_null()) && result.get("answer").and_then(Value::as_str).unwrap_or("").is_empty() {
            let err = result.get("error").and_then(Value::as_str).unwrap_or("recall failed");
            return Err(ApiError::new(StatusCode::SERVICE_UNAVAILABLE, json!(err)));
        }

        let graph = rag::load_graph(&state.root);
        let neighbor_set = rag::neighbor_node_ids(graph.as_ref().unwrap_or(&Value::Null), &node_ids, 1);
        let mut neighbors: Vec<String> = neighbor_set.into_iter().collect();
        neighbors.sort();
        let citation_nodes = result.get("citation_nodes").cloned().unwrap_or(json!({}));
        let citations: Vec<Value> = result
            .get("citations")
            .and_then(Value::as_array)
            .map(|arr| arr.iter().map(map_citation(&citation_nodes)).collect())
            .unwrap_or_default();
        Ok(ok_json(json!({
            "answer": result.get("answer").cloned().unwrap_or(Value::Null),
            "citations": citations,
            "degraded": result.get("degraded").and_then(Value::as_bool).unwrap_or(false),
            "seed_node_ids": neighbors,
        })))
    })
    .await
}

fn map_citation(
    citation_nodes: &Value,
) -> impl Fn(&Value) -> Value + '_ {
    move |c: &Value| {
        let snippet_full = c.get("snippet").and_then(Value::as_str).unwrap_or_default();
        let snippet: String = snippet_full.chars().take(240).collect();
        let id = c.get("id").and_then(Value::as_str).unwrap_or_default();
        let nodes = citation_nodes
            .get(id)
            .cloned()
            .unwrap_or_else(|| json!([format!("entry:{id}")]));
        json!({
            "id": c.get("id"),
            "kind": c.get("kind"),
            "score": c.get("score"),
            "snippet": snippet,
            "path": c.get("path"),
            "node_ids": nodes,
        })
    }
}

async fn post_ask(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let question = body
        .get("question")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::bad_request("question is required"))?;
    if question.chars().count() > 8000 {
        return Err(ApiError::payload_too_large("question too long (max 8000 chars)"));
    }
    let question = question.to_string();
    blocking(move || {
        let cfg = config::load_config(&state.root)?;
        let (provider_box, pname) = active_provider(&state);
        enforce_cloud_rate(&state, &pname)?;
        let limits = crate::provider::limits_for(&pname);
        let result = rag::ask(
            &state.root,
            &cfg,
            &state.llm_runtime(),
            provider_box.as_deref(),
            &pname,
            limits,
            &question,
        );
        // ok:false → HTTP 500 with the SAME body (python _maybe_error_response).
        if result.get("ok") == Some(&json!(false)) {
            return Ok((StatusCode::INTERNAL_SERVER_ERROR, Json(result)).into_response());
        }
        Ok(ok_json(result))
    })
    .await
}

async fn post_resume(
    State(state): State<Arc<App>>,
    body: Result<Json<Value>, axum::extract::rejection::JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body.map_err(|_| ApiError::bad_request("body must be JSON"))?;
    let role = body
        .get("role")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::bad_request("role is required"))?
        .chars()
        .take(200)
        .collect::<String>();
    blocking(move || {
        let cfg = config::load_config(&state.root)?;
        let (provider_box, pname) = active_provider(&state);
        enforce_cloud_rate(&state, &pname)?;
        let limits = crate::provider::limits_for(&pname);
        let result = rag::resume(
            &state.root,
            &cfg,
            &state.llm_runtime(),
            provider_box.as_deref(),
            &pname,
            limits,
            &role,
        );
        if result.get("ok") == Some(&json!(false)) {
            return Ok((StatusCode::INTERNAL_SERVER_ERROR, Json(result)).into_response());
        }
        Ok(ok_json(result))
    })
    .await
}

// ---------------------------------------------------------------------------
// process / rebuild-index
// ---------------------------------------------------------------------------

async fn post_process(
    State(state): State<Arc<App>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Response, ApiError> {
    let run_brain = params.get("run_brain").map(|v| v != "false").unwrap_or(true);
    let dry_run = params.get("dry_run").map(|v| v == "true").unwrap_or(false);
    blocking(move || {
        let deps = pipeline::ProcessDeps { rt: &state.llm_runtime() };
        let result = pipeline::run_process_with_deps(&state.root, Some(deps), dry_run, run_brain, false)?;
        Ok(ok_json(json!({ "ok": true }).chain_merge(result)))
    })
    .await
}

trait ChainMerge {
    fn chain_merge(self, other: Value) -> Value;
}
impl ChainMerge for Value {
    fn chain_merge(mut self, mut other: Value) -> Value {
        if let (Some(dst), Some(src)) = (self.as_object_mut(), other.as_object_mut()) {
            for (k, v) in src {
                dst.insert(k.clone(), v.take());
            }
        }
        self
    }
}

#[derive(serde::Deserialize, Default)]
struct RebuildIndexBody {
    process: Option<bool>,
    sqlite: Option<bool>,
}

async fn post_rebuild_index(
    State(state): State<Arc<App>>,
    Query(params): Query<HashMap<String, String>>,
    body: Option<Json<Value>>,
) -> Result<Response, ApiError> {
    let dry_run = params.get("dry_run").map(|v| v == "true").unwrap_or(false);
    let (do_process, do_sqlite) = match body {
        Some(Json(b)) => (
            b.get("process").and_then(Value::as_bool).unwrap_or(false),
            b.get("sqlite").and_then(Value::as_bool).unwrap_or(false),
        ),
        None => (false, false),
    };
    blocking(move || {
        let md = crate::markdown_index::rebuild_markdown_index(&state.root, dry_run)?;
        let mut out = json!({
            "ok": true,
            "markdown_index": md,
            "dry_run": dry_run,
        });
        if do_sqlite && !dry_run {
            match crate::index_store::run_index_with_rt(
                &state.root,
                &config::load_config(&state.root)?,
                &state.llm_runtime(),
                false,
                false,
            ) {
                Ok(v) => out["sqlite_index"] = v,
                Err(e) => return Err(ApiError::from(e)),
            }
        }
        if do_process && !dry_run {
            let deps = pipeline::ProcessDeps { rt: &state.llm_runtime() };
            let res = pipeline::run_process_with_deps(&state.root, Some(deps), false, false, false)?;
            out["process"] = res;
        }
        Ok(ok_json(out))
    })
    .await
}

// ---------------------------------------------------------------------------
// SPA catch-all
// ---------------------------------------------------------------------------

fn mime_for(path: &str) -> &'static str {
    let ext = path.rsplit('.').next().unwrap_or("");
    match ext {
        "html" => "text/html; charset=utf-8",
        "js" | "mjs" => "text/javascript",
        "css" => "text/css",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "woff2" => "font/woff2",
        "woff" => "font/woff",
        "json" => "application/json",
        "txt" => "text/plain; charset=utf-8",
        "map" => "application/json",
        "ico" => "image/x-icon",
        _ => "application/octet-stream",
    }
}

async fn spa_catch_all(
    AxumPath(full): AxumPath<String>,
) -> Result<Response, ApiError> {
    let Some(dist) = frontend_dist() else {
        return Err(ApiError::not_found("Not Found"));
    };
    if full.split('/').any(|seg| seg == "..") {
        return Err(ApiError::bad_request("invalid path"));
    }
    let candidate = dist.join(&full);
    let dist_resolved = dist.canonicalize().unwrap_or_else(|_| dist.clone());
    let cand_resolved = candidate.canonicalize().unwrap_or(candidate.clone());
    if !cand_resolved.starts_with(&dist_resolved) {
        return Err(ApiError::bad_request("invalid path"));
    }
    if candidate.is_file() {
        let bytes = std::fs::read(&candidate)
            .map_err(|e| ApiError::internal(e.to_string()))?;
        return Ok((
            [(header::CONTENT_TYPE, mime_for(&full))],
            bytes,
        )
            .into_response());
    }
    let index = dist.join("index.html");
    if index.is_file() {
        let bytes = std::fs::read(index).map_err(|e| ApiError::internal(e.to_string()))?;
        return Ok((
            [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
            bytes,
        )
            .into_response());
    }
    Err(ApiError::not_found("frontend index missing"))
}

// Silence unused import warnings for headers used conditionally.
#[allow(unused)]
fn _hdr_used(h: &HeaderMap) -> bool {
    h.is_empty()
}
