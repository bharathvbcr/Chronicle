# Chronicle PC frontend (React + Vite)

Rose/glass SPA for Timeline, Notes, Brain, and Settings. Talks to `chronicle serve` over REST (CONTRACT v1.10).

**Notes sections:** Knowledge Base (`30-Knowledge/`), Notes (`00-Inbox/` / `10-Work/` / `20-Personal/` / `90-Archive/`), Journal (`40-Journal/` + derived). `Home.md` opens Notes; cross-section wikilinks switch tab + open. Creates apply convention-complete frontmatter. Legacy `kb/notes/` paths are not openable (cutover complete).

## Develop

```bash
# terminal 1 — API
cd chronicle-pc && source .venv/bin/activate && chronicle serve --lan

# terminal 2 — Vite (proxies API to :8765)
cd chronicle-pc/frontend && npm install && npm run dev
```

Open http://127.0.0.1:5173/

## Production build (served by FastAPI)

```bash
cd chronicle-pc/frontend && npm run build
```

Output lands in `frontend/dist/`. With that present, `chronicle serve` serves the SPA at `/` (legacy dashboard remains at `/legacy` and in `dashboard/dashboard.html`).

## Design tokens (Android parity)

Shared rose/glass tokens live in [`src/styles/tokens.css`](src/styles/tokens.css). Mirror these in Android `Color.kt` / type scale.
