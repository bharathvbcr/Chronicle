//! Vault zip backup (backup.py): deflate, Chronicle/<rel> arcnames,
//! exclusions {index,.git,.venv,__pycache__,.stfolder} + *.tmp/.DS_Store.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use serde_json::json;

use crate::errors::ChronicleError;
use crate::paths::resolve_chronicle_dir;

const EXCLUDED_DIRS: [&str; 5] = ["index", ".git", ".venv", "__pycache__", ".stfolder"];

pub fn default_backup_path(root: &Path) -> PathBuf {
    let stamp = chrono::Local::now().format("%Y%m%d-%H%M%S");
    root.parent()
        .unwrap_or(root)
        .join(format!("chronicle-backup-{stamp}.zip"))
}

fn collect_files(root: &Path) -> Vec<PathBuf> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<PathBuf>, depth: usize) {
        if depth >= crate::paths::MAX_WALK_DEPTH {
            return;
        }
        for (p, is_dir) in crate::paths::list_children(dir) {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if is_dir {
                if EXCLUDED_DIRS.contains(&name.as_str()) || name.starts_with('.') {
                    continue;
                }
                walk(root, &p, out, depth + 1);
            } else {
                if name.ends_with(".tmp") || name == ".DS_Store" || name.starts_with('.') {
                    continue;
                }
                out.push(p);
            }
        }
    }
    let mut out = Vec::new();
    walk(root, root, &mut out, 0);
    out.sort();
    out
}

pub fn run_backup(
    dir: Option<&Path>,
    out_path: Option<&Path>,
    force: bool,
) -> Result<serde_json::Value, ChronicleError> {
    let root = resolve_chronicle_dir(dir)?;
    let target = out_path
        .map(Path::to_path_buf)
        .unwrap_or_else(|| default_backup_path(&root));
    if target.exists() && !force {
        return Err(ChronicleError::msg(format!(
            "backup target exists: {} (pass --force to overwrite)",
            target.display()
        )));
    }
    let canonical_root = root.canonicalize().unwrap_or_else(|_| root.clone());
    if let Ok(t) = target.canonicalize() {
        if t.starts_with(&canonical_root) {
            // Never zip the target itself.
            return Err(ChronicleError::msg(format!(
                "backup target exists inside vault: {} — choose a path outside the vault",
                target.display()
            )));
        }
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let file = std::fs::File::create(&target).map_err(ChronicleError::from)?;
    let mut zip = zip::ZipWriter::new(file);
    let opts =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let files = collect_files(&root);
    let mut count = 0usize;
    for f in &files {
        let rel = match f.strip_prefix(&root) {
            Ok(r) => r.to_string_lossy().replace('\\', "/"),
            Err(_) => continue,
        };
        let Ok(mut src) = std::fs::File::open(f) else { continue };
        let mut bytes = Vec::new();
        if src.read_to_end(&mut bytes).is_err() {
            continue;
        }
        if zip.start_file(format!("Chronicle/{rel}"), opts).is_err() {
            continue;
        }
        if zip.write_all(&bytes).is_err() {
            continue;
        }
        count += 1;
    }
    zip.finish().map_err(|e| ChronicleError::msg(e.to_string()))?;
    Ok(json!({ "path": target.to_string_lossy(), "files": count }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zips_vault_excluding_index_and_dotfiles() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("v");
        std::fs::create_dir_all(vault.join("index")).unwrap();
        std::fs::create_dir_all(vault.join("notes/daily")).unwrap();
        std::fs::write(vault.join("config.json"), "{}").unwrap();
        std::fs::write(vault.join("notes/daily/a.md"), "# A").unwrap();
        std::fs::write(vault.join("notes/x.tmp"), "junk").unwrap();
        std::fs::write(vault.join("index/db.sqlite"), "secret").unwrap();

        let out = dir.path().join("out.zip");
        let res = run_backup(Some(&vault), Some(&out), false).unwrap();
        assert_eq!(res["files"], json!(2));
        assert!(out.is_file());

        // Roundtrip check.
        let f = std::fs::File::open(&out).unwrap();
        let mut z = zip::ZipArchive::new(f).unwrap();
        assert!(z.by_name("Chronicle/config.json").is_ok());
        assert!(z.by_name("Chronicle/notes/daily/a.md").is_ok());
        assert!(z.by_name("Chronicle/index/db.sqlite").is_err());

        // --force gate with exact message.
        let err = run_backup(Some(&vault), Some(&out), false).unwrap_err();
        assert!(err.to_string().contains("pass --force to overwrite"));
        assert!(run_backup(Some(&vault), Some(&out), true).is_ok());
    }
}
