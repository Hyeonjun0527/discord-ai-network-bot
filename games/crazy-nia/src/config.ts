// Core game tuning constants. Kept in one place so balance + layout are easy to read.

export const TILE = 48; // pixel size of one grid cell

// Grid dimensions (odd numbers keep the classic Bomberman pillar lattice symmetric).
export const COLS = 13;
export const ROWS = 11;

export const HUD_HEIGHT = 56;

export const GAME_WIDTH = COLS * TILE;
export const GAME_HEIGHT = ROWS * TILE + HUD_HEIGHT;

// Tile kinds on the logical grid.
export const enum Cell {
  Floor = 0,
  SolidWall = 1, // indestructible border + lattice pillars
  Block = 2, // destructible crate
}

export const BOMB_FUSE_MS = 2200; // time from placement to explosion
export const EXPLOSION_MS = 420; // how long the flame stays lethal/visible

export const BLOCK_FILL_CHANCE = 0.78; // chance a free interior cell becomes a destructible block
export const ITEM_DROP_CHANCE = 0.32; // chance a destroyed block drops a power-up

// Per-player starting stats and caps.
export const START_BOMBS = 1;
export const START_RANGE = 1;
export const MAX_RANGE = 6;
export const MAX_BOMBS = 6;

// Movement: grid-step tween duration in ms (smaller = faster). Speed power-up reduces this.
export const BASE_STEP_MS = 150;
export const MIN_STEP_MS = 70;
export const STEP_SPEEDUP_MS = 18; // subtracted per speed power-up

export type ItemKind = 'bomb' | 'range' | 'speed';

export interface PlayerControls {
  up: string;
  down: string;
  left: string;
  right: string;
  bomb: string;
}

export interface PlayerSpec {
  id: 1 | 2;
  name: string;
  color: number; // body tint for the placeholder sprite
  spawn: { col: number; row: number };
  controls: PlayerControls;
}

// Four corners are reserved spawn points (kept clear of blocks).
export const PLAYERS: PlayerSpec[] = [
  {
    id: 1,
    name: 'P1',
    color: 0x4f8cff,
    spawn: { col: 1, row: 1 },
    controls: { up: 'UP', down: 'DOWN', left: 'LEFT', right: 'RIGHT', bomb: 'SPACE' },
  },
  {
    id: 2,
    name: 'P2',
    color: 0xff5d6c,
    spawn: { col: COLS - 2, row: ROWS - 2 },
    controls: { up: 'W', down: 'S', left: 'A', right: 'D', bomb: 'SHIFT' },
  },
];
