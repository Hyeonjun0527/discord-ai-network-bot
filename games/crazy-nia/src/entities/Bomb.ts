import Phaser from 'phaser';
import { Grid } from '../systems/grid';
import { Player } from './Player';

// A live bomb on the grid. Holds its cell, owner, blast range, and the fuse timer.
export class Bomb {
  readonly col: number;
  readonly row: number;
  readonly owner: Player;
  readonly range: number;
  exploded = false;

  readonly sprite: Phaser.GameObjects.Image;
  private timer: Phaser.Time.TimerEvent;

  constructor(
    scene: Phaser.Scene,
    col: number,
    row: number,
    owner: Player,
    texture: string,
    fuseMs: number,
    onFuse: (bomb: Bomb) => void,
  ) {
    this.col = col;
    this.row = row;
    this.owner = owner;
    this.range = owner.range;
    this.sprite = scene.add.image(Grid.cellToX(col), Grid.cellToY(row), texture).setDepth(4);

    // Subtle pulse so the player can read the imminent blast.
    scene.tweens.add({
      targets: this.sprite,
      scale: 1.12,
      duration: 280,
      yoyo: true,
      repeat: -1,
    });

    this.timer = scene.time.delayedCall(fuseMs, () => onFuse(this));
  }

  /** Triggers the explosion early (used for chain reactions). */
  detonateNow(onFuse: (bomb: Bomb) => void): void {
    if (this.exploded) return;
    this.timer.remove(false);
    onFuse(this);
  }

  cleanup(): void {
    this.sprite.destroy();
  }
}
