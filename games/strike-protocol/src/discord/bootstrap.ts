import { DiscordSDK } from "@discord/embedded-app-sdk";

import { isInDiscord, tokenEndpoint } from "./proxy";

// Result of bootstrapping. Outside Discord everything is a no-op (`inDiscord:false`,
// no user) and the game runs exactly as the standalone local/web build — no behavior
// change for normal browser play. Inside Discord the authenticated user's display
// info is available for callers that want to prefill the callsign.
export interface DiscordSession {
  inDiscord: boolean;
  userId: string | null;
  displayName: string | null;
  avatarUrl: string | null;
}

const NOOP_SESSION: DiscordSession = {
  inDiscord: false,
  userId: null,
  displayName: null,
  avatarUrl: null,
};

const CLIENT_ID = (import.meta.env?.VITE_DISCORD_CLIENT_ID as string | undefined) ?? "";

// OAuth scopes we need: identify (display name/avatar) + guilds (instance context).
const SCOPES: Array<"identify" | "guilds"> = ["identify", "guilds"];

// Build a CDN avatar URL from the authenticated user, or null if they have none.
function avatarUrl(user: { id: string; avatar?: string | null }): string | null {
  if (!user.avatar) return null;
  return `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png?size=64`;
}

// Bootstraps the Discord Activity integration.
//
// Outside Discord (no `frame_id`, or running during SSR with no window): returns
// immediately with the no-op session — the standalone build is untouched.
//
// Inside Discord: ready -> authorize (get OAuth code) -> exchange code for an
// access_token via the backend token route (client_secret stays server-side) ->
// authenticate.
export async function bootstrapDiscord(): Promise<DiscordSession> {
  // SSR / non-browser: nothing to bootstrap. Guard before touching window.
  if (typeof window === "undefined") return NOOP_SESSION;

  const inDiscord = isInDiscord(window.location.search);
  if (!inDiscord) return NOOP_SESSION;

  if (!CLIENT_ID) {
    throw new Error(
      "VITE_DISCORD_CLIENT_ID 가 설정되지 않았습니다. Discord Activity 로 실행하려면 빌드 시 이 값을 지정하세요.",
    );
  }

  const sdk = new DiscordSDK(CLIENT_ID);
  await sdk.ready();

  // 1) Get an OAuth authorization code from Discord (no consent prompt re-shown).
  const { code } = await sdk.commands.authorize({
    client_id: CLIENT_ID,
    response_type: "code",
    state: "",
    prompt: "none",
    scope: SCOPES,
  });

  // 2) Exchange the code for an access_token via our backend (client_secret stays
  //    server-side). In Discord the request goes through the proxy mapping.
  const res = await fetch(tokenEndpoint(true), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code }),
  });
  if (!res.ok) {
    throw new Error(`토큰 교환 실패 (${res.status}). 백엔드 /api/token 설정을 확인하세요.`);
  }
  const { access_token: accessToken } = (await res.json()) as { access_token?: string };
  if (!accessToken) throw new Error("토큰 교환 응답에 access_token 이 없습니다.");

  // 3) Authenticate the SDK session with the access token.
  const auth = await sdk.commands.authenticate({ access_token: accessToken });
  if (!auth) throw new Error("Discord 인증에 실패했습니다.");

  const user = auth.user;
  return {
    inDiscord: true,
    userId: user.id,
    displayName: user.global_name ?? user.username ?? null,
    avatarUrl: avatarUrl(user),
  };
}
