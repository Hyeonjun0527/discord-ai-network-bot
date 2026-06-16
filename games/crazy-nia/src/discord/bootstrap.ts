import { DiscordSDK } from '@discord/embedded-app-sdk';
import {
  gameServerUrl,
  isInDiscord,
  roomIdFromInstance,
  tokenEndpoint,
  type LocationLike,
} from './proxy';

// Result of bootstrapping. The game reads `serverUrl` (where to connect Colyseus),
// `roomId` (so the same Activity instance shares a room), and the authenticated
// user's display info (shown on their player). When `inDiscord` is false everything
// is a no-op and the game runs exactly as the standalone local/multiplayer build.
export interface DiscordSession {
  inDiscord: boolean;
  serverUrl: string; // Colyseus WS URL (proxy in Discord, direct otherwise)
  roomId: string | null; // Activity-instance room id, or null for default matchmaking
  userId: string | null;
  displayName: string | null;
  avatarUrl: string | null;
}

const CLIENT_ID = (import.meta.env?.VITE_DISCORD_CLIENT_ID as string | undefined) ?? '';

// OAuth scopes we need: identify (display name/avatar) + guilds (instance context).
const SCOPES: Array<'identify' | 'guilds'> = ['identify', 'guilds'];

function locationLike(): LocationLike {
  return {
    protocol: window.location.protocol,
    host: window.location.host,
    search: window.location.search,
  };
}

// Build a CDN avatar URL from the authenticated user, or null if they have none.
function avatarUrl(user: { id: string; avatar?: string | null }): string | null {
  if (!user.avatar) return null;
  return `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png?size=64`;
}

// Bootstraps the Discord Activity integration.
//
// Outside Discord (no `frame_id`): returns immediately with the direct game-server
// URL and no user — the standalone build is untouched.
//
// Inside Discord: ready -> authorize (get OAuth code) -> exchange code for an
// access_token via the backend token route -> authenticate. Maps the running
// Activity instance to a Colyseus room id so co-launchers share a game.
export async function bootstrapDiscord(directServerUrl: string): Promise<DiscordSession> {
  const loc = locationLike();
  const inDiscord = isInDiscord(loc.search);

  if (!inDiscord) {
    return {
      inDiscord: false,
      serverUrl: directServerUrl,
      roomId: null,
      userId: null,
      displayName: null,
      avatarUrl: null,
    };
  }

  if (!CLIENT_ID) {
    throw new Error(
      'VITE_DISCORD_CLIENT_ID 가 설정되지 않았습니다. Discord Activity 로 실행하려면 빌드 시 이 값을 지정하세요.',
    );
  }

  const sdk = new DiscordSDK(CLIENT_ID);
  await sdk.ready();

  // 1) Get an OAuth authorization code from Discord (no consent prompt re-shown).
  const { code } = await sdk.commands.authorize({
    client_id: CLIENT_ID,
    response_type: 'code',
    state: '',
    prompt: 'none',
    scope: SCOPES,
  });

  // 2) Exchange the code for an access_token via our backend (client_secret stays
  //    server-side). In Discord the request goes through the proxy mapping.
  const res = await fetch(tokenEndpoint(true), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  if (!res.ok) {
    throw new Error(`토큰 교환 실패 (${res.status}). 백엔드 /api/token 설정을 확인하세요.`);
  }
  const { access_token: accessToken } = (await res.json()) as { access_token?: string };
  if (!accessToken) throw new Error('토큰 교환 응답에 access_token 이 없습니다.');

  // 3) Authenticate the SDK session with the access token.
  const auth = await sdk.commands.authenticate({ access_token: accessToken });
  if (!auth) throw new Error('Discord 인증에 실패했습니다.');

  const user = auth.user;
  return {
    inDiscord: true,
    serverUrl: gameServerUrl(true, loc, directServerUrl),
    roomId: roomIdFromInstance(sdk.instanceId, sdk.channelId),
    userId: user.id,
    displayName: user.global_name ?? user.username ?? null,
    avatarUrl: avatarUrl(user),
  };
}
