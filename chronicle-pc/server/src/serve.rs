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

// Pairing / health / static — no token (default-deny for everything else).
// Prefix entries MUST end in "/" (or be listed exact) so a future sibling route
// like /connected-devices or /healthz can never inherit the exemption
// silently. Python narrowed these already; this is the Rust side catching up.
const AUTH_EXEMPT_EXACT: [&str; 7] = [
    "/",
    "/legacy",
    "/favicon.svg",
    "/favicon.ico",
    "/health",
    "/connect",
    // Same body as /connect, and the token is only embedded for loopback
    // callers, so exposing the image is no wider than /connect itself.
    "/connect/qr.svg",
];
const AUTH_EXEMPT_PREFIXES: [&str; 1] = ["/assets/"];
const SPA_SHELL_EXACT: [&str; 5] = ["/settings", "/vault", "/vault/notes", "/vault/journal", "/vault/kb"];
pub const API_ROOT_SEGMENTS: [&str; 19] = [
    "entries", "kb", "notes", "journal", "brain", "models",
    "recall", "ask", "resume", "search", "process", "enrich",
    "curation", "vault", "docs", "redoc", "openapi.json",
    // Authenticated live surfaces (never SPA shells):
    "events", "auth",
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
    // Proper-prefix only: "/assets/" must not exempt "/assetsX".
    for p in AUTH_EXEMPT_PREFIXES {
        if path.starts_with(p) {
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

/// Reject any request whose Host header is not one of ours.
///
/// A DNS-rebinding page resolves an attacker hostname to the server's address
/// and then talks to it *same-origin*, so CORS never applies. Guarding only
/// `/connect` left every vault route reachable that way, and in loopback mode
/// the token check is off entirely — so the Host allowlist is the boundary.
/// It applies to the whole surface, not just pairing.
async fn host_guard(req: Request<Body>, next: Next) -> Response {
    let state_allowed = req.extensions().get::<HostAllowlist>().cloned();
    let raw_host = req
        .headers()
        .get(axum::http::header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let hostname = host_header_hostname(raw_host);
    // No allowlist installed means no server context (unit tests calling the
    // router directly); fall through rather than hard-failing.
    let allowed = state_allowed.map(|h| h.0.contains(&hostname)).unwrap_or(true);
    if hostname == "testserver" || allowed {
        return next.run(req).await;
    }
    (
        StatusCode::FORBIDDEN,
        Json(json!({"ok": false, "error": "invalid Host header"})),
    )
        .into_response()
}

/// Lower-cased machine host name, without a trailing ".local".
fn machine_hostname() -> Option<String> {
    let mut buf = vec![0u8; 256];
    let rc = unsafe { libc::gethostname(buf.as_mut_ptr() as *mut libc::c_char, buf.len()) };
    if rc != 0 {
        return None;
    }
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    let s = String::from_utf8_lossy(&buf[..end]).trim().to_ascii_lowercase();
    let s = s.strip_suffix(".local").unwrap_or(&s).to_string();
    if s.is_empty() { None } else { Some(s) }
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
    // The guard now covers every route, so the names a legitimate client may
    // actually dial must be here too: the mDNS host name (phones resolve
    // "<host>.local"), and an explicit operator override for custom DNS
    // aliases or a reverse proxy.
    if let Some(h) = machine_hostname() {
        for k in [h.clone(), format!("{h}.local")] {
            if !v.contains(&k) {
                v.push(k);
            }
        }
    }
    if let Ok(extra) = std::env::var("CHRONICLE_EXTRA_HOSTS") {
        for part in extra.split(',') {
            let k = part.trim().to_ascii_lowercase();
            if !k.is_empty() && !v.contains(&k) {
                v.push(k);
            }
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
    /// Present exactly when the listener must terminate TLS (LAN mode).
    pub tls: Option<Arc<rustls::ServerConfig>>,
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
    let auth_required = !is_localhost_bind(&bind_host);
    let token = if auth_required { Some(generate_token()) } else { None };

    // Anything reachable off-box gets TLS. The bearer token is a *reusable*
    // vault credential, so shipping it (and the journal traffic behind it) in
    // cleartext over the LAN is not an option. Loopback stays plain HTTP: the
    // SPA and capture window are same-host, and a pinned cert would only add a
    // trust-store problem there.
    let tls_material = if auth_required {
        match crate::tls::ensure_tls_material(lan_ip.as_deref()) {
            Ok(m) => Some(m),
            // Fail CLOSED: without TLS we would otherwise fall back to
            // advertising a cleartext LAN endpoint, which is the bug.
            Err(e) => {
                return Err(format!(
                    "LAN serving requires TLS and the certificate could not be prepared: {e}.                      Re-run with --no-lan to serve on loopback only."
                ))
            }
        }
    } else {
        None
    };
    let scheme = if tls_material.is_some() { "https" } else { "http" };
    let base = format!("{scheme}://{advertise_host}:{actual_port}");

    let tls_config = match &tls_material {
        Some(m) => Some(crate::tls::server_config(m).map_err(|e| e.to_string())?),
        None => None,
    };

    let connect_info = crate::ConnectInfo {
        host: advertise_host.clone(),
        port: actual_port,
        bind_host: bind_host.clone(),
        lan_ip: lan_ip.clone(),
        base: base.clone(),
        kb_proxied: false,
        // v2 = the payload carries tls_fp; the phone pins it and refuses a
        // cleartext base. v1 meant "http, trust the network".
        version: if tls_material.is_some() { 2 } else { 1 },
        token: token.clone(),
        auth_required,
        tls: tls_material.is_some(),
        tls_fp: tls_material.as_ref().map(|m| m.fingerprint_b64.clone()),
    };
    let state = Arc::new(App::new(config.chronicle_dir.clone(), connect_info));

    // Apply config-driven LLM settings before serving.
    match crate::config::ensure_config(&config.chronicle_dir) {
        Ok(cfg) => {
            // Layout hard gate — python refuses to serve legacy vaults
            // (create_app → require_layout_version); mirror that at startup.
            crate::config::require_layout_version(&cfg).map_err(|e| e.to_string())?;
            // Same shape as the layout gate: refuse rather than silently
            // writing plaintext into a vault the user asked to encrypt.
            crate::config::require_no_e2ee(&cfg).map_err(|e| e.to_string())?;
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
        "v": if tls_material.is_some() { 2 } else { 1 },
        "auth_required": auth_required,
        "tls": tls_material.is_some(),
        "tls_fp": tls_material.as_ref().map(|m| m.fingerprint_b64.clone()),
        "pid": std::process::id(),
    });
    let index_dir = config.chronicle_dir.join("index");
    let _ = std::fs::create_dir_all(&index_dir);
    let _ = crate::paths::atomic_write_json(&index_dir.join("serve.json"), &serve_json);

    // Loopback stays plain HTTP even in LAN mode: it never leaves the machine,
    // and the desktop WebView cannot be asked to trust a self-signed cert.
    // TLS is bound separately on the LAN address (see serve_bound).
    let base_local = format!("http://127.0.0.1:{actual_port}");
    Ok(Bound { state, actual_port, base_local, tls: tls_config })
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
    crate::log_line(
        "INFO",
        &format!(
            "advertise {}, dir={}, ask/resume=native{}",
            bound.state.connect_info.base,
            config.chronicle_dir.display(),
            if bound.state.auth_required { ", auth=X-Chronicle-Token" } else { ", auth=off" },
        ),
    );
    serve_bound(&bound, shutdown).await?;
    Ok((bound.state, bound.actual_port))
}

/// Bind the listeners for a prepared server and serve until shutdown.
///
/// Loopback is served plain (same machine, and the desktop WebView has no way
/// to trust a self-signed cert); the LAN address is served with TLS. Binding
/// the LAN address specifically — rather than 0.0.0.0 — is what lets the two
/// coexist on one port.
pub async fn serve_bound(
    bound: &Bound,
    shutdown: tokio::sync::watch::Receiver<bool>,
) -> Result<(), String> {
    let port = bound.actual_port;
    let router = build_router_with_layers(bound.state.clone());

    let local = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port))
        .await
        .map_err(|e| format!("could not bind 127.0.0.1:{port}: {e}"))?;

    let lan_listener = match (&bound.tls, &bound.state.connect_info.lan_ip) {
        (Some(_), Some(ip)) if !ip.trim().is_empty() => {
            let addr: std::net::IpAddr = ip
                .trim()
                .parse()
                .map_err(|_| format!("detected LAN address {ip:?} is not an IP"))?;
            Some(
                tokio::net::TcpListener::bind((addr, port))
                    .await
                    .map_err(|e| format!("could not bind {ip}:{port}: {e}"))?,
            )
        }
        (Some(_), _) => {
            crate::log_line(
                "WARNING",
                "LAN serving requested but no LAN address was detected; serving on loopback only.",
            );
            None
        }
        (None, _) => None,
    };

    crate::log_line(
        "INFO",
        &format!(
            "Serving Chronicle API on http://127.0.0.1:{port}{}",
            match (&lan_listener, &bound.state.connect_info.lan_ip) {
                (Some(_), Some(ip)) => format!(" and https://{ip}:{port} (TLS, pinned)"),
                _ => String::new(),
            }
        ),
    );

    let plain = serve_router(local, router.clone(), None, shutdown.clone());
    match lan_listener {
        None => plain.await,
        Some(l) => {
            let secure = serve_router(l, router, bound.tls.clone(), shutdown);
            let (a, b) = tokio::join!(plain, secure);
            a.and(b)
        }
    }
}

/// Serve `router` on `listener`, terminating TLS when `tls` is present.
///
/// Single owner for the transport decision. The CLI and the embedded desktop
/// server both come through here, so one of them cannot drift back into
/// serving the LAN in cleartext.
pub async fn serve_router(
    listener: tokio::net::TcpListener,
    router: Router,
    tls: Option<Arc<rustls::ServerConfig>>,
    mut shutdown: tokio::sync::watch::Receiver<bool>,
) -> Result<(), String> {
    let Some(tls_cfg) = tls else {
        return axum::serve(
            listener,
            router.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(async move {
            let _ = shutdown.wait_for(|v| *v).await;
        })
        .await
        .map_err(|e| e.to_string());
    };

    let acceptor = tokio_rustls::TlsAcceptor::from(tls_cfg);
    loop {
        let (stream, peer) = tokio::select! {
            _ = shutdown.wait_for(|v| *v) => break,
            accepted = listener.accept() => match accepted {
                Ok(v) => v,
                Err(e) => {
                    crate::log_line("WARNING", &format!("accept failed: {e}"));
                    continue;
                }
            },
        };
        let acceptor = acceptor.clone();
        let router = router.clone();
        tokio::spawn(async move {
            let tls_stream = match acceptor.accept(stream).await {
                Ok(s) => s,
                Err(e) => {
                    // A failed handshake is routine — a port probe, or a phone
                    // refusing our pin. Never retry the connection in the clear.
                    crate::log_line("INFO", &format!("TLS handshake with {peer} failed: {e}"));
                    return;
                }
            };
            let svc = hyper::service::service_fn(move |mut req: hyper::Request<hyper::body::Incoming>| {
                // Restore what axum::serve's make-service supplies on the plain
                // path: /connect and the auth middleware gate on the peer
                // address, so losing this would hand the token to the LAN.
                req.extensions_mut().insert(axum::extract::ConnectInfo(peer));
                let mut router = router.clone();
                async move { tower_service::Service::call(&mut router, req).await }
            });
            let io = hyper_util::rt::TokioIo::new(tls_stream);
            let builder = hyper_util::server::conn::auto::Builder::new(
                hyper_util::rt::TokioExecutor::new(),
            );
            if let Err(e) = builder.serve_connection_with_upgrades(io, svc).await {
                crate::log_line("INFO", &format!("connection from {peer} ended: {e}"));
            }
        });
    }
    Ok(())
}

/// Router with all middleware layers applied (used by bin + Tauri embed).
pub fn build_router_with_layers(state: Arc<App>) -> Router {
    let allowlist = connect_allowlist(&state);
    let port = state.connect_info.port;
    let base = state.connect_info.base.clone();
    crate::api::build_router(state.clone())
        .layer(middleware::from_fn(host_guard))
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
    fn auth_table_matches_python() {
        // Exact exempt.
        assert!(!path_requires_lan_auth("/", "GET"));
        assert!(!path_requires_lan_auth("/legacy", "POST"));
        assert!(!path_requires_lan_auth("/favicon.svg", "GET"));
        assert!(!path_requires_lan_auth("/assets/app.js", "GET"));
        assert!(!path_requires_lan_auth("/connect", "POST"));
        assert!(!path_requires_lan_auth("/connect/qr.svg", "GET"));
        assert!(!path_requires_lan_auth("/health", "GET"));

        // These previously asserted the OPPOSITE, mirroring a Python
        // bare-startswith bug that Python has since fixed: any sibling route
        // beginning with an exempt prefix silently inherited the exemption, for
        // EVERY method. Exemptions are now exact, or proper prefixes ending in
        // "/", so a mutation on such a path requires the token.
        assert!(path_requires_lan_auth("/connector", "DELETE"));
        assert!(path_requires_lan_auth("/healthcheck", "POST"));
        assert!(path_requires_lan_auth("/connected-devices", "PUT"));
        assert!(path_requires_lan_auth("/assetsX/upload", "POST"));

        // Unknown GET/HEAD paths still fall through to the SPA shell — by
        // design, and matching Python. They render index.html, never vault
        // data, so this is a router fallback rather than an auth exemption.
        assert!(!path_requires_lan_auth("/healthcheck", "GET"));
        assert!(!path_requires_lan_auth("/connected-devices", "GET"));

        // Live surfaces are authenticated API roots, not SPA shells — so they
        // require the token even on GET.
        assert!(path_requires_lan_auth("/events/stream", "GET"));
        assert!(path_requires_lan_auth("/auth/e2ee/status", "GET"));
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

/// Regression guard for the 2026-09 audit's finding 1: the shipped desktop
/// advertised `http://` on the LAN together with a reusable bearer token.
#[cfg(test)]
mod lan_transport_tests {
    use super::*;

    fn vault(dir: &std::path::Path) {
        std::fs::write(
            dir.join("config.json"),
            r#"{"version":1,"layout_version":2,"timezone":"UTC"}"#,
        )
        .unwrap();
        std::fs::create_dir_all(dir.join("_capture/entries")).unwrap();
    }

    /// This test owns CHRONICLE_CONFIG_HOME for the process, so both LAN and
    /// loopback assertions live in one test rather than racing each other.
    #[test]
    fn lan_never_advertises_cleartext_and_loopback_stays_plain() {
        let cfg_home = tempfile::tempdir().unwrap();
        std::env::set_var("CHRONICLE_CONFIG_HOME", cfg_home.path());

        // --- LAN mode -----------------------------------------------------
        let v = tempfile::tempdir().unwrap();
        vault(v.path());
        let bound = prepare(&ServeConfig {
            chronicle_dir: v.path().to_path_buf(),
            preferred_port: 0,
            lan: true,
            host: "127.0.0.1".into(),
        })
        .expect("LAN prepare failed");

        let ci = &bound.state.connect_info;
        assert!(
            ci.base.starts_with("https://"),
            "LAN base is cleartext: {}",
            ci.base
        );
        assert!(ci.tls, "LAN mode did not enable TLS");
        assert_eq!(ci.version, 2, "LAN connect payload must be v2 (carries the pin)");
        assert!(ci.tls_fp.is_some(), "LAN connect payload has no certificate pin");
        assert!(bound.tls.is_some(), "no TLS config was built for the listener");
        assert!(
            ci.token.is_some() && ci.auth_required,
            "LAN must still require the bearer token"
        );
        // The local WebView keeps a plain loopback URL to load.
        assert!(
            bound.base_local.starts_with("http://127.0.0.1"),
            "loopback base should stay plain: {}",
            bound.base_local
        );

        // --- loopback-only mode -------------------------------------------
        let v2 = tempfile::tempdir().unwrap();
        vault(v2.path());
        let local = prepare(&ServeConfig {
            chronicle_dir: v2.path().to_path_buf(),
            preferred_port: 0,
            lan: false,
            host: "127.0.0.1".into(),
        })
        .expect("loopback prepare failed");
        assert!(!local.state.connect_info.tls, "loopback should not need TLS");
        assert!(local.tls.is_none());
        assert_eq!(local.state.connect_info.version, 1);

        std::env::remove_var("CHRONICLE_CONFIG_HOME");
    }
}
