//! Serve stack: LAN auth middleware (default-deny), /connect host guard,
//! CORS pinning, port/LAN discovery, state construction, embedded server.
//!
//! The auth matcher reproduces python `_path_requires_lan_auth` exactly,
//! including its prefix `startswith` semantics (documented incumbent quirk).

use std::net::{IpAddr, SocketAddr, TcpListener, UdpSocket};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::http::{HeaderValue, Method, Request, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde_json::json;

use crate::App;

pub const TOKEN_HEADER: &str = "X-Chronicle-Token";

const AUTH_EXEMPT_EXACT: [&str; 4] = ["/", "/legacy", "/favicon.svg", "/favicon.ico"];
const AUTH_EXEMPT_PREFIXES: [&str; 3] = ["/health", "/connect", "/assets/"];
const SPA_SHELL_EXACT: [&str; 5] = ["/settings", "/vault", "/vault/notes", "/vault/journal", "/vault/kb"];
pub const API_ROOT_SEGMENTS: [&str; 17] = [
    "entries", "kb", "notes", "journal", "brain", "models",
    "recall", "ask", "resume", "search", "process", "enrich",
    "curation", "vault", "docs", "redoc", "openapi.json",
];
const CONNECT_HOST_ALLOWED_BASE: [&str; 4] = ["127.0.0.1", "localhost", "::1", "testserver"];

/// Python parity table (first match wins):
/// 1 exact-exempt → false · 2 prefix-exempt → false · 3 non-GET/HEAD → true
/// 4 SPA shell GET/HEAD → false · 5/6 /vault shells → false
/// 7 unknown first segment → false · 8 API root GET/HEAD → true
pub fn path_requires_lan_auth(path: &str, method: &str) -> bool {
    if AUTH_EXEMPT_EXACT.contains(&path) {
        return false;
    }
    for p in AUTH_EXEMPT_PREFIXES {
        let p = p.trim_end_matches('/');
        if path == p || path.starts_with(p) {
            return false;
        }
    }
    let m = method.to_ascii_uppercase();
    if m != "GET" && m != "HEAD" {
        return true;
    }
    if SPA_SHELL_EXACT.contains(&path) || path.starts_with("/settings/") {
        return false;
    }
    let parts: Vec<&str> = path.split('/').filter(|p| !p.is_empty()).collect();
    let first = parts.first().copied().unwrap_or("");
    if first == "vault" && parts.len() >= 2 && matches!(parts[1], "notes" | "journal" | "kb") {
        return false;
    }
    if first == "vault" && parts.len() == 1 {
        return false;
    }
    if !first.is_empty() && !API_ROOT_SEGMENTS.contains(&first) {
        return false;
    }
    true
}

fn host_header_hostname(raw: &str) -> String {
    let h = raw.trim().to_ascii_lowercase();
    if h.starts_with('[') {
        return h[1..].split(']').next().unwrap_or("").to_string();
    }
    match h.rsplit_once(':') {
        Some((host, _)) if host.contains(':') || !host.is_empty() => {
            // IPv6 without brackets has multiple ':'; rsplit picks last segment
            // which would be numeric only for host:port forms.
            if host.parse::<IpAddr>().is_ok() || !host.contains(':') {
                host.to_string()
            } else {
                h.clone()
            }
        }
        _ => h,
    }
}

async fn lan_auth_middleware(
    axum::extract::State(state): axum::extract::State<Arc<App>>,
    req: Request<Body>,
    next: Next,
) -> Response {
    if !state.auth_required || state.token.is_none() {
        return next.run(req).await;
    }
    if req.method() == Method::OPTIONS {
        return next.run(req).await;
    }
    let path = req.uri().path().to_string();
    let method = req.method().as_str().to_string();
    if !path_requires_lan_auth(&path, &method) {
        return next.run(req).await;
    }
    let got = req
        .headers()
        .get(TOKEN_HEADER)
        .or_else(|| req.headers().get(&TOKEN_HEADER.to_ascii_lowercase()))
        .and_then(|v| v.to_str().ok());
    let ok = got.is_some_and(|g| {
        openssl_ct_eq(g, state.token.as_deref().unwrap_or_default())
    });
    if !ok {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"ok": false, "error": "missing or invalid X-Chronicle-Token"})),
        )
            .into_response();
    }
    next.run(req).await
}

