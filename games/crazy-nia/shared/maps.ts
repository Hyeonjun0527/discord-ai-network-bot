// Map generation — shared by the authoritative server (chooses the round's map) and
// kept in the shared module so dimensions/cell kinds stay the single source of truth.
// NOTHING here may import Phaser or browser/Node-only APIs.
//
// A map is built in two layers:
//   1) a TEMPLATE lays down the indestructible structure (outer border + interior
//      pillar/wall pattern). Several templates give visual + tactical variety.
//   2) the remaining free interior cells are randomly filled with destructible crates,
//      except the four spawn corners and their elbow cells (always kept walkable).
// The server picks a random template each round and syncs the resulting flat grid; the
// client renders whatever grid it receives (no client-side map generation).

import {
  BLOCK_FILL_CHANCE,
  CELL_BLOCK,
  CELL_FLOOR,
  CELL_SOLID,
  COLS,
  ROWS,
  cellKey,
  reservedCells,
  type CellKind,
} from './constants';

// A template decides ONLY whether an interior cell is an indestructible wall. The outer
// border is always solid and added by the generator. `(col, row)` are interior coords
// in [1, COLS-2] x [1, ROWS-2]. Returning true => CELL_SOLID, false => candidate floor.
export interface MapTemplate {
  readonly id: string;
  readonly name: string;
  isWall(col: number, row: number): boolean;
}

// 1) Classic Bomberman lattice: a pillar on every even/even interior cell.
const classicLattice: MapTemplate = {
  id: 'lattice',
  name: '클래식 격자',
  isWall: (col, row) => col % 2 === 0 && row % 2 === 0,
};

// 2) Sparse pillars: only the lattice pillars that are NOT on the centre cross, leaving
//    long open lanes through the middle for fast crossfield play.
const openLanes: MapTemplate = {
  id: 'lanes',
  name: '열린 통로',
  isWall: (col, row) => {
    if (col % 2 !== 0 || row % 2 !== 0) return false;
    const midCol = (COLS - 1) / 2;
    const midRow = (ROWS - 1) / 2;
    return col !== midCol && row !== midRow;
  },
};

// 3) Central plus/arena: lattice pillars PLUS a solid cross through the middle, splitting
//    the board into four quadrants connected only at the rim — more positional.
const crossArena: MapTemplate = {
  id: 'cross',
  name: '십자 광장',
  isWall: (col, row) => {
    const midCol = (COLS - 1) / 2;
    const midRow = (ROWS - 1) / 2;
    // Solid cross arms, but leave a gap every few cells so it isn't a hard wall.
    const onCross = (col === midCol && row % 2 === 1) || (row === midRow && col % 2 === 1);
    const pillar = col % 2 === 0 && row % 2 === 0;
    return onCross || pillar;
  },
};

// 4) Diagonal pillars: pillars only where (col+row) is even on the lattice, giving a
//    checkerboarded, more diagonal feel with fewer total walls.
const diagonalDrift: MapTemplate = {
  id: 'diagonal',
  name: '대각 기둥',
  isWall: (col, row) => col % 2 === 0 && row % 2 === 0 && (col + row) % 4 === 0,
};

// 5) Twin chambers: two vertical wall lines partway in, creating left/right chambers
//    joined top and bottom — encourages flanking. Plus the usual lattice pillars.
const twinChambers: MapTemplate = {
  id: 'chambers',
  name: '쌍둥이 방',
  isWall: (col, row) => {
    const pillar = col % 2 === 0 && row % 2 === 0;
    const leftWall = Math.floor(COLS / 3);
    const rightWall = COLS - 1 - Math.floor(COLS / 3);
    // Wall columns, but keep openings near the top and bottom rims to pass through.
    const onWall =
      (col === leftWall || col === rightWall) && row > 1 && row < ROWS - 2 && row % 3 !== 0;
    return pillar || onWall;
  },
};

export const MAP_TEMPLATES: ReadonlyArray<MapTemplate> = [
  classicLattice,
  openLanes,
  crossArena,
  diagonalDrift,
  twinChambers,
];

export function pickTemplate(rng: () => number = Math.random): MapTemplate {
  return MAP_TEMPLATES[Math.floor(rng() * MAP_TEMPLATES.length)] ?? classicLattice;
}

/**
 * Builds a flat row-major grid (index = row * COLS + col) for one round:
 * solid outer border, the template's interior walls, random destructible crates on the
 * rest, and always-clear spawn corners + elbows. `rng` defaults to Math.random; the
 * server passes its own so the map is reproducible/owned server-side.
 */
export function generateMap(
  template: MapTemplate = classicLattice,
  rng: () => number = Math.random,
): CellKind[] {
  const reserved = reservedCells();
  const cells: CellKind[] = new Array(COLS * ROWS);
  for (let row = 0; row < ROWS; row++) {
    for (let col = 0; col < COLS; col++) {
      let cell: CellKind;
      if (col === 0 || row === 0 || col === COLS - 1 || row === ROWS - 1) {
        cell = CELL_SOLID; // outer border
      } else if (template.isWall(col, row)) {
        cell = CELL_SOLID; // template-defined indestructible wall/pillar
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

/** Convenience: pick a random template and generate its map in one call. */
export function generateRandomMap(rng: () => number = Math.random): CellKind[] {
  return generateMap(pickTemplate(rng), rng);
}
