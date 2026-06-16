import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CELL_FLOOR, CELL_SOLID, COLS, ROWS, SPAWNS, cellKey } from './constants';
import { MAP_TEMPLATES, generateMap, pickTemplate } from './maps';

test('every template produces a full row-major grid', () => {
  for (const t of MAP_TEMPLATES) {
    const grid = generateMap(t, () => 0); // rng=0 -> no random crates
    assert.equal(grid.length, COLS * ROWS, `${t.id} grid size`);
  }
});

test('outer border is always solid on every template', () => {
  for (const t of MAP_TEMPLATES) {
    const grid = generateMap(t, () => 0);
    for (let c = 0; c < COLS; c++) {
      assert.equal(grid[c], CELL_SOLID, `${t.id} top border`);
      assert.equal(grid[(ROWS - 1) * COLS + c], CELL_SOLID, `${t.id} bottom border`);
    }
    for (let r = 0; r < ROWS; r++) {
      assert.equal(grid[r * COLS], CELL_SOLID, `${t.id} left border`);
      assert.equal(grid[r * COLS + COLS - 1], CELL_SOLID, `${t.id} right border`);
    }
  }
});

test('spawn corners + elbows stay walkable on every template (even with max crates)', () => {
  // rng=0 forces a crate on every free interior cell; spawns must still be floor.
  for (const t of MAP_TEMPLATES) {
    const grid = generateMap(t, () => -1); // < BLOCK_FILL_CHANCE always -> crate
    const reserved = new Set<string>();
    for (const s of SPAWNS) {
      const dc = s.col === 1 ? 1 : -1;
      const dr = s.row === 1 ? 1 : -1;
      reserved.add(cellKey(s.col, s.row));
      reserved.add(cellKey(s.col + dc, s.row));
      reserved.add(cellKey(s.col, s.row + dr));
    }
    for (const key of reserved) {
      const [c, r] = key.split(',').map(Number);
      assert.equal(grid[r * COLS + c], CELL_FLOOR, `${t.id} reserved cell ${key} must be floor`);
    }
  }
});

test('pickTemplate always returns a known template', () => {
  for (const v of [0, 0.25, 0.5, 0.75, 0.999]) {
    const t = pickTemplate(() => v);
    assert.ok(MAP_TEMPLATES.includes(t));
  }
});
