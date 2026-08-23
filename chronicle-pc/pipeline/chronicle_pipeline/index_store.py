"""SQLite search index with optional sqlite-vec; embeddings as JSON blobs otherwise."""

from __future__ import annotations

import json
import logging
import sqlite3
from pathlib import Path
from typing import Any

from . import e2ee
from . import ollama as ollama_mod
from .config import ensure_config
from .entries import load_all_entries
from .kb_enrich import format_enrichment_prefix, load_enrich_cache
from .paths import content_hash, resolve_chronicle_dir

log = logging.getLogger("chronicle.index")

# Documented choice: prefer sqlite-vec when importable; else store embeddings
# as JSON text and compute cosine similarity in Python.
EMBED_DIM_HINT = 768  # nomic-embed-text
# Ornith long-context RAG: store/return full entries & notes (not 500-char snippets).
DOC_TEXT_STORE_LIMIT = 32000
SEARCH_TEXT_DEFAULT_LIMIT = 16000

SCOPE_KINDS: dict[str, frozenset[str] | None] = {
    "all": None,
    "journal": frozenset({"entry", "note"}),
    "kb": frozenset({"kb"}),
}


def _has_sqlite_vec() -> bool:
    try:
        import sqlite_vec  # noqa: F401

        return True
    except ImportError:
        return False


def index_db_path(root: Path) -> Path:
    return root / "index" / "chronicle.sqlite"


def _connect(root: Path) -> sqlite3.Connection:
    path = index_db_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    if _has_sqlite_vec():
        try:
            import sqlite_vec

            conn.enable_load_extension(True)
            sqlite_vec.load(conn)
            conn.enable_load_extension(False)
        except Exception as e:  # noqa: BLE001
            log.warning("sqlite-vec load failed, using JSON embeddings: %s", e)
    return conn


def _init_schema(conn: sqlite3.Connection, *, use_vec: bool) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS documents (
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            path TEXT,
            text TEXT NOT NULL,
            content_hash TEXT NOT NULL,
            embed_model TEXT,
            embedding_json TEXT,
            updated_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_documents_kind ON documents(kind);
        """
    )
    if use_vec:
        # Best-effort vec0 virtual table; ignore if already exists / unsupported
        try:
            conn.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS vec_documents USING vec0(
                    id TEXT PRIMARY KEY,
                    embedding FLOAT[768]
                );
                """
            )
        except sqlite3.Error as e:
            log.warning("Could not create vec0 table: %s", e)
    conn.commit()


def _upsert_doc(
    conn: sqlite3.Connection,
    *,
    doc_id: str,
    kind: str,
    path: str,
    text: str,
    embed_model: str,
    existing: dict[str, Any],
    force: bool,
    use_vec: bool,
    hash_source: str | None = None,
) -> str:
    """Return 'upserted' | 'skipped'.

    ``hash_source`` overrides the content used for skip-detection hashing
    (e.g. raw note + enrichment fingerprint) while ``text`` is what is stored
    and embedded.
    """
    ch = content_hash(hash_source if hash_source is not None else text)
    prev = existing.get(doc_id)
    if (
        not force
        and prev
        and prev["content_hash"] == ch
        and prev["embed_model"] == embed_model
    ):
        return "skipped"

    emb: list[float] = []
    if ollama_mod.ollama_reachable():
        emb = ollama_mod.try_embed(text[:2000], model=embed_model)
    # Preserve prior embedding when content changed but embed failed / unavailable.
    if not emb and prev and prev["embedding_json"] and prev["embedding_json"] != "[]":
        emb_json = prev["embedding_json"]
        try:
            emb = json.loads(emb_json)
            if not isinstance(emb, list):
                emb = []
                emb_json = "[]"
        except json.JSONDecodeError:
            emb = []
            emb_json = "[]"
        else:
            log.info("Preserved prior embedding for %s (new embed empty)", doc_id)
    else:
        emb_json = json.dumps(emb) if emb else "[]"

    conn.execute(
        """
        INSERT OR REPLACE INTO documents
        (id, kind, path, text, content_hash, embed_model, embedding_json, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
        """,
        (doc_id, kind, path, text[:DOC_TEXT_STORE_LIMIT], ch, embed_model, emb_json),
    )
    if use_vec and emb:
        try:
            conn.execute("DELETE FROM vec_documents WHERE id = ?", (doc_id,))
            conn.execute(
                "INSERT INTO vec_documents(id, embedding) VALUES (?, ?)",
                (doc_id, json.dumps(emb)),
            )
        except sqlite3.Error as ex:
            log.debug("vec insert skipped: %s", ex)
    return "upserted"


