import Phaser from 'phaser';
import { Cell, BOMB_FUSE_MS, EXPLOSION_MS, ITEM_DROP_CHANCE } from '../config';
import { Grid } from './grid';
import { TEX } from './textures';
import { Bomb } from '../entities/Bomb';
import { Item } from '../entities/Item';
import { Player } from '../entities/Player';

// Owns all bombs, the cross-shaped explosions they produce, chain reactions,
// block destruction, item drops, and lethal-cell detection for that frame.
export class BombManager {
  private readonly bombs: Bomb[] = [];
  private readonly items: Item[] = [];
  // Cells currently on fire this frame -> used to kill players standing in them.
  private readonly burning = new Set<string>();

  constructor(
    private readonly scene: Phaser.Scene,
    private readonly grid: Grid,
  ) {}

  hasBombAt(col: number, row: number): boolean {
    return this.bombs.some((b) => !b.exploded && b.col === col && b.row === row);
  }

  /** Places a bomb for the player if they have spare capacity and the cell is free. */
  place(player: Player): void {
    if (!player.alive || player.activeBombs >= player.maxBombs) return;
    if (this.hasBombAt(player.col, player.row)) return;

    player.activeBombs += 1;
    const bomb = new Bomb(
      this.scene,
      player.col,
      player.row,
      player,
      TEX.bomb,
      BOMB_FUSE_MS,
      (b) => this.explode(b),
    );
    this.bombs.push(bomb);
  }

  private explode(bomb: Bomb): void {
    if (bomb.exploded) return;
    bomb.exploded = true;
    bomb.owner.activeBombs = Math.max(0, bomb.owner.activeBombs - 1);
    bomb.cleanup();
    this.bombs.splice(this.bombs.indexOf(bomb), 1);

    const flameCells: Array<{ col: number; row: number }> = [{ col: bomb.col, row: bomb.row }];

    // Cross arms: stop at solid walls; a block stops the arm but is destroyed.
    const dirs = [
      { dc: 1, dr: 0 },
      { dc: -1, dr: 0 },
      { dc: 0, dr: 1 },
      { dc: 0, dr: -1 },
    ];
    for (const d of dirs) {
      for (let step = 1; step <= bomb.range; step++) {
        const c = bomb.col + d.dc * step;
        const r = bomb.row + d.dr * step;
        const cell = this.grid.at(c, r);
        if (cell === Cell.SolidWall) break;
        flameCells.push({ col: c, row: r });
        if (cell === Cell.Block) {
          this.grid.destroyBlock(c, r);
          this.maybeDropItem(c, r);
          break; // crate absorbs the rest of this arm
        }
      }
    }

    // Spawn flame sprites + register lethal cells for the explosion window.
    for (const { col, row } of flameCells) {
      this.spawnFlame(col, row);
      // Chain-detonate any bomb caught in the blast.
      const caught = this.bombs.find((b) => !b.exploded && b.col === col && b.row === row);
      if (caught) caught.detonateNow((b) => this.explode(b));
    }
  }

  private spawnFlame(col: number, row: number): void {
    const key = Grid.key(col, row);
    this.burning.add(key);
    const flame = this.scene.add
      .image(Grid.cellToX(col), Grid.cellToY(row), TEX.flame)
      .setDepth(6);
    // Walking into an item that's now on fire shouldn't survive: burn it too.
    const item = this.items.find((i) => i.col === col && i.row === row);
    if (item) this.removeItem(item);

    this.scene.tweens.add({
      targets: flame,
      alpha: 0,
      duration: EXPLOSION_MS,
      onComplete: () => {
        flame.destroy();
        this.burning.delete(key);
      },
    });
  }

  private maybeDropItem(col: number, row: number): void {
    if (Math.random() > ITEM_DROP_CHANCE) return;
    this.items.push(new Item(this.scene, col, row, Item.randomKind()));
  }

  private removeItem(item: Item): void {
    item.destroy();
    const idx = this.items.indexOf(item);
    if (idx >= 0) this.items.splice(idx, 1);
  }

  /** Per-frame: pick up items the player stands on, and kill players in flames. */
  update(players: Player[]): void {
    for (const p of players) {
      if (!p.alive) continue;

      const item = this.items.find((i) => i.col === p.col && i.row === p.row);
      if (item) {
        p.collect(item.kind);
        this.removeItem(item);
      }

      if (this.burning.has(Grid.key(p.col, p.row))) {
        p.kill();
      }
    }
  }
}
