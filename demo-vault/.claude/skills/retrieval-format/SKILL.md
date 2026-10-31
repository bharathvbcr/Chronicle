---
name: retrieval-format
description: Use whenever the user asks about stored information — find, summarize, remind, temporal queries. Knowledge via MD + index skim; journal/entities prefer serve search/recall. Do NOT use for statements providing new information (that is capture-workflow).
user-invocable: false
---

# Retrieval format

1. Read `_system/preferences.md` for answer style.
2. **Knowledge:** skim `_system/index.md` (rebuild via `chronicle rebuild-markdown-index`) → shortlist PARA notes (`00-Inbox` … `90-Archive`). PARA-only after dual-read cutover.
3. **Journal / entities:** when Mac `chronicle serve` is up, prefer `POST /search`, `/recall`, `/ask`, `/resume` over scraping `brain/` by hand.
4. **Respect SoT split:**
   - **Prose** = body inside `40-Journal/` `entry:<id>` fences (after filed)
   - **Structured** = `mood`/`tags`/`type`/`ts`/`media` in entry JSON forever
5. Temporal / upcoming → check `Upcoming.md` and source notes with 📅 dated checkboxes, then verify sources.
6. Lead with the answer; end with Sources (wikilinks / entry ids). Never fabricate vault contents.