def _collect_note_docs(root: Path) -> list[tuple[str, str, str, str]]:
    """Return list of (doc_id, kind, rel_path, text) for journal + derived + knowledge."""
    from . import path_map
    from .journal import extract_block, list_fenced_ids
    from .vault_paths import DERIVED_DIR, JOURNAL_DIR

    docs: list[tuple[str, str, str, str]] = []

    # File-once journal: index per-entry fence prose (preferred RAG for filed entries)
    journal_root = root / JOURNAL_DIR
    if journal_root.is_dir():
        for path in sorted(journal_root.rglob("*.md")):
            if path.name.startswith(".") or ".sync-conflict" in path.name:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            rel = str(path.relative_to(root)).replace("\\", "/")
            for eid in list_fenced_ids(text):
                body = extract_block(text, eid)
                if body and body.strip():
                    docs.append((f"journal:{eid}", "note", rel, body.strip()))
            # Also keep whole-day doc for browsing
            docs.append((f"note:{rel}", "note", rel, text))

    # Derived rollups / topics / daily chrome
    derived_root = root / DERIVED_DIR
    if derived_root.is_dir():
        for path in sorted(derived_root.rglob("*.md")):
            if path.name.startswith(".") or ".sync-conflict" in path.name:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            rel = str(path.relative_to(root)).replace("\\", "/")
            docs.append((f"note:{rel}", "note", rel, text))

    # Legacy notes/ (dual-read during cutover)
    notes_root = root / "notes"
    if notes_root.is_dir():
        for path in sorted(notes_root.rglob("*.md")):
            if path.name.startswith(".") or ".sync-conflict" in path.name:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            rel = str(path.relative_to(root)).replace("\\", "/")
            docs.append((f"note:{rel}", "note", rel, text))

    # PARA preferred (legacy kb/notes dual-read retired)
    for rel, path in path_map.iter_knowledge_md(root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        docs.append((rel, "kb", rel, text))

    return docs


def _entry_index_text(root: Path, e, journal_bodies: dict[str, str]) -> tuple[str, str]:
    """
    After filed, prefer MD block prose for RAG; JSON contributes metadata.
    Returns (stored_text, path_hint).
    """
    meta = f"{e.type} {' '.join(e.tags)}".strip()
    prose = journal_bodies.get(e.id)
    if prose and getattr(e, "filed", False):
        text = f"{meta}\n{prose}".strip()
        path = getattr(e, "filed_path", None) or f"40-Journal/.../{e.id}"
    else:
        text = f"{meta}\n{e.text or ''}".strip()
        path = f"_capture/entries/.../{e.id}.json"
    return text, path


def run_index(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
    force: bool = False,
) -> dict:
    root = resolve_chronicle_dir(chronicle_dir)
    cfg = ensure_config(root)
    embed_model = cfg.models.embed
    use_vec = _has_sqlite_vec()
    mode = "sqlite-vec" if use_vec else "sqlite+json-cosine"

    entries = load_all_entries(root, fallback_tz=cfg.timezone)
    note_docs = _collect_note_docs(root)
    enrich_cache = load_enrich_cache(root)
    enrich_notes = enrich_cache.get("notes") if isinstance(enrich_cache, dict) else {}
    if not isinstance(enrich_notes, dict):
        enrich_notes = {}

    # Map entry id → journal prose from note_docs
    journal_bodies: dict[str, str] = {}
    for doc_id, kind, _rel, text in note_docs:
        if kind == "note" and doc_id.startswith("journal:"):
            journal_bodies[doc_id[len("journal:") :]] = text

    if dry_run:
        log.info(
            "[dry-run] would index %d entries + %d notes/kb (mode=%s)",
            len(entries),
            len(note_docs),
            mode,
        )
        return {
            "mode": mode,
            "would_index": len(entries) + len(note_docs),
            "dry_run": True,
        }

    conn = _connect(root)
    _init_schema(conn, use_vec=use_vec)
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)",
        ("embed_model", embed_model),
    )
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)",
        ("index_mode", mode),
    )
    # Record embedding dim from a sample when available (default hint otherwise).
    sample_dim = EMBED_DIM_HINT
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)",
        ("embed_dim", str(sample_dim)),
    )
    conn.commit()

    existing = {
        row["id"]: row
        for row in conn.execute(
            "SELECT id, content_hash, embed_model, embedding_json FROM documents"
        )
    }

    live_ids: set[str] = set()
    upserted = 0
    skipped = 0

    for e in entries:
        if e2ee.entry_locked(e, root):
            # Locked ciphertext must not become an embeddable document.
            skipped += 1
            live_ids.add(e.id)
            continue
        text, path_hint = _entry_index_text(root, e, journal_bodies)
        live_ids.add(e.id)
        result = _upsert_doc(
            conn,
            doc_id=e.id,
            kind="entry",
            path=path_hint,
            text=text,
            embed_model=embed_model,
            existing=existing,
            force=force,
            use_vec=use_vec,
        )
        if result == "upserted":
            upserted += 1
        else:
            skipped += 1

    for doc_id, kind, rel, text in note_docs:
        # Prefer entry:{id} docs over duplicate journal:{id} for same prose
        if doc_id.startswith("journal:"):
            continue
        live_ids.add(doc_id)
        index_text = text
        hash_source: str | None = None
        if kind == "kb":
            entry = enrich_notes.get(doc_id)
            entry_dict = entry if isinstance(entry, dict) else None
            prefix = format_enrichment_prefix(entry_dict)
            if prefix:
                index_text = prefix + "\n\n" + text
            enrich_fp = ""
            if entry_dict is not None:
                enrich_fp = str(entry_dict.get("content_hash") or "") + "|" + prefix
            # Skip detection: raw note + enrichment fingerprint
            hash_source = text + "\n" + enrich_fp
        result = _upsert_doc(
            conn,
            doc_id=doc_id,
            kind=kind,
            path=rel,
            text=index_text,
            embed_model=embed_model,
            existing=existing,
            force=force,
            use_vec=use_vec,
            hash_source=hash_source,
        )
        if result == "upserted":
            upserted += 1
        else:
            skipped += 1

    # Stale-document deletion: remove index rows whose source vanished
    stale = 0
    for doc_id in list(existing.keys()):
        if doc_id in live_ids:
            continue
        conn.execute("DELETE FROM documents WHERE id = ?", (doc_id,))
        if use_vec:
            try:
                conn.execute("DELETE FROM vec_documents WHERE id = ?", (doc_id,))
            except sqlite3.Error:
                pass
        stale += 1

    conn.commit()
    conn.close()
    log.info(
        "Index %s: upserted=%d skipped=%d stale_deleted=%d",
        mode,
        upserted,
        skipped,
        stale,
    )
    return {
        "mode": mode,
        "upserted": upserted,
        "skipped": skipped,
        "stale_deleted": stale,
        "dry_run": False,
        "path": str(index_db_path(root)),
    }


