import Phaser from 'phaser';
import { TILE, PLAYERS } from '../config';

// TODO: Kenney 에셋 교체 — 전체가 코드 생성 플레이스홀더(단색/도형) 스프라이트다.
// Kenney "Sokoban"(CC0) 팩 다운로드가 막혀 있어, 게임 로직을 우선 동작시키기 위해
// 런타임에 Phaser Graphics 로 타일/엔티티 텍스처를 그려 캐시에 등록한다.
// 실제 PNG 타일시트로 교체하려면 이 모듈 대신 scene.load.spritesheet() 를 쓰면 된다.

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

/** Draws every placeholder texture and stores it in the texture manager. */
export function generateTextures(scene: Phaser.Scene): void {
  const g = scene.make.graphics({ x: 0, y: 0 }, false);

  // Floor: checker-ish flat tile with a subtle border.
  g.clear();
  g.fillStyle(0x2a2d3e, 1);
  g.fillRect(0, 0, TILE, TILE);
  g.lineStyle(1, 0x343852, 1);
  g.strokeRect(0.5, 0.5, TILE - 1, TILE - 1);
  g.generateTexture(TEX.floor, TILE, TILE);

  // Solid wall: dark slab with a beveled highlight.
  g.clear();
  g.fillStyle(0x4b4f6b, 1);
  g.fillRect(0, 0, TILE, TILE);
  g.fillStyle(0x5d6188, 1);
  g.fillRect(2, 2, TILE - 4, 6);
  g.fillStyle(0x363a52, 1);
  g.fillRect(2, TILE - 8, TILE - 4, 6);
  g.generateTexture(TEX.wall, TILE, TILE);

  // Destructible block: crate look.
  g.clear();
  g.fillStyle(0xb5763a, 1);
  rrect(g, 3, 3, TILE - 6, TILE - 6, 6);
  g.lineStyle(3, 0x7d4f24, 1);
  g.strokeRoundedRect(3, 3, TILE - 6, TILE - 6, 6);
  g.beginPath();
  g.moveTo(3, 3);
  g.lineTo(TILE - 3, TILE - 3);
  g.moveTo(TILE - 3, 3);
  g.lineTo(3, TILE - 3);
  g.strokePath();
  g.generateTexture(TEX.block, TILE, TILE);

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

  // Power-ups: rounded badge with a glyph color.
  const drawItem = (key: string, base: number, glyph: number) => {
    g.clear();
    g.fillStyle(0x101220, 0.85);
    rrect(g, 6, 6, TILE - 12, TILE - 12, 8);
    g.fillStyle(base, 1);
    rrect(g, 10, 10, TILE - 20, TILE - 20, 6);
    g.fillStyle(glyph, 1);
    g.fillCircle(TILE / 2, TILE / 2, TILE * 0.12);
    g.generateTexture(key, TILE, TILE);
  };
  drawItem(TEX.itemBomb, 0x2d6cdf, 0xffffff); // bomb +1
  drawItem(TEX.itemRange, 0xdf4d2d, 0xfff0a0); // range +1
  drawItem(TEX.itemSpeed, 0x2dbf6c, 0xffffff); // speed +1

  // Players: colored rounded body with eyes, per spec color.
  for (const p of PLAYERS) {
    g.clear();
    g.fillStyle(0x000000, 0.25);
    g.fillEllipse(TILE / 2, TILE - 8, TILE * 0.6, TILE * 0.2); // shadow
    g.fillStyle(p.color, 1);
    rrect(g, 8, 6, TILE - 16, TILE - 14, 10);
    g.fillStyle(0xffffff, 1);
    g.fillCircle(TILE / 2 - 6, TILE / 2 - 2, 4);
    g.fillCircle(TILE / 2 + 6, TILE / 2 - 2, 4);
    g.fillStyle(0x14151f, 1);
    g.fillCircle(TILE / 2 - 6, TILE / 2 - 2, 2);
    g.fillCircle(TILE / 2 + 6, TILE / 2 - 2, 2);
    g.generateTexture(p.id === 1 ? TEX.player1 : TEX.player2, TILE, TILE);
  }

  g.destroy();
}
