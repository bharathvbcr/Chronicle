"""Baseline for keyword search when the query embedding is empty.

`search` then skips vector KNN and reads every document text. There is no FTS
and no SQL row cap. This records that scan; it does not change ranking.
"""

from __future__ import annotations

import math
import sqlite3
import time
from pathlib import Path
from unittest.mock import patch

from chronicle_pipeline import index_store

_DOC_COUNT = 1000
_TOKEN = "keywordbenchtoken"


def _is_unbounded_document_text_scan(sql: str) -> bool:
    compact = " ".join(sql.split()).lower()
    return (
        compact.startswith("select ")
        and " from documents" in compact
        and "text" in compact
        and " where " not in compact
        and " limit " not in compact
    )


class _CountingCursor:
    """Counts rows pulled from the keyword scan. `search` does not return that count."""

    def __init__(self, cursor: sqlite3.Cursor, sink: dict[str, int]) -> None:
        self._cursor = cursor
        self._sink = sink

    def __iter__(self):
        for row in self._cursor:
            self._sink["rows_read"] += 1
            yield row

    def fetchall(self):
        rows = self._cursor.fetchall()
        self._sink["rows_read"] += len(rows)
        return rows

    def fetchone(self):
        row = self._cursor.fetchone()
        if row is not None:
            self._sink["rows_read"] += 1
        return row

    def __getattr__(self, name: str):
        return getattr(self._cursor, name)


class _CountingConnection:
    def __init__(self, conn: sqlite3.Connection, sink: dict[str, int]) -> None:
        self._conn = conn
        self._sink = sink

    def execute(self, sql, parameters=()):
        cursor = self._conn.execute(sql, parameters)
        if _is_unbounded_document_text_scan(sql):
            return _CountingCursor(cursor, self._sink)
        return cursor

    def __getattr__(self, name: str):
        return getattr(self._conn, name)


def test_keyword_scan_reads_every_indexed_document(chronicle_dir: Path) -> None:
    kb = chronicle_dir / "30-Knowledge" / "keyword-bench"
    kb.mkdir(parents=True)
    for i in range(_DOC_COUNT):
        (kb / f"doc-{i:04d}.md").write_text(
            f"{_TOKEN} doc-{i:04d} short distinct text\n",
            encoding="utf-8",
        )

    # search uses ollama_reachable() to decide q_emb, and try_embed only when that is true.
    offline = (
        patch(
            "chronicle_pipeline.index_store.ollama_mod.ollama_reachable",
            return_value=False,
        ),
        patch(
            "chronicle_pipeline.index_store.ollama_mod.try_embed",
            return_value=[],
        ),
    )
    with offline[0], offline[1]:
        index_store.run_index(chronicle_dir, force=True)
        counted = sqlite3.connect(str(index_store.index_db_path(chronicle_dir)))
        try:
            docs = int(counted.execute("SELECT COUNT(*) FROM documents").fetchone()[0])
        finally:
            counted.close()

        sink = {"rows_read": 0}
        original_connect = index_store._connect

        def counting_connect(root: Path) -> _CountingConnection:
            return _CountingConnection(original_connect(root), sink)

        with patch.object(index_store, "_connect", counting_connect):
            index_store.search(chronicle_dir, _TOKEN, top_k=8)
            sink["rows_read"] = 0
            started = time.perf_counter()
            hits = index_store.search(chronicle_dir, _TOKEN, top_k=8)
            wall_s = time.perf_counter() - started

    rows_read = sink["rows_read"]
    line = (
        f"keyword_bench rows_read={rows_read} wall_s={wall_s:.6f} "
        f"row_cap=none docs={docs}"
    )
    print(line, flush=True)

    assert docs >= _DOC_COUNT
    assert rows_read == docs
    assert math.isfinite(wall_s) and wall_s >= 0
    assert "row_cap=none" in line
    assert hits
    assert all(_TOKEN in (hit.get("text") or "") for hit in hits)
