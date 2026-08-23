//! Embedded Chronicle server lifecycle for the Tauri shell.
//!
//! The server runs IN-PROCESS (no sidecar, no Python venv). Lifecycle:
//! prepare → mark ready + notify splash → serve until shutdown requested →
//! graceful drain. `request_restart` drives both the Retry button and quit.

use serde::Serialize;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use tauri::Emitter;

#[derive(Debug, Clone, Serialize)]
pub struct ServeStatus {
    pub ready: bool,
    pub url: Option<String>,
    pub port: Option<u16>,
    pub message: String,
    pub error: Option<String>,
}

#[derive(Default)]
pub struct ServerState {
    pub ready: bool,
    pub port: Option<u16>,
    pub url: Option<String>,
    pub message: String,
    pub error: Option<String>,
    pub vault_dir: Option<PathBuf>,
    shutdown_tx: Option<tokio::sync::watch::Sender<bool>>,
}

impl ServerState {
    pub fn snapshot(&self) -> ServeStatus {
        ServeStatus {
            ready: self.ready,
            url: self.url.clone(),
            port: self.port,
            message: self.message.clone(),
            error: self.error.clone(),
        }
    }

    pub fn stop_signal(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(true);
        }
        self.ready = false;
    }
}

pub type SharedServer = Arc<Mutex<ServerState>>;

fn update(state: &SharedServer, f: impl FnOnce(&mut ServerState)) {
    if let Ok(mut g) = state.lock() {
        f(&mut g);
    }
}

fn notify(handle: &tauri::AppHandle, state: &SharedServer) {
    let snap = state.lock().map(|g| g.snapshot()).ok();
    if let Some(snap) = snap {
        let _ = handle.emit("serve-status-changed", snap);
    }
}

// ---- pc-root / vault discovery (kept from the sidecar era) ----

pub fn is_valid_pc_root(path: &std::path::Path) -> bool {
    if !path.is_absolute() || path == std::path::Path::new("/") || !path.is_dir() {
        return false;
    }
    path.join("pipeline").is_dir()
        && (path.join("server/Cargo.toml").is_file()
            || path.join("frontend/dist/index.html").is_file())
}

fn walk_for_pc_root(start: &std::path::Path) -> Option<PathBuf> {
    let mut cur = Some(if start.is_file() {
        start.parent()?.to_path_buf()
    } else {
        start.to_path_buf()
    });
    for _ in 0..10 {
        let dir = cur?;
        if is_valid_pc_root(&dir) {
            return Some(dir);
        }
        cur = dir.parent().map(|p| p.to_path_buf());
    }
    None
}

pub fn pc_root_support_file() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
        .join("Library/Application Support/Chronicle/pc_root")
}

fn read_persisted_pc_root(support_file: &Path) -> Option<PathBuf> {
    let raw = std::fs::read_to_string(support_file).ok()?;
    let line = raw.lines().next().unwrap_or("").trim().to_string();
    if line.is_empty() {
        return None;
    }
    let p = PathBuf::from(&line);
    if is_valid_pc_root(&p) {
        Some(p)
    } else {
        None
    }
}

pub fn pc_root() -> Option<PathBuf> {
    if let Ok(env) = std::env::var("CHRONICLE_PC_ROOT") {
        let p = PathBuf::from(env.trim());
        if is_valid_pc_root(&p) {
            return Some(p);
        }
    }
    if let Ok(manifest) = std::env::var("CARGO_MANIFEST_DIR") {
        let p = PathBuf::from(manifest);
        // .../chronicle-pc/desktop/src-tauri
        if let Some(pc) = p.parent().and_then(|x| x.parent()) {
            if is_valid_pc_root(pc) {
                return Some(pc.to_path_buf());
            }
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(found) = walk_for_pc_root(&exe) {
            return Some(found);
        }
    }
    // Persisted hint from Start Chronicle.command / previous installs —
    // essential when the app lives in /Applications (cwd=/, no env).
    if let Some(persisted) = read_persisted_pc_root(&pc_root_support_file()) {
        return Some(persisted);
    }
    let cwd = std::env::current_dir().ok()?;
    if is_valid_pc_root(&cwd) {
        return Some(cwd);
    }
    if let Some(parent) = cwd.parent() {
        if is_valid_pc_root(parent) {
            return Some(parent.to_path_buf());
        }
    }
    let home = std::env::var_os("HOME").map(PathBuf::from)?;
    let candidate = home.join("Code/Chronicle/chronicle-pc");
    if is_valid_pc_root(&candidate) {
        return Some(candidate);
    }
    None
}

pub fn resolve_vault_dir(pc: &std::path::Path) -> Result<PathBuf, String> {
    if let Ok(env) = std::env::var("CHRONICLE_DIR") {
        let p = PathBuf::from(env.trim());
        if p.is_dir() {
            return Ok(p);
        }
        return Err(format!("CHRONICLE_DIR is not a directory: {}", p.display()));
    }
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("/"));
    let home_vault = home.join("Chronicle");
    if home_vault.is_dir() {
        return Ok(home_vault);
    }
    let demo = pc.parent().map(|r| r.join("demo-vault"));
    if demo.as_ref().is_some_and(|d| d.is_dir()) {
        return Ok(demo.unwrap());
    }
    Err("Set CHRONICLE_DIR to your Syncthing Chronicle folder (e.g. ~/Chronicle).".into())
}

