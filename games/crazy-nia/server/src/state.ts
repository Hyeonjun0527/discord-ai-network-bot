import { Schema, MapSchema, ArraySchema, type } from '@colyseus/schema';

// Authoritative, network-synced game state. @colyseus/schema diffs these and pushes
// only the deltas to every client. Pixel positions (px/py) are interpolation targets
// the server advances each tick so the client can render smooth movement without
// running any of its own gameplay simulation.

export class PlayerState extends Schema {
  @type('string') id = '';
  @type('string') name = ''; // Discord display name when launched as an Activity; else ''
  @type('uint8') slot = 0; // 0..3 -> spawn corner + color
  @type('uint32') color = 0xffffff;
  @type('int8') col = 0;
  @type('int8') row = 0;
  @type('number') px = 0; // current pixel x (server-advanced toward the target cell)
  @type('number') py = 0; // current pixel y
  @type('boolean') alive = true;
  @type('uint8') maxBombs = 1;
  @type('uint8') range = 1;
  @type('uint8') activeBombs = 0;
}

export class BombState extends Schema {
  @type('int8') col = 0;
  @type('int8') row = 0;
  @type('uint8') range = 1;
  @type('string') ownerId = '';
}

export class ExplosionState extends Schema {
  @type('int8') col = 0;
  @type('int8') row = 0;
}

export class ItemState extends Schema {
  @type('int8') col = 0;
  @type('int8') row = 0;
  @type('string') kind = ''; // 'bomb' | 'range' | 'speed'
}

export class GameState extends Schema {
  // Flat row-major grid (row * COLS + col) of CellKind. ArraySchema<number> so block
  // destruction syncs to every client by index.
  @type(['uint8']) grid = new ArraySchema<number>();
  @type({ map: PlayerState }) players = new MapSchema<PlayerState>();
  @type([BombState]) bombs = new ArraySchema<BombState>();
  @type([ExplosionState]) explosions = new ArraySchema<ExplosionState>();
  @type([ItemState]) items = new ArraySchema<ItemState>();
  @type('string') status = '대결 대기 중…'; // round status text shown in the client HUD
  @type('boolean') roundOver = false;
}
