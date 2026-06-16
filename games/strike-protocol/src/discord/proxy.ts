// Pure, SDK-free helpers for the Discord Activity integration. Kept separate from
// bootstrap.ts (which touches the real @discord/embedded-app-sdk + DOM) so the
// branching logic — "are we in Discord?", which URL to POST the token exchange to —
// is unit-testable with plain inputs and no browser/SDK mocks.
//
// Discord embeds Activities under https://<CLIENT_ID>.discordsays.com/ and proxies
// every outbound request through that origin. Requests to a configured URL Mapping
// prefix must be sent to `/.proxy/<prefix>/...` (the proxy strips `/.proxy/<prefix>`
// and forwards the rest to the mapped external host). Outside Discord (local dev /
// normal web) we POST to a same-origin token route on the TanStack Start server.
//
// Strike Protocol's multiplayer is WebRTC peer-to-peer (peerjs), so there is no
// self-hosted game server / WebSocket to proxy — only the token-exchange route
// needs a `/.proxy/api` mapping. That is the single difference from crazy-nia,
// whose Colyseus server also needed a `/colyseus` mapping.

// Same-origin token route served by the TanStack Start server (src/routes/api/token.ts).
// Inside Discord it is reached through the proxy as `/.proxy/api/token`; the portal
// URL Mapping `/api` -> <server host> makes the proxied path resolve to this route.
export const LOCAL_TOKEN_PATH = "/api/token";
export const PROXY_TOKEN_PATH = "/.proxy/api/token";

// Minimal shape of the bits of `window.location` we read — lets tests pass plain
// objects instead of constructing a full Location.
export interface LocationLike {
  search: string; // '?frame_id=...'
}

// Discord injects `frame_id` (and friends) into the iframe URL. Its presence is the
// canonical "we are embedded in Discord" signal — outside Discord it is absent.
export function isInDiscord(search: string): boolean {
  return new URLSearchParams(search).has("frame_id");
}

// Where the client POSTs the OAuth `code` for token exchange.
// In Discord: through the proxy to the mapped token host. Otherwise: same-origin.
export function tokenEndpoint(inDiscord: boolean): string {
  return inDiscord ? PROXY_TOKEN_PATH : LOCAL_TOKEN_PATH;
}