def kinds_for_scope(scope: str | None) -> frozenset[str] | None:
    """Map recall scope to index kinds; None means all kinds."""
    key = (scope or "all").strip().lower()
    if key not in SCOPE_KINDS:
        key = "all"
    return SCOPE_KINDS[key]


def _vec_table_ready(conn: sqlite3.Connection) -> bool:
    try:
        row = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='vec_documents'"
        ).fetchone()
        return row is not None
    except sqlite3.Error:
        return False


def _embed_dim(conn: sqlite3.Connection) -> int:
    try:
        row = conn.execute(
            "SELECT value FROM meta WHERE key='embed_dim'"
        ).fetchone()
        if row and row["value"]:
            return max(1, int(row["value"]))
    except (sqlite3.Error, TypeError, ValueError):
        pass
    return EMBED_DIM_HINT


def search(
    chronicle_dir: Path | str | None,
    query: str,
    *,
    top_k: int = 8,
    kinds: frozenset[str] | set[str] | list[str] | None = None,
    scope: str | None = None,
    text_limit: int | None = SEARCH_TEXT_DEFAULT_LIMIT,
    ids: set[str] | frozenset[str] | list[str] | None = None,
) -> list[dict[str, Any]]:
    root = resolve_chronicle_dir(chronicle_dir)
    cfg = ensure_config(root)
    db = index_db_path(root)
    if not db.is_file():
        return []

    kind_filter = frozenset(kinds) if kinds is not None else kinds_for_scope(scope)
    id_filter = frozenset(ids) if ids is not None else None
    limit = None if text_limit is None else max(0, int(text_limit))

    def _row_hit(row: Any, score: float) -> dict[str, Any]:
        text = row["text"] or ""
        if limit is not None:
            text = text[:limit]
        return {
            "id": row["id"],
            "kind": row["kind"],
            "path": row["path"],
            "text": text,
            "score": score,
        }

    def _keyword_boost(text: str, q: str) -> float:
        text_l = (text or "").lower()
        boost = 0.0
        for tok in q.lower().split():
            if tok and tok in text_l:
                boost += 0.05
        return boost

    conn = _connect(root)
    try:
        q_emb = ollama_mod.try_embed(query, model=cfg.models.embed) if ollama_mod.ollama_reachable() else []

        # Prefer sqlite-vec KNN when the extension and virtual table are available.
        if q_emb and _vec_table_ready(conn):
            try:
                overfetch = max(top_k * 4, top_k)
                knn = list(
                    conn.execute(
                        """
                        SELECT d.id, d.kind, d.path, d.text, v.distance
                        FROM vec_documents v
                        JOIN documents d ON d.id = v.id
                        WHERE v.embedding MATCH ?
                          AND k = ?
                        ORDER BY v.distance
                        """,
                        (json.dumps(q_emb), overfetch),
                    )
                )
                scored_vec: list[tuple[float, dict[str, Any]]] = []
                for row in knn:
                    if kind_filter is not None and row["kind"] not in kind_filter:
                        continue
                    if id_filter is not None and row["id"] not in id_filter:
                        continue
                    # sqlite-vec distance is lower-is-better; map to a similarity-like score.
                    distance = float(row["distance"] if row["distance"] is not None else 1.0)
                    score = max(0.0, 1.0 - distance) + _keyword_boost(row["text"] or "", query)
                    scored_vec.append((score, _row_hit(row, score)))
                if scored_vec:
                    scored_vec.sort(key=lambda x: -x[0])
                    return [s for _, s in scored_vec[:top_k]]
            except sqlite3.Error as e:
                log.debug("sqlite-vec KNN failed, falling back to cosine: %s", e)

        rows = list(
            conn.execute(
                "SELECT id, kind, path, text, embedding_json FROM documents"
            )
        )
    finally:
        conn.close()

    if kind_filter is not None:
        rows = [r for r in rows if r["kind"] in kind_filter]
    if id_filter is not None:
        rows = [r for r in rows if r["id"] in id_filter]

    if q_emb:
        scored: list[tuple[float, dict[str, Any]]] = []
        for row in rows:
            try:
                emb = json.loads(row["embedding_json"] or "[]")
            except json.JSONDecodeError:
                emb = []
            score = ollama_mod.cosine(q_emb, emb) if emb else 0.0
            score += _keyword_boost(row["text"] or "", query)
            scored.append((score, _row_hit(row, score)))
        scored.sort(key=lambda x: -x[0])
        return [s for _, s in scored[:top_k]]

    # Keyword-only fallback
    q_tokens = [t for t in query.lower().split() if t]
    scored_kw: list[tuple[float, dict[str, Any]]] = []
    for row in rows:
        text_l = (row["text"] or "").lower()
        score = sum(1 for t in q_tokens if t in text_l)
        if score:
            scored_kw.append((float(score), _row_hit(row, float(score))))
    scored_kw.sort(key=lambda x: -x[0])
    return [s for _, s in scored_kw[:top_k]]


