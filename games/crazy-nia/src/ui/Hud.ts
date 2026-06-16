import Phaser from 'phaser';
import { GAME_WIDTH, HUD_HEIGHT } from '../config';

// Minimal data the HUD needs about a player — fed from the synced server state.
export interface HudPlayer {
  name: string;
  maxBombs: number;
  range: number;
  alive: boolean;
  color: number;
}

// Top status strip: per-player bomb/range stats (left-aligned, wrapping) + a center
// round-status line. Player count is dynamic (1..4) so the list is rebuilt each frame.
export class Hud {
  private readonly playersText: Phaser.GameObjects.Text;
  private readonly statusText: Phaser.GameObjects.Text;

  constructor(scene: Phaser.Scene) {
    scene.add.rectangle(0, 0, GAME_WIDTH, HUD_HEIGHT, 0x1b1d2b).setOrigin(0, 0).setDepth(10);

    const style: Phaser.Types.GameObjects.Text.TextStyle = {
      fontFamily: 'system-ui, sans-serif',
      fontSize: '14px',
      color: '#e7e9f3',
    };
    this.playersText = scene.add.text(14, 8, '', style).setDepth(11);
    this.statusText = scene.add
      .text(GAME_WIDTH / 2, HUD_HEIGHT - 14, '', { ...style, fontSize: '13px', color: '#cfd3e6' })
      .setOrigin(0.5)
      .setDepth(11);
  }

  update(players: HudPlayer[], status: string): void {
    const parts = players.map(
      (p) => `${p.name} 💣${p.maxBombs} 🔥${p.range}${p.alive ? '' : ' ☠'}`,
    );
    this.playersText.setText(parts.join('    '));
    this.statusText.setText(status);
  }
}
