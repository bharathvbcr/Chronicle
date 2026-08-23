"""Rewrite vault wikilinks / markdown links after a note move, rename, or archive.

Used by ``POST /kb/move`` and ``POST /kb/archive``. Agents moving files outside
the API should apply the same procedure (see capture-workflow ``reorganization.md``).
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from urllib.parse import unquote

from .paths import atomic_write_text

log = logging.getLogger("chronicle.link_repair")

# [[target]], [[target|alias]], [[target#heading]], ![[embed]] — target is group 1
_WIKILINK_RE = re.compile(
    r"(!?\[\[)"  # open (optional embed bang)
    r"([^\]|#]+)"  # target
    r"((?:#[^\]|]*)?(?:\|[^\]]*)?)"  # optional #heading and/or |alias
    r"(\]\])"
)

# [text](href) / ![alt](href)
_MD_LINK_RE = re.compile(r"(!?\[[^\]]*\])\(([^)]+)\)")

_SKIP_DIR_NAMES = frozenset(
    {
        ".git",
        ".venv",
        "node_modules",
        "__pycache__",
        "index",  # sqlite search index
    }
)


@dataclass(frozen=True)
class RepairResult:
    files_updated: int
    replacements: int
    changelog_appended: bool

    def as_dict(self) -> dict[str, int | bool]:
        return {
            "files_updated": self.files_updated,
            "replacements": self.replacements,
            "changelog_appended": self.changelog_appended,
        }


def _norm_rel(rel: str) -> str:
    p = rel.strip().lstrip("/").replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    return p


def _stem_path(rel: str) -> str:
    rel = _norm_rel(rel)
    if rel.lower().endswith(".md"):
        return rel[:-3]
    return rel


def _old_target_forms(old_rel: str) -> frozenset[str]:
    """All string forms that may appear inside ``[[…]]`` or markdown hrefs."""
    rel = _norm_rel(old_rel)
    stem = _stem_path(rel)
    base = Path(rel).stem
    forms = {rel, stem, base, f"{base}.md"}
    # Lowercase variants for case-insensitive href compare
    return frozenset(forms)


def _replacement_for_wikilink_target(
    target: str, old_rel: str, new_rel: str
) -> str | None:
    """Return new wikilink target, preserving basename-vs-path and .md style."""
    old_rel = _norm_rel(old_rel)
    new_rel = _norm_rel(new_rel)
    old_stem = _stem_path(old_rel)
    new_stem = _stem_path(new_rel)
    old_base = Path(old_rel).stem
    new_base = Path(new_rel).stem

    raw = target.strip()
    had_md = raw.lower().endswith(".md")
    core = raw[:-3] if had_md else raw

    matched: str | None = None
    if core == old_base or raw == f"{old_base}.md":
        matched = "basename"
    elif core == old_stem or raw == old_rel or raw == f"{old_stem}.md":
        matched = "path"
    else:
        return None

    if matched == "basename":
        out = new_base
    else:
        out = new_stem
    if had_md:
        out = f"{out}.md"
    return out


def _normalize_md_href(href: str) -> str:
    h = unquote(href.strip())
    # Drop angle-bracket wrapping: <path with spaces>
    if h.startswith("<") and ">" in h:
        h = h[1 : h.index(">")]
    # Optional CommonMark title: path "title" / path 'title' (not spaces in the path)
    title_m = re.match(r"^(.*\S)\s+([\"'].*)$", h)
    if title_m:
        h = title_m.group(1)
    h = h.lstrip("./")
    return _norm_rel(h)


def _md_href_matches_old(href: str, old_forms: frozenset[str]) -> bool:
    norm = _normalize_md_href(href)
    if not norm or norm.startswith(("http://", "https://", "mailto:", "#")):
        return False
    if norm in old_forms:
        return True
    # Case-insensitive path match
    lower_forms = {f.lower() for f in old_forms}
    return norm.lower() in lower_forms


def _replacement_md_href(href: str, new_rel: str) -> str:
    """Replace path portion of href; keep optional title / angle brackets."""
    new_rel = _norm_rel(new_rel)
    stripped = href.strip()
    # Preserve angle brackets
    if stripped.startswith("<") and ">" in stripped:
        rest = stripped[stripped.index(">") + 1 :]
        return f"<{new_rel}>{rest}"
    # Decoded form may include spaces in the path; only keep a quoted title suffix
    decoded = unquote(stripped)
    title_m = re.match(r"^(.*\S)\s+([\"'].*)$", decoded)
    if title_m:
        return f"{new_rel} {title_m.group(2)}"
    return new_rel


def rewrite_content(text: str, old_rel: str, new_rel: str) -> tuple[str, int]:
    """Rewrite links in one markdown body. Returns (new_text, replacement_count)."""
    old_rel = _norm_rel(old_rel)
    new_rel = _norm_rel(new_rel)
    if old_rel == new_rel:
        return text, 0

    old_forms = _old_target_forms(old_rel)
    count = 0

    def _wiki_sub(m: re.Match[str]) -> str:
        nonlocal count
        open_, target, suffix, close = m.group(1), m.group(2), m.group(3), m.group(4)
        repl = _replacement_for_wikilink_target(target, old_rel, new_rel)
        if repl is None:
            return m.group(0)
        count += 1
        return f"{open_}{repl}{suffix}{close}"

    def _md_sub(m: re.Match[str]) -> str:
        nonlocal count
        prefix, href = m.group(1), m.group(2)
        if not _md_href_matches_old(href, old_forms):
            return m.group(0)
        count += 1
        return f"{prefix}({_replacement_md_href(href, new_rel)})"

    out = _WIKILINK_RE.sub(_wiki_sub, text)
    out = _MD_LINK_RE.sub(_md_sub, out)
    return out, count


def iter_vault_markdown(root: Path) -> list[Path]:
    """All ``*.md`` files under the vault, excluding tooling / index dirs."""
    root = root.resolve()
    found: list[Path] = []
    if not root.is_dir():
        return found
    for path in sorted(root.rglob("*.md")):
        try:
            rel_parts = path.relative_to(root).parts
        except ValueError:
            continue
        if any(p in _SKIP_DIR_NAMES or p.startswith(".") for p in rel_parts[:-1]):
            continue
        if path.name.startswith(".") or ".sync-conflict" in path.name:
            continue
        found.append(path)
    return found


def append_changelog_line(root: Path, line: str) -> None:
    """Append one structural-change line to ``_system/changelog.md``."""
    path = root / "_system" / "changelog.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    entry = line.strip()
    if not entry.startswith("- "):
        entry = f"- {entry}"
    if not entry.endswith("\n"):
        entry += "\n"

    if path.is_file():
        existing = path.read_text(encoding="utf-8", errors="replace")
        if not existing.endswith("\n"):
            existing += "\n"
        atomic_write_text(path, existing + entry)
    else:
        atomic_write_text(path, f"# Vault changelog\n\n{entry}")


def repair_links_after_move(
    root: Path,
    old_rel: str,
    new_rel: str,
    *,
    log_changelog: bool = True,
) -> RepairResult:
    """Grep vault markdown for links to ``old_rel`` and rewrite to ``new_rel``.

    Matches ``[[OldTitle]]``, ``[[old/path]]`` / ``[[old/path.md]]``, and
    markdown ``[text](old/path.md)``. Logs one line to ``_system/changelog.md``
    when ``log_changelog`` is true (even if zero link replacements — the move
    itself is a structural change).
    """
    old_rel = _norm_rel(old_rel)
    new_rel = _norm_rel(new_rel)
    if old_rel == new_rel:
        return RepairResult(0, 0, False)

    files_updated = 0
    replacements = 0
    changelog_path = (root / "_system" / "changelog.md").resolve()

    for path in iter_vault_markdown(root):
        # Don't rewrite the changelog while we are about to append to it
        if path.resolve() == changelog_path:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            log.warning("skip unreadable %s: %s", path, e)
            continue
        new_text, n = rewrite_content(text, old_rel, new_rel)
        if n == 0:
            continue
        if not new_text.endswith("\n"):
            new_text += "\n"
        atomic_write_text(path, new_text)
        files_updated += 1
        replacements += n

    appended = False
    if log_changelog:
        today = date.today().isoformat()
        line = (
            f"{today}: moved {old_rel} → {new_rel} "
            f"({replacements} links in {files_updated} files)"
        )
        append_changelog_line(root, line)
        appended = True
        log.info("link repair: %s", line)

    return RepairResult(
        files_updated=files_updated,
        replacements=replacements,
        changelog_appended=appended,
    )
