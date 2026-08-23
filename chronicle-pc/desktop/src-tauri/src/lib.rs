mod embedded;

use embedded::{ServerState, SharedServer};
use std::sync::Arc;
use std::time::Duration;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder,
};

#[tauri::command]
fn get_serve_status(state: tauri::State<'_, SharedServer>) -> embedded::ServeStatus {
    state.lock().map(|g| g.snapshot()).unwrap_or(embedded::ServeStatus {
        ready: false,
        url: None,
        port: None,
        message: "Lock poisoned".into(),
        error: Some("internal state lock poisoned".into()),
    })
}

#[tauri::command]
fn restart_serve(
    app: AppHandle,
    state: tauri::State<'_, SharedServer>,
) -> embedded::ServeStatus {
    let shared = state.inner().clone();
    embedded::request_restart(&shared, &app)
}

#[tauri::command]
fn notify_capture_saved(app: AppHandle) {
    let _ = app.emit("capture-saved", ());
    refresh_dock_badge(&app);
}

fn show_main(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.show();
        let _ = win.set_focus();
        let _ = win.unminimize();
    }
}

fn open_capture(app: &AppHandle) {
    if let Some(existing) = app.get_webview_window("capture") {
        let _ = existing.show();
        let _ = existing.set_focus();
        return;
    }
    let url = if cfg!(debug_assertions) {
        WebviewUrl::External("http://localhost:1420/capture.html".parse().unwrap())
    } else {
        WebviewUrl::App("capture.html".into())
    };
    let _ = WebviewWindowBuilder::new(app, "capture", url)
        .title("Quick Capture")
        .inner_size(420.0, 320.0)
        .resizable(true)
        .center()
        .build();
}

fn refresh_dock_badge(app: &AppHandle) {
    let (ready, port) = app
        .state::<SharedServer>()
        .lock()
        .ok()
        .map(|g| (g.ready, g.port))
        .unwrap_or((false, None));
    if !ready {
        return;
    }
    let Some(port) = port else { return };
    // The embedded server knows its own vault; badge count comes from the API.
    let count = fetch_unprocessed_count(port).unwrap_or(0);
    if let Some(main) = app.get_webview_window("main") {
        let badge = if count > 0 { Some(count) } else { None };
        let _ = main.set_badge_count(badge);
    }
}

/// Loopback /connect → token → entries count. Same contract as the sidecar era.
fn fetch_unprocessed_count(port: u16) -> Option<i64> {
    let base = format!("http://127.0.0.1:{port}");
    let token: Option<String> = ureq_lite_get(&format!("{base}/connect"))
        .and_then(|body| serde_json::from_str::<serde_json::Value>(&body).ok())
        .and_then(|v| {
            if v.get("auth_required").and_then(serde_json::Value::as_bool) == Some(false) {
                None
            } else {
                v.get("token").and_then(serde_json::Value::as_str).map(String::from)
            }
        });
    let mut url = format!("{base}/entries?processed=false&limit=1");
    if token.is_some() {
        // header appended below
    }
    let body = ureq_lite_get_with_header(
        &url,
        token.as_deref().map(|t| ("X-Chronicle-Token", t)),
    )?;
    let v: serde_json::Value = serde_json::from_str(&body).ok()?;
    v.get("total").and_then(serde_json::Value::as_i64)
}

fn ureq_lite_get(url: &str) -> Option<String> {
    ureq_lite_get_with_header(url, None)
}

fn ureq_lite_get_with_header(url: &str, header: Option<(&str, &str)>) -> Option<String> {
    use std::io::{Read, Write};
    // Minimal HTTP/1.1 GET over TcpStream — zero extra deps beyond std.
    let mut parts = url.strip_prefix("http://")?.splitn(2, '/');
    let hostport = parts.next()?;
    let path = match parts.next() {
        Some(p) => format!("/{p}"),
        None => "/".into(),
    };
    use std::net::TcpStream;
    let mut stream = TcpStream::connect(hostport).ok()?;
    stream.set_read_timeout(Some(Duration::from_millis(1200))).ok()?;
    stream.set_write_timeout(Some(Duration::from_millis(1200))).ok()?;
    let mut req = format!("GET {path} HTTP/1.1\r\nHost: {hostport}\r\nConnection: close\r\n");
    if let Some((k, v)) = header {
        req.push_str(&format!("{k}: {v}\r\n"));
    }
    req.push_str("\r\n");
    stream.write_all(req.as_bytes()).ok()?;
    let mut buf = String::new();
    stream.read_to_string(&mut buf).ok()?;
    let status_ok = buf
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .map(|c| c.starts_with('2'))
        .unwrap_or(false);
    if !status_ok {
        return None;
    }
    buf.split_once("\r\n\r\n").map(|(_, body)| body.to_string())
}

fn setup_tray(app: &AppHandle) -> tauri::Result<()> {
    let show_i = MenuItem::with_id(app, "show", "Show Chronicle", true, None::<&str>)?;
    let capture_i = MenuItem::with_id(app, "capture", "Quick Capture", true, None::<&str>)?;
    let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show_i, &capture_i, &quit_i])?;

    let mut builder = TrayIconBuilder::new()
        .menu(&menu)
        .show_menu_on_left_click(true)
        .tooltip("Chronicle")
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main(app),
            "capture" => open_capture(app),
            "quit" => {
                if let Ok(mut g) = app.state::<SharedServer>().lock() {
                    g.stop_signal();
                }
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        });

    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }

    let _ = builder.build(app)?;
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let shared: SharedServer = Arc::new(std::sync::Mutex::new(ServerState::default()));

    tauri::Builder::default()
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .manage(shared.clone())
        .invoke_handler(tauri::generate_handler![
            get_serve_status,
            restart_serve,
            notify_capture_saved
        ])
        .setup(move |app| {
            setup_tray(app.handle())?;

            let boot_state = shared.clone();
            let boot_handle = app.handle().clone();
            std::thread::spawn(move || {
                embedded::boot(boot_state, boot_handle);
            });

            let badge_handle = app.handle().clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(Duration::from_secs(60));
                refresh_dock_badge(&badge_handle);
            });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building Chronicle desktop")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Ok(mut g) = app.state::<SharedServer>().lock() {
                    g.stop_signal();
                }
            }
        });
}
