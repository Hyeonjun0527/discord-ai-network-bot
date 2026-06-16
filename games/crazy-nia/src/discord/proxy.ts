// Pure, SDK-free helpers for the Discord Activity integration. Kept separate from
// bootstrap.ts (which touches the real @discord/embedded-app-sdk + DOM) so the
// branching logic — "are we in Discord?", which URLs to hit — is unit-testable with
// plain inputs and no browser/SDK mocks.
//
// Discord embeds Activities under https://<CLIENT_ID>.discordsays.com/ and proxies
// every outbound request through that origin. Requests to a configured URL Mapping
// prefix must be sent to `/.proxy/<prefix>/...` (the proxy strips `/.proxy/<prefix>`
// and forwards the rest to the mapped external host). Outside Discord (local dev /
// normal web) we hit the game server directly and POST to a same-origin token route.

// URL Mapping prefixes registered in the Discord Developer Portal. The client uses
// the `/.proxy/<prefix>` form; the portal maps each prefix to an external host.
export const PROXY_TOKEN_PATH = '/.proxy/api/token';
export const PROXY_COLYSEUS_PREFIX = '/.proxy/colyseus';

// Same-origin token route outside Discord (served by the game server, see server).
export const LOCAL_TOKEN_PATH = '/api/token';

// Minimal shape of the bits of `window.location` we read — lets tests pass plain
// objects instead of constructing a full Location.
export interface LocationLike {
  protocol: string; // 'https:' | 'http:'
  host: string; // 'abc.discordsays.com' | '127.0.0.1:5173'
  search: string; // '?frame_id=...'
}

// Discord injects `frame_id` (and friends) into the iframe URL. Its presence is the
// canonical "we are embedded in Discord" signal — outside Discord it is absent.
export function isInDiscord(search: string): boolean {
  return new URLSearchParams(search).has('frame_id');
}

// Where the client POSTs the OAuth `code` for token exchange.
// In Discord: through the proxy to the mapped token host. Otherwise: same-origin.
export function tokenEndpoint(inDiscord: boolean): string {
  return inDiscord ? PROXY_TOKEN_PATH : LOCAL_TOKEN_PATH;
}

// WebSocket URL the Colyseus client should connect to.
// In Discord: wss through the proxy origin (`wss://<host>/.proxy/colyseus`), since
// the activity origin is https. Outside Discord: the configured direct server URL
// (default ws://localhost:2567).
export function gameServerUrl(
  inDiscord: boolean,
  loc: LocationLike,
  directUrl: string,
): string {
  if (!inDiscord) return directUrl;
  const wsProto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${wsProto}//${loc.host}${PROXY_COLYSEUS_PREFIX}`;
}

// Stable room id for a given Activity instance so everyone who launched the SAME
// activity (same voice channel session) lands in the SAME Colyseus room. Discord's
// `instanceId` is unique per running activity instance; channelId is the fallback.
// Returns null when neither is known (caller then uses default matchmaking).
export function roomIdFromInstance(
  instanceId: string | null | undefined,
  channelId: string | null | undefined,
): string | null {
  const id = (instanceId ?? channelId ?? '').trim();
  return id.length > 0 ? id : null;
}
