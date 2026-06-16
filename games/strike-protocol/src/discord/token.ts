// Discord OAuth token exchange. The Activity client sends an authorization `code`;
// we exchange it (with the app's client_secret) for an access_token and return ONLY
// the access_token to the client. The client_secret never leaves the server.
//
// Kept dependency-light (global fetch) and separate from the route wiring so the
// exchange logic stays unit-testable with an injected fetch. The TanStack Start
// server route (src/routes/api/token.ts) reads the credentials from process.env and
// calls this — so this module ships ZERO secrets and is import-safe from anywhere.

const DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token";

export interface TokenExchangeConfig {
  clientId: string;
  clientSecret: string;
  // Injectable for tests; defaults to the global fetch in real use.
  fetchImpl?: typeof fetch;
}

export interface TokenExchangeResult {
  ok: boolean;
  status: number; // HTTP status to return to the client
  accessToken?: string;
  error?: string;
}

// Exchange an authorization code for a Discord access token.
// Returns a result object (never throws on a bad code / Discord error) so the route
// handler can map it straight to an HTTP response.
export async function exchangeCodeForToken(
  code: unknown,
  config: TokenExchangeConfig,
): Promise<TokenExchangeResult> {
  if (typeof code !== "string" || code.length === 0) {
    return { ok: false, status: 400, error: "missing code" };
  }
  if (!config.clientId || !config.clientSecret) {
    return { ok: false, status: 500, error: "server missing DISCORD_CLIENT_ID/SECRET" };
  }

  const doFetch = config.fetchImpl ?? fetch;
  const body = new URLSearchParams({
    client_id: config.clientId,
    client_secret: config.clientSecret,
    grant_type: "authorization_code",
    code,
  });

  let res: Response;
  try {
    res = await doFetch(DISCORD_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
  } catch (err) {
    return { ok: false, status: 502, error: `discord unreachable: ${String(err)}` };
  }

  if (!res.ok) {
    return { ok: false, status: 502, error: `discord token endpoint ${res.status}` };
  }

  const data = (await res.json()) as { access_token?: string };
  if (!data.access_token) {
    return { ok: false, status: 502, error: "no access_token in discord response" };
  }
  return { ok: true, status: 200, accessToken: data.access_token };
}
