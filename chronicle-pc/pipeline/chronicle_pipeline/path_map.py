"""PARA knowledge path map (v1.10 — dual-read cutover complete).

Knowledge candidates are PARA areas only. Legacy ``kb/notes/`` is retired;
``is_legacy_kb_path`` remains for cutover/410 detection. Journal paths
(``entries/``, ``img/``, ``audio/``, ``notes/``) are unchanged.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

# Preferred knowledge layout (PARA). Numbers keep sort order in file browsers.
PARA_AREAS: tuple[str, ...] = (
    "00-Inbox",
    "10-Work",
    "20-Personal",
    "30-Knowledge",
    "90-Archive",
)

LEGACY_KB_NOTES = "kb/notes"

# UI "Notes" sections (v1.10): Knowledge Base = 30-Knowledge only;
# Notes = all other PARA areas. Journal is handled separately.
KB_AREA = "30-Knowledge"
NOTES_AREAS: tuple[str, ...] = tuple(a for a in PARA_AREAS if a != KB_AREA)
SECTION_KB = "kb"
SECTION_NOTES = "notes"
VALID_SECTIONS: frozenset[str] = frozenset({SECTION_KB, SECTION_NOTES})

# Hide from app knowledge trees (keep MOCs). Matches Android KnowledgePathMap.
CHROME_BASENAMES: frozenset[str] = frozenset({"CLAUDE.md", ".gitkeep", "README.md"})

# Machine / regenerable dirs — Obsidian should exclude these when opening vault root.
MACHINE_EXCLUDE_DIRS: tuple[str, ...] = (
    "index",
    "brain",
    "_capture",
    "_attachments",
    "entries",
    "img",
    "audio",
    ".stfolder",
    "_staging",
)

_SAFE_REL = re.compile(r"^[A-Za-z0-9._\- /]+$")
_FORBIDDEN = ("..", "\0")


def is_para_prefix(rel: str) -> bool:
    """True if vault-relative path is under a PARA area (or is the area itself)."""
    p = _norm(rel)
    for area in PARA_AREAS:
        if p == area or p.startswith(area + "/"):
            return True
    return False


def is_legacy_kb_path(rel: str) -> bool:
    """True for retired kb/notes paths (cutover/410 detection only)."""
    p = _norm(rel)
    return p == LEGACY_KB_NOTES or p.startswith(LEGACY_KB_NOTES + "/")


def is_knowledge_path(rel: str) -> bool:
    """True if path is under a PARA knowledge area (legacy kb/notes excluded)."""
    return is_para_prefix(rel)


def section_for(rel: str) -> str | None:
    """UI section for a knowledge-relative path: 'kb', 'notes', or None if not knowledge."""
    p = _norm(rel)
    if p == KB_AREA or p.startswith(KB_AREA + "/"):
        return SECTION_KB
    for area in NOTES_AREAS:
        if p == area or p.startswith(area + "/"):
            return SECTION_NOTES
    return None


def default_create_area(section: str) -> str:
    """Default PARA area for a new note in the given UI section."""
    return KB_AREA if section == SECTION_KB else "00-Inbox"


def validate_section(section: str | None) -> str | None:
    """Return normalized section or None. Raise ValueError on garbage values."""
    if section is None or section == "":
        return None
    if section not in VALID_SECTIONS:
        raise ValueError(f"section must be 'kb' or 'notes', got {section!r}")
    return section


def is_chrome_basename(name: str) -> bool:
    return name in CHROME_BASENAMES


def is_chrome_path(rel: str) -> bool:
    return Path(_norm(rel)).name in CHROME_BASENAMES


def path_allowed_for_section(rel: str, section: str | None) -> bool:
    """Hard create-scope: when section is set, path must belong to that section."""
    if section is None:
        return True
    try:
        section = validate_section(section)
    except ValueError:
        return False
    return section_for(rel) == section


def assert_path_allowed_for_section(rel: str, section: str | None) -> None:
    """Raise ValueError if [rel] is outside [section]'s areas."""
    section = validate_section(section)
    if section is None:
        return
    if section_for(rel) != section:
        raise ValueError(
            f"path {rel!r} is outside section {section!r} "
            f"(kb → {KB_AREA}/; notes → Inbox/Work/Personal/Archive)"
        )


