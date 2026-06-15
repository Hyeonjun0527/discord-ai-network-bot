import Phaser from 'phaser';
import { ItemKind } from '../config';
import { Grid } from '../systems/grid';
import { TEX } from '../systems/textures';

const TEX_BY_KIND: Record<ItemKind, string> = {
  bomb: TEX.itemBomb,
  range: TEX.itemRange,
  speed: TEX.itemSpeed,
};

// A power-up sitting on the floor, waiting to be walked over.
export class Item {
  readonly col: number;
  readonly row: number;
  readonly kind: ItemKind;
  readonly sprite: Phaser.GameObjects.Image;

  constructor(scene: Phaser.Scene, col: number, row: number, kind: ItemKind) {
    this.col = col;
    this.row = row;
    this.kind = kind;
    this.sprite = scene.add
      .image(Grid.cellToX(col), Grid.cellToY(row), TEX_BY_KIND[kind])
      .setDepth(3);
    scene.tweens.add({
      targets: this.sprite,
      y: this.sprite.y - 4,
      duration: 600,
      yoyo: true,
      repeat: -1,
      ease: 'Sine.inOut',
    });
  }

  static randomKind(): ItemKind {
    const kinds: ItemKind[] = ['bomb', 'range', 'speed'];
    return kinds[Math.floor(Math.random() * kinds.length)];
  }

  destroy(): void {
    this.sprite.destroy();
  }
}