/// Constant-time string equality (secrets.compare_digest analogue).
fn openssl_ct_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        // Still burn time proportional to max len to avoid length oracles.
        let _ = b.iter().fold(0u8, |acc, x| acc ^ x);
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn connect_host_guard(req: Request<Body>, next: Next) -> Response {
    let path = req.uri().path();
    if path == "/connect" || path.starts_with("/connect/") {
        let state_allowed = req
            .extensions()
            .get::<HostAllowlist>()
            .cloned();
        let raw_host = req
            .headers()
            .get(axum::http::header::HOST)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let hostname = host_header_hostname(raw_host);
        let allowed = state_allowed.map(|h| h.0.contains(&hostname)).unwrap_or(true);
        if hostname == "testserver" || allowed {
            return next.run(req).await;
        }
        return (
            StatusCode::FORBIDDEN,
            Json(json!({"ok": false, "error": "invalid Host header"})),
        )
            .into_response();
    }
    next.run(req).await
}

#[derive(Clone)]
pub struct HostAllowlist(pub Vec<String>);

pub fn connect_allowlist(app: &App) -> HostAllowlist {
    let mut v = CONNECT_HOST_ALLOWED_BASE
        .iter()
        .map(|s| s.to_string())
        .collect::<Vec<_>>();
    for key in [&app.connect_info.host, &app.connect_info.bind_host] {
        let k = key.trim().to_ascii_lowercase();
        if !k.is_empty() && !v.contains(&k) {
            v.push(k);
        }
    }
    if let Some(ip) = &app.connect_info.lan_ip {
        let k = ip.trim().to_ascii_lowercase();
        if !k.is_empty() && !v.contains(&k) {
            v.push(k);
        }
    }
    HostAllowlist(v)
}

// ---------------------------------------------------------------------------
// Discovery + state
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct ServeConfig {
    pub chronicle_dir: PathBuf,
    pub preferred_port: u16,
    pub lan: bool,
    pub host: String,
}

impl Default for ServeConfig {
    fn default() -> Self {
        Self {
            chronicle_dir: std::env::var("CHRONICLE_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|_| std::env::current_dir().unwrap_or_default()),
            preferred_port: 8765,
            lan: true,
            host: "127.0.0.1".into(),
        }
    }
}

pub fn find_free_port(host: &str, preferred: u16, attempts: u16) -> std::io::Result<u16> {
    for port in preferred..preferred.saturating_add(attempts) {
        if let Ok(l) = TcpListener::bind((host, port)) {
            drop(l);
            return Ok(port);
        }
    }
    Err(std::io::Error::new(
        std::io::ErrorKind::AddrInUse,
        format!("no free port in range {preferred}-{}", preferred + attempts - 1),
    ))
}

pub fn detect_lan_ip() -> String {
    if let Ok(s) = UdpSocket::bind("0.0.0.0:0") {
        if s.connect("8.8.8.8:80").is_ok() {
            if let Ok(addr) = s.local_addr() {
                if !addr.ip().is_loopback() {
                    return addr.ip().to_string();
                }
            }
        }
    }
    if let Ok(hostname) = std::env::var("HOSTNAME").or_else(|_| {
        std::process::Command::new("hostname")
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
    }) {
        use std::net::ToSocketAddrs;
        if let Ok(addrs) = (hostname.as_str(), 80u16).to_socket_addrs() {
            for a in addrs {
                match a.ip() {
                    IpAddr::V4(v4) if !v4.is_loopback() => return v4.to_string(),
                    _ => {}
                }
            }
        }
    }
    "127.0.0.1".into()
}

fn is_localhost_bind(bind_host: &str) -> bool {
    matches!(bind_host.trim().to_ascii_lowercase().as_str(), "127.0.0.1" | "localhost" | "::1")
}

/// token_urlsafe(24): 24 random bytes → 32 base64url chars.
pub fn generate_token() -> String {
    use rand::RngCore;
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|b| TABLE[(b & 63) as usize] as char).collect()
}

pub struct Bound {
    pub state: Arc<App>,
    pub actual_port: u16,
    pub base_local: String,
}

