// Shared game constants + pure grid logic — the single source of truth for both the
// authoritative server (Node) and the renderer client (browser). NOTHING here may
// import Phaser or any browser/Node-only API, so it stays importable from both sides.

export const TILE = 48; // pixel size of one grid cell

// Grid dimensions (odd numbers keep the classic Bomberman pillar lattice symmetric).
export const COLS = 13;
export const ROWS = 11;

export const HUD_HEIGHT = 56;

export const GAME_WIDTH = COLS * TILE;
export const GAME_HEIGHT = ROWS * TILE + HUD_HEIGHT;

// Tile kinds on the logical grid (plain numbers — schema-friendly, no const enum so
// the values survive across the network and across Node/browser module boundaries).
export const CELL_FLOOR = 0;
export const CELL_SOLID = 1; // indestructible border + lattice pillars
export const CELL_BLOCK = 2; // destructible crate
export type CellKind = typeof CELL_FLOOR | typeof CELL_SOLID | typeof CELL_BLOCK;

export const BOMB_FUSE_MS = 2200; // time from placement to explosion
export const EXPLOSION_MS = 420; // how long the flame stays lethal/visible

export const BLOCK_FILL_CHANCE = 0.78; // chance a free interior cell becomes a destructible block
export const ITEM_DROP_CHANCE = 0.32; // chance a destroyed block drops a power-up

// Per-player starting stats and caps.
export const START_BOMBS = 1;
export const START_RANGE = 1;
export const MAX_RANGE = 6;
export const MAX_BOMBS = 6;

// Movement: server steps a player one cell every STEP_MS while a direction is held.
// Speed power-ups reduce the interval (down to MIN_STEP_MS).
export const BASE_STEP_MS = 150;
export const MIN_STEP_MS = 70;
export const STEP_SPEEDUP_MS = 18; // subtracted per speed power-up

// Authoritative simulation tick rate.
export const TICK_HZ = 30;
export const TICK_MS = 1000 / TICK_HZ;

export const MAX_PLAYERS = 4;

export type ItemKind = 'bomb' | 'range' | 'speed';

// Four reserved corner spawns. Player slot index 0..3 maps to these in order.
export const SPAWNS: ReadonlyArray<{ col: number; row: number; color: number }> = [
  { col: 1, row: 1, color: 0x4f8cff },
  { col: COLS - 2, row: ROWS - 2, color: 0xff5d6c },
  { col: COLS - 2, row: 1, color: 0x2dbf6c },
  { col: 1, row: ROWS - 2, color: 0xffb648 },
];

export function cellKey(col: number, row: number): string {
  return `${col},${row}`;
}

export function inBounds(col: number, row: number): boolean {
  return col >= 0 && col < COLS && row >= 0 && row < ROWS;
}

// Pixel position of a cell center (HUD occupies the top strip). Pure — used by the
// renderer for sprite placement, and reused server-side only for reference.
export function cellToX(col: number): number {
  return col * TILE + TILE / 2;
}
export function cellToY(row: number): number {
  return HUD_HEIGHT + row * TILE + TILE / 2;
}

// Spawn corner plus its two adjacent cells stay open so players aren't boxed in.
function reservedCells(): Set<string> {
  const set = new Set<string>();
  for (const s of SPAWNS) {
    const dc = s.col === 1 ? 1 : -1;
    const dr = s.row === 1 ? 1 : -1;
    set.add(cellKey(s.col, s.row));
    set.add(cellKey(s.col + dc, s.row));
    set.add(cellKey(s.col, s.row + dr));
  }
  return set;
}

/**
 * Generates the classic lattice as a flat row-major array of CellKind:
 * solid border, even-grid pillars, random crates, clear corners.
 * `rng` defaults to Math.random; the server passes its own so the map is
 * reproducible/owned server-side. Index = row * COLS + col.
 */
export function generateGrid(rng: () => number = Math.random): CellKind[] {
  const reserved = reservedCells();
  const cells: CellKind[] = new Array(COLS * ROWS);
  for (let row = 0; row < ROWS; row++) {
    for (let col = 0; col < COLS; col++) {
      let cell: CellKind;
      if (col === 0 || row === 0 || col === COLS - 1 || row === ROWS - 1) {
        cell = CELL_SOLID; // outer border
      } else if (col % 2 === 0 && row % 2 === 0) {
        cell = CELL_SOLID; // interior pillars
      } else if (reserved.has(cellKey(col, row))) {
        cell = CELL_FLOOR; // keep spawns + their elbows clear
      } else {
        cell = rng() < BLOCK_FILL_CHANCE ? CELL_BLOCK : CELL_FLOOR;
      }
      cells[row * COLS + col] = cell;
    }
  }
  return cells;
}
