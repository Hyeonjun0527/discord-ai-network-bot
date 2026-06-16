// Client-side config. Gameplay/balance constants live in ../shared/constants.ts
// (the SSOT shared with the authoritative server) and are re-exported here so the
// rest of the client keeps importing from './config' unchanged.

export {
  TILE,
  COLS,
  ROWS,
  HUD_HEIGHT,
  GAME_WIDTH,
  GAME_HEIGHT,
  CELL_FLOOR,
  CELL_SOLID,
  CELL_BLOCK,
  cellToX,
  cellToY,
  type CellKind,
  type ItemKind,
} from '../shared/constants';

// Local keyboard bindings for THIS browser's player (the only one this client controls;
// the server owns everyone else). Arrow keys + WASD both move so a single tester can
// drive whichever hand they prefer; Space or Shift drops a bomb.
export interface KeyBindings {
  up: string[];
  down: string[];
  left: string[];
  right: string[];
  bomb: string[];
}

export const LOCAL_KEYS: KeyBindings = {
  up: ['UP', 'W'],
  down: ['DOWN', 'S'],
  left: ['LEFT', 'A'],
  right: ['RIGHT', 'D'],
  bomb: ['SPACE', 'SHIFT'],
};

// Colyseus server endpoint. Override at build/dev time with VITE_GAME_SERVER_URL.
export const SERVER_URL =
  (import.meta.env?.VITE_GAME_SERVER_URL as string | undefined) ?? 'ws://localhost:2567';

export const ROOM_NAME = 'crazy';
