# Chronicle application UI

React / TypeScript / Vite application shared by the browser and Tauri desktop. It reads and writes the vault through REST APIs; it does not use the browser File System Access API.

## Build

From this directory:

```bash
npm ci
npm run build
```

The build runs TypeScript project checking followed by Vite and writes `dist/`. Both Rust and Python servers can serve this output at `/`; the legacy dashboard remains at `/legacy`. Building the Tauri shell does not build this directory automatically.

## Develop

Start the native server from the repo root:

```bash
export CHRONICLE_DIR="$HOME/Chronicle"
./chronicle-pc/server/target/release/chronicle serve --no-lan --port 8765
```

Then, from this directory, run `npm run dev`. [vite.config.ts](vite.config.ts) declares the development API proxies to port 8765. It is an explicit endpoint list, not a catch-all; when diagnosing a missing development-only API request, compare the path to that list. Served production assets use the server's same-origin API.

## UI ownership

- [App.tsx](src/App.tsx): routing and shared entry/search overlays.
- [views/](src/views/): Timeline, Notes/Knowledge/Journal, Brain, Settings.
- [api/client.ts](src/api/client.ts): API transport.
- [notes/](src/notes/): note routing, frontmatter, safe Markdown links, tree helpers.
- [brain/](src/brain/): graph interaction, node inspection, recall.
- [styles/tokens.css](src/styles/tokens.css): application theme tokens.

Notes groups editable areas under `00-Inbox/`, `10-Work/`, `20-Personal/`, and `90-Archive/`. Knowledge Base is `30-Knowledge/`. Journal uses `40-Journal/` and derived summaries; amendments go through the conflict-aware journal API. Legacy `kb/notes/` is retired.

## Checks

```bash
npm run lint
npm run test
npm run build
```

The product website is a different application: `src/pages/Chronicle.jsx` in the Portfolio repository. Do not redesign this vault UI when changing the public website. See [development ownership](../../docs/DEVELOPMENT.md#website-and-public-content).
