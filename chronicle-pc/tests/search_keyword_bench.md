# Keyword search baseline (no embeddings)

Date: 2026-10-13

One sample from a single measured `search` call after one warmup call. Not a minimum-of-N.

Command:

```
/Users/bharath/Code/apps/Chronicle/chronicle-pc/.venv/bin/python -m pytest chronicle-pc/tests/test_search_keyword_bench.py --rootdir=chronicle-pc -q -s
```

Printed line:

```
keyword_bench rows_read=1004 wall_s=0.002426 row_cap=none docs=1004
```

| field | value |
| --- | --- |
| rows_read | 1004 |
| wall_s | 0.002426 |
| docs | 1004 |
| row_cap | none |

`rows_read` is the number of rows the keyword path pulled from `SELECT id, kind, path, text, embedding_json FROM documents` (no `WHERE`, no `LIMIT`). It equals the indexed fixture (`docs`). The query embedding was forced empty (`ollama_reachable` false, `try_embed` returns `[]`), so vector KNN was skipped.

The fixture is 1000 short, distinct `30-Knowledge` notes plus the 4 entry documents the shared `chronicle_dir` fixture already contains. Each synthetic note is one short line, so the scan still reads the `text` column without using 32000 characters times 1000. Production rows can be up to the store text limit `DOC_TEXT_STORE_LIMIT` (32000) in `chronicle-pc/pipeline/chronicle_pipeline/index_store.py`. This sample does not size that case.

There is no FTS table and no row cap on this path. A later FTS change should quote this line.
