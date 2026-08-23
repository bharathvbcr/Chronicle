"""Tests for the externalised project inventory in ingest_project_docs.py.

The point of the config is that no repository name -- least of all a private
one -- is hardcoded in tracked source. These fail against the pre-config
version, which listed every repo (including private ones) in the module body.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import ingest_project_docs as M  # noqa: E402

SOURCE = (SCRIPTS / "ingest_project_docs.py").read_text(encoding="utf-8")
LOCAL_CONFIG = SCRIPTS / "projects.local.json"


def test_example_config_ships_and_names_no_real_repo():
    example = SCRIPTS / "projects.example.json"
    assert example.is_file(), "projects.example.json must be committed as the template"
    raw = json.loads(example.read_text(encoding="utf-8"))
    assert raw["repos"], "example must show the expected shape"
    for name in raw["repos"]:
        assert name.lower().startswith("example"), f"example config leaks a real repo name: {name}"


def test_source_bakes_in_no_inventory_at_all(monkeypatch):
    """Config-independent form of the guard below, so CI still covers it.

    projects.local.json is gitignored, so the name check below skips on a fresh
    checkout -- exactly where a regression would land. Two vectors are covered:
    the dataclass field defaults, and the no-config load path. Checking only the
    latter is not enough; its discovery branch builds ProjectConfig(repos=...)
    explicitly and so hides a hardcoded default entirely.
    """
    bare = M.ProjectConfig()
    assert bare.repos == (), f"ProjectConfig defaults hardcode repos: {bare.repos}"
    assert bare.tier_c_repos == frozenset(), f"defaults hardcode tier-C: {bare.tier_c_repos}"
    assert bare.tier_a_rules == {}, f"defaults hardcode rules: {sorted(bare.tier_a_rules)}"

    monkeypatch.setattr(M, "_config_path", lambda explicit=None: None)
    loaded = M.ProjectConfig.load(code_root=None)
    assert loaded.repos == (), f"no-config load yields repos: {loaded.repos}"
    assert loaded.tier_c_repos == frozenset()
    assert loaded.tier_a_rules == {}


@pytest.mark.skipif(not LOCAL_CONFIG.is_file(), reason="no local inventory on this machine")
def test_no_configured_repo_name_appears_in_source():
    """The regression guard: source must not enumerate the repos it ingests."""
    configured = json.loads(LOCAL_CONFIG.read_text(encoding="utf-8"))
    names = set(configured.get("repos") or [])
    names |= set(configured.get("tier_c_repos") or [])
    names |= set(configured.get("tier_a_rules") or {})
    leaked = sorted(n for n in names if f'"{n}"' in SOURCE)
    assert not leaked, f"repo names hardcoded in tracked source: {leaked}"


@pytest.mark.skipif(not LOCAL_CONFIG.is_file(), reason="no local inventory on this machine")
def test_local_config_is_gitignored():
    repo_root = SCRIPTS.parents[1]
    proc = subprocess.run(
        ["git", "-C", str(repo_root), "check-ignore", str(LOCAL_CONFIG)],
        capture_output=True, text=True, check=False,
    )
    assert proc.returncode == 0, "projects.local.json must be gitignored -- it may name private repos"


def _cfg(**kw) -> M.ProjectConfig:
    base = dict(repos=("Alpha", "beta-tool"), skip_repos=frozenset({"Chronicle"}))
    base.update(kw)
    return M.ProjectConfig(**base)


def test_skip_repos_filtered_from_active():
    cfg = _cfg(repos=("Alpha", "Chronicle", "beta-tool"))
    assert cfg.active_repos() == ("Alpha", "beta-tool")


def test_doc_to_repo_derives_default_stems():
    assert _cfg().doc_to_repo() == {"Alpha_README": "Alpha", "beta-tool_README": "beta-tool"}


def test_alias_replaces_the_derived_stem_rather_than_adding_to_it():
    cfg = _cfg(doc_stem_aliases={"Beta_README": "beta-tool"})
    mapping = cfg.doc_to_repo()
    assert mapping["Beta_README"] == "beta-tool"
    assert "beta-tool_README" not in mapping, "alias must replace, not sit alongside"


@pytest.mark.parametrize(
    "rule, rel, expected",
    [
        ({"subdir_any_md": ["Docs"]}, "Docs/deep/plan.md", True),
        ({"subdir_any_md": ["Docs"]}, "other/plan.md", False),
        ({"basenames": ["METHODS.md"]}, "METHODS.md", True),
        ({"basenames": ["METHODS.md"]}, "METHODS.txt", False),
        ({"paths": ["docs/DESIGN.md"]}, "docs/DESIGN.md", True),
        ({"paths": ["docs/DESIGN.md"]}, "docs/OTHER.md", False),
        ({"subdir_name_contains": {"docs": "architecture"}}, "docs/architecture-v2.md", True),
        ({"subdir_name_contains": {"docs": "architecture"}}, "docs/notes.md", False),
        ({"subdir_name_contains": {"docs": "architecture"}}, "src/architecture.md", False),
    ],
)
def test_tier_a_repo_rule_kinds(rule, rel, expected):
    cfg = _cfg(tier_a_rules={"Alpha": rule})
    assert cfg.tier_a_repo_match("Alpha", Path(rel)) is expected


def test_rules_do_not_leak_across_repos():
    cfg = _cfg(tier_a_rules={"Alpha": {"basenames": ["METHODS.md"]}})
    assert cfg.tier_a_repo_match("Alpha", Path("METHODS.md")) is True
    assert cfg.tier_a_repo_match("beta-tool", Path("METHODS.md")) is False


def test_discovery_fallback_finds_git_repos_and_skips_chronicle(tmp_path, monkeypatch):
    for name in ("repo_a", "repo_b", "Chronicle"):
        (tmp_path / name / ".git").mkdir(parents=True)
    (tmp_path / "not_a_repo").mkdir()
    monkeypatch.setattr(M, "_config_path", lambda explicit=None: None)
    cfg = M.ProjectConfig.load(code_root=tmp_path)
    assert cfg.repos == ("repo_a", "repo_b"), "discovery must skip non-repos and Chronicle"


def test_missing_explicit_config_is_an_error_not_a_silent_fallback(tmp_path):
    """A requested inventory that is absent must fail, never fall back to another."""
    with pytest.raises(FileNotFoundError):
        M.ProjectConfig.load(explicit=tmp_path / "missing.json", code_root=tmp_path)


def test_missing_env_config_is_an_error(tmp_path, monkeypatch):
    monkeypatch.setenv(M.CONFIG_ENV, str(tmp_path / "nope.json"))
    with pytest.raises(FileNotFoundError):
        M.ProjectConfig.load(code_root=tmp_path)


def test_explicit_config_is_loaded(tmp_path):
    path = tmp_path / "projects.json"
    path.write_text(json.dumps({
        "repos": ["Solo"], "tier_c_repos": ["Solo"],
        "tier_a_rules": {"Solo": {"basenames": ["X.md"]}},
    }), encoding="utf-8")
    cfg = M.ProjectConfig.load(explicit=path)
    assert cfg.repos == ("Solo",)
    assert "Solo" in cfg.tier_c_repos
    assert cfg.tier_a_repo_match("Solo", Path("X.md")) is True
