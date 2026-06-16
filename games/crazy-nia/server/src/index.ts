import http from 'http';
import express from 'express';
import { Server } from '@colyseus/core';
import { WebSocketTransport } from '@colyseus/ws-transport';
import { CrazyRoom } from './CrazyRoom';
import { exchangeCodeForToken } from './token';

// Authoritative Colyseus game server. Hosts the single Bomberman room type; clients
// connect over WebSocket and receive synced GameState (see CrazyRoom + state.ts).
// Also serves the Discord OAuth token-exchange route used by the Activity client.
const PORT = Number(process.env.PORT ?? 2567);
const DISCORD_CLIENT_ID = process.env.DISCORD_CLIENT_ID ?? '';
const DISCORD_CLIENT_SECRET = process.env.DISCORD_CLIENT_SECRET ?? '';

const app = express();
app.use(express.json());
app.get('/health', (_req, res) => res.json({ ok: true }));

// Discord Activity token exchange: { code } -> { access_token }. The client_secret
// stays here and is never sent to the browser. Mapped to `/.proxy/api/token` in the
// Discord URL Mappings (the client posts the proxied path when embedded).
app.post('/api/token', async (req, res) => {
  const result = await exchangeCodeForToken(req.body?.code, {
    clientId: DISCORD_CLIENT_ID,
    clientSecret: DISCORD_CLIENT_SECRET,
  });
  if (!result.ok) {
    res.status(result.status).json({ error: result.error });
    return;
  }
  res.json({ access_token: result.accessToken });
});

const httpServer = http.createServer(app);
const gameServer = new Server({
  transport: new WebSocketTransport({ server: httpServer }),
});

// filterBy(['instanceId']) co-locates clients that join with the same `instanceId`
// (the Discord Activity bootstrap passes sdk.instanceId) into one room — so everyone
// who launched the same Activity shares a game. Clients without an instanceId (plain
// local/web multiplayer) all match the default empty-instanceId room.
gameServer.define('crazy', CrazyRoom).filterBy(['instanceId']);

gameServer
  .listen(PORT)
  .then(() => console.log(`[crazy-nia] Colyseus server listening on ws://localhost:${PORT}`))
  .catch((err) => {
    console.error('[crazy-nia] failed to start server', err);
    process.exit(1);
  });
