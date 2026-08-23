"""Idempotent LLM enrichment of KB notes for resume retrieval."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from . import llm
from .config import ensure_config
from .paths import atomic_write_json, content_hash, read_json, resolve_chronicle_dir

log = logging.getLogger("chronicle.kb_enrich")

ENRICH_PROMPT = """You enrich a knowledge-base note for resume retrieval.
You are a reasoning model: think privately if needed, then emit ONLY final JSON
(never include <think> tags or chain-of-thought).

Return JSON only with this shape:
{
  "summary": "1-3 sentence factual summary",
  "skills": ["skill or tech names"],
  "highlights": ["STAR-style metric bullet", "..."]
}
Highlights should be concrete, measurable bullets (action + result + metric when possible).
If the note has little resume value, still return a short summary and empty arrays.
"""


def enrich_cache_path(root: Path) -> Path:
    return root / "index" / "kb_enrich.json"


def load_enrich_cache(root: Path) -> dict[str, Any]:
    path = enrich_cache_path(root)
    if not path.is_file():
        return {"version": 1, "notes": {}}
    try:
        data = read_json(path)
    except (OSError, ValueError):
        return {"version": 1, "notes": {}}
    if not isinstance(data, dict):
        return {"version": 1, "notes": {}}
    notes = data.get("notes")
    if not isinstance(notes, dict):
        notes = {}
    return {"version": int(data.get("version") or 1), "notes": notes}


def save_enrich_cache(root: Path, cache: dict[str, Any]) -> Path:
    path = enrich_cache_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": int(cache.get("version") or 1),
        "notes": cache.get("notes") if isinstance(cache.get("notes"), dict) else {},
    }
    atomic_write_json(path, payload)
    return path


def format_enrichment_prefix(entry: dict[str, Any] | None) -> str:
    """Build text prepended to KB note content for indexing."""
    if not isinstance(entry, dict):
        return ""
    parts: list[str] = []
    summary = entry.get("summary")
    if isinstance(summary, str) and summary.strip():
        parts.append(summary.strip())
    skills = entry.get("skills")
    if isinstance(skills, list):
        cleaned = [str(s).strip() for s in skills if str(s).strip()]
        if cleaned:
            parts.append("Skills: " + ", ".join(cleaned))
    highlights = entry.get("highlights")
    if isinstance(highlights, list):
        cleaned_h = [str(h).strip() for h in highlights if str(h).strip()]
        if cleaned_h:
            parts.append("Highlights:\n" + "\n".join(f"- {h}" for h in cleaned_h))
    return "\n".join(parts)


def collect_kb_notes(root: Path) -> list[tuple[str, Path, str]]:
    """Return (doc_id, path, text) for PARA knowledge MD — same ids as index_store."""
    from . import path_map

    docs: list[tuple[str, Path, str]] = []
    for rel, path in path_map.iter_knowledge_md(root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        docs.append((rel, path, text))
    return docs


def _normalize_enrichment(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("enrichment must be a JSON object")
    summary = raw.get("summary")
    if not isinstance(summary, str):
        summary = str(summary or "")
    skills_raw = raw.get("skills") or []
    highlights_raw = raw.get("highlights") or []
    skills = (
        [str(s).strip() for s in skills_raw if str(s).strip()]
        if isinstance(skills_raw, list)
        else []
    )
    highlights = (
        [str(h).strip() for h in highlights_raw if str(h).strip()]
        if isinstance(highlights_raw, list)
        else []
    )
    return {
        "summary": summary.strip(),
        "skills": skills,
        "highlights": highlights,
    }


def _enrich_one(text: str, *, model: str, provider, num_ctx: int) -> dict[str, Any]:
    clipped = text if len(text) <= 6000 else text[:6000]
    messages = [
        {"role": "system", "content": ENRICH_PROMPT},
        {"role": "user", "content": clipped},
    ]
    out = provider.chat(
        messages,
        model=model,
        temperature=0.6,
        format_json=True,
        num_predict=800,
        num_ctx=num_ctx,
    )
    return _normalize_enrichment(llm.extract_json(out))


def run_kb_enrich(
    chronicle_dir: Path | str | None = None,
    *,
    force: bool = False,
) -> dict[str, Any]:
    """Enrich KB notes into index/kb_enrich.json. Idempotent; no-op if LLM offline."""
    root = resolve_chronicle_dir(chronicle_dir)
    cfg = ensure_config(root)
    notes = collect_kb_notes(root)
    cache = load_enrich_cache(root)
    notes_map: dict[str, Any] = dict(cache.get("notes") or {})
    provider = llm.try_get_provider(cfg)
    pname = llm.provider_name(cfg)
    limits = llm.context_limits(pname)

    if provider is None or not provider.reachable():
        log.warning("LLM provider %r unreachable; skipping KB enrichment", pname)
        return {
            "ok": True,
            "ollama": False,
            "provider": pname,
            "provider_ok": False,
            "enriched": 0,
            "skipped": len(notes),
            "failed": 0,
            "total": len(notes),
        }

    enriched = 0
    skipped = 0
    failed = 0

    for doc_id, _path, text in notes:
        ch = content_hash(text)
        prev = notes_map.get(doc_id)
        if (
            not force
            and isinstance(prev, dict)
            and prev.get("content_hash") == ch
            and prev.get("summary") is not None
        ):
            skipped += 1
            continue
        try:
            payload = _enrich_one(
                text,
                model=cfg.models.llm,
                provider=provider,
                num_ctx=limits.num_ctx_enrich,
            )
            payload["content_hash"] = ch
            notes_map[doc_id] = payload
            enriched += 1
        except Exception as e:  # noqa: BLE001
            log.warning("KB enrich failed for %s: %s", doc_id, e)
            failed += 1

    # Drop cache entries for deleted notes
    live = {doc_id for doc_id, _, _ in notes}
    for stale_id in [k for k in notes_map if k not in live]:
        del notes_map[stale_id]

    cache["notes"] = notes_map
    save_enrich_cache(root, cache)
    log.info(
        "KB enrich: enriched=%d skipped=%d failed=%d",
        enriched,
        skipped,
        failed,
    )
    return {
        "ok": True,
        "ollama": pname == "ollama",
        "provider": pname,
        "provider_ok": True,
        "enriched": enriched,
        "skipped": skipped,
        "failed": failed,
        "total": len(notes),
        "path": str(enrich_cache_path(root)),
    }
