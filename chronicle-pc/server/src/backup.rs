//! Vault zip backup (backup.py): deflate, Chronicle/<rel> arcnames.
//!
//! Exclusions come from `contract/backup-exclusions.json`, the single list
//! shared with the Python oracle: a directory whose name is in `dir_names` is
//! skipped wholesale, a file is skipped when its name is in `file_names` or
//! ends with one of `file_suffixes`. Everything else — dotfiles such as
//! `.stignore` and `.obsidian/` included — is archived.
//!
//! Fail loud: an unreadable directory or file, or a tree deeper than
//! [`crate::paths::MAX_WALK_DEPTH`], aborts the backup with an error naming
//! the path. The archive is written to a temp file beside the target and
//! renamed into place only on success, so a failed run leaves no zip behind.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use serde::Deserialize;
use serde_json::json;

use crate::errors::ChronicleError;
use crate::paths::resolve_chronicle_dir;

const EXCLUSIONS_JSON: &str = include_str!("../../contract/backup-exclusions.json");

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Exclusions {
    dir_names: Vec<String>,
    file_names: Vec<String>,
    file_suffixes: Vec<String>,
}

impl Exclusions {
    fn skips_dir(&self, name: &str) -> bool {
        self.dir_names.iter().any(|d| d == name)
    }

    fn skips_file(&self, name: &str) -> bool {
        self.file_names.iter().any(|f| f == name)
            || self.file_suffixes.iter().any(|s| name.ends_with(s.as_str()))
    }
}

fn parse_exclusions(raw: &str) -> Exclusions {
    serde_json::from_str(raw).expect("contract/backup-exclusions.json is malformed")
}

fn exclusions() -> &'static Exclusions {
    static EXCLUSIONS: OnceLock<Exclusions> = OnceLock::new();
    EXCLUSIONS.get_or_init(|| parse_exclusions(EXCLUSIONS_JSON))
}

pub fn default_backup_path(root: &Path) -> PathBuf {
    let stamp = chrono::Local::now().format("%Y%m%d-%H%M%S");
    root.parent()
        .unwrap_or(root)
        .join(format!("chronicle-backup-{stamp}.zip"))
}

/// Every file to archive under `root`, sorted. Real directories are descended
/// (symlinked ones are not, matching `Path.rglob`); a non-directory is kept
/// only when it resolves to a regular file (symlink→file included, dangling
/// links / sockets / FIFOs skipped, matching `Path.is_file`).
fn collect_files(root: &Path) -> Result<Vec<PathBuf>, ChronicleError> {
    fn walk(dir: &Path, out: &mut Vec<PathBuf>, depth: usize) -> Result<(), ChronicleError> {
        if depth >= crate::paths::MAX_WALK_DEPTH {
            return Err(ChronicleError::msg(format!(
                "backup aborted: {} exceeds the maximum walk depth ({})",
                dir.display(),
                crate::paths::MAX_WALK_DEPTH
            )));
        }
        let children = crate::paths::try_list_children(dir).map_err(|e| {
            ChronicleError::msg(format!("backup aborted: cannot read directory {}: {e}", dir.display()))
        })?;
        let ex = exclusions();
        for (p, is_dir) in children {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if is_dir {
                if !ex.skips_dir(&name) {
                    walk(&p, out, depth + 1)?;
                }
            } else if !ex.skips_file(&name) {
                match std::fs::metadata(&p) {
                    Ok(m) if m.is_file() => out.push(p),
                    // symlink→dir, FIFO, socket: Path.is_file() is False.
                    Ok(_) => {}
                    // Dangling symlink: Path.is_file() is False.
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(e) => {
                        return Err(ChronicleError::msg(format!(
                            "backup aborted: cannot stat {}: {e}",
                            p.display()
                        )))
                    }
                }
            }
        }
        Ok(())
    }
    let mut out = Vec::new();
    walk(root, &mut out, 0)?;
    out.sort();
    Ok(out)
}

