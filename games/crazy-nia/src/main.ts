import Phaser from 'phaser';
import { GAME_WIDTH, GAME_HEIGHT } from './config';
import { GameScene } from './scenes/GameScene';

const config: Phaser.Types.Core.GameConfig = {
  type: Phaser.AUTO,
  parent: 'game',
  width: GAME_WIDTH,
  height: GAME_HEIGHT,
  backgroundColor: '#14151f',
  pixelArt: true,
  scene: [GameScene],
  scale: {
    mode: Phaser.Scale.FIT,
    autoCenter: Phaser.Scale.CENTER_BOTH,
  },
};

const game = new Phaser.Game(config);

// Expose the game for the headless smoke test (harmless in production).
(window as unknown as { __crazyNia?: Phaser.Game }).__crazyNia = game;
