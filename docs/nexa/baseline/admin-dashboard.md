# NEXA admin dashboard baseline (T021)

Status: `REVIEW` candidate; depends on T020, which is still dependency-gated `REVIEW` through the T017 human security gate.  
Audit date: 2026-06-20.  
Scope: runtime-served central static dashboard, the adjacent Vite admin-console prototype, dashboard routing/security, API dependencies, build ownership, and current automated test coverage.

## Runtime surface

The production/runtime dashboard is the checked-in static SPA under `central-server/src/main/resources/static/admin/dashboard/`:

| File | Current role | Build rule |
| --- | --- | --- |
| `index.html` | Shell, pages, tabs, forms, IDs used by `app.js`, token input, footer, and script tags. | Edited directly; served from the Spring Boot jar static resources. |
| `app.js` | Same-origin API client, dashboard state loading, entity-first router, rendering, write actions, and 5s pool polling. | Edited directly; no bundler/minifier/transpile step. |
| `ux.js` | Progressive UX layer: mobile nav sync, context bar sync, folds operational controls, and confirms danger buttons. | Edited directly; loaded after `app.js`. |
| `style.css` | Layout, dark theme, cards, tabs, responsive rules, and operational folding styles. | Edited directly. |

`index.html` links only `/admin/dashboard/style.css` and loads only `/admin/dashboard/app.js` plus `/admin/dashboard/ux.js`; therefore the central-server static directory is the runtime source for this screen. Do not create a second copy of these files for a shadow or policy screen.

A separate React/Vite app exists in `admin-console/`:

- Source: `admin-console/src/App.tsx`, `admin-console/src/api.ts`, `admin-console/src/styles.css`.
- Build script: `pnpm --dir admin-console build` (`tsc -p tsconfig.json && vite build`).
- Vite base: `/admin/dashboard/` by default (`VITE_APP_BASE` override available).
- `admin-console/dist/` is ignored by `admin-console/.gitignore` and is not listed by `git ls-files`; it is not the central-server runtime asset source today.

Until a migration deliberately promotes `admin-console/` as the dashboard SSOT and wires its generated assets into central-server, treat it as a separate console/prototype surface, not as the source for the runtime static dashboard.

## Routing and page model

| Route / selector | Owner | Behavior |
| --- | --- | --- |
| `/admin/dashboard` | `CorsConfig.addViewControllers` | Forwards to `/admin/dashboard/index.html`. |
| `/admin/dashboard/` | `CorsConfig.addViewControllers` | Forwards to `/admin/dashboard/index.html`. |
| `/admin/dashboard/index.html` | Spring Boot static resource handling | Serves the dashboard shell. |
| `/admin/dashboard/app.js`, `/admin/dashboard/ux.js`, `/admin/dashboard/style.css` | Spring Boot static resource handling | Serves static assets directly. |
| `#overview` | `app.js showPage` | Network observation summary. Default route. |
| `#server` | `app.js showPage` + `showServerTab` | Server-level details with sub-tabs `summary`, `channels`, `providers`, `presets`, `policy`. |
| `#channel` | `app.js showPage` + `showChannelTab` | Channel-level details with sub-tabs `channel-ai`, `model`, `rag`, `quality`, `advanced`. |

The UI intentionally follows an entity-first path: overview -> server -> channel. Channel IDs are synchronized into all channel-scoped controls after selecting/clicking a real channel; future screens should preserve this flow instead of adding disconnected ID-entry-only copies.

## Authentication and security boundary

The dashboard has two independent layers:

1. **Static page access**
   - When `central.oauth.enabled=true`, `SecurityConfig` leaves `/admin/dashboard/**` outside the public allowlist, so Spring Security requires an authenticated Discord OAuth session for the page and static assets.
   - When `central.oauth.enabled=false` (default/local), Spring Security permits requests, but admin data/write APIs are still protected by `AiNetworkApiSecurityFilter`.
2. **Admin API access**
   - `AiNetworkApiSecurityFilter` accepts `X-Dashboard-Admin-Token` only when it matches `central.dashboard.admin-token` / `CENTRAL_DASHBOARD_ADMIN_TOKEN`.
   - OAuth sessions are admin-capable only if the Discord user ID is present in `central.dashboard.admin-user-ids`; an OAuth login without allowlist membership fails closed for admin API access.
   - The static dashboard stores the token in `sessionStorage` under `nexa.dashboardAdminToken`; it is not persisted to localStorage.

Static `fetch()` calls are same-origin by default, so OAuth cookies are sent for `/admin/dashboard/` -> `/api/**` calls. The Vite `admin-console` client is different: it supports an optional `baseUrl`, stores token/base/guild in localStorage, and calls `fetch(..., { credentials: "include" })` for cross-origin-capable development.

## API dependency map

`app.js` depends on these endpoint families. Additions must be backed by controller tests and by dashboard serving/drift tests before adding new UI controls.

