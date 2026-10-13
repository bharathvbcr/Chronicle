//! Embedded Chronicle server lifecycle for the Tauri shell.
//!
//! The server runs IN-PROCESS (no sidecar, no Python venv). Lifecycle:
//! prepare → mark ready + notify splash → serve until shutdown requested →
//! graceful drain. `request_restart` drives both the Retry button and quit.

use serde::Serialize;
use std::collections::HashMap;
use std::ffi::{OsStr, OsString};
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

type EnvMap = HashMap<String, OsString>;

fn process_env() -> EnvMap {
    std::env::vars_os()
        .filter_map(|(k, v)| k.into_string().ok().map(|k| (k, v)))
        .collect()
}

fn env_nonempty<'a>(env: &'a EnvMap, key: &str) -> Option<&'a OsStr> {
    let value = env.get(key)?;
    if value.is_empty() {
        None
    } else {
        Some(value.as_os_str())
    }
}

fn absolute_base(env: &EnvMap, key: &str) -> Option<PathBuf> {
    let value = env_nonempty(env, key)?;
    let path = PathBuf::from(value);
    if path.is_absolute() {
        Some(path)
    } else {
        None
    }
}

fn support_file_for(os: &str, env: &EnvMap) -> Option<PathBuf> {
    let base = match os {
        "macos" => absolute_base(env, "HOME").unwrap_or_else(|| PathBuf::from("/")),
        "windows" => {
            if let Some(base) = absolute_base(env, "APPDATA") {
                base
            } else if let Some(base) = absolute_base(env, "LOCALAPPDATA") {
                base
            } else if let Some(profile) = absolute_base(env, "USERPROFILE") {
                profile.join("AppData").join("Roaming")
            } else if let Some(home) = absolute_base(env, "HOME") {
                home.join("AppData").join("Roaming")
            } else {
                return None;
            }
        }
        _ => {
            // Linux and any other host: XDG config, never macOS Library.
            if let Some(xdg) = absolute_base(env, "XDG_CONFIG_HOME") {
                xdg
            } else if let Some(home) = absolute_base(env, "HOME") {
                home.join(".config")
            } else {
                return None;
            }
        }
    };
    if os == "macos" {
        Some(base.join("Library/Application Support/Chronicle/pc_root"))
    } else {
        Some(base.join("Chronicle").join("pc_root"))
    }
}

fn vault_candidate_for(os: &str, env: &EnvMap) -> Option<PathBuf> {
    let base = match os {
        // HOME unset stays `/Chronicle`, matching the previous macOS shell.
        "macos" => absolute_base(env, "HOME").unwrap_or_else(|| PathBuf::from("/")),
        "windows" => absolute_base(env, "USERPROFILE").or_else(|| absolute_base(env, "HOME"))?,
        // Do not move an existing Syncthing vault under XDG_DATA_HOME.
        _ => absolute_base(env, "HOME")?,
    };
    Some(base.join("Chronicle"))
}

