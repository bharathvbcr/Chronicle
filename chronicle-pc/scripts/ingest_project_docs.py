#!/usr/bin/env python3
"""Ingest tiered high-signal project docs into 10-Work/Projects/."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

# Allow imports from chronicle_pipeline when run as script.
_PIPELINE = Path(__file__).resolve().parents[1] / "pipeline"
if str(_PIPELINE) not in sys.path:
    sys.path.insert(0, str(_PIPELINE))

from chronicle_pipeline import curation as curation_mod
from chronicle_pipeline.migrate_kb import strip_existing_frontmatter, title_from_markdown
from chronicle_pipeline.paths import resolve_chronicle_dir

CONFIG_ENV = "CHRONICLE_PROJECTS_CONFIG"
DEFAULT_CONFIG_NAME = "projects.local.json"


def _config_path(explicit: Path | None = None) -> Path | None:
    """Resolve the project-inventory config, or None to fall back to discovery.

    An explicitly requested path (flag or env var) that does not exist is an
    error: silently falling back to a different inventory would ingest a
    different set of repos than the caller asked for.
    """
    for requested, label in (
        (explicit, "--projects-config"),
        (Path(os.environ[CONFIG_ENV]) if os.environ.get(CONFIG_ENV) else None, f"${CONFIG_ENV}"),
    ):
        if requested is None:
            continue
        resolved = requested.expanduser()
        if not resolved.is_file():
            raise FileNotFoundError(f"{label} points at a missing file: {resolved}")
        return resolved
    default = Path(__file__).resolve().parent / DEFAULT_CONFIG_NAME
    return default if default.is_file() else None


@dataclass(frozen=True)
class ProjectConfig:
    """Which repos to ingest and the per-repo tier rules, kept out of source."""

    repos: tuple[str, ...] = ()
    skip_repos: frozenset[str] = frozenset({"Chronicle"})
    doc_stem_aliases: dict[str, str] = field(default_factory=dict)
    tier_a_rules: dict[str, dict] = field(default_factory=dict)
    tier_c_repos: frozenset[str] = frozenset()

    @classmethod
    def load(cls, explicit: Path | None = None, code_root: Path | None = None) -> "ProjectConfig":
        path = _config_path(explicit)
        if path is None:
            # No config: discover every git repo under code_root. Nothing is
            # hardcoded here, so a private repo is never named in source.
            repos: tuple[str, ...] = ()
            if code_root and code_root.is_dir():
                repos = tuple(sorted(
                    d.name for d in code_root.iterdir()
                    if d.is_dir() and (d / ".git").exists() and d.name != "Chronicle"
                ))
            return cls(repos=repos)
        raw = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            repos=tuple(raw.get("repos") or ()),
            skip_repos=frozenset(raw.get("skip_repos") or {"Chronicle"}),
            doc_stem_aliases=dict(raw.get("doc_stem_aliases") or {}),
            tier_a_rules=dict(raw.get("tier_a_rules") or {}),
            tier_c_repos=frozenset(raw.get("tier_c_repos") or ()),
        )

    def active_repos(self) -> tuple[str, ...]:
        return tuple(r for r in self.repos if r not in self.skip_repos)

    def doc_to_repo(self) -> dict[str, str]:
        """Vault doc stem -> repo folder, derived from the inventory."""
        mapping = {f"{repo}_README": repo for repo in self.repos}
        # An alias names the vault's own stem for that repo, so it replaces the
        # derived default rather than sitting alongside it.
        for stem, repo in self.doc_stem_aliases.items():
            mapping.pop(f"{repo}_README", None)
            mapping[stem] = repo
        return mapping

    def tier_a_repo_match(self, repo: str, rel: Path) -> bool:
        rule = self.tier_a_rules.get(repo)
        if not rule:
            return False
        parts, name, posix = rel.parts, rel.name, rel.as_posix()
        if not name.endswith(".md"):
            return False
        if parts[:1] and parts[0] in (rule.get("subdir_any_md") or ()):
            return True
        if name in (rule.get("basenames") or ()):
            return True
        if posix in (rule.get("paths") or ()):
            return True
        for subdir, needle in (rule.get("subdir_name_contains") or {}).items():
            if parts[:1] == (subdir,) and needle in name.lower():
                return True
        return False


# Populated in main(); module-level default keeps import-time use working.
CONFIG = ProjectConfig.load()

TIER_A_ROOT_NAMES: frozenset[str] = frozenset(
    {
        "AGENTS.md",
        "CODEBASE_MAP.md",
        "ARCHITECTURE.md",
        "METHODS.md",
        "SUBMISSION.md",
        "PORTFOLIO_HIGHLIGHTS.md",
        "APP_DESCRIPTION.md",
        "ROADMAP.md",
        "product-contract.md",
        "MASTER_INDEX.md",
        "WIRING-MAP.md",
    }
)

TIER_A_GLOB_PREFIXES: tuple[str, ...] = ("GITNEXUS_",)

EXCLUDED_DIR_NAMES: frozenset[str] = frozenset(
    {
        ".git",
        "node_modules",
        ".venv",
        "venv",
        "vendor",
        "gomodcache",
        "target",
        "dist",
        "build",
        "out",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".tox",
        ".cache",
        "cache",
        "tmp",
        "temp",
        ".stfolder",
    }
)

LOCALIZED_README_RE = re.compile(
    r"^README\.(ja|zh-CN|ko)\.md$", re.IGNORECASE
)

NOISE_BASENAME_RE = re.compile(
    r"(signing|privacy|contributing|changelog|third_party|release_checklist|"
    r"play_store|linkedin|blog)",
    re.IGNORECASE,
)

MAX_FILE_BYTES = 400 * 1024

Tier = Literal["A", "B", "C"]

_FRONTMATTER_RE = re.compile(r"^---\s*\n.*?\n---\s*\n?", re.DOTALL)
_GITHUB_RE = re.compile(r"https?://github\.com/[^\s)>\"]+")


@dataclass
class IngestCandidate:
    repo: str
    src: Path
    rel: Path
    tier: Tier
    dest_name: str
    skip_copy: bool = False
    vault_peer: str | None = None
    reason: str = ""


@dataclass
class ProjectStats:
    copied: int = 0
    skipped_dedup: int = 0
    skipped_unchanged: int = 0
    bytes_written: int = 0
    hub_updated: bool = False
    files: list[str] = field(default_factory=list)


def _yaml_scalar(value: str) -> str:
    if not value:
        return '""'
    if re.fullmatch(r"[A-Za-z0-9_./:-]+", value):
        return value
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def body_hash(text: str) -> str:
    body = strip_existing_frontmatter(text).strip()
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def rename_dest_basename(name: str) -> str:
    upper = name.upper()
    if upper == "README.MD":
        return "Overview.md"
    if upper == "README_MINIMAL.MD":
        return "Overview-Minimal.md"
    return name


def is_readme_basename(name: str) -> bool:
    lower = name.lower()
    return lower.startswith("readme") and lower.endswith(".md")


def vault_readme_peer(repo: str, rel: Path) -> str:
    """Map repo-relative README path to vault *_README.md basename."""
    parent_parts = rel.parent.parts
    if not parent_parts or parent_parts == (".",):
        if rel.name.upper() == "README.MD":
            return f"{repo}_README.md"
        return f"{repo}_{rel.name}"
    joined = "_".join(parent_parts)
    return f"{repo}_{joined}_{rel.name}"


def load_vault_readme_index(vault: Path) -> dict[str, tuple[str, str]]:
    """basename -> (vault_rel, body_hash)."""
    index: dict[str, tuple[str, str]] = {}
    for area in ("30-Knowledge", "10-Work"):
        base = vault / area
        if not base.is_dir():
            continue
        for path in base.rglob("*_README.md"):
            if "90-Archive" in path.parts:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except OSError:
                continue
            rel = path.relative_to(vault).as_posix()
            index[path.name] = (rel, body_hash(text))
    return index


def parse_overview_meta(vault: Path, vault_rel: str | None) -> dict[str, str]:
    if not vault_rel:
        return {}
    path = vault / vault_rel
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8")
    body = strip_existing_frontmatter(text)
    github = ""
    for match in _GITHUB_RE.finditer(text + "\n" + body):
        github = match.group(0).rstrip(").,")
        break
    summary = ""
    for line in body.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        summary = s
        break
    return {"summary": summary, "github": github}


def excluded_dir(name: str, *, skip_git: bool) -> bool:
    if name in EXCLUDED_DIR_NAMES:
        return True
    if skip_git and name == ".git":
        return True
    if name.startswith(".") and name not in {".github"}:
        return True
    return False


def is_noise_basename(name: str) -> bool:
    if LOCALIZED_README_RE.match(name):
        return True
    return bool(NOISE_BASENAME_RE.search(name))


def nested_agent_chrome(rel: Path) -> bool:
    if rel.name != "AGENTS.md":
        return False
    parts = set(rel.parts)
    return bool(parts & {"apps", "src", "crates"})


def tier_a_match(repo: str, rel: Path) -> bool:
    name = rel.name
    if name in TIER_A_ROOT_NAMES:
        return True
    for prefix in TIER_A_GLOB_PREFIXES:
        if name.startswith(prefix) and name.endswith(".md"):
            return True

    parts = rel.parts
    if CONFIG.tier_a_repo_match(repo, rel):
        return True

    if parts[:1] == ("learning-notes",):
        return True
    if parts[:1] == ("semantic_layer",):
        return True
    if parts[:1] == ("semantic",):
        return True

    if parts[:2] == ("docs", "architecture.md") or (
        len(parts) >= 2 and parts[0] == "docs" and "architecture" in parts[1].lower()
    ):
        return True
    if len(parts) >= 2 and parts[0] == "docs":
        low = rel.name.lower()
        if low in {"product-contract.md", "code-graph.md", "corpus.md"}:
            return True
        if low.startswith("semantic"):
            return True

    if rel.as_posix().startswith("Rust_MLKit/docs/") and name.endswith(".md"):
        return True

    if rel.as_posix().startswith("wisdev-arc/docs/") and name.endswith(".md"):
        if is_noise_basename(name):
            return False
        return True

    return False


def tier_c_match(repo: str, rel: Path) -> bool:
    if repo not in CONFIG.tier_c_repos:
        return False
    parts = rel.parts
    if not parts or parts[0] != "docs":
        return False
    blocked_prefixes = (
        "docs/archive",
        "docs/business",
        "docs/ops",
        "docs/legal",
        "docs/migration",
        "docs/superpowers",
    )
    rel_posix = rel.as_posix()
    for blocked in blocked_prefixes:
        if rel_posix.startswith(blocked):
            return False
    if "/plans/" in rel_posix:
        return False
    if rel.name in {"MASTER_INDEX.md", "WIRING-MAP.md"}:
        return True
    if parts[:2] == ("docs", "dev"):
        return True
    if parts[:2] == ("docs", "user"):
        return True
    return False


def tier_b_match(rel: Path) -> bool:
    return is_readme_basename(rel.name)


def classify_file(repo: str, rel: Path) -> Tier | None:
    if nested_agent_chrome(rel):
        return None
    if is_noise_basename(rel.name):
        return None
    if tier_a_match(repo, rel):
        return "A"
    if tier_b_match(rel):
        return "B"
    if tier_c_match(repo, rel):
        return "C"
    return None


def iter_repo_markdown(repo_root: Path, *, skip_git: bool) -> list[Path]:
    found: list[Path] = []
    for path in repo_root.rglob("*.md"):
        rel = path.relative_to(repo_root)
        skip = False
        for part in rel.parts[:-1]:
            if excluded_dir(part, skip_git=skip_git):
                skip = True
                break
        if skip:
            continue
        if excluded_dir(rel.parts[0], skip_git=skip_git) if rel.parts else False:
            continue
        found.append(path)
    return found


def build_candidates(
    code_root: Path,
    vault: Path,
    *,
    skip_git: bool,
) -> tuple[list[IngestCandidate], dict[str, tuple[str, str]]]:
    vault_index = load_vault_readme_index(vault)
    candidates: list[IngestCandidate] = []

    for repo in CONFIG.active_repos():
        repo_root = code_root / repo
        if not repo_root.is_dir():
            continue
        for src in iter_repo_markdown(repo_root, skip_git=skip_git):
            rel = src.relative_to(repo_root)
            tier = classify_file(repo, rel)
            if tier is None:
                continue
            try:
                if src.stat().st_size > MAX_FILE_BYTES:
                    continue
            except OSError:
                continue

            dest_name = rename_dest_basename(rel.name)
            dest_rel = rel.with_name(dest_name)
            cand = IngestCandidate(
                repo=repo,
                src=src,
                rel=rel,
                tier=tier,
                dest_name=dest_name,
            )

            if dest_name == "README.md":
                cand.skip_copy = True
                cand.reason = "chrome basename blocked"
                candidates.append(cand)
                continue

            if tier == "B" and is_readme_basename(rel.name):
                peer_name = vault_readme_peer(repo, rel)
                peer = vault_index.get(peer_name)
                if peer:
                    vault_rel, vhash = peer
                    try:
                        src_hash = body_hash(src.read_text(encoding="utf-8"))
                    except OSError:
                        src_hash = ""
                    if src_hash and src_hash == vhash:
                        cand.skip_copy = True
                        cand.vault_peer = vault_rel
                        cand.reason = "dedup vault overview"
                    else:
                        cand.vault_peer = vault_rel

            candidates.append(cand)

    return candidates, vault_index


def build_frontmatter(
    *,
    title: str,
    repo: str,
    source: str,
    local_path: str,
    tier: Tier,
) -> str:
    slug = repo.lower().replace("_", "-")
    lines = [
        "---",
        f"title: {_yaml_scalar(title)}",
        "type: project",
        "status: active",
        f"source: {_yaml_scalar(source)}",
        f"local_path: {_yaml_scalar(local_path)}",
        f"tags: [project-docs, {slug}]",
        f"ingest_tier: {tier}",
        "---",
        "",
    ]
    return "\n".join(lines)


def dest_vault_rel(repo: str, rel: Path, dest_name: str) -> str:
    dest = rel.with_name(dest_name)
    return f"10-Work/Projects/{repo}/{dest.as_posix()}"


def wikilink_stem(vault_rel: str) -> str:
    return vault_rel[:-3] if vault_rel.endswith(".md") else vault_rel


def write_doc(
    vault: Path,
    cand: IngestCandidate,
    *,
    code_root: Path,
    force: bool,
    dry_run: bool,
) -> tuple[bool, int]:
    if cand.skip_copy:
        return False, 0

    dest_rel = dest_vault_rel(cand.repo, cand.rel, cand.dest_name)
    dest_path = vault / dest_rel
    source_label = f"Code/{cand.repo}/{cand.rel.as_posix()}"
    local_path = str((code_root / cand.repo).resolve())

    try:
        raw = cand.src.read_text(encoding="utf-8")
    except OSError:
        return False, 0

    body = strip_existing_frontmatter(raw).lstrip("\n")
    title = title_from_markdown(body, cand.dest_name.removesuffix(".md"))
    content_hash = body_hash(raw)
    new_body = build_frontmatter(
        title=title,
        repo=cand.repo,
        source=source_label,
        local_path=local_path,
        tier=cand.tier,
    ) + body

    if dest_path.is_file() and not force:
        existing = dest_path.read_text(encoding="utf-8")
        if body_hash(existing) == content_hash and existing == new_body:
            return False, 0

    if dry_run:
        return True, len(new_body.encode("utf-8"))

    dest_path.parent.mkdir(parents=True, exist_ok=True)
    dest_path.write_text(new_body, encoding="utf-8")
    return True, len(new_body.encode("utf-8"))


def overview_vault_rel(repo: str, vault_index: dict[str, tuple[str, str]]) -> str | None:
    peer = vault_index.get(f"{repo}_README.md")
    if peer:
        return peer[0]
    for name, (rel, _) in vault_index.items():
        if name.startswith(f"{repo}_") and name.endswith("_README.md"):
            if name.count("_") == 1:  # repo_README only
                return rel
    return None


def build_hub_content(
    repo: str,
    ingested: list[IngestCandidate],
    *,
    code_root: Path,
    vault: Path,
    vault_index: dict[str, tuple[str, str]],
) -> str:
    overview_rel = overview_vault_rel(repo, vault_index)
    for cand in ingested:
        if cand.vault_peer and cand.tier == "B":
            overview_rel = cand.vault_peer
            break

    meta = parse_overview_meta(vault, overview_rel)
    local_path = str((code_root / repo).resolve())
    title = repo

    lines = [
        "---",
        f"title: {_yaml_scalar(f'{repo} — Project Hub')}",
        "type: project",
        "status: active",
        f"local_path: {_yaml_scalar(local_path)}",
        f"tags: [project-docs, {repo.lower()}]",
        "---",
        "",
        f"# {repo}",
        "",
    ]
    if meta.get("summary"):
        lines.extend([meta["summary"], ""])
    lines.append(f"**Local path:** `{local_path}`")
    if meta.get("github"):
        lines.append(f"**GitHub:** {meta['github']}")
    lines.append("")
    if overview_rel:
        lines.append(f"**Overview:** [[{wikilink_stem(overview_rel)}]]")
        lines.append("")

    copied = [
        c
        for c in ingested
        if not c.skip_copy or (c.skip_copy and c.reason == "dedup vault overview")
    ]
    if copied:
        lines.append("## Ingested docs")
        lines.append("")
        for cand in sorted(copied, key=lambda c: c.rel.as_posix()):
            if cand.skip_copy and cand.reason == "dedup vault overview":
                if cand.vault_peer:
                    label = cand.rel.as_posix()
                    lines.append(f"- {label} → [[{wikilink_stem(cand.vault_peer)}]] (vault overview)")
                continue
            dest_rel = dest_vault_rel(cand.repo, cand.rel, cand.dest_name)
            label = cand.rel.as_posix()
            if cand.dest_name != cand.rel.name:
                label = f"{label} → {cand.dest_name}"
            lines.append(f"- [[{wikilink_stem(dest_rel)}|{label}]]")
        lines.append("")

    return "\n".join(lines)


def update_moc_work(vault: Path, repos: list[str], *, dry_run: bool) -> None:
    moc_path = vault / "10-Work" / "MOC-Work.md"
    base = """# Work MOC

