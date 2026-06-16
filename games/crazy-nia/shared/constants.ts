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

// Movement: the server slides each player at a constant pixel velocity in the held
// direction (no per-cell stepping/cooldown), so motion is smooth and continuous like
// Crazy Arcade. Speed power-ups add a flat amount to the velocity (capped).
export const BASE_SPEED = 3.2 * TILE; // px/sec at speed level 0 (~one cell per 0.31s)
export const SPEED_PER_LEVEL = 0.55 * TILE; // px/sec added per speed power-up
export const MAX_SPEED = 6.5 * TILE; // px/sec cap

// When moving along one axis, allow turning onto the perpendicular axis (and entering
// the next cell) only while within this many px of the lane center — keeps players on
// the grid but makes cornering forgiving instead of pixel-perfect.
export const TURN_TOLERANCE = TILE * 0.34;

// Speed (px/sec) for a given number of speed power-ups collected.
export function speedForLevel(level: number): number {
  return Math.min(MAX_SPEED, BASE_SPEED + level * SPEED_PER_LEVEL);
}

// Authoritative simulation tick rate.
export const TICK_HZ = 30;
export const TICK_MS = 1000 / TICK_HZ;

// How often the server flushes synced state deltas to clients. Bumped above the
// Colyseus default (50ms / 20Hz) to 30Hz so continuous px/py updates arrive densely
// enough for smooth client-side interpolation.
export const PATCH_HZ = 30;
export const PATCH_MS = 1000 / PATCH_HZ;

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

// Inverse of cellToX/cellToY: derive the grid cell a pixel position sits on. The server
// drives players by continuous px/py and rounds to a cell for bomb/item/blast logic.
export function xToCol(px: number): number {
  return Math.round((px - TILE / 2) / TILE);
}
export function yToRow(py: number): number {
  return Math.round((py - HUD_HEIGHT - TILE / 2) / TILE);
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
