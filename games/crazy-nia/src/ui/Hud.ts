import Phaser from 'phaser';
import { GAME_WIDTH, HUD_HEIGHT } from '../config';
import { Player } from '../entities/Player';

// Top status strip: each player's bomb/range stats + center round status text.
export class Hud {
  private readonly p1Text: Phaser.GameObjects.Text;
  private readonly p2Text: Phaser.GameObjects.Text;
  private readonly statusText: Phaser.GameObjects.Text;

  constructor(scene: Phaser.Scene) {
    scene.add.rectangle(0, 0, GAME_WIDTH, HUD_HEIGHT, 0x1b1d2b).setOrigin(0, 0).setDepth(10);

    const style: Phaser.Types.GameObjects.Text.TextStyle = {
      fontFamily: 'system-ui, sans-serif',
      fontSize: '16px',
      color: '#e7e9f3',
    };
    this.p1Text = scene.add.text(14, 8, '', { ...style, color: '#8fc0ff' }).setDepth(11);
    this.p2Text = scene.add
      .text(GAME_WIDTH - 14, 8, '', { ...style, color: '#ff9aa4' })
      .setOrigin(1, 0)
      .setDepth(11);
    this.statusText = scene.add
      .text(GAME_WIDTH / 2, HUD_HEIGHT / 2, '', { ...style, fontSize: '15px' })
      .setOrigin(0.5)
      .setDepth(11);
  }

  update(players: Player[], status: string): void {
    const fmt = (p: Player) =>
      `${p.name}  💣${p.maxBombs}  🔥${p.range}${p.alive ? '' : '  ☠'}`;
    this.p1Text.setText(fmt(players[0]));
    this.p2Text.setText(fmt(players[1]));
    this.statusText.setText(status);
  }
}
