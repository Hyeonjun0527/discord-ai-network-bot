import http from 'http';
import express from 'express';
import { Server } from '@colyseus/core';
import { WebSocketTransport } from '@colyseus/ws-transport';
import { CrazyRoom } from './CrazyRoom';

// Authoritative Colyseus game server. Hosts the single Bomberman room type; clients
// connect over WebSocket and receive synced GameState (see CrazyRoom + state.ts).
const PORT = Number(process.env.PORT ?? 2567);

const app = express();
app.get('/health', (_req, res) => res.json({ ok: true }));

const httpServer = http.createServer(app);
const gameServer = new Server({
  transport: new WebSocketTransport({ server: httpServer }),
});

gameServer.define('crazy', CrazyRoom);

gameServer
  .listen(PORT)
  .then(() => console.log(`[crazy-nia] Colyseus server listening on ws://localhost:${PORT}`))
  .catch((err) => {
    console.error('[crazy-nia] failed to start server', err);
    process.exit(1);
  });
