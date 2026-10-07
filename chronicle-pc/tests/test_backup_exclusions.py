"""Python backup oracle applies the shared contract/backup-exclusions.json."""

from __future__ import annotations

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
