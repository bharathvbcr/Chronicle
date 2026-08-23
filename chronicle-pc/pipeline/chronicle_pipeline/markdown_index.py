"""Live ``_system/index.md`` agent shortlist (PARA + journal day files).

Sqlite under ``index/`` remains the RAG source of truth. This markdown file is a
regenerated skim index for Claude Code / Obsidian — not hand-edited SoT.

Rebuild via ``chronicle rebuild-markdown-index``, ``chronicle index --write-markdown``,
or ``POST /vault/rebuild-index``. Inbox filing and ``_staging`` import stay
skill/CLI-driven (not SPA wizards).
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import path_map
from .paths import atomic_write_text, resolve_chronicle_dir
from .vault_paths import JOURNAL_DIR

log = logging.getLogger("chronicle.markdown_index")

INDEX_REL = "_system/index.md"

_FRONTMATTER_RE = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n?", re.DOTALL)
_H1_RE = re.compile(r"^#\s+(.+)$", re.MULTILINE)

INDEX_HEADER = """# Vault index

Agent shortlist (regenerated — do not hand-edit as SoT). Sqlite RAG lives under
`index/`. Rebuild: `chronicle rebuild-markdown-index` or `POST /vault/rebuild-index`.

Format: `title | type | tags | updated`

"""


def _parse_frontmatter(text: str) -> dict[str, str]:
    m = _FRONTMATTER_RE.match(text)
    if not m:
        return {}
    meta: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" not in line:
            continue
        key, _, val = line.partition(":")
        meta[key.strip().lower()] = val.strip().strip("\"'")
    return meta


def _format_tags(raw: str) -> str:
    raw = (raw or "").strip()
    if not raw:
        return ""
    if raw.startswith("[") and raw.endswith("]"):
        inner = raw[1:-1].strip()
        if not inner:
            return ""
        parts = [p.strip().strip("\"'") for p in inner.split(",")]
        return ", ".join(p for p in parts if p)
    return raw


def _file_mtime_date(path: Path) -> str:
    try:
        ts = path.stat().st_mtime
        return datetime.fromtimestamp(ts, tz=timezone.utc).astimezone().date().isoformat()
    except OSError:
        return datetime.now(timezone.utc).astimezone().date().isoformat()


def _row_for_file(
    rel: str,
    path: Path,
    text: str,
    *,
    default_type: str,
) -> dict[str, str]:
    meta = _parse_frontmatter(text)
    title = (meta.get("title") or "").strip()
    if not title:
        h1 = _H1_RE.search(text)
        title = h1.group(1).strip() if h1 else Path(rel).stem
    note_type = (meta.get("type") or "").strip() or default_type
    tags = _format_tags(meta.get("tags") or "")
    updated = (meta.get("updated") or meta.get("created") or "").strip()
    if not updated:
        updated = _file_mtime_date(path)
    return {
        "rel": rel,
        "title": title,
        "type": note_type,
        "tags": tags,
        "updated": updated,
    }


def collect_index_rows(root: Path) -> list[dict[str, str]]:
    """Walk PARA knowledge notes + ``40-Journal/`` day files."""
    rows: list[dict[str, str]] = []
    seen: set[str] = set()

    for rel, path in path_map.iter_knowledge_md(root):
        if rel in seen:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        seen.add(rel)
        rows.append(_row_for_file(rel, path, text, default_type="note"))

    journal_root = root / JOURNAL_DIR
    if journal_root.is_dir():
        for path in sorted(journal_root.rglob("*.md")):
            if path.name.startswith(".") or ".sync-conflict" in path.name:
                continue
            if path_map.is_chrome_basename(path.name):
                continue
            rel = str(path.relative_to(root)).replace("\\", "/")
            if rel in seen:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            seen.add(rel)
            rows.append(_row_for_file(rel, path, text, default_type="journal"))

    rows.sort(key=lambda r: (r["updated"], r["title"].lower()), reverse=True)
    return rows


def format_index_markdown(rows: list[dict[str, str]]) -> str:
    lines = [INDEX_HEADER.rstrip(), ""]
    for row in rows:
        tags = row["tags"] or "—"
        lines.append(
            f"{row['title']} | {row['type']} | {tags} | {row['updated']}"
        )
    lines.append("")
    return "\n".join(lines)


def rebuild_markdown_index(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
) -> dict[str, Any]:
    """Rewrite ``_system/index.md`` from PARA + journal day files."""
    root = resolve_chronicle_dir(chronicle_dir)
    rows = collect_index_rows(root)
    content = format_index_markdown(rows)
    dest = root / INDEX_REL
    if dry_run:
        log.info(
            "[dry-run] would write %s (%d rows, %d bytes)",
            INDEX_REL,
            len(rows),
            len(content.encode("utf-8")),
        )
        return {
            "path": INDEX_REL,
            "rows": len(rows),
            "dry_run": True,
            "would_write": True,
        }

    dest.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_text(dest, content if content.endswith("\n") else content + "\n")
    log.info("Wrote %s (%d rows)", INDEX_REL, len(rows))
    return {
        "path": INDEX_REL,
        "rows": len(rows),
        "dry_run": False,
        "ok": True,
    }
