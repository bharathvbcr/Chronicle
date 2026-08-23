"""Knowledge-base notes markdown CRUD + tree (PARA-only after dual-read cutover)."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from .. import path_map
from ..cutover_kb import MIGRATE_HINT
from ..link_repair import repair_links_after_move
from ..lock import vault_process_lock
from ..paths import atomic_write_text, content_hash
from .deps import get_root

log = logging.getLogger("chronicle.api.kb")

router = APIRouter(tags=["kb"])


class NoteBody(BaseModel):
    content: str = ""
    title: str | None = None
    tags: list[str] = Field(default_factory=list)
    section: str | None = None
    """Optional 'kb' | 'notes' — when creating a bare filename, picks the default area.
    When set, cross-section explicit PARA paths are rejected (400)."""
    base_hash: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
        description="Required on overwrite PUT; must match on-disk content_hash",
    )


class MoveBody(BaseModel):
    from_path: str
    to_path: str


class ArchiveBody(BaseModel):
    path: str


def _reject_legacy_or_400(path: str) -> str:
    """Normalize path; 410 for retired kb/notes; 400 for other invalid."""
    try:
        normalized = path_map.normalize_api_path(path)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    if path_map.is_legacy_kb_path(normalized):
        raise HTTPException(410, MIGRATE_HINT)
    try:
        return path_map.validate_knowledge_rel(normalized)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


def _norm_or_400(path: str) -> str:
    return _reject_legacy_or_400(path)


def _section_or_400(section: str | None) -> str | None:
    try:
        return path_map.validate_section(section)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


def _assert_section_or_400(rel: str, section: str | None) -> None:
    try:
        path_map.assert_path_allowed_for_section(rel, section)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


def _is_bare_create_alias(raw_path: str) -> bool:
    """True when the client sent a bare relative path (not PARA, not legacy)."""
    p = raw_path.strip().lstrip("/").replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    if path_map.is_para_prefix(p) or path_map.is_legacy_kb_path(p):
        return False
    if p.startswith("ResumePoints/"):
        return False
    return bool(p)


def _resolve_write_with_section(
    root: Path,
    rel: str,
    section: str | None,
    *,
    create: bool,
    bare_alias: bool = False,
) -> Path:
    """Like path_map.resolve_write, but honors an explicit create-time section
    for bare filenames remapped to Inbox (not ResumePoints).

    Section remap applies **only on create** for bare aliases. Explicit PARA
    paths are section-checked immediately (cross-section → 400).
    """
    section = path_map.validate_section(section)
    # Bare aliases normalize to Inbox before section remap — do not section-check
    # the intermediate Inbox path. Explicit PARA paths are checked immediately.
    if section and path_map.is_para_prefix(rel) and not bare_alias:
        _assert_section_or_400(rel, section)
    if not create:
        return path_map.resolve_write(root, rel, create=False)
    if (
        create
        and bare_alias
        and section
        and rel.startswith("00-Inbox/")
        and "ResumePoints" not in rel
    ):
        suffix = rel[len("00-Inbox/") :]
        dest_rel = f"{path_map.default_create_area(section)}/{suffix}"
        return path_map.abs_under_root(root, dest_rel)
    return path_map.resolve_write(root, rel, create=True)


def _title_for_create(rel: str, body: NoteBody) -> str | None:
    """Prefer explicit body.title; fall back to filename stem (not a path)."""
    if body.title is not None and body.title.strip():
        t = body.title.strip()
        # SPA historically passed the vault path as title — use stem instead.
        if "/" in t or t.endswith(".md"):
            return Path(t).stem
        return t
    return Path(rel).stem


def _content_for_create(rel: str, body: NoteBody) -> str:
    """Apply optional title/tags, then convention-complete create frontmatter."""
    from ..note_frontmatter import ensure_create_frontmatter

    content = body.content
    if not content.lstrip().startswith("---") and (body.title is not None or body.tags):
        lines = ["---"]
        if body.title is not None:
            lines.append(f"title: {_title_for_create(rel, body)}")
        if body.tags:
            tag_list = ", ".join(body.tags)
            lines.append(f"tags: [{tag_list}]")
        lines.append("---")
        lines.append("")
        content = "\n".join(lines) + content
    return ensure_create_frontmatter(
        content,
        title=_title_for_create(rel, body),
        note_type="note",
    )


def _maybe_frontmatter(body: NoteBody) -> str:
    """Legacy helper for overwrite paths that only inject title/tags when bare."""
    content = body.content
    if body.title is None and not body.tags:
        return content
    if content.lstrip().startswith("---"):
        return content
    lines = ["---"]
    if body.title is not None:
        lines.append(f"title: {body.title}")
    if body.tags:
        tag_list = ", ".join(body.tags)
        lines.append(f"tags: [{tag_list}]")
    lines.append("---")
    lines.append("")
    return "\n".join(lines) + content


def _para_suffix(rel: str) -> str:
    """Area-relative suffix for a knowledge path (for archive nesting)."""
    for area in path_map.PARA_AREAS:
        if rel == area or rel.startswith(area + "/"):
            return rel[len(area) + 1 :] if rel.startswith(area + "/") else Path(rel).name
    return Path(rel).name


def _move_note(root: Path, from_rel: str, to_rel: str) -> dict[str, Any]:
    """Move a PARA note to [to_rel]."""
    if not path_map.is_para_prefix(to_rel):
        raise HTTPException(400, "to_path must be under a PARA area")
    if path_map.is_chrome_path(to_rel):
        raise HTTPException(400, "cannot move onto chrome basename")

    abs_src = path_map.resolve_read_abs(root, from_rel)
    if abs_src is None or not abs_src.is_file():
        raise HTTPException(404, f"note not found: {from_rel}")
    primary_rel = path_map.vault_rel(root, abs_src)

    dest = path_map.abs_under_root(root, to_rel)
    if dest.exists() and dest.resolve() != abs_src.resolve():
        raise HTTPException(409, f"destination exists: {to_rel}")

    content = abs_src.read_text(encoding="utf-8", errors="replace")
    if dest.resolve() != abs_src.resolve():
        atomic_write_text(dest, content if content.endswith("\n") else content + "\n")
        abs_src.unlink()

    repair = repair_links_after_move(root, primary_rel, to_rel)
    return {
        "ok": True,
        "from_path": primary_rel,
        "to_path": to_rel,
        "quarantined": [],
        "links_repaired": repair.replacements,
        "files_updated": repair.files_updated,
        "changelog_appended": repair.changelog_appended,
    }


def _archive_dest_rel(rel: str) -> str:
    """90-Archive/<original subpath> — strip leading 90-Archive/ if already archived."""
    suffix = _para_suffix(rel)
    if path_map.is_para_prefix(rel) and rel.startswith("90-Archive/"):
        inner = rel[len("90-Archive/") :]
        if inner.startswith("_legacy-kb/"):
            return rel
        return f"90-Archive/{inner}"
    return f"90-Archive/{suffix}"


HUB_READ_PATHS: frozenset[str] = frozenset({"Home.md"})


def _norm_read_path(path: str) -> str:
    """Validate path for GET; allows vault-root hub files outside PARA."""
    p = path.strip().lstrip("/")
    if p in HUB_READ_PATHS:
        return p
    return _norm_or_400(p)


@router.get("/kb/templates")
def kb_templates(root: Path = Depends(get_root)) -> dict[str, Any]:
    """List ``_templates/*.md`` for the SPA create picker."""
    templates_dir = root / "_templates"
    files: list[dict[str, str]] = []
    if templates_dir.is_dir():
        for path in sorted(templates_dir.glob("*.md")):
            content = path.read_text(encoding="utf-8", errors="replace")
            files.append(
                {
                    "name": path.stem,
                    "path": f"_templates/{path.name}",
                    "content": content,
                }
            )
    return {"files": files}


@router.get("/kb/tree")
def kb_tree(
    root: Path = Depends(get_root),
    section: str | None = Query(default=None, description="'kb' or 'notes'; omit for all"),
) -> dict[str, Any]:
    section = _section_or_400(section)
    try:
        tree = path_map.build_knowledge_tree(root, section=section)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    files = path_map.list_knowledge_files(root)
    if section:
        files = [f for f in files if path_map.section_for(f) == section]
    return {"tree": tree, "files": files}


def _note_response(abs_path: Path, root: Path, *, path_override: str | None = None) -> dict[str, Any]:
    content = abs_path.read_text(encoding="utf-8", errors="replace")
    rel = path_override or path_map.vault_rel(root, abs_path)
    return {
        "path": rel,
        "content": content,
        "content_hash": content_hash(content),
    }


@router.get("/kb/notes/{path:path}")
def get_kb_note(path: str, root: Path = Depends(get_root)) -> dict[str, Any]:
    rel = _norm_read_path(path)
    if rel in HUB_READ_PATHS:
        abs_path = root / rel
        if not abs_path.is_file():
            raise HTTPException(404, f"note not found: {rel}")
        return _note_response(abs_path, root, path_override=rel)
    abs_path = path_map.resolve_read_abs(root, rel)
    if abs_path is None or not abs_path.is_file():
        raise HTTPException(404, f"note not found: {rel}")
    return _note_response(abs_path, root)


@router.put("/kb/notes/{path:path}")
def put_kb_note(
    path: str, body: NoteBody, root: Path = Depends(get_root)
) -> dict[str, Any]:
    """Create or overwrite a knowledge note (PARA only).

    Overwrite requires ``base_hash`` matching on-disk ``content_hash`` (409 on mismatch).
    Creates ignore ``base_hash``.
    """
    with vault_process_lock(root):
        bare = _is_bare_create_alias(path)
        rel = _norm_or_400(path)
        section = _section_or_400(body.section)
        existing = path_map.resolve_read_abs(root, rel)
        creating = existing is None or not existing.is_file()
        if creating and section:
            abs_probe = _resolve_write_with_section(
                root, rel, section, create=True, bare_alias=bare
            )
            dest_rel = path_map.vault_rel(root, abs_probe)
            _assert_section_or_400(dest_rel, section)
        abs_path = _resolve_write_with_section(
            root, rel, section, create=False, bare_alias=bare
        )
        if not abs_path.is_file():
            abs_path = _resolve_write_with_section(
                root, rel, section, create=True, bare_alias=bare
            )
            if section:
                _assert_section_or_400(path_map.vault_rel(root, abs_path), section)
            creating = True
        if not creating:
            disk = abs_path.read_text(encoding="utf-8", errors="replace")
            disk_hash = content_hash(disk)
            if not body.base_hash:
                raise HTTPException(
                    400,
                    "base_hash required for overwrite (from GET content_hash)",
                )
            if disk_hash != body.base_hash:
                raise HTTPException(
                    409,
                    {
                        "detail": "knowledge note hash mismatch",
                        "on_disk_hash": disk_hash,
                    },
                )
        text = (
            _content_for_create(path_map.vault_rel(root, abs_path), body)
            if creating
            else _maybe_frontmatter(body)
        )
        atomic_write_text(abs_path, text if text.endswith("\n") else text + "\n")
        return _note_response(abs_path, root)


@router.post("/kb/notes/{path:path}", status_code=201)
def post_kb_note(
    path: str, body: NoteBody, root: Path = Depends(get_root)
) -> dict[str, Any]:
    """Create a knowledge note; fails if it already exists."""
    with vault_process_lock(root):
        bare = _is_bare_create_alias(path)
        rel = _norm_or_400(path)
        section = _section_or_400(body.section)
        existing = path_map.resolve_read_abs(root, rel)
        if existing is not None and existing.is_file():
            raise HTTPException(
                409, f"note already exists: {path_map.vault_rel(root, existing)}"
            )
        abs_path = _resolve_write_with_section(
            root, rel, section, create=True, bare_alias=bare
        )
        if section:
            _assert_section_or_400(path_map.vault_rel(root, abs_path), section)
        if abs_path.is_file():
            raise HTTPException(
                409, f"note already exists: {path_map.vault_rel(root, abs_path)}"
            )
        text = _content_for_create(path_map.vault_rel(root, abs_path), body)
        atomic_write_text(abs_path, text if text.endswith("\n") else text + "\n")
        return _note_response(abs_path, root)


@router.delete("/kb/notes/{path:path}")
def delete_kb_note(path: str, root: Path = Depends(get_root)) -> dict[str, Any]:
    """Hard-delete a PARA note. Prefer archive."""
    with vault_process_lock(root):
        rel = _norm_or_400(path)
        abs_path = path_map.resolve_read_abs(root, rel)
        if abs_path is None or not abs_path.is_file():
            raise HTTPException(404, f"note not found: {rel}")
        deleted_rel = path_map.vault_rel(root, abs_path)
        abs_path.unlink()
        return {"ok": True, "deleted": deleted_rel, "deleted_all": [deleted_rel]}


@router.post("/kb/move")
def move_kb_note(body: MoveBody, root: Path = Depends(get_root)) -> dict[str, Any]:
    """Move a note to a PARA path."""
    with vault_process_lock(root):
        from_rel = _norm_or_400(body.from_path)
        to_rel = _norm_or_400(body.to_path)
        return _move_note(root, from_rel, to_rel)


@router.post("/kb/archive")
def archive_kb_note(body: ArchiveBody, root: Path = Depends(get_root)) -> dict[str, Any]:
    """Archive under 90-Archive/<original subpath>/ (preferred over hard delete)."""
    with vault_process_lock(root):
        from_rel = _norm_or_400(body.path)
        to_rel = _archive_dest_rel(from_rel)
        to_rel = _norm_or_400(to_rel)
        return _move_note(root, from_rel, to_rel)