def get_documents_by_ids(
    chronicle_dir: Path | str | None,
    doc_ids: set[str] | frozenset[str] | list[str],
    *,
    text_limit: int | None = SEARCH_TEXT_DEFAULT_LIMIT,
) -> list[dict[str, Any]]:
    """Fetch indexed documents by id (full stored text, capped by text_limit)."""
    root = resolve_chronicle_dir(chronicle_dir)
    db = index_db_path(root)
    if not db.is_file() or not doc_ids:
        return []
    wanted = list(dict.fromkeys(str(i) for i in doc_ids if i))
    if not wanted:
        return []
    limit = None if text_limit is None else max(0, int(text_limit))
    conn = _connect(root)
    try:
        placeholders = ",".join("?" * len(wanted))
        rows = list(
            conn.execute(
                f"SELECT id, kind, path, text FROM documents WHERE id IN ({placeholders})",
                wanted,
            )
        )
    finally:
        conn.close()
    by_id = {r["id"]: r for r in rows}
    out: list[dict[str, Any]] = []
    for doc_id in wanted:
        row = by_id.get(doc_id)
        if not row:
            continue
        text = row["text"] or ""
        if limit is not None:
            text = text[:limit]
        out.append(
            {
                "id": row["id"],
                "kind": row["kind"],
                "path": row["path"],
                "text": text,
                "score": 1.0,
            }
        )
    return out