/// Write `files` into a deflated zip at `dest`, fsynced. Returns entry count.
fn write_archive(root: &Path, files: &[PathBuf], dest: &Path) -> Result<usize, ChronicleError> {
    let file = std::fs::File::create(dest).map_err(|e| {
        ChronicleError::msg(format!("backup aborted: cannot create {}: {e}", dest.display()))
    })?;
    let mut zip = zip::ZipWriter::new(file);
    let opts =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for f in files {
        let rel = f
            .strip_prefix(root)
            .map_err(|_| {
                ChronicleError::msg(format!(
                    "backup aborted: {} is not under {}",
                    f.display(),
                    root.display()
                ))
            })?
            .to_string_lossy()
            .replace('\\', "/");
        let mut bytes = Vec::new();
        std::fs::File::open(f)
            .and_then(|mut src| src.read_to_end(&mut bytes))
            .map_err(|e| {
                ChronicleError::msg(format!("backup aborted: cannot read {}: {e}", f.display()))
            })?;
        zip.start_file(format!("Chronicle/{rel}"), opts).map_err(|e| {
            ChronicleError::msg(format!("backup aborted: cannot add {} to archive: {e}", f.display()))
        })?;
        zip.write_all(&bytes).map_err(|e| {
            ChronicleError::msg(format!("backup aborted: cannot write {} to archive: {e}", f.display()))
        })?;
    }
    let file = zip
        .finish()
        .map_err(|e| ChronicleError::msg(format!("backup aborted: cannot finish archive: {e}")))?;
    file.sync_all()
        .map_err(|e| ChronicleError::msg(format!("backup aborted: cannot sync {}: {e}", dest.display())))?;
    Ok(files.len())
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
    // Collect before any archive file exists so the in-progress zip can never
    // be part of its own input.
    let files = collect_files(&root)?;
    let target_name = target
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .ok_or_else(|| ChronicleError::msg(format!("backup target has no file name: {}", target.display())))?;
    let partial = target.with_file_name(format!(".{target_name}.{}.partial", std::process::id()));
    let count = write_archive(&root, &files, &partial).map_err(|e| discard_partial(&partial, e))?;
    std::fs::rename(&partial, &target).map_err(|e| {
        discard_partial(
            &partial,
            ChronicleError::msg(format!(
                "backup aborted: cannot move archive into place at {}: {e}",
                target.display()
            )),
        )
    })?;
    Ok(json!({ "path": target.to_string_lossy(), "files": count }))
}

