//! Vault path resolution + atomic writes (temp-in-same-dir → fsync → rename).

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::Serialize;

use crate::errors::ChronicleError;

/// Recursion cap for vault walkers: far above any real PARA tree, low enough
/// that even a hostile on-disk shape cannot exhaust the stack.
pub const MAX_WALK_DEPTH: usize = 128;

/// Children of `dir` as `(path, is_real_dir)` pairs. Symlinks are reported as
/// non-directories and are never followed, so recursive walkers cannot loop on
/// cycles or double-index linked subtrees (Syncthing/iCloud/Obsidian can plant
/// links in the vault).
pub fn list_children(dir: &Path) -> Vec<(PathBuf, bool)> {
    let Ok(rd) = fs::read_dir(dir) else {
        return Vec::new();
    };
    rd.flatten()
        .filter_map(|e| {
            let is_dir = e.file_type().ok()?.is_dir();
            Some((e.path(), is_dir))
        })
        .collect()
}

/// Strict [`list_children`]: same `(path, is_real_dir)` shape and symlink
/// rule, but a `read_dir` failure or an unreadable entry is returned as an
/// error instead of being dropped. For walkers whose output must be complete
/// (backup), where a silently shortened listing is data loss.
pub fn try_list_children(dir: &Path) -> std::io::Result<Vec<(PathBuf, bool)>> {
    let mut out = Vec::new();
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let is_dir = entry.file_type()?.is_dir();
        out.push((entry.path(), is_dir));
    }
    Ok(out)
}

/// Depth-capped, symlink-safe recursive file collection under `dir`.
/// `keep_file(path, name)` decides which files to emit; directories are always
/// descended into (callers pre-filter excluded dir names themselves when
/// needed).
pub fn walk_files_filtered<F>(dir: &Path, out: &mut Vec<PathBuf>, depth: usize, keep_file: &F)
where
    F: Fn(&Path, &str) -> bool,
{
    if depth >= MAX_WALK_DEPTH {
        return;
    }
    for (p, is_dir) in list_children(dir) {
        if is_dir {
            walk_files_filtered(&p, out, depth + 1, keep_file);
        } else {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if keep_file(&p, &name) {
                out.push(p);
            }
        }
    }
}

/// explicit arg → CHRONICLE_DIR env → cwd (paths.py:12-19).
pub fn resolve_chronicle_dir(explicit: Option<&Path>) -> Result<PathBuf, ChronicleError> {
    if let Some(p) = explicit {
        return Ok(p.to_path_buf());
    }
    if let Ok(env) = std::env::var("CHRONICLE_DIR") {
        let trimmed = env.trim();
        if !trimmed.is_empty() {
            return Ok(PathBuf::from(trimmed));
        }
    }
    Ok(std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")))
}

pub fn content_hash(text: &str) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(text.as_bytes());
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

fn fsync_and_replace(tmp_path: &Path, target: &Path) -> Result<(), ChronicleError> {
    let f = File::open(tmp_path)?;
    let _ = f.sync_all();
    drop(f);
    fs::rename(tmp_path, target)?;
    // Best-effort directory durability (python relies on os.replace only).
    if let Some(parent) = target.parent() {
        if let Ok(dir) = File::open(parent) {
            let _ = dir.sync_all();
        }
    }
    Ok(())
}

static ATOMIC_WRITE_SEQ: AtomicU64 = AtomicU64::new(0);

fn atomic_write_via_temp(path: &Path, data: &[u8]) -> Result<(), ChronicleError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let n = ATOMIC_WRITE_SEQ.fetch_add(1, Ordering::Relaxed);
    let mut tmp = path.as_os_str().to_owned();
    tmp.push(format!(".{}.{}.tmp", std::process::id(), n));
    let tmp_path = PathBuf::from(tmp);
    {
        // create_new fails with EEXIST on a planted name, including a symlink,
        // and does not follow that symlink.
        let mut f = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&tmp_path)?;
        f.write_all(data)?;
        f.sync_all()?;
    }
    match fsync_and_replace(&tmp_path, path) {
        Ok(()) => Ok(()),
        Err(e) => {
            let _ = fs::remove_file(&tmp_path);
            Err(e)
        }
    }
}

pub fn atomic_write_text(path: &Path, text: &str) -> Result<(), ChronicleError> {
    atomic_write_via_temp(path, text.as_bytes())
}

pub fn atomic_write_bytes(path: &Path, data: &[u8]) -> Result<(), ChronicleError> {
    atomic_write_via_temp(path, data)
}

pub fn atomic_write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), ChronicleError> {
    let mut text = serde_json::to_string_pretty(value).map_err(|e| ChronicleError::msg(e.to_string()))?;
    if !text.ends_with('\n') {
        text.push('\n');
    }
    atomic_write_text(path, &text)
}

pub fn read_json(path: &Path) -> Result<serde_json::Value, ChronicleError> {
    let raw = fs::read_to_string(path)?;
    serde_json::from_str(&raw).map_err(|e| ChronicleError::msg(format!("{}: {e}", path.display())))
}

pub fn read_text_lossy(path: &Path) -> String {
    fs::read(path)
        .map(|b| String::from_utf8_lossy(&b).to_string())
        .unwrap_or_default()
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn containment_atomic_write_temp_symlink_not_followed() {
        let dir = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let dest = dir.path().join("note.md");
        let stolen = outside.path().join("stolen.txt");
        fs::write(&stolen, b"original").unwrap();
        let planted = PathBuf::from(format!("{}.{}.tmp", dest.display(), std::process::id()));
        std::os::unix::fs::symlink(&stolen, &planted).unwrap();

        atomic_write_text(&dest, "payload-not-for-outside").unwrap();
        atomic_write_bytes(&dest, b"payload-bytes").unwrap();

        assert_eq!(fs::read(&stolen).unwrap(), b"original", "temp symlink was followed");
        assert_eq!(fs::read(&dest).unwrap(), b"payload-bytes");
        assert!(planted.symlink_metadata().unwrap().file_type().is_symlink());
    }

    #[test]
    fn containment_atomic_write_concurrent_no_tear() {
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("note.md");
        let a = "A".repeat(512 * 1024);
        let b = "B".repeat(512 * 1024);
        for round in 0..40 {
            let dest_a = dest.clone();
            let dest_b = dest.clone();
            let payload_a = a.clone();
            let payload_b = b.clone();
            let left = std::thread::spawn(move || atomic_write_text(&dest_a, &payload_a));
            let right = std::thread::spawn(move || atomic_write_text(&dest_b, &payload_b));
            left.join().unwrap().unwrap();
            right.join().unwrap().unwrap();
            let got = fs::read_to_string(&dest).unwrap();
            assert!(
                got == a || got == b,
                "round {round} tore the destination (len {}, distinct bytes {})",
                got.len(),
                got.bytes().filter(|c| *c != b'A' && *c != b'B').count()
                    + usize::from(got.contains('A') && got.contains('B'))
            );
        }
    }
}
