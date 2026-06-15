import Phaser from 'phaser';
import {
  PlayerSpec,
  START_BOMBS,
  START_RANGE,
  BASE_STEP_MS,
  MIN_STEP_MS,
  STEP_SPEEDUP_MS,
  MAX_BOMBS,
  MAX_RANGE,
  ItemKind,
  TILE,
} from '../config';
import { Grid } from '../systems/grid';

type Dir = { dc: number; dr: number };

// A grid-locked player. Movement is one cell at a time via a position tween;
// a new step is only accepted when not already mid-tween (classic snappy feel).
export class Player {
  readonly spec: PlayerSpec;
  readonly sprite: Phaser.GameObjects.Image;
  col: number;
  row: number;
  alive = true;

  maxBombs = START_BOMBS;
  range = START_RANGE;
  activeBombs = 0;

  private speedLevel = 0;
  private moving = false;

  constructor(
    private readonly scene: Phaser.Scene,
    spec: PlayerSpec,
    texture: string,
  ) {
    this.spec = spec;
    this.col = spec.spawn.col;
    this.row = spec.spawn.row;
    this.sprite = scene.add
      .image(Grid.cellToX(this.col), Grid.cellToY(this.row), texture)
      .setDisplaySize(TILE, TILE)
      .setDepth(5);
  }

  get name(): string {
    return this.spec.name;
  }

  get stepMs(): number {
    return Math.max(MIN_STEP_MS, BASE_STEP_MS - this.speedLevel * STEP_SPEEDUP_MS);
  }

  /**
   * Attempts a one-cell move in the held direction. `occupied` returns true for
   * cells blocked by a bomb (players can't walk through bombs).
   */
  tryMove(dir: Dir, grid: Grid, occupied: (col: number, row: number) => boolean): void {
    if (!this.alive || this.moving || (dir.dc === 0 && dir.dr === 0)) return;
    const nc = this.col + dir.dc;
    const nr = this.row + dir.dr;
    if (!grid.isWalkable(nc, nr) || occupied(nc, nr)) return;

    this.moving = true;
    this.col = nc;
    this.row = nr;
    this.scene.tweens.add({
      targets: this.sprite,
      x: Grid.cellToX(nc),
      y: Grid.cellToY(nr),
      duration: this.stepMs,
      ease: 'Linear',
      onComplete: () => {
        this.moving = false;
      },
    });
  }

  collect(kind: ItemKind): void {
    if (kind === 'bomb') this.maxBombs = Math.min(MAX_BOMBS, this.maxBombs + 1);
    else if (kind === 'range') this.range = Math.min(MAX_RANGE, this.range + 1);
    else if (kind === 'speed') this.speedLevel += 1;
  }

  kill(): void {
    if (!this.alive) return;
    this.alive = false;
    this.scene.tweens.add({
      targets: this.sprite,
      alpha: 0,
      scale: 1.4,
      angle: 90,
      duration: 300,
      onComplete: () => this.sprite.setVisible(false),
    });
  }
}
