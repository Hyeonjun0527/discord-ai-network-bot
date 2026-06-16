import { Client, Room } from 'colyseus.js';
import { ROOM_NAME, SERVER_URL } from '../config';
import { MSG_INPUT, MSG_PLACE_BOMB, type Direction } from '../../shared/messages';

// Thin wrapper around colyseus.js: connects to the authoritative room, forwards local
// input, and exposes the synced room state for the renderer to subscribe to. This is
// the ONLY place the client talks to the server — no gameplay logic lives here.
export class NetClient {
  private readonly client: Client;
  room: Room | null = null;
  private lastDir: Direction | null = null;

  constructor(serverUrl: string = SERVER_URL) {
    this.client = new Client(serverUrl);
  }

  async connect(): Promise<Room> {
    const room = await this.client.joinOrCreate(ROOM_NAME);
    this.room = room;
    return room;
  }

  get sessionId(): string {
    return this.room?.sessionId ?? '';
  }

  // Send a held direction (or null to stop). Deduped so we only emit on change —
  // the server keeps applying the last direction every tick until it changes.
  sendDir(dir: Direction | null): void {
    if (!this.room || dir === this.lastDir) return;
    this.lastDir = dir;
    this.room.send(MSG_INPUT, { dir });
  }

  placeBomb(): void {
    this.room?.send(MSG_PLACE_BOMB);
  }

  leave(): void {
    this.room?.leave();
    this.room = null;
  }
}
