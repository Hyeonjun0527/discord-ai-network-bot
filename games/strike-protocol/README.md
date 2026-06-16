# Strike Protocol

Tactical browser FPS — round-based combat against AI bots (bomb defuse / elimination)
or online deathmatch with friends over WebRTC via a single invite link. Buy phase,
economy, recoil, headshots, radar — all running in the browser, no downloads.

Rendered with `three`, UI built on React + shadcn/ui, served by TanStack Start.

## Run

```bash
npm install
npm run dev      # vite dev server on http://localhost:5173
```

Open `/` for the briefing/landing page and `/play` for the game (desktop + mouse;
click the arena to lock the cursor).

## Scripts

| Script | What it does |
| --- | --- |
| `npm run dev` | Vite dev server (SSR) |
| `npm run build` | Production build — emits a Cloudflare Worker server bundle (`dist/server/server.js`) + hashed client assets (`dist/client`) |
| `npm run preview` | Preview the production client build |
| `npm run lint` | ESLint |
| `npm run format` | Prettier |

## Stack

- **TanStack Start** (React 19 + Vite 7 + TypeScript) — file-based routing, SSR.
- **three** — 3D rendering / game engine (`src/lib/game/engine.ts`).
- **shadcn/ui** (Radix + Tailwind CSS v4) — UI primitives in `src/components/ui`.
- **peerjs** — WebRTC peer connections for online multiplayer rooms.

The production build targets **Cloudflare Workers** (server bundle is
`export default { fetch }`, all deps bundled in). `npm run dev` runs the same code
on a local Node SSR server.

## Layout

```
src/
  routes/        file-based routes (index = landing, play = game)
  lib/game/      three.js FPS engine
  components/ui/ shadcn/ui primitives
  server.ts      Worker fetch entry + SSR error normalization
  start.ts       request middleware
public/          GLB models, textures, SFX, key art
```

## Notes / Provenance

Source provided by Higgsfield (Strike Protocol) and integrated here for remixing.
Integrated as a standalone project under `games/` — it does not touch the root
build, CI, or the central-server / provider-agent stacks.

Package manager: **npm** (`package-lock.json`). The upstream `bun.lock` /
`bunfig.toml` were dropped in favor of npm.

Local fix vs. upstream: `vite.config.ts` applies `ssr.noExternal: true` only for
`vite build` (the Worker bundle needs deps inlined). During `vite dev` that option
is left off so Node resolves CJS deps (e.g. React) externally — otherwise the dev
SSR runner inlines React's CJS entry and throws `module is not defined`. Game logic
is unchanged.
