"""Python backup oracle applies the shared contract/backup-exclusions.json."""

from __future__ import annotations

import os
import zipfile
from pathlib import Path

import pytest

from chronicle_pipeline import backup
from chronicle_pipeline.backup import load_exclusions, run_backup

PC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = PC_ROOT.parent


def test_oracle_reads_the_shared_contract_file() -> None:
    assert backup.EXCLUSIONS_PATH == PC_ROOT / "contract" / "backup-exclusions.json"
    assert backup.EXCLUSIONS_PATH.read_bytes() == (
        REPO_ROOT / "chronicle-android" / "contract" / "backup-exclusions.json"
    ).read_bytes()


def test_backup_applies_shared_rules(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    files = {
        "config.json": "{}",
        ".stignore": "index\n",
        ".obsidian/app.json": "{}",
        "index/chronicle.sqlite": "rebuildable",
        "notes/index/deep.md": "under an excluded dir component",
        "notes/index.md": "kept",
        "40-Journal/index": "a file named like an excluded dir is kept",
        ".stfolder/marker": "",
        "_system/scratch.tmp": "junk",
        ".DS_Store": "junk",
        "40-Journal/2026-08-01.md": "# day",
    }
    for rel, body in files.items():
        p = vault / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")

    out = tmp_path / "bak.zip"
    res = run_backup(vault, path=out)
    with zipfile.ZipFile(out) as zf:
        names = sorted(zf.namelist())
    assert names == [
        "Chronicle/.obsidian/app.json",
        "Chronicle/.stignore",
        "Chronicle/40-Journal/2026-08-01.md",
        "Chronicle/40-Journal/index",
        "Chronicle/config.json",
        "Chronicle/notes/index.md",
    ]
    assert res["files"] == len(names)


def test_dir_names_match_directory_components_only() -> None:
    ex = load_exclusions()
    assert ex.skips(Path("index/db.sqlite"))
    assert ex.skips(Path("a/__pycache__/m.pyc"))
    assert not ex.skips(Path("index"))
    assert not ex.skips(Path(".stignore"))
    assert ex.skips(Path("x/y.tmp"))
    assert ex.skips(Path("x/.DS_Store"))


@pytest.mark.parametrize(
    "body",
    ["{not json", '{"dir_names": ["index"]}', '{"dir_names": "index", "file_names": [], "file_suffixes": []}'],
)
def test_malformed_exclusions_fail_loud(tmp_path: Path, body: str) -> None:
    bad = tmp_path / "backup-exclusions.json"
    bad.write_text(body, encoding="utf-8")
    with pytest.raises(RuntimeError, match="backup exclusions"):
        load_exclusions(bad)


def test_missing_exclusions_fail_loud(tmp_path: Path) -> None:
    with pytest.raises(RuntimeError, match="cannot load backup exclusions"):
        load_exclusions(tmp_path / "absent.json")


def _partials(directory: Path) -> list[Path]:
    return [p for p in directory.iterdir() if p.name.endswith(".partial")]


def test_crash_before_rename_keeps_existing_zip(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    (vault / "note.md").write_text("hello", encoding="utf-8")
    out = tmp_path / "bak.zip"
    original = b"ORIGINAL-ZIP-BYTES-NOT-A-ZIP"
    out.write_bytes(original)

    def boom(src: object, dst: object) -> None:
        raise OSError("commit failed")

    # Publish calls os.replace at commit time (not a copy taken at import).
    monkeypatch.setattr(os, "replace", boom)
    with pytest.raises(OSError, match="commit failed"):
        run_backup(vault, path=out, force=True)
    assert out.read_bytes() == original
    assert _partials(out.parent) == []


def test_backup_omits_symlink_targets(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    marker = b"SECRET-MARKER-BYTES"
    real = vault / "real.txt"
    real.write_bytes(marker)
    (vault / "alias.txt").symlink_to(real)

    outside = tmp_path / "outside-secret.txt"
    outside.write_bytes(b"OUTSIDE-SECRET-MARKER")
    (vault / "outside.txt").symlink_to(outside)

    ext = tmp_path / "outside-dir"
    ext.mkdir()
    (ext / "hidden.txt").write_bytes(b"DIR-SECRET-MARKER")
    (vault / "linked-dir").symlink_to(ext, target_is_directory=True)

    out = tmp_path / "bak.zip"
    run_backup(vault, path=out)
    with zipfile.ZipFile(out) as zf:
        names = zf.namelist()
        assert "Chronicle/alias.txt" not in names
        assert "Chronicle/outside.txt" not in names
        assert not any(name == "Chronicle/linked-dir" or name.startswith("Chronicle/linked-dir/") for name in names)
        assert zf.read("Chronicle/real.txt") == marker
        other = b"".join(zf.read(name) for name in names if name != "Chronicle/real.txt")
    assert b"SECRET-MARKER" not in other
    assert b"OUTSIDE-SECRET-MARKER" not in other
    assert b"DIR-SECRET-MARKER" not in other


def test_force_replaces_existing_zip(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    (vault / "a.md").write_text("v1", encoding="utf-8")
    out = tmp_path / "bak.zip"
    out.write_bytes(b"stale-not-a-zip")
    res = run_backup(vault, path=out, force=True)
    with zipfile.ZipFile(out) as zf:
        assert zf.namelist() == ["Chronicle/a.md"]
        assert zf.read("Chronicle/a.md") == b"v1"
    assert res["files"] == 1
    assert res["path"] == str(out.resolve())
    assert _partials(out.parent) == []


def test_without_force_existing_output_is_unmodified(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    (vault / "a.md").write_text("v1", encoding="utf-8")
    out = tmp_path / "bak.zip"
    original = b"keep-me"
    out.write_bytes(original)
    with pytest.raises(FileExistsError, match="backup target exists"):
        run_backup(vault, path=out)
    assert out.read_bytes() == original
    assert _partials(out.parent) == []


def test_backup_skips_output_inside_the_vault(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    vault.mkdir()
    (vault / "note.txt").write_text("n", encoding="utf-8")
    out = vault / "self.zip"
    run_backup(vault, path=out)
    with zipfile.ZipFile(out) as zf:
        names = zf.namelist()
    assert names == ["Chronicle/note.txt"]
    assert not any(name.endswith(".partial") or name.endswith("self.zip") for name in names)


def test_unreadable_directory_leaves_previous_zip(tmp_path: Path) -> None:
    vault = tmp_path / "vault"
    blocked = vault / "blocked"
    blocked.mkdir(parents=True)
    (blocked / "secret.txt").write_text("hidden", encoding="utf-8")
    (vault / "ok.txt").write_text("visible", encoding="utf-8")
    out = tmp_path / "bak.zip"
    original = b"PREVIOUS-ZIP"
    out.write_bytes(original)
    os.chmod(blocked, 0)
    try:
        try:
            next(os.scandir(blocked))
        except OSError:
            pass
        else:
            pytest.skip("this OS still lists a mode-000 directory owned by the caller")
        with pytest.raises(OSError):
            run_backup(vault, path=out, force=True)
        assert out.read_bytes() == original
        assert _partials(out.parent) == []
    finally:
        os.chmod(blocked, 0o755)