def para_area_roots(root: Path) -> list[Path]:
    return [root / area for area in PARA_AREAS]


def knowledge_roots(root: Path, *, existing_only: bool = True) -> list[Path]:
    """Roots to scan for knowledge markdown (PARA only)."""
    roots: list[Path] = []
    for area in PARA_AREAS:
        p = root / area
        if not existing_only or p.is_dir():
            roots.append(p)
    return roots


def _norm(path: str) -> str:
    p = path.strip().lstrip("/").replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    return p


def normalize_api_path(path: str) -> str:
    """
    Normalize a serve/API path to a vault-relative knowledge path.

    Accepts:
      - PARA: 10-Work/ResumePoints/foo.md
      - legacy full: kb/notes/foo.md (returned as-is for 410 detection)
      - bare relative: ResumePoints/foo.md → 10-Work/…; else → 00-Inbox/…
    """
    p = _norm(path)
    if p.startswith("kb/notes/"):
        return p
    if p == "kb/notes":
        raise ValueError("path must be a .md file")
    if is_para_prefix(p):
        return p
    # Bare relative → PARA (dual-read alias retired)
    if p.startswith("ResumePoints/"):
        return f"10-Work/{p}"
    return f"00-Inbox/{p}"


def validate_knowledge_rel(rel: str) -> str:
    """Validate and return normalized vault-relative PARA knowledge .md path.

    Raises ValueError for invalid paths. Legacy ``kb/notes/…`` raises with a
    cutover hint (callers that need HTTP 410 should check ``is_legacy_kb_path``
    before validating).
    """
    p = normalize_api_path(rel)
    if is_legacy_kb_path(p):
        raise ValueError(
            "legacy kb/notes/ path retired; run chronicle cutover-kb "
            "and use a PARA path"
        )
    if any(f in p for f in _FORBIDDEN) or p.startswith("../") or "/../" in p:
        raise ValueError("invalid note path")
    if not _SAFE_REL.match(p):
        raise ValueError("note path has invalid characters")
    if not p.endswith(".md"):
        raise ValueError("note path must end with .md")
    if not is_knowledge_path(p):
        raise ValueError("path is not under a PARA knowledge area")
    return p


def abs_under_root(root: Path, rel: str) -> Path:
    """Resolve vault-relative path; raise ValueError if it escapes root."""
    base = root.resolve()
    target = (root / rel).resolve()
    if not target.is_relative_to(base):
        raise ValueError("note path escapes vault root")
    return target


def candidate_read_paths(root: Path, rel: str) -> list[Path]:
    """
    Ordered candidates for reading a note (PARA only).

    Exact vault-relative path first. ResumePoints: bare ``ResumePoints/X.md``
    probes ``10-Work/ResumePoints/X.md`` via normalize. No legacy kb/notes peers.
    """
    rel = normalize_api_path(rel)
    seen: set[Path] = set()
    out: list[Path] = []

    def add(p: Path) -> None:
        try:
            rp = p.resolve()
        except OSError:
            return
        if rp in seen:
            return
        seen.add(rp)
        out.append(p)

    if is_legacy_kb_path(rel):
        # Retired — no candidates (API returns 410)
        return out

    add(root / rel)
    return out


def same_suffix_peers(root: Path, rel: str) -> list[str]:
    """Existing PARA path for [rel] (self only after dual-read cutover)."""
    rel = normalize_api_path(rel)
    peers: list[str] = []
    if is_legacy_kb_path(rel):
        return peers
    if is_para_prefix(rel) and (root / rel).is_file():
        peers.append(rel)
    return peers


def find_dual_copy_pairs(root: Path) -> list[dict[str, str]]:
    """Report leftover PARA + legacy same-suffix copies (doctor / pre-cutover)."""
    pairs: list[dict[str, str]] = []
    legacy_root = root / "kb" / "notes"
    if not legacy_root.is_dir():
        return pairs
    for path in sorted(legacy_root.rglob("*.md")):
        if path.name.startswith(".") or ".sync-conflict" in path.name:
            continue
        if is_chrome_basename(path.name):
            continue
        rel = vault_rel(root, path)
        suffix = rel[len(LEGACY_KB_NOTES) + 1 :]
        for area in PARA_AREAS:
            para = root / area / suffix
            if para.is_file():
                pairs.append(
                    {
                        "suffix": suffix,
                        "para": f"{area}/{suffix}",
                        "legacy": rel,
                    }
                )
                break
    return pairs


