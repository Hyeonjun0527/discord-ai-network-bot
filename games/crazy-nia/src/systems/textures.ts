import Phaser from 'phaser';
import { TILE } from '../config';

// TEX keys are the single source of truth for texture names used across all modules.
// floor / wall / block / player1 / player2 → Kenney Sokoban CC0 PNG (loaded in GameScene.preload)
// bomb / flame / item* → code-generated (no equivalent in Sokoban pack)
export const TEX = {
  floor: 'tex-floor',
  wall: 'tex-wall',
  block: 'tex-block',
  bomb: 'tex-bomb',
  flame: 'tex-flame',
  itemBomb: 'tex-item-bomb',
  itemRange: 'tex-item-range',
  itemSpeed: 'tex-item-speed',
  player1: 'tex-player-1',
  player2: 'tex-player-2',
} as const;

function rrect(g: Phaser.GameObjects.Graphics, x: number, y: number, w: number, h: number, r: number): void {
  g.fillRoundedRect(x, y, w, h, r);
}

/**
 * Generates code-drawn textures for game elements that have no Sokoban sprite:
 * bomb, flame, and the three power-up items.
 * floor / wall / block / players are loaded from Kenney Sokoban PNG in GameScene.preload().
 */
export function generateTextures(scene: Phaser.Scene): void {
  const g = scene.make.graphics({ x: 0, y: 0 }, false);

  // Bomb: black sphere + fuse spark.
  g.clear();
  g.fillStyle(0x16181f, 1);
  g.fillCircle(TILE / 2, TILE / 2 + 3, TILE * 0.36);
  g.fillStyle(0x3a3d4a, 1);
  g.fillCircle(TILE / 2 - 6, TILE / 2 - 3, TILE * 0.1);
  g.fillStyle(0xffb648, 1);
  g.fillCircle(TILE / 2 + 8, 8, 4);
  g.generateTexture(TEX.bomb, TILE, TILE);

  // Flame: glowing cross-cell square.
  g.clear();
  g.fillStyle(0xff6a2b, 1);
  rrect(g, 2, 2, TILE - 4, TILE - 4, 8);
  g.fillStyle(0xffd23f, 1);
  rrect(g, 8, 8, TILE - 16, TILE - 16, 6);
  g.fillStyle(0xfff6c9, 0.9);
  g.fillCircle(TILE / 2, TILE / 2, TILE * 0.14);
  g.generateTexture(TEX.flame, TILE, TILE);

  // Power-ups: rounded badge with a distinct color AND a distinct glyph per kind, so
  // they're tellable apart at a glance (not just by colour).
  const c = TILE / 2;
  const badge = (base: number) => {
    g.clear();
    g.fillStyle(0x101220, 0.85);
    rrect(g, 6, 6, TILE - 12, TILE - 12, 8);
    g.fillStyle(base, 1);
    rrect(g, 10, 10, TILE - 20, TILE - 20, 6);
  };

  // bomb +1 (blue): a small bomb dot to signal "extra bomb".
  badge(0x2d6cdf);
  g.fillStyle(0x0e1330, 1);
  g.fillCircle(c, c + 2, TILE * 0.16);
  g.fillStyle(0xffd23f, 1);
  g.fillCircle(c + TILE * 0.14, c - TILE * 0.13, 3);
  g.generateTexture(TEX.itemBomb, TILE, TILE);

  // range +1 (red): a four-way arrow cross to signal "longer blast".
  badge(0xdf4d2d);
  g.fillStyle(0xfff0a0, 1);
  g.fillRect(c - 2, 14, 4, TILE - 28); // vertical bar
  g.fillRect(14, c - 2, TILE - 28, 4); // horizontal bar
  g.fillTriangle(c, 10, c - 6, 18, c + 6, 18); // up arrowhead
  g.fillTriangle(c, TILE - 10, c - 6, TILE - 18, c + 6, TILE - 18); // down
  g.fillTriangle(10, c, 18, c - 6, 18, c + 6); // left
  g.fillTriangle(TILE - 10, c, TILE - 18, c - 6, TILE - 18, c + 6); // right
  g.generateTexture(TEX.itemRange, TILE, TILE);

  // speed +1 (green): double chevron (>>) to signal "faster".
  badge(0x2dbf6c);
  g.fillStyle(0xffffff, 1);
  g.fillTriangle(c - 12, 14, c - 12, TILE - 14, c + 2, c); // left chevron
  g.fillTriangle(c, 14, c, TILE - 14, c + 14, c); // right chevron
  g.generateTexture(TEX.itemSpeed, TILE, TILE);

  g.destroy();
}
