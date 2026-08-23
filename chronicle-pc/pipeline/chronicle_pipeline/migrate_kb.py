"""One-time migrate KnowledgeBase content into the Chronicle vault kb/ tree."""

from __future__ import annotations

import json
import logging
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import curation as curation_mod
from .import_knowledgebase import (
    DEFAULT_KB_SOURCE,
    chronicle_node_id,
    default_kb_source,
    run_import_knowledgebase,
)
from .paths import atomic_write_json, atomic_write_text, resolve_chronicle_dir

log = logging.getLogger("chronicle.migrate_kb")

# Prefer CHRONICLE_KB_ROOT, else sibling KnowledgeBase next to chronicle-pc/.
_BINARY_SUFFIXES = {".pdf", ".docx", ".doc", ".png", ".jpg", ".jpeg", ".gif", ".webp"}
_FRONTMATTER_RE = re.compile(r"\A---\r?\n.*?\r?\n---\r?\n?", re.DOTALL)


def default_kb_root() -> Path:
    import os

    env = (os.environ.get("CHRONICLE_KB_ROOT") or "").strip()
    candidates: list[Path] = []
    if env:
        candidates.append(Path(env).expanduser())
    # chronicle_pipeline/ → pipeline/ → chronicle-pc/ → workspace root
    workspace = Path(__file__).resolve().parents[3]
    candidates.append(workspace / "KnowledgeBase")
    for cand in candidates:
        if cand.is_dir():
            return cand
    return candidates[0] if candidates else workspace / "KnowledgeBase"


# Resolved at import for CLI help strings (no hardcoded home path).
DEFAULT_KB_ROOT = default_kb_root()


def _yaml_scalar(value: str) -> str:
    """Quote YAML scalars that need it; keep simple tokens bare."""
    v = (value or "").replace("\n", " ").strip()
    if not v:
        return '""'
    if re.search(r'[:#\[\]{},&*!|>\'"%@`]', v) or v.lower() in {
        "true",
        "false",
        "null",
        "yes",
        "no",
    }:
        escaped = v.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    return v


def _yaml_tags(tags: list[str]) -> str:
    parts = [_yaml_scalar(t) for t in tags if t]
    return "[" + ", ".join(parts) + "]"


def strip_existing_frontmatter(text: str) -> str:
    return _FRONTMATTER_RE.sub("", text, count=1)


def build_note_frontmatter(
    *,
    title: str,
    tags: list[str],
    source: str,
    group: str = "",
) -> str:
    lines = ["---", f"title: {_yaml_scalar(title)}"]
    if tags:
        lines.append(f"tags: {_yaml_tags(tags)}")
    lines.append(f"source: {_yaml_scalar(source)}")
    if group:
        lines.append(f"group: {_yaml_scalar(group)}")
    lines.append("---")
    return "\n".join(lines) + "\n"


def title_from_markdown(body: str, fallback: str) -> str:
    for line in body.splitlines():
        s = line.strip()
        if s.startswith("#"):
            return s.lstrip("#").strip() or fallback
    return fallback