/// Boot the embedded server on the async runtime.
fn spawn_server(state: SharedServer, handle: tauri::AppHandle) {
    let h = handle.clone();
    tauri::async_runtime::spawn(async move {
        run_embedded(state, h).await;
    });
}

async fn run_embedded(state: SharedServer, handle: tauri::AppHandle) {
    let (vault_dir, err) = match pc_root() {
        None => (None, "Could not locate chronicle-pc/. Run Start Chronicle.command once, or set CHRONICLE_PC_ROOT.".to_string()),
        Some(pc) => match resolve_vault_dir(&pc) {
            Ok(d) => (Some(d), String::new()),
            Err(e) => (None, e),
        },
    };
    if let Some(d) = vault_dir.clone() {
        update(&state, |g| g.vault_dir = Some(d));
    }
    if !err.is_empty() {
        update(&state, |g| {
            g.message = "Vault not found".into();
            g.error = Some(err);
        });
        notify(&handle, &state);
        return;
    }
    let vault_dir = vault_dir.unwrap();
    eprintln!("[chronicle] vault resolved: {}", vault_dir.display());

    update(&state, |g| g.message = "Starting local server…".into());

    let config = chronicle_server::serve::ServeConfig {
        chronicle_dir: vault_dir,
        preferred_port: 8765,
        lan: true,
        host: "127.0.0.1".into(),
    };

    // prepare() validates layout + picks port BEFORE we report ready.
    let bound = match chronicle_server::serve::prepare(&config) {
        Ok(b) => b,
        Err(e) => {
            update(&state, |g| {
                g.message = if e.contains("layout_version") {
                    "Vault layout incompatible".into()
                } else {
                    "Failed to start server".into()
                };
                g.error = Some(e);
            });
            notify(&handle, &state);
            return;
        }
    };

    let (tx, mut rx) = tokio::sync::watch::channel(false);
    eprintln!("[chronicle] prepared on :{}, ready", bound.actual_port);
    update(&state, |g| {
        g.ready = true;
        g.port = Some(bound.actual_port);
        g.url = Some(bound.base_local.clone());
        g.message = format!("Serve ready on :{}", bound.actual_port);
        g.error = None;
        g.shutdown_tx = Some(tx);
    });
    notify(&handle, &state);

    let lan_bind = config.lan;
    let addr = SocketAddr::new(
        if lan_bind {
            std::net::Ipv4Addr::UNSPECIFIED.into()
        } else {
            std::net::Ipv4Addr::LOCALHOST.into()
        },
        bound.actual_port,
    );

    let router = chronicle_server::serve::build_router_with_layers(bound.state.clone());
    match tokio::net::TcpListener::bind(addr).await {
        Ok(listener) => {
            eprintln!("[chronicle] listening on {addr}");
            let result = axum::serve(
                listener,
                router.into_make_service_with_connect_info::<SocketAddr>(),
            )
            .with_graceful_shutdown(async move {
                let _ = rx.wait_for(|v| *v).await;
            })
            .await;
            update(&state, |g| {
                g.ready = false;
                g.message = if result.is_ok() {
                    "Server stopped".into()
                } else {
                    format!("Server error: {}", result.err().map(|e| e.to_string()).unwrap_or_default())
                };
                g.shutdown_tx = None;
            });
        }
        Err(e) => {
            update(&state, |g| {
                g.ready = false;
                g.message = "Bind failed".into();
                g.error = Some(format!("could not bind {addr}: {e}"));
            });
        }
    }
    notify(&handle, &state);
}

pub fn request_restart(state: &SharedServer, handle: &tauri::AppHandle) -> ServeStatus {
    update(state, |g| {
        g.stop_signal();
        g.port = None;
        g.url = None;
        g.message = "Restarting Chronicle service…".into();
        g.error = None;
    });
    notify(handle, state);
    spawn_server(state.clone(), handle.clone());
    state.lock().map(|g| g.snapshot()).unwrap_or(ServeStatus {
        ready: false,
        url: None,
        port: None,
        message: "Lock poisoned".into(),
        error: Some("internal state lock poisoned".into()),
    })
}

/// Called once at app setup and again after restarts.
pub fn boot(state: SharedServer, handle: tauri::AppHandle) {
    update(&state, |g| {
        g.stop_signal();
    });
    spawn_server(state, handle);
}