/// Build state + router, resolve port, write serve.json AFTER validation.
pub fn prepare(config: &ServeConfig) -> Result<Bound, String> {
    let bind_host = if config.lan { "0.0.0.0".into() } else { config.host.clone() };
    let probe_host = if bind_host == "0.0.0.0" { "127.0.0.1" } else { bind_host.as_str() };
    let actual_port = find_free_port(probe_host, config.preferred_port, 50).map_err(|e| e.to_string())?;
    if actual_port != config.preferred_port {
        crate::log_line(
            "WARNING",
            &format!("Port {} busy; using {} instead", config.preferred_port, actual_port),
        );
    }

    let lan_ip = if config.lan { Some(detect_lan_ip()) } else { None };
    let advertise_host = match (&lan_ip, config.lan) {
        (Some(ip), true) if !ip.is_empty() => ip.clone(),
        _ => {
            if bind_host == "0.0.0.0" { "127.0.0.1".to_string() } else { bind_host.clone() }
        }
    };
    let base = format!("http://{advertise_host}:{actual_port}");
    let auth_required = !is_localhost_bind(&bind_host);
    let token = if auth_required { Some(generate_token()) } else { None };

    let connect_info = crate::ConnectInfo {
        host: advertise_host.clone(),
        port: actual_port,
        bind_host: bind_host.clone(),
        lan_ip: lan_ip.clone(),
        base: base.clone(),
        kb_proxied: false,
        version: 1,
        token: token.clone(),
        auth_required,
    };
    let state = Arc::new(App::new(config.chronicle_dir.clone(), connect_info));

    // Apply config-driven LLM settings before serving.
    match crate::config::ensure_config(&config.chronicle_dir) {
        Ok(cfg) => {
            // Layout hard gate — python refuses to serve legacy vaults
            // (create_app → require_layout_version); mirror that at startup.
            crate::config::require_layout_version(&cfg).map_err(|e| e.to_string())?;
            state.apply_ollama_settings(&cfg.ollama.base_url, cfg.ollama.num_ctx, cfg.ollama.temperature);
        }
        Err(e) => return Err(e.to_string()),
    }

    let router = crate::api::build_router(state.clone());

    // serve.json written after router/state construction (python ordering bug fixed).
    let serve_json = json!({
        "host": advertise_host,
        "port": actual_port,
        "bind_host": bind_host,
        "lan_ip": lan_ip,
        "base": base,
        "kb_proxied": false,
        "v": 1,
        "auth_required": auth_required,
        "pid": std::process::id(),
    });
    let index_dir = config.chronicle_dir.join("index");
    let _ = std::fs::create_dir_all(&index_dir);
    let _ = crate::paths::atomic_write_json(&index_dir.join("serve.json"), &serve_json);

    Ok(Bound { state, actual_port, base_local: format!("http://127.0.0.1:{actual_port}") })
}

pub fn cors_layer(port: u16, base: &str) -> tower_http::cors::CorsLayer {
    use tower_http::cors::AllowOrigin;
    let mut origins = vec![
        base.to_string(),
        format!("http://127.0.0.1:{port}"),
        format!("http://localhost:{port}"),
    ];
    origins.dedup();
    let list: Vec<HeaderValue> = origins
        .iter()
        .filter_map(|o| HeaderValue::from_str(o).ok())
        .collect();
    CorsLayerBuilder::build(list)
}

mod CorsLayerBuilder {
    use super::*;
    pub fn build(origins: Vec<HeaderValue>) -> tower_http::cors::CorsLayer {
        let base = tower_http::cors::CorsLayer::new()
            .allow_origin(origins)
            .allow_credentials(false)
            .allow_methods([
                Method::GET,
                Method::POST,
                Method::PUT,
                Method::PATCH,
                Method::DELETE,
                Method::OPTIONS,
                Method::HEAD,
            ])
            .allow_headers([
                axum::http::header::CONTENT_TYPE,
                axum::http::header::AUTHORIZATION,
                axum::http::HeaderName::from_static("x-chronicle-token"),
            ]);
        base
    }
}

/// Bind + serve until `shutdown` resolves. Returns the bound port.
pub async fn start_server(
    config: ServeConfig,
    shutdown: tokio::sync::watch::Receiver<bool>,
) -> Result<(Arc<App>, u16), String> {
    let bound = prepare(&config).map_err(|e| e.to_string())?;
    let addr: SocketAddr = ([0, 0, 0, 0], bound.actual_port).into();
    let listener = tokio::net::TcpListener::bind(if config.lan { addr } else {
        let host = if config.host == "localhost" { "127.0.0.1" } else { config.host.as_str() };
        SocketAddr::new(host.parse().unwrap_or_else(|_| std::net::Ipv4Addr::LOCALHOST.into()), bound.actual_port)
    })
    .await
    .map_err(|e| e.to_string())?;

    crate::log_line(
        "INFO",
        &format!(
            "Serving Chronicle API on {}:{} (advertise {}, dir={}, ask/resume=native{})",
            if config.lan { "0.0.0.0" } else { config.host.as_str() },
            bound.actual_port,
            bound.state.connect_info.base,
            config.chronicle_dir.display(),
            if bound.state.auth_required { ", auth=X-Chronicle-Token" } else { ", auth=off" },
        ),
    );

    let mut shutdown_rx = shutdown;
    axum::serve(
        listener,
        build_router_with_layers(bound.state.clone()).into_make_service_with_connect_info::<SocketAddr>(),
    )
        .with_graceful_shutdown(async move {
            let _ = shutdown_rx.wait_for(|v| *v).await;
        })
        .await
        .map_err(|e| e.to_string())?;
    Ok((bound.state, bound.actual_port))
}

