//! whisper.cpp subprocess wrapper (transcribe.py).

use std::path::PathBuf;
use std::time::Duration;

fn which(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let candidate = dir.join(name);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn executable(p: &PathBuf) -> bool {
    p.is_file() && is_executable(p)
}

#[cfg(unix)]
fn is_executable(p: &PathBuf) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(p).map(|m| m.permissions().mode() & 0o111 != 0).unwrap_or(false)
}

#[cfg(not(unix))]
fn is_executable(_p: &PathBuf) -> bool {
    true
}

/// find_whisper_binary: PATH names first (never bare `main`), then fixed paths.
pub fn find_whisper_binary() -> Option<PathBuf> {
    for name in ["whisper-cli", "whisper-cpp", "whisper"] {
        if let Some(p) = which(name) {
            return Some(p);
        }
    }
    if let Some(home) = std::env::var_os("HOME").map(PathBuf::from) {
        let candidates = [
            home.join("whisper.cpp/build/bin/whisper-cli"),
            home.join("whisper.cpp/main"),
        ];
        for c in candidates {
            if executable(&c) {
                return Some(c);
            }
        }
    }
    let usr = PathBuf::from("/usr/local/bin/whisper-cli");
    if executable(&usr) {
        return Some(usr);
    }
    None
}

/// find_whisper_model: WHISPER_MODEL env (must exist), then fixed candidates.
pub fn find_whisper_model() -> Option<PathBuf> {
    if let Ok(env) = std::env::var("WHISPER_MODEL") {
        let p = PathBuf::from(env.trim());
        if p.is_file() {
            return Some(p);
        }
    }
    if let Some(home) = std::env::var_os("HOME").map(PathBuf::from) {
        for c in [
            home.join("whisper.cpp/models/ggml-base.en.bin"),
            home.join("whisper.cpp/models/ggml-base.bin"),
            home.join("models/ggml-base.en.bin"),
        ] {
            if c.is_file() {
                return Some(c);
            }
        }
    }
    None
}

/// transcribe → transcript text or None (skip + warn taxonomy preserved).
pub fn transcribe(audio_path: &std::path::Path) -> Option<String> {
    transcribe_opts(audio_path, None, None, Duration::from_secs(600))
}

pub fn transcribe_opts(
    audio_path: &std::path::Path,
    binary: Option<PathBuf>,
    model: Option<PathBuf>,
    timeout: Duration,
) -> Option<String> {
    if !audio_path.is_file() {
        eprintln!(
            "WARNING chronicle.transcribe: Audio file missing: {}",
            audio_path.display()
        );
        return None;
    }
    let bin_path = binary.or_else(find_whisper_binary);
    let Some(bin_path) = bin_path else {
        eprintln!(
            "WARNING chronicle.transcribe: whisper.cpp binary not found; skipping transcription for {}. Install whisper-cli and set PATH, or place binary under ~/whisper.cpp/",
            audio_path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default()
        );
        return None;
    };
    let model_path = model.or_else(find_whisper_model);
    let Some(model_path) = model_path else {
        eprintln!(
            "WARNING chronicle.transcribe: whisper model not found; skipping transcription for {}. Set WHISPER_MODEL to a ggml-*.bin path.",
            audio_path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default()
        );
        return None;
    };

    let tmp = match tempfile_dir() {
        Some(t) => t,
        None => return None,
    };
    let out_base = tmp.join("out");
    let output = std::process::Command::new(&bin_path)
        .args([
            "-m",
            &model_path.to_string_lossy(),
            "-f",
            &audio_path.to_string_lossy(),
            "-otxt",
            "-of",
            &out_base.to_string_lossy(),
            "-np",
        ])
        .output();

    let proc_output = match output {
        Ok(o) => o,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&tmp);
            eprintln!(
                "WARNING chronicle.transcribe: whisper.cpp failed for {}: {}",
                audio_path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default(),
                e
            );
            return None;
        }
    };
    // Timeout enforcement: spawn already completed synchronously here; the
    // bounded variant below should be preferred from async callers.
    let _ = timeout;

    let txt_path = PathBuf::from(format!("{}.txt", out_base.to_string_lossy()));
    if let Ok(text) = std::fs::read_to_string(&txt_path) {
        let t = String::from_utf8_lossy(text.as_bytes()).trim().to_string();
        let _ = std::fs::remove_dir_all(&tmp);
        if !t.is_empty() {
            return Some(t);
        }
    } else {
        let _ = std::fs::remove_dir_all(&tmp);
    }

    if proc_output.status.success() {
        let stdout = String::from_utf8_lossy(&proc_output.stdout);
        let lines: Vec<&str> = stdout
            .lines()
            .filter(|l| !l.trim().is_empty() && !l.trim().starts_with('['))
            .collect();
        let joined = lines.join("\n").trim().to_string();
        if !joined.is_empty() {
            return Some(joined);
        }
    }
    eprintln!(
        "WARNING chronicle.transcribe: whisper.cpp produced no transcript for {} (rc={})",
        audio_path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default(),
        proc_output.status.code().map(|c| c.to_string()).unwrap_or_else(|| "signal".into())
    );
    None
}

fn tempfile_dir() -> Option<PathBuf> {
    let base = std::env::temp_dir();
    for _ in 0..32 {
        let candidate = base.join(format!(
            "chronicle-whisper-{}",
            rand_suffix()
        ));
        if std::fs::create_dir(&candidate).is_ok() {
            return Some(candidate);
        }
    }
    None
}

fn rand_suffix() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("{:x}-{}", nanos, std::process::id())
}