pub fn pc_root_support_file() -> Option<PathBuf> {
    support_file_for(std::env::consts::OS, &process_env())
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
    if let Some(support) = pc_root_support_file() {
        if let Some(persisted) = read_persisted_pc_root(&support) {
            return Some(persisted);
        }
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

fn chronicle_dir_override(env: &EnvMap) -> Result<Option<PathBuf>, String> {
    let Some(raw) = env.get("CHRONICLE_DIR") else {
        return Ok(None);
    };
    if raw.is_empty() {
        return Ok(None);
    }
    let Some(text) = raw.to_str() else {
        return Ok(None);
    };
    let path = PathBuf::from(text.trim());
    if path.is_dir() {
        Ok(Some(path))
    } else {
        Err(format!(
            "CHRONICLE_DIR is not a directory: {}",
            path.display()
        ))
    }
}

fn resolve_vault_dir_from(os: &str, env: &EnvMap, pc: &Path) -> Result<PathBuf, String> {
    if let Some(dir) = chronicle_dir_override(env)? {
        return Ok(dir);
    }
    if let Some(home_vault) = vault_candidate_for(os, env) {
        if home_vault.is_dir() {
            return Ok(home_vault);
        }
    }
    let demo = pc.parent().map(|r| r.join("demo-vault"));
    if demo.as_ref().is_some_and(|d| d.is_dir()) {
        return Ok(demo.unwrap());
    }
    Err("Set CHRONICLE_DIR to your Syncthing Chronicle folder (e.g. ~/Chronicle).".into())
}

pub fn resolve_vault_dir(pc: &std::path::Path) -> Result<PathBuf, String> {
    resolve_vault_dir_from(std::env::consts::OS, &process_env(), pc)
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

    // Binding and transport now have one owner in chronicle_server::serve:
    // loopback plain for this WebView, the LAN address with TLS for the phone.
    eprintln!(
        "[chronicle] listening on {} (LAN: {})",
        bound.base_local,
        bound.state.connect_info.base
    );
    match chronicle_server::serve::serve_bound(&bound, rx).await {
        Ok(()) => {
            update(&state, |g| {
                g.ready = false;
                g.message = "Server stopped".into();
                g.shutdown_tx = None;
            });
        }
        Err(e) => {
            update(&state, |g| {
                g.ready = false;
                g.message = "Bind failed".into();
                g.error = Some(e.clone());
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

#[cfg(test)]
mod tests {
    use super::*;

    fn env_map(pairs: &[(&str, &str)]) -> EnvMap {
        pairs
            .iter()
            .map(|(key, value)| ((*key).to_string(), OsString::from(*value)))
            .collect()
    }

    fn assert_no_library(path: &Path) {
        assert!(
            path.components().all(|c| c.as_os_str() != "Library"),
            "path must not contain Library: {}",
            path.display()
        );
    }

    #[test]
    fn macos_home_support_uses_library_and_ignores_userprofile() {
        let home = PathBuf::from("/Users/me");
        let env = env_map(&[
            ("HOME", "/Users/me"),
            ("USERPROFILE", "/Users/ignored"),
        ]);
        assert_eq!(
            support_file_for("macos", &env),
            Some(home.join("Library/Application Support/Chronicle/pc_root"))
        );
        assert_eq!(vault_candidate_for("macos", &env), Some(home.join("Chronicle")));
    }

    #[test]
    fn windows_appdata_support_ignores_library_home_and_vault_uses_userprofile() {
        let appdata = PathBuf::from("/Users/me/AppData/Roaming");
        let profile = PathBuf::from("/Users/me");
        let env = env_map(&[
            ("APPDATA", "/Users/me/AppData/Roaming"),
            ("HOME", "/Users/me/Library/Application Support"),
            ("USERPROFILE", "/Users/me"),
        ]);
        let support = support_file_for("windows", &env).unwrap();
        assert_eq!(support, appdata.join("Chronicle").join("pc_root"));
        assert_no_library(&support);
        assert_eq!(
            vault_candidate_for("windows", &env),
            Some(profile.join("Chronicle"))
        );
    }

    #[test]
    fn windows_localappdata_only_support() {
        let local = PathBuf::from("/Users/me/AppData/Local");
        let env = env_map(&[("LOCALAPPDATA", "/Users/me/AppData/Local")]);
        assert_eq!(
            support_file_for("windows", &env),
            Some(local.join("Chronicle").join("pc_root"))
        );
    }

    #[test]
    fn windows_userprofile_roaming_before_home() {
        let profile = PathBuf::from("/Users/me");
        let env = env_map(&[
            ("USERPROFILE", "/Users/me"),
            ("HOME", "/home/other"),
        ]);
        assert_eq!(
            support_file_for("windows", &env),
            Some(
                profile
                    .join("AppData")
                    .join("Roaming")
                    .join("Chronicle")
                    .join("pc_root")
            )
        );
        assert_eq!(
            vault_candidate_for("windows", &env),
            Some(profile.join("Chronicle"))
        );
    }

    #[test]
    fn windows_home_appdata_when_profile_unset() {
        let home = PathBuf::from("/home/me");
        let env = env_map(&[("HOME", "/home/me")]);
        assert_eq!(
            support_file_for("windows", &env),
            Some(
                home.join("AppData")
                    .join("Roaming")
                    .join("Chronicle")
                    .join("pc_root")
            )
        );
        assert_eq!(
            vault_candidate_for("windows", &env),
            Some(home.join("Chronicle"))
        );
    }

    #[test]
    fn windows_nothing_set_skips_support_and_root_vault() {
        let env = env_map(&[]);
        assert_eq!(support_file_for("windows", &env), None);
        assert_eq!(vault_candidate_for("windows", &env), None);
        let err = resolve_vault_dir_from("windows", &env, Path::new("/no/such/chronicle-pc"))
            .unwrap_err();
        assert_eq!(
            err,
            "Set CHRONICLE_DIR to your Syncthing Chronicle folder (e.g. ~/Chronicle)."
        );
    }

    #[test]
    fn linux_home_config_support_and_home_vault_without_library() {
        let home = PathBuf::from("/home/me");
        let env = env_map(&[("HOME", "/home/me")]);
        let support = support_file_for("linux", &env).unwrap();
        assert_eq!(
            support,
            home.join(".config").join("Chronicle").join("pc_root")
        );
        assert_no_library(&support);
        let vault = vault_candidate_for("linux", &env).unwrap();
        assert_eq!(vault, home.join("Chronicle"));
        assert_no_library(&vault);
    }

    #[test]
    fn linux_xdg_config_home_support_keeps_home_vault() {
        let env = env_map(&[
            ("XDG_CONFIG_HOME", "/xdg"),
            ("HOME", "/home/me"),
        ]);
        assert_eq!(
            support_file_for("linux", &env),
            Some(PathBuf::from("/xdg").join("Chronicle").join("pc_root"))
        );
        assert_eq!(
            vault_candidate_for("linux", &env),
            Some(PathBuf::from("/home/me").join("Chronicle"))
        );
    }

    #[test]
    fn empty_env_values_are_unset() {
        let env = env_map(&[
            ("APPDATA", ""),
            ("LOCALAPPDATA", ""),
            ("USERPROFILE", ""),
            ("HOME", ""),
            ("XDG_CONFIG_HOME", ""),
            ("CHRONICLE_DIR", ""),
        ]);
        assert_eq!(support_file_for("windows", &env), None);
        assert_eq!(vault_candidate_for("windows", &env), None);
        assert_eq!(support_file_for("linux", &env), None);
        assert_eq!(vault_candidate_for("linux", &env), None);
        assert_eq!(
            support_file_for("macos", &env),
            Some(PathBuf::from("/").join("Library/Application Support/Chronicle/pc_root"))
        );
        assert_eq!(
            vault_candidate_for("macos", &env),
            Some(PathBuf::from("/").join("Chronicle"))
        );
        let err = resolve_vault_dir_from("linux", &env, Path::new("/no/such/chronicle-pc"))
            .unwrap_err();
        assert_eq!(
            err,
            "Set CHRONICLE_DIR to your Syncthing Chronicle folder (e.g. ~/Chronicle)."
        );
    }

    #[test]
    fn relative_bases_are_not_joined_onto_root() {
        let relative = "Chronicle/../../etc";
        let env = env_map(&[
            ("APPDATA", relative),
            ("LOCALAPPDATA", "Chronicle"),
            ("USERPROFILE", "Chronicle"),
            ("HOME", relative),
            ("XDG_CONFIG_HOME", "Chronicle"),
        ]);
        assert_eq!(support_file_for("windows", &env), None);
        assert_eq!(vault_candidate_for("windows", &env), None);
        assert_eq!(support_file_for("linux", &env), None);
        assert_eq!(vault_candidate_for("linux", &env), None);
        let mac_support = support_file_for("macos", &env).unwrap();
        assert_eq!(
            mac_support,
            PathBuf::from("/").join("Library/Application Support/Chronicle/pc_root")
        );
        assert_eq!(
            vault_candidate_for("macos", &env),
            Some(PathBuf::from("/").join("Chronicle"))
        );

        let mixed = env_map(&[
            ("APPDATA", "Chronicle"),
            ("USERPROFILE", "Chronicle"),
            ("XDG_CONFIG_HOME", "Chronicle"),
            ("HOME", "/home/me"),
        ]);
        let home = PathBuf::from("/home/me");
        assert_eq!(
            support_file_for("windows", &mixed),
            Some(
                home.join("AppData")
                    .join("Roaming")
                    .join("Chronicle")
                    .join("pc_root")
            )
        );
        assert_eq!(
            vault_candidate_for("windows", &mixed),
            Some(home.join("Chronicle"))
        );
        assert_eq!(
            support_file_for("linux", &mixed),
            Some(home.join(".config").join("Chronicle").join("pc_root"))
        );
    }

    #[test]
    fn chronicle_dir_wins_when_directory_and_errors_when_missing() {
        let root = std::env::temp_dir().join(format!(
            "chronicle-embedded-paths-{}",
            std::process::id()
        ));
        let chronicle_dir = root.join("explicit");
        let home = root.join("home");
        let home_vault = home.join("Chronicle");
        std::fs::create_dir_all(&chronicle_dir).unwrap();
        std::fs::create_dir_all(&home_vault).unwrap();
        let win = resolve_vault_dir_from(
            "windows",
            &env_map(&[
                ("CHRONICLE_DIR", chronicle_dir.to_str().unwrap()),
                ("USERPROFILE", home.to_str().unwrap()),
            ]),
            Path::new("/no/such/chronicle-pc"),
        )
        .unwrap();
        assert_eq!(win, chronicle_dir);

        let missing = resolve_vault_dir_from(
            "macos",
            &env_map(&[
                ("CHRONICLE_DIR", "/no/such/chronicle-dir-not-a-directory"),
                ("HOME", home.to_str().unwrap()),
            ]),
            Path::new("/no/such/chronicle-pc"),
        )
        .unwrap_err();
        assert!(missing.starts_with("CHRONICLE_DIR is not a directory:"));

        let pc = root.join("chronicle-pc");
        let demo = root.join("demo-vault");
        std::fs::create_dir_all(&pc).unwrap();
        std::fs::create_dir_all(&demo).unwrap();
        let fell_through = resolve_vault_dir_from("linux", &env_map(&[]), &pc).unwrap();
        assert_eq!(fell_through, demo);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn production_support_wrapper_matches_host_env() {
        let env = process_env();
        assert_eq!(
            pc_root_support_file(),
            support_file_for(std::env::consts::OS, &env)
        );
        if std::env::consts::OS == "macos" {
            let home = absolute_base(&env, "HOME").expect("HOME");
            assert_eq!(
                pc_root_support_file(),
                Some(home.join("Library/Application Support/Chronicle/pc_root"))
            );
        }
    }
}
