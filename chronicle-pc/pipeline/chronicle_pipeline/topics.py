"""Topic notes including dream symbol clustering."""

from __future__ import annotations

import logging
import re
from collections import defaultdict
from pathlib import Path

from .config import ensure_config
from .entries import load_all_entries
from .notes import mirror_note, topic_slug, write_if_changed
from .paths import resolve_chronicle_dir

log = logging.getLogger("chronicle.topics")

WORD_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9']{3,}")
STOP = {
    "that",
    "this",
    "with",
    "from",
    "have",
    "were",
    "been",
    "they",
    "them",
    "then",
    "when",
    "what",
    "your",
    "about",
    "into",
    "just",
    "like",
    "there",
    "their",
    "would",
    "could",
    "should",
    "dream",
    "dreams",
    "dreamt",
    "dreamed",
}


def _render_topic_note(tag: str, entries: list) -> str:
    entries = sorted(entries, key=lambda e: (e.ts, e.id))
    lines = [
        "---",
        f"topic: {tag}",
        f"entries: {len(entries)}",
        "---",
        "",
        f"# {tag}",
        "",
    ]
    for e in entries:
        preview = (e.text or "").strip().splitlines()
        preview_s = preview[0][:120] if preview else "(no text)"
        lines.append(f"- [[{e.id}]] · {e.type}: {preview_s}")
    return "\n".join(lines).rstrip() + "\n"


def _dream_symbols(entries: list) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for e in entries:
        for w in WORD_RE.findall((e.text or "").lower()):
            if w in STOP:
                continue
            counts[w] += 1
    return dict(sorted(counts.items(), key=lambda x: (-x[1], x[0]))[:40])


def _render_dreams_note(dream_entries: list, symbols: dict[str, int]) -> str:
    lines = [
        "---",
        "topic: dreams",
        f"entries: {len(dream_entries)}",
        "---",
        "",
        "# Dreams",
        "",
        "## Symbols",
        "",
    ]
    for sym, n in symbols.items():
        lines.append(f"- {sym} ({n})")
    lines.extend(["", "## Entries", ""])
    for e in sorted(dream_entries, key=lambda x: (x.ts, x.id)):
        preview = (e.text or "").strip().splitlines()
        preview_s = preview[0][:120] if preview else "(voice / empty)"
        lines.append(f"- [[{e.id}]]: {preview_s}")
    return "\n".join(lines).rstrip() + "\n"


def run_topics(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
) -> dict:
    root = resolve_chronicle_dir(chronicle_dir)
    cfg = ensure_config(root)
    entries = load_all_entries(root, fallback_tz=cfg.timezone)

    by_tag: dict[str, list] = defaultdict(list)
    dreams = []
    for e in entries:
        if e.type == "dream":
            dreams.append(e)
        for t in e.tags:
            if t.startswith("future:") or t.startswith("prompt:"):
                continue
            key = t if t == "#plan" else t.lstrip("#").lower()
            by_tag[key].append(e)

    written: list[str] = []
    for tag, ents in sorted(by_tag.items()):
        slug = topic_slug(tag)
        path = root / "_system" / "derived" / "topics" / f"{slug}.md"
        content = _render_topic_note(tag, ents)
        if write_if_changed(path, content, dry_run=dry_run):
            written.append(str(path))
        if not dry_run and path.is_file():
            mirror_note(path, cfg.vault_mirror)

    if dreams:
        symbols = _dream_symbols(dreams)
        path = root / "_system" / "derived" / "topics" / "dreams.md"
        content = _render_dreams_note(dreams, symbols)
        if write_if_changed(path, content, dry_run=dry_run):
            written.append(str(path))
        if not dry_run and path.is_file():
            mirror_note(path, cfg.vault_mirror)

    log.info("%s %d topic notes", "[dry-run]" if dry_run else "Wrote", len(written))
    return {"written": written, "dreams": len(dreams), "dry_run": dry_run}