def purge_locked_entries(chronicle_dir: Path | str | None) -> int:
    """Drop index documents for entries that are currently E2EE-locked.

    Closing the stale-index leak: an entry indexed during an unlocked window
    kept its plaintext snippet searchable after the vault locked (the sweep in
    ``run_index`` treats known ids as live). Called on every lock transition
    (CLI ``chronicle lock``, ``POST /auth/e2ee/lock``) so search/recall stay
    fail-closed against CONTRACT v1.11. Returns rows removed.
    """
    from . import e2ee as e2ee_mod
    from .entries import iter_entry_paths
    from .paths import read_json, resolve_chronicle_dir

    root = resolve_chronicle_dir(chronicle_dir)
    db = index_db_path(root)
    if not db.is_file():
        return 0

    def _is_encrypted(raw_path: Path) -> bool:
        try:
            raw = read_json(raw_path)
        except Exception:  # noqa: BLE001 — unreadable file can't prove lock
            return False
        return isinstance(raw.get("text_enc"), dict)

    locked_ids = {
        p.stem
        for p in iter_entry_paths(root)
        if _is_encrypted(p) and not e2ee_mod.is_unlocked(root)
    }
    if not locked_ids:
        return 0

    conn = _connect(root)
    try:
        placeholders = ",".join("?" * len(locked_ids))
        cur = conn.execute(
            f"DELETE FROM documents WHERE id IN ({placeholders})",
            sorted(locked_ids),
        )
        removed = cur.rowcount
        if _vec_table_ready(conn):
            for doc_id in locked_ids:
                conn.execute("DELETE FROM vec_documents WHERE id = ?", (doc_id,))
        conn.commit()
    finally:
        conn.close()
    return removed