Projects, resume points, career notes.

- Resume points: `10-Work/ResumePoints/`
- [[10-Work/Projects|Project docs hubs]] — architecture maps and depth notes from code repos.

## Project hubs

"""
    links = []
    for repo in sorted(repos):
        hub = f"10-Work/Projects/{repo}/_hub"
        links.append(f"- [[{hub}|{repo}]]")
    content = base + "\n".join(links) + "\n"

    if dry_run:
        print(f"[dry-run] would update {moc_path}")
        return
    moc_path.parent.mkdir(parents=True, exist_ok=True)
    moc_path.write_text(content, encoding="utf-8")


# Vault overview doc stem -> repo folder name, derived from the project config.


def emit_hub_set_doc_ops(
    vault: Path,
    *,
    dry_run: bool = False,
    device: str = "pc",
) -> list[dict[str, object]]:
    graph_path = vault / "brain" / "graph.json"
    if not graph_path.is_file():
        return []
    graph = json.loads(graph_path.read_text(encoding="utf-8"))

    existing: set[str] = set()
    for op in curation_mod.read_ops(vault):
        if op.get("op") == "set_doc" and op.get("node"):
            existing.add(str(op["node"]))
    for node in graph.get("nodes") or []:
        if isinstance(node, dict) and node.get("id") and node.get("doc"):
            existing.add(str(node["id"]))

    when = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    ops: list[dict[str, object]] = []

    for node in graph.get("nodes") or []:
        if not isinstance(node, dict):
            continue
        if node.get("kind") != "project":
            continue
        nid = str(node.get("id") or "")
        doc = str(node.get("doc") or "")
        if not nid or not doc:
            continue
        stem = Path(doc).stem
        repo = CONFIG.doc_to_repo().get(stem)
        if not repo:
            continue
        hub_doc = f"10-Work/Projects/{repo}/_hub.md"
        if not (vault / hub_doc).is_file() and not dry_run:
            continue
        if nid in existing and str(node.get("doc")) == hub_doc:
            continue
        op = {
            "op": "set_doc",
            "ts": when,
            "device": device,
            "node": nid,
            "doc": hub_doc,
        }
        ops.append(op)
        existing.add(nid)

    if dry_run:
        print(f"[dry-run] would append {len(ops)} set_doc hub ops")
        for op in ops:
            print(f"  {op['node']} -> {op['doc']}")
    else:
        for op in ops:
            curation_mod.append_op(vault, op, device=device)
        if ops:
            print(f"Appended {len(ops)} set_doc hub ops")

    return ops


def run_ingest(
    *,
    code_root: Path,
    vault: Path,
    dry_run: bool,
    apply: bool,
    force: bool,
    skip_git: bool,
) -> dict[str, ProjectStats]:
    if not apply and not dry_run:
        print("Specify --dry-run or --apply")
        sys.exit(1)

    candidates, vault_index = build_candidates(code_root, vault, skip_git=skip_git)
    by_repo: dict[str, list[IngestCandidate]] = defaultdict(list)
    for cand in candidates:
        by_repo[cand.repo].append(cand)

    stats: dict[str, ProjectStats] = {}
    total_copy = 0
    total_dedup = 0
    total_bytes = 0

    for repo in sorted(by_repo):
        ps = ProjectStats()
        repo_cands = by_repo[repo]
        for cand in repo_cands:
            if cand.skip_copy:
                if cand.reason == "dedup vault overview":
                    ps.skipped_dedup += 1
                continue
            written, nbytes = write_doc(
                vault, cand, code_root=code_root, force=force, dry_run=dry_run
            )
            if written:
                ps.copied += 1
                ps.bytes_written += nbytes
                ps.files.append(dest_vault_rel(cand.repo, cand.rel, cand.dest_name))
            else:
                if (vault / dest_vault_rel(cand.repo, cand.rel, cand.dest_name)).is_file():
                    ps.skipped_unchanged += 1

        hub_content = build_hub_content(
            repo, repo_cands, code_root=code_root, vault=vault, vault_index=vault_index
        )
        hub_path = vault / f"10-Work/Projects/{repo}/_hub.md"
        if dry_run:
            print(f"[dry-run] would write hub {hub_path}")
            ps.hub_updated = True
        elif apply:
            hub_path.parent.mkdir(parents=True, exist_ok=True)
            hub_path.write_text(hub_content, encoding="utf-8")
            ps.hub_updated = True

        stats[repo] = ps
        total_copy += ps.copied
        total_dedup += ps.skipped_dedup
        total_bytes += ps.bytes_written

        print(
            f"{repo}: copy={ps.copied} dedup={ps.skipped_dedup} "
            f"unchanged={ps.skipped_unchanged} bytes={ps.bytes_written}"
        )

    actionable = sum(1 for c in candidates if not c.skip_copy)
    print(
        f"\nTOTAL: candidates={len(candidates)} to_copy={actionable} "
        f"copied={total_copy} dedup={total_dedup} bytes={total_bytes}"
    )

    readme_chrome = [c for c in candidates if c.dest_name == "README.md"]
    if readme_chrome:
        print(f"WARNING: {len(readme_chrome)} chrome README.md candidates (blocked)")

    if apply or dry_run:
        update_moc_work(vault, sorted(by_repo), dry_run=dry_run)

    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest project docs into Chronicle vault.")
    parser.add_argument(
        "--code-root",
        type=Path,
        default=Path.home() / "Code",
        help="Root directory containing project repos",
    )
    parser.add_argument(
        "--vault",
        type=Path,
        default=None,
        help="Chronicle vault (default: CHRONICLE_DIR or ~/Chronicle)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print plan only")
    parser.add_argument("--apply", action="store_true", help="Write files to vault")
    parser.add_argument("--force", action="store_true", help="Refresh existing bodies")
    parser.add_argument("--skip-git", action="store_true", help="Skip .git directories")
    parser.add_argument(
        "--projects-config",
        type=Path,
        default=None,
        help=(
            f"Project inventory JSON (default: ${CONFIG_ENV} or {DEFAULT_CONFIG_NAME} "
            "beside this script; without one, every git repo under --code-root is used)"
        ),
    )
    parser.add_argument(
        "--wire-brain",
        action="store_true",
        help="Append set_doc ops pointing project nodes to _hub.md",
    )
    args = parser.parse_args()

    vault = resolve_chronicle_dir(args.vault)
    code_root = args.code_root.expanduser().resolve()

    global CONFIG
    CONFIG = ProjectConfig.load(args.projects_config, code_root)
    if not CONFIG.active_repos():
        print(
            "No projects resolved. Copy projects.example.json to projects.local.json "
            f"(or set ${CONFIG_ENV}), or point --code-root at a directory of git repos.",
            file=sys.stderr,
        )
        raise SystemExit(2)

    if args.wire_brain:
        emit_hub_set_doc_ops(vault, dry_run=args.dry_run and not args.apply)
        return

    run_ingest(
        code_root=code_root,
        vault=vault,
        dry_run=args.dry_run,
        apply=args.apply,
        force=args.force,
        skip_git=args.skip_git,
    )


if __name__ == "__main__":
    main()
