import { createFileRoute } from "@tanstack/react-router";

import { exchangeCodeForToken } from "../../discord/token";

// Discord Activity OAuth token exchange — a TanStack Start *server route* (real
// HTTP endpoint, not a createServerFn RPC) so it resolves at the fixed path
// `POST /api/token`. Inside a Discord Activity the client posts `/.proxy/api/token`,
// which the portal URL Mapping `/api` -> <server host> forwards here.
//
// The handler runs server-only: `process.env` is read at REQUEST time (on Cloudflare
// Workers env binds per request, so module-scope reads would be undefined). The
// client_secret is read here and used only to call Discord — it never ships to the
// browser and is never put in any response.
//
// Input: JSON `{ code }`. Output: `{ access_token }` on success, `{ error }` otherwise.
export const Route = createFileRoute("/api/token")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        let code: unknown;
        try {
          code = ((await request.json()) as { code?: unknown }).code;
        } catch {
          return Response.json({ error: "invalid json body" }, { status: 400 });
        }

        const result = await exchangeCodeForToken(code, {
          clientId: process.env.DISCORD_CLIENT_ID ?? "",
          clientSecret: process.env.DISCORD_CLIENT_SECRET ?? "",
        });

        if (!result.ok) {
          return Response.json({ error: result.error }, { status: result.status });
        }
        return Response.json({ access_token: result.accessToken });
      },
    },
  },
});