/// Remove a failed run's temp archive; a cleanup failure is appended to the
/// original error rather than replacing or hiding it.
fn discard_partial(partial: &Path, err: ChronicleError) -> ChronicleError {
    match std::fs::remove_file(partial) {
        Ok(()) => err,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => err,
        Err(e) => ChronicleError::msg(format!(
            "{err} (also failed to remove partial archive {}: {e})",
            partial.display()
        )),
    }
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

    #[test]
    fn dotfiles_outside_the_shared_exclusion_list_are_backed_up() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("v");
        std::fs::create_dir_all(vault.join(".obsidian")).unwrap();
        std::fs::create_dir_all(vault.join(".stfolder")).unwrap();
        std::fs::write(vault.join("config.json"), "{}").unwrap();
        std::fs::write(vault.join(".stignore"), "index\n").unwrap();
        std::fs::write(vault.join(".obsidian/app.json"), "{}").unwrap();
        std::fs::write(vault.join(".stfolder/marker"), "").unwrap();
        std::fs::write(vault.join(".DS_Store"), "junk").unwrap();

        let out = dir.path().join("out.zip");
        run_backup(Some(&vault), Some(&out), false).unwrap();
        let mut z = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        let mut names: Vec<String> = z.file_names().map(String::from).collect();
        names.sort();
        assert_eq!(
            names,
            vec![
                "Chronicle/.obsidian/app.json".to_string(),
                "Chronicle/.stignore".to_string(),
                "Chronicle/config.json".to_string(),
            ]
        );
        assert!(z.by_name("Chronicle/.stignore").is_ok());
    }

    #[test]
    fn shipped_exclusion_list_parses() {
        let ex = parse_exclusions(EXCLUSIONS_JSON);
        assert!(ex.skips_dir("index"));
        assert!(ex.skips_dir(".stfolder"));
        assert!(!ex.skips_dir(".obsidian"));
        assert!(ex.skips_file(".DS_Store"));
        assert!(ex.skips_file("x.tmp"));
        assert!(!ex.skips_file(".stignore"));
    }

    #[test]
    #[should_panic(expected = "backup-exclusions.json is malformed")]
    fn malformed_exclusion_list_panics() {
        parse_exclusions(r#"{"dir_names": ["index"]}"#);
    }

    #[test]
    fn walk_deeper_than_cap_fails_loud() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("v");
        let mut deep = vault.clone();
        for _ in 0..=crate::paths::MAX_WALK_DEPTH {
            deep.push("d");
        }
        std::fs::create_dir_all(&deep).unwrap();
        std::fs::write(deep.join("buried.md"), "x").unwrap();
        let out = dir.path().join("out.zip");
        let err = run_backup(Some(&vault), Some(&out), false).unwrap_err();
        assert!(err.to_string().contains("maximum walk depth"), "{err}");
        assert!(!out.exists());
    }

    fn sorted_tree(root: &Path) -> Vec<String> {
        let mut files = Vec::new();
        crate::paths::walk_files_filtered(root, &mut files, 0, &|_p, _n| true);
        let mut rels: Vec<String> = files
            .iter()
            .map(|p| p.strip_prefix(root).unwrap().to_string_lossy().replace('\\', "/"))
            .collect();
        rels.sort();
        rels
    }

    /// Restore drill: back up a realistic vault, extract it into a fresh
    /// directory, and require doctor to see the restored vault exactly as it
    /// saw the original (docs/OPERATIONS.md "Backup and recovery").
    #[test]
    fn backup_restore_round_trip_passes_doctor() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("vault");
        for d in [
            "_capture/entries/2026/08",
            "40-Journal",
            "brain",
            ".obsidian",
            "index",
            "_system",
        ] {
            std::fs::create_dir_all(vault.join(d)).unwrap();
        }
        std::fs::write(vault.join("config.json"), r#"{"layout_version":2,"timezone":"UTC"}"#).unwrap();
        std::fs::write(
            vault.join("_capture/entries/2026/08/2026-08-01_100000-pc.json"),
            json!({"version":1,"id":"2026-08-01_100000-pc","ts":"2026-08-01T10:00:00+00:00","type":"log","text":"hi"})
                .to_string(),
        )
        .unwrap();
        std::fs::write(vault.join("40-Journal/2026-08-01.md"), "# 2026-08-01\n\nA quiet day.\n").unwrap();
        std::fs::write(
            vault.join("brain/graph.json"),
            r#"{"version":1,"generated":"2026-08-01T00:00:00+00:00","nodes":[],"edges":[]}"#,
        )
        .unwrap();
        std::fs::write(vault.join(".stignore"), "index\n").unwrap();
        std::fs::write(vault.join(".obsidian/app.json"), "{}").unwrap();
        std::fs::write(vault.join("index/chronicle.sqlite"), "rebuildable").unwrap();
        std::fs::write(vault.join("_system/scratch.tmp"), "junk").unwrap();
        std::fs::write(vault.join(".DS_Store"), "junk").unwrap();

        let mut original = crate::doctor::run_doctor(Some(&vault), false, false).unwrap();
        assert_eq!(original["ok"], json!(true), "fixture must start clean: {original}");

        let out = dir.path().join("backup.zip");
        let res = run_backup(Some(&vault), Some(&out), false).unwrap();
        assert_eq!(res["files"], json!(6));

        let restored = dir.path().join("restored");
        std::fs::create_dir_all(&restored).unwrap();
        let mut z = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        for i in 0..z.len() {
            let mut f = z.by_index(i).unwrap();
            let name = f.enclosed_name().expect("archive entry escapes extraction root");
            let rel = name.strip_prefix("Chronicle").expect("arcname must start with Chronicle/");
            let dest = restored.join(rel);
            std::fs::create_dir_all(dest.parent().unwrap()).unwrap();
            let mut bytes = Vec::new();
            f.read_to_end(&mut bytes).unwrap();
            std::fs::write(&dest, bytes).unwrap();
        }

        assert_eq!(
            sorted_tree(&restored),
            vec![
                ".obsidian/app.json",
                ".stignore",
                "40-Journal/2026-08-01.md",
                "_capture/entries/2026/08/2026-08-01_100000-pc.json",
                "brain/graph.json",
                "config.json",
            ]
        );
        for rel in sorted_tree(&restored) {
            assert_eq!(
                std::fs::read(restored.join(&rel)).unwrap(),
                std::fs::read(vault.join(&rel)).unwrap(),
                "content drift in {rel}"
            );
        }

        let mut after = crate::doctor::run_doctor(Some(&restored), false, false).unwrap();
        original.as_object_mut().unwrap().remove("chronicle_dir");
        after.as_object_mut().unwrap().remove("chronicle_dir");
        assert_eq!(after, original);
    }

    #[cfg(unix)]
    #[test]
    fn unreadable_directory_fails_loud_and_leaves_no_zip() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("v");
        let locked = vault.join("lockeddir");
        std::fs::create_dir_all(&locked).unwrap();
        std::fs::write(vault.join("config.json"), "{}").unwrap();
        std::fs::write(locked.join("a.md"), "a").unwrap();
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o000)).unwrap();
        if std::fs::read_dir(&locked).is_ok() {
            eprintln!("skipping: chmod 000 does not block directory reads for this user");
            std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o755)).unwrap();
            return;
        }
        let out = dir.path().join("out.zip");
        let res = run_backup(Some(&vault), Some(&out), false);
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o755)).unwrap();
        let err = res.expect_err("unreadable directory must fail the backup");
        assert!(err.to_string().contains("lockeddir"), "{err}");
        assert!(!out.exists());
    }

    /// Directory listable but not searchable (0o444): read_dir succeeds, but
    /// stat/open on each child fails with EACCES — must not be a silent skip.
    #[cfg(unix)]
    #[test]
    fn unsearchable_directory_fails_loud_and_leaves_no_zip() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("v");
        let locked = vault.join("nosearch");
        std::fs::create_dir_all(&locked).unwrap();
        std::fs::write(vault.join("config.json"), "{}").unwrap();
        let child = locked.join("hidden.md");
        std::fs::write(&child, "a").unwrap();
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o444)).unwrap();
        if std::fs::metadata(&child).is_ok() {
            eprintln!("skipping: 0o444 does not block stat for this user");
            std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o755)).unwrap();
            return;
        }
        let out = dir.path().join("out.zip");
        let res = run_backup(Some(&vault), Some(&out), false);
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o755)).unwrap();
        let err = res.expect_err("unstat-able file must fail the backup");
        assert!(err.to_string().contains("hidden.md"), "{err}");
        assert!(!out.exists());
    }

    #[cfg(unix)]
    #[test]
    fn unreadable_file_fails_loud_and_leaves_no_zip() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().join("v");
        std::fs::create_dir_all(vault.join("notes")).unwrap();
        std::fs::write(vault.join("config.json"), "{}").unwrap();
        let locked = vault.join("notes/locked.md");
        std::fs::write(&locked, "secret").unwrap();
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o000)).unwrap();
        if std::fs::File::open(&locked).is_ok() {
            // Running as root: chmod 000 does not block reads, so the
            // failure path cannot be provoked here.
            eprintln!("skipping: chmod 000 does not block reads for this user");
            std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o644)).unwrap();
            return;
        }

        let out = dir.path().join("out.zip");
        let res = run_backup(Some(&vault), Some(&out), false);
        std::fs::set_permissions(&locked, std::fs::Permissions::from_mode(0o644)).unwrap();
        let err = res.expect_err("unreadable file must fail the backup");
        assert!(
            err.to_string().contains("locked.md"),
            "error must name the unreadable path: {err}"
        );
        assert!(!out.exists(), "no partial zip may be left at the target");
        let leftovers: Vec<_> = std::fs::read_dir(dir.path())
            .unwrap()
            .flatten()
            .map(|e| e.file_name().to_string_lossy().to_string())
            .filter(|n| n != "v")
            .collect();
        assert!(leftovers.is_empty(), "temp archive must be removed: {leftovers:?}");
    }
}