def build_file_meta(brain: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """
    Map note basename → {title, group, tags, node_id} from brain.json nodes.

    First node that references a file wins (stable brain order).
    """
    meta: dict[str, dict[str, Any]] = {}
    for raw in brain.get("nodes") or []:
        if not isinstance(raw, dict):
            continue
        kb_file = str(raw.get("file") or "").strip()
        if not kb_file or kb_file.endswith(".json"):
            continue
        name = Path(kb_file).name
        if name in meta:
            continue
        group = str(raw.get("group") or "").strip()
        label = str(raw.get("label") or name).strip() or name
        cid, _ = chronicle_node_id(raw)
        tags = ["knowledgebase"]
        if group:
            tags.append(group)
        meta[name] = {
            "title": label,
            "group": group,
            "tags": tags,
            "node_id": cid,
        }
    return meta


def write_note_with_frontmatter(
    src: Path,
    dest: Path,
    *,
    source_label: str,
    file_meta: dict[str, dict[str, Any]],
    dry_run: bool,
) -> bool:
    if not src.is_file():
        return False
    if dry_run:
        log.info("[dry-run] would write note %s → %s", src, dest)
        return True

    raw = src.read_text(encoding="utf-8")
    body = strip_existing_frontmatter(raw).lstrip("\n")
    meta = file_meta.get(src.name) or {}
    title = str(meta.get("title") or title_from_markdown(body, src.stem))
    group = str(meta.get("group") or "")
    tags = list(meta.get("tags") or ["knowledgebase"])
    if "ResumePoints" in source_label and "resumepoints" not in tags:
        tags.append("resumepoints")
    fm = build_note_frontmatter(
        title=title,
        tags=tags,
        source=source_label,
        group=group,
    )
    atomic_write_text(dest, fm + "\n" + body if body else fm)
    return True


def _copy_binary(src: Path, dest: Path, *, dry_run: bool) -> bool:
    if not src.is_file():
        return False
    if dry_run:
        log.info("[dry-run] would copy %s → %s", src, dest)
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    return True


def copy_kb_tree(
    kb_root: Path,
    vault: Path,
    *,
    dry_run: bool = False,
    brain: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Copy KnowledgeBase/{Docs,ReadMe,KnowledgeMap.md} into vault kb/.

    - Markdown → kb/notes/ with YAML frontmatter (title, tags, source, group)
    - ResumePoints stay under kb/notes/ResumePoints/
    - PDFs/docx → kb/files/
    - knowledge.json → kb/knowledge.json
    """
    stats = {
        "notes": 0,
        "files": 0,
        "knowledge_json": False,
        "note_paths": {},  # basename → vault-relative path
        "frontmatter": 0,
    }
    note_index: dict[str, str] = {}
    file_meta = build_file_meta(brain or {})

    def register_note(dest_rel: str) -> None:
        name = Path(dest_rel).name
        note_index[name] = dest_rel
        note_index[dest_rel] = dest_rel

    def write_md(src: Path, dest_rel: str, source_label: str) -> None:
        if write_note_with_frontmatter(
            src,
            vault / dest_rel,
            source_label=source_label,
            file_meta=file_meta,
            dry_run=dry_run,
        ):
            stats["notes"] += 1
            stats["frontmatter"] += 1
            register_note(dest_rel)

    # ReadMe/*.md → kb/notes/
    readme = kb_root / "ReadMe"
    if readme.is_dir():
        for src in sorted(readme.rglob("*")):
            if not src.is_file() or src.name.startswith("."):
                continue
            if src.suffix.lower() != ".md":
                continue
            rel = src.relative_to(readme)
            dest_rel = f"kb/notes/{rel.as_posix()}"
            write_md(src, dest_rel, f"KnowledgeBase/ReadMe/{rel.as_posix()}")

    # Docs/ — split md vs binary; ResumePoints nested
    docs = kb_root / "Docs"
    if docs.is_dir():
        for src in sorted(docs.rglob("*")):
            if not src.is_file() or src.name.startswith("."):
                continue
            rel = src.relative_to(docs)
            suffix = src.suffix.lower()
            if src.name == "knowledge.json" and rel.as_posix() == "knowledge.json":
                dest = vault / "kb" / "knowledge.json"
                if _copy_binary(src, dest, dry_run=dry_run):
                    stats["knowledge_json"] = True
                continue
            if suffix in _BINARY_SUFFIXES:
                dest_rel = f"kb/files/{rel.as_posix()}"
                if _copy_binary(src, vault / dest_rel, dry_run=dry_run):
                    stats["files"] += 1
                continue
            if suffix == ".md":
                dest_rel = f"kb/notes/{rel.as_posix()}"
                write_md(src, dest_rel, f"KnowledgeBase/Docs/{rel.as_posix()}")

    # KnowledgeMap.md → kb/notes/KnowledgeMap.md
    km = kb_root / "KnowledgeMap.md"
    if km.is_file():
        dest_rel = "kb/notes/KnowledgeMap.md"
        write_md(km, dest_rel, "KnowledgeBase/KnowledgeMap.md")

    stats["note_paths"] = note_index
    return stats


def resolve_kb_file_to_doc(filename: str, note_index: dict[str, str]) -> str | None:
    """Map a KB brain.json `file` field to a vault kb/notes/ path."""
    raw = (filename or "").strip()
    if not raw or raw.endswith(".json"):
        return None
    name = Path(raw).name
    if name in note_index:
        return note_index[name]
    # Try ResumePoints/
    rp = f"kb/notes/ResumePoints/{name}"
    if rp in note_index.values() or name in note_index:
        return note_index.get(name) or rp
    candidate = f"kb/notes/{name}"
    if candidate in note_index.values():
        return candidate
    return None


def emit_set_doc_ops(
    root: Path,
    brain: dict[str, Any],
    note_index: dict[str, str],
    *,
    device: str = "pc",
    dry_run: bool = False,
) -> list[dict[str, Any]]:
    """Emit set_doc ops for KB nodes whose file maps to a migrated note."""
    existing_docs: set[str] = set()
    for op in curation_mod.read_ops(root):
        if op.get("op") == "set_doc" and op.get("node") and op.get("doc"):
            existing_docs.add(str(op["node"]))
    graph_path = root / "brain" / "graph.json"
    if graph_path.is_file():
        try:
            graph = json.loads(graph_path.read_text(encoding="utf-8"))
            for n in graph.get("nodes") or []:
                if isinstance(n, dict) and n.get("id") and n.get("doc"):
                    existing_docs.add(str(n["id"]))
        except Exception:  # noqa: BLE001
            pass

    when = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    ops: list[dict[str, Any]] = []
    for raw in brain.get("nodes") or []:
        if not isinstance(raw, dict):
            continue
        kb_file = str(raw.get("file") or "").strip()
        doc = resolve_kb_file_to_doc(kb_file, note_index)
        if not doc:
            continue
        cid, _ = chronicle_node_id(raw)
        if cid in existing_docs:
            continue
        op = {
            "op": "set_doc",
            "ts": when,
            "device": device,
            "node": cid,
            "doc": doc,
        }
        ops.append(op)
        existing_docs.add(cid)

    if dry_run:
        log.info("[dry-run] would append %d set_doc ops", len(ops))
    else:
        for op in ops:
            curation_mod.append_op(root, op, device=device)
        if ops:
            log.info("Appended %d set_doc ops", len(ops))
    return ops


def apply_groups_to_graph(
    root: Path,
    brain: dict[str, Any],
    *,
    dry_run: bool = False,
) -> dict[str, Any]:
    """
    Persist brain.json groups onto graph.json and stamp group on matching nodes.

    Also writes brain/kb_meta.json so later `chronicle brain` rebuilds keep groups.
    """
    graph_path = root / "brain" / "graph.json"
    meta_path = root / "brain" / "kb_meta.json"
    groups = brain.get("groups") if isinstance(brain.get("groups"), dict) else {}
    node_groups: dict[str, str] = {}
    for raw in brain.get("nodes") or []:
        if not isinstance(raw, dict):
            continue
        group = str(raw.get("group") or "").strip()
        if not group:
            continue
        cid, _ = chronicle_node_id(raw)
        node_groups[cid] = group

    meta = {
        "version": 1,
        "source": "KnowledgeBase/brain.json",
        "groups": groups,
        "node_groups": node_groups,
    }

    if dry_run:
        log.info(
            "[dry-run] would set %d groups defs and %d node.group fields",
            len(groups),
            len(node_groups),
        )
        return {"groups": len(groups), "nodes_tagged": len(node_groups), "dry_run": True}

    atomic_write_json(meta_path, meta)

    if not graph_path.is_file():
        log.warning("No graph.json yet; wrote kb_meta.json only")
        return {"groups": len(groups), "nodes_tagged": 0, "skipped_graph": True}

    graph = json.loads(graph_path.read_text(encoding="utf-8"))
    if not isinstance(graph, dict):
        raise ValueError(f"Invalid graph at {graph_path}")

    stamp_kb_meta_onto_graph(graph, meta)
    atomic_write_json(graph_path, graph)
    log.info("Applied %d group defs; tagged %d nodes", len(groups), len(node_groups))
    return {"groups": len(groups), "nodes_tagged": len(node_groups)}


def stamp_kb_meta_onto_graph(graph: dict[str, Any], meta: dict[str, Any]) -> int:
    """Apply kb_meta groups + node_groups onto a graph dict. Returns nodes tagged."""
    groups = meta.get("groups") if isinstance(meta.get("groups"), dict) else {}
    node_groups = meta.get("node_groups") if isinstance(meta.get("node_groups"), dict) else {}
    if groups:
        graph["groups"] = groups
    tagged = 0
    for n in graph.get("nodes") or []:
        if not isinstance(n, dict) or not n.get("id"):
            continue
        gid = node_groups.get(str(n["id"]))
        if gid:
            n["group"] = str(gid)
            tagged += 1
    return tagged


def load_and_stamp_kb_meta(root: Path, graph: dict[str, Any]) -> int:
    """If brain/kb_meta.json exists, stamp it onto graph. Returns nodes tagged."""
    meta_path = root / "brain" / "kb_meta.json"
    if not meta_path.is_file():
        return 0
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        log.warning("Cannot read kb_meta.json: %s", e)
        return 0
    if not isinstance(meta, dict):
        return 0
    return stamp_kb_meta_onto_graph(graph, meta)


def run_migrate_kb(
    chronicle_dir: Path | str | None = None,
    *,
    kb_root: Path | str | None = None,
    source: Path | str | None = None,
    dry_run: bool = False,
    apply: bool | None = None,
    device: str = "pc",
) -> dict[str, Any]:
    """
    Copy KB content into vault kb/, import brain.json as curation ops, emit set_doc.

    Leaves the old KnowledgeBase/ directory untouched (retirement is a separate step).
    """
    root = resolve_chronicle_dir(chronicle_dir)
    kb = Path(kb_root).expanduser().resolve() if kb_root else default_kb_root()
    if not kb.is_dir():
        raise FileNotFoundError(f"KnowledgeBase root not found: {kb}")

    brain_src = Path(source).expanduser().resolve() if source else (kb / "brain.json")
    if not brain_src.is_file():
        brain_src = default_kb_source()
    if not brain_src.is_file():
        raise FileNotFoundError(
            f"KnowledgeBase brain.json not found (tried {kb / 'brain.json'} and {DEFAULT_KB_SOURCE})"
        )

    brain = json.loads(brain_src.read_text(encoding="utf-8"))
    if not isinstance(brain, dict):
        raise ValueError(f"Expected object in {brain_src}")

    copy_stats = copy_kb_tree(kb, root, dry_run=dry_run, brain=brain)

    import_result = run_import_knowledgebase(
        root,
        source=brain_src,
        dry_run=dry_run,
        apply=False if dry_run else (False if apply is False else apply),
        device=device,
    )

    set_docs = emit_set_doc_ops(
        root,
        brain,
        copy_stats["note_paths"],
        device=device,
        dry_run=dry_run,
    )

    brain_result = None
    should_apply = bool(apply) if apply is not None else True
    if should_apply and not dry_run and (import_result.get("ops_appended") or set_docs):
        from .brain import run_brain

        brain_result = run_brain(root, dry_run=False)

    groups_result = apply_groups_to_graph(root, brain, dry_run=dry_run)

    return {
        "kb_root": str(kb),
        "chronicle_dir": str(root),
        "copied_notes": copy_stats["notes"],
        "copied_files": copy_stats["files"],
        "frontmatter": copy_stats["frontmatter"],
        "knowledge_json": copy_stats["knowledge_json"],
        "import": import_result,
        "set_doc_ops": len(set_docs),
        "groups": groups_result,
        "dry_run": dry_run,
        "brain": brain_result,
    }