/// Router with all middleware layers applied (used by bin + Tauri embed).
pub fn build_router_with_layers(state: Arc<App>) -> Router {
    let allowlist = connect_allowlist(&state);
    let port = state.connect_info.port;
    let base = state.connect_info.base.clone();
    crate::api::build_router(state.clone())
        .layer(middleware::from_fn(connect_host_guard))
        .layer(middleware::from_fn_with_state(state, lan_auth_middleware))
        .layer(cors_layer(port, &base))
        .layer(AddAllowlist(allowlist))
}

#[derive(Clone)]
struct AddAllowlist(HostAllowlist);

impl<S> tower::Layer<S> for AddAllowlist {
    type Service = AddAllowlistSvc<S>;
    fn layer(&self, inner: S) -> Self::Service {
        AddAllowlistSvc { inner, allowlist: self.0.clone() }
    }
}

#[derive(Clone)]
struct AddAllowlistSvc<S> {
    inner: S,
    allowlist: HostAllowlist,
}

impl<S> tower::Service<Request<Body>> for AddAllowlistSvc<S>
where
    S: tower::Service<Request<Body>, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = std::pin::Pin<Box<dyn std::future::Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut std::task::Context<'_>) -> std::task::Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, mut req: Request<Body>) -> Self::Future {
        let allowlist = self.allowlist.clone();
        let mut inner = self.inner.clone();
        Box::pin(async move {
            req.extensions_mut().insert(allowlist);
            inner.call(req).await
        })
    }
}

// Keep unused-import warnings quiet for items used only in tests/embedding.
#[allow(unused_imports)]
use crate::paths::atomic_write_json as _awj;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_table_matches_python_quirks() {
        // Exact exempt.
        assert!(!path_requires_lan_auth("/", "GET"));
        assert!(!path_requires_lan_auth("/legacy", "POST"));
        assert!(!path_requires_lan_auth("/favicon.svg", "GET"));
        // Prefix quirk: /healthcheck and /connector ARE exempt (startswith).
        assert!(!path_requires_lan_auth("/healthcheck", "GET"));
        assert!(!path_requires_lan_auth("/connector", "DELETE"));
        assert!(!path_requires_lan_auth("/assets/app.js", "GET"));
        assert!(!path_requires_lan_auth("/connect", "POST"));
        assert!(!path_requires_lan_auth("/connect/qr.svg", "GET"));
        // Mutations require token.
        assert!(path_requires_lan_auth("/entries", "POST"));
        assert!(path_requires_lan_auth("/kb/notes/a.md", "PUT"));
        assert!(path_requires_lan_auth("/unknown-api-root", "POST"));
        // SPA shells GET-only exempt.
        assert!(!path_requires_lan_auth("/settings", "GET"));
        assert!(!path_requires_lan_auth("/settings/profile", "GET"));
        assert!(path_requires_lan_auth("/settings", "POST"));
        // /vault shell deep links pass; rebuild-index does not.
        assert!(!path_requires_lan_auth("/vault/notes/a/b.md", "GET"));
        assert!(path_requires_lan_auth("/vault/rebuild-index", "POST"));
        assert!(path_requires_lan_auth("/entries", "GET"));
        assert!(path_requires_lan_auth("/brain/graph", "GET"));
        // Unknown first segment → SPA fallback exempt on GET/HEAD.
        assert!(!path_requires_lan_auth("/some/spa/route", "GET"));
        assert!(!path_requires_lan_auth("/", "HEAD"));
    }

    #[test]
    fn host_header_parsing() {
        assert_eq!(host_header_hostname("[::1]:8765"), "::1");
        assert_eq!(host_header_hostname("127.0.0.1:9000"), "127.0.0.1");
        assert_eq!(host_header_hostname("Evil.example.com"), "evil.example.com");
    }

    #[test]
    fn token_shape_is_32_base64url_chars() {
        let t = generate_token();
        assert_eq!(t.len(), 32);
        assert!(t.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }
}