def resolve_read_abs(root: Path, rel: str) -> Path | None:
    """Return first existing absolute file among candidates, or None."""
    for cand in candidate_read_paths(root, rel):
        if cand.is_file():
            return cand
    return None


def resolve_read(root: Path, rel: str) -> str | None:
    """Return vault-relative path of first existing candidate, or None."""
    abs_path = resolve_read_abs(root, rel)
    if abs_path is None:
        return None
    return vault_rel(root, abs_path)


def vault_rel(root: Path, abs_path: Path) -> str:
    return abs_path.resolve().relative_to(root.resolve()).as_posix()


def preferred_write_rel(rel: str, *, create: bool = False) -> str:
    """
    Canonical write path for a normalized knowledge rel.

    PARA paths stay as-is. Legacy/bare paths normalize to PARA
    (Inbox or Work/ResumePoints). ``create`` kept for API compatibility.
    """
    del create  # normalize already picks PARA destinations
    p = normalize_api_path(rel)
    if is_legacy_kb_path(p):
        suffix = p[len(LEGACY_KB_NOTES) + 1 :]
        if suffix.startswith("ResumePoints/"):
            return f"10-Work/{suffix}"
        return f"00-Inbox/{suffix}"
    return p


def resolve_write(root: Path, rel: str, *, create: bool = False) -> Path:
    """
    Absolute path to write.

    If the note already exists, write to the existing file.
    If creating, write under the preferred PARA path.
    """
    existing = resolve_read_abs(root, rel)
    if existing is not None and not create:
        return existing
    if existing is not None and create:
        return existing

    dest_rel = preferred_write_rel(rel, create=True)
    return abs_under_root(root, dest_rel)


def iter_knowledge_md(root: Path) -> Iterator[tuple[str, Path]]:
    """Yield (vault_rel, abs_path) for all PARA knowledge .md files."""
    seen_rels: set[str] = set()

    for area in PARA_AREAS:
        base = root / area
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.md")):
            if path.name.startswith(".") or ".sync-conflict" in path.name:
                continue
            if is_chrome_basename(path.name):
                continue
            rel = vault_rel(root, path)
            if rel in seen_rels:
                continue
            seen_rels.add(rel)
            yield rel, path


def build_knowledge_tree(root: Path, *, section: str | None = None) -> dict[str, Any]:
    """Nested tree spanning PARA areas only.

    ``section`` optionally restricts to 'kb' (30-Knowledge) or 'notes'
    (all other PARA areas). None (default) returns everything.
    Chrome basenames (CLAUDE.md, .gitkeep, README.md) are omitted; MOCs kept.
    """
    section = validate_section(section)

    def empty_dir(path_label: str) -> dict[str, Any]:
        return {"path": path_label, "type": "dir", "children": []}

    def walk(directory: Path, vault_prefix: str) -> dict[str, Any]:
        children: list[dict[str, Any]] = []
        if not directory.is_dir():
            return empty_dir(vault_prefix)
        for child in sorted(
            directory.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower())
        ):
            if child.name.startswith(".") or ".sync-conflict" in child.name:
                continue
            if is_chrome_basename(child.name):
                continue
            child_vault = f"{vault_prefix}/{child.name}" if vault_prefix else child.name
            if child.is_dir():
                node = walk(child, child_vault)
                if node["children"]:
                    children.append(node)
            elif child.suffix == ".md":
                children.append(
                    {"path": child_vault, "name": child.name, "type": "file"}
                )
        return {"path": vault_prefix, "type": "dir", "children": children}

    areas = PARA_AREAS
    if section == SECTION_KB:
        areas = (KB_AREA,)
    elif section == SECTION_NOTES:
        areas = NOTES_AREAS

    top: list[dict[str, Any]] = []
    for area in areas:
        area_path = root / area
        if area_path.is_dir():
            node = walk(area_path, area)
            if node["children"] or area_path.is_dir():
                top.append(node)

    return {
        "path": "knowledge",
        "type": "dir",
        "children": top,
    }


def list_knowledge_files(root: Path) -> list[str]:
    return [rel for rel, _ in iter_knowledge_md(root)]