| UI area | Representative endpoints |
| --- | --- |
| Global status | `GET /api/metrics/pool`, `GET /api/metrics/pool/{guildId}`, `GET /api/me`. |
| Server picker and classic dashboard | `GET /api/dashboard/guilds`, `GET /api/dashboard/{guildId}/channels`, `GET /api/dashboard/{guildId}/overview`, `GET /api/dashboard/{guildId}/usage-trend?days=7`, `GET /api/dashboard/{guildId}/requests`. |
| Classic policy writes | `POST /api/dashboard/{guildId}/welcome`, `POST /api/dashboard/{guildId}/auto-approve`. |
| AI network overview | `GET /api/ai-network/{guildId}/dashboard?audience=admin`, `GET /api/ai-network/{guildId}/overview?refresh=true`, `GET /api/ai-network/{guildId}/launch-checklist?audience=admin`, `GET /api/ai-network/{guildId}/channel-usage`, `GET /api/ai-network/{guildId}/users?limit=20`, `GET /api/ai-network/{guildId}/provider-history`, `GET /api/ai-network/{guildId}/providers?audience=admin`, `GET /api/ai-network/{guildId}/model-map`. |
| Channel AI wizard | `GET /api/ai-network/channel-ai/wizard/options`, `POST /api/ai-network/channel-ai/wizard/draft`, `POST /api/ai-network/channel-ai/{guildId}/{channelId}/wizard`. |
| Model/routing | `GET/POST /api/ai-network/channel-ai-routing/{guildId}/{channelId}`, `GET /api/ai-network/channel-ai-routing/{guildId}/{channelId}/model-candidates`, `GET /api/ai-network/channel-ai-routing/{guildId}/{channelId}/model-choice`. |
| Knowledge/RAG | `GET /api/ai-network/knowledge/{guildId}/readiness`, `quality-summary`, `indexing-operations`, `index-jobs`, `search`; `POST /spaces`, `/spaces/{spaceId}/sources`, `/spaces/{spaceId}/index-jobs`, `/index-jobs/{jobId}/complete`, `/eval`. |
| Quality/safety | `GET /api/ai-network/quality/{guildId}/summary`, `review-summary`, `models`, `/{channelId}/summary`; `POST /feedback`, `/feedback/{feedbackId}/review`; `GET /api/ai-network/safety/{guildId}/overload-alerts?audience=admin`, `/execution-plan`; `POST /providers/{providerId}/overload?audience=admin`. |
| Presets/moderation | `GET /api/ai-network/presets/catalog`, `/guilds/{guildId}`, `/moderation/summary`, `/reports/open`; `POST/PUT/DELETE /api/ai-network/presets/**`, publish/unlist/republish/like/report/import-preview/import/review. |
| Multi-response advanced | `GET /api/ai-network/multi-response/{guildId}/operations-summary`, `/runs`, `/decision-summary`, `/recommendation`; `POST /policy`, `/pseudo-stream-plan`; `GET /api/ai-network/features`. |
| License funnel | `GET /api/ai-network/license/funnel?audience=admin`. |

The smaller Vite `admin-console/src/api.ts` currently consumes only `/api/dashboard/guilds`, `/api/dashboard/{guildId}/overview`, `/api/ai-network/{guildId}/dashboard`, `/api/dashboard/{guildId}/requests`, and `/api/dashboard/{guildId}/usage-trend?days=14`.

## Build, deployment, and SSOT rule

- Runtime static dashboard changes are part of the central-server jar; there is no generated asset step for `central-server/src/main/resources/static/admin/dashboard/**`.
- Central verification is `./scripts/nexa-verify.sh central`, which runs the Gradle build/test/ktlint/Kover gate for central-server.
- Do **not** copy `index.html`/`app.js`/`ux.js`/`style.css` into a new shadow or policy screen. For NEXA shadow/policy additions, either:
  1. extend the existing entity-first dashboard files in place; or
  2. first introduce an explicit dashboard frontend SSOT (for example, promote `admin-console/` and wire its build output into central-server), then migrate existing runtime coverage with no duplicate UX files.
- If API routes are renamed, update the dashboard JS and backend controllers/tests in the same change. For sensitive/admin routes, update `AiNetworkApiSecurityFilter` and its tests at the same time.

## Current automated test coverage

| Coverage | Current guard |
| --- | --- |
| Static asset serving | `DashboardServingTest` checks `/admin/dashboard/index.html`, `/admin/dashboard/app.js`, `/admin/dashboard/style.css`, and directory forwards `/admin/dashboard`, `/admin/dashboard/`. |
| UI structure smoke | `DashboardServingTest` asserts key IDs, section markers, token header use, sessionStorage token handling, API string dependencies, owner-observation UI, and folded operational controls. |
| HTML/JS ID drift | `DashboardElementIdDriftTest` fails when literal `$("id")` references in `app.js` are missing from `index.html`. |
| Admin API protection | `AiNetworkApiSecurityFilterTest` verifies admin reads/writes are rejected without a dashboard token and allowed with `X-Dashboard-Admin-Token`. |
| Controller behavior | `DashboardControllerTest`, `AiNetworkDashboardControllerTest`, `DashboardWriteControllerTest`, and related network/quality/preset tests exercise backend DTO and policy behavior. |

Known testability boundary: there is no dedicated browser/Playwright test for the central static dashboard today, and `admin-console/` is not covered by the T021 required verification commands. The current central tests are server-side asset/API/structure guards, not full browser interaction coverage.

## Verification commands

- `./scripts/nexa-verify.sh docs` — validate the NEXA task graph, package graph, conversation fixtures, docs links, and repository diff policy.
- `./scripts/nexa-verify.sh central` — run central-server Gradle verification, including dashboard serving/security/ID drift tests.

## Verification result

- `./scripts/nexa-verify.sh docs` — passed: task graph valid (`500 tasks, 20 programs, DAG acyclic`), package graph snapshot OK, conversation fixtures OK, `134` docs checked with no broken relative links, and `git diff --check` clean.
- `./scripts/nexa-verify.sh central` — passed: Gradle `BUILD SUCCESSFUL` with central build/check/Kover/ktlint gate.

Because T021 depends on T020 and T020 is still dependency-gated `REVIEW`, this task should remain `REVIEW` after automated verification. It can move to `VERIFIED` only after upstream gates are approved or the task graph dependency is revised.
