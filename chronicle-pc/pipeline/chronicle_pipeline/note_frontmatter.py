"""Convention-complete frontmatter helpers for knowledge note creates.

Ensures ``created`` / ``updated`` / ``type`` / ``title`` per ``_system/conventions.md``
so empty creates are not bare markdown. Existing keys are preserved (except
``updated``, which is always stamped on create).
"""

from __future__ import annotations

import re
from datetime import date

_FRONTMATTER_RE = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n?", re.DOTALL)

_DEFAULT_KEY_ORDER = ("title", "created", "updated", "type", "tags", "aliases")


def today_iso() -> str:
    return date.today().isoformat()


def parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    """Return (frontmatter dict, body after the closing fence)."""
    m = _FRONTMATTER_RE.match(text)
    if not m:
        return {}, text
    meta: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" not in line:
            continue
        key, _, val = line.partition(":")
        meta[key.strip()] = val.strip()
    return meta, text[m.end() :]


def format_frontmatter(fm: dict[str, str]) -> str:
    ordered: list[tuple[str, str]] = []
    seen: set[str] = set()
    for key in _DEFAULT_KEY_ORDER:
        if key in fm:
            ordered.append((key, fm[key]))
            seen.add(key)
    for key, val in fm.items():
        if key not in seen:
            ordered.append((key, val))
    lines = [f"{k}: {v}" for k, v in ordered]
    return "---\n" + "\n".join(lines) + "\n---"


def _set_key(fm: dict[str, str], key: str, value: str, *, overwrite: bool) -> None:
    lower_map = {k.lower(): k for k in fm}
    existing = lower_map.get(key.lower())
    if existing is not None:
        if not overwrite and fm[existing].strip() != "":
            return
        if existing != key:
            del fm[existing]
    fm[key] = value


def ensure_create_frontmatter(
    content: str,
    *,
    title: str | None = None,
    note_type: str = "note",
    today: str | None = None,
) -> str:
    """Fill missing create-time frontmatter; stamp ``updated`` to *today*."""
    day = today or today_iso()
    fm, body = parse_frontmatter(content)

    cleaned_title = (title or "").strip()
    if cleaned_title:
        _set_key(fm, "title", cleaned_title, overwrite=False)
    _set_key(fm, "created", day, overwrite=False)
    _set_key(fm, "updated", day, overwrite=True)
    _set_key(fm, "type", note_type, overwrite=False)
    _set_key(fm, "tags", "[]", overwrite=False)

    if body and not body.startswith("\n"):
        body = "\n" + body
    if not body:
        body = "\n"
    return f"{format_frontmatter(fm)}{body}"
