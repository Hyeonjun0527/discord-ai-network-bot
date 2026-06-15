import Phaser from 'phaser';
import { PLAYERS, PlayerSpec } from '../config';
import { generateTextures, TEX } from '../systems/textures';
import { Grid } from '../systems/grid';
import { BombManager } from '../systems/BombManager';
import { Player } from '../entities/Player';
import { Hud } from '../ui/Hud';

// One key per control, looked up each frame. Held-direction movement with a
// "just pressed" check for bomb placement so one tap = one bomb.
interface PlayerInput {
  up: Phaser.Input.Keyboard.Key;
  down: Phaser.Input.Keyboard.Key;
  left: Phaser.Input.Keyboard.Key;
  right: Phaser.Input.Keyboard.Key;
  bomb: Phaser.Input.Keyboard.Key;
}

export class GameScene extends Phaser.Scene {
  private grid!: Grid;
  private bombs!: BombManager;
  private players: Player[] = [];
  private inputs: PlayerInput[] = [];
  private hud!: Hud;
  private restartKey!: Phaser.Input.Keyboard.Key;

  private roundOver = false;
  private statusText = '대결 시작!';

  constructor() {
    super('game');
  }

  create(): void {
    generateTextures(this);

    this.grid = new Grid(this);
    this.grid.render(TEX.floor, TEX.wall, TEX.block);

    this.bombs = new BombManager(this, this.grid);

    this.players = PLAYERS.map(
      (spec) => new Player(this, spec, spec.id === 1 ? TEX.player1 : TEX.player2),
    );

    this.inputs = PLAYERS.map((spec) => this.buildInput(spec));
    this.restartKey = this.input.keyboard!.addKey('R');

    this.hud = new Hud(this);
    this.roundOver = false;
    this.statusText = '대결 시작!';
  }

  private buildInput(spec: PlayerSpec): PlayerInput {
    const kb = this.input.keyboard!;
    const c = spec.controls;
    return {
      up: kb.addKey(c.up),
      down: kb.addKey(c.down),
      left: kb.addKey(c.left),
      right: kb.addKey(c.right),
      bomb: kb.addKey(c.bomb),
    };
  }

  update(): void {
    if (this.roundOver) {
      if (Phaser.Input.Keyboard.JustDown(this.restartKey)) this.scene.restart();
      return;
    }

    for (let i = 0; i < this.players.length; i++) {
      this.handlePlayer(this.players[i], this.inputs[i]);
    }

    this.bombs.update(this.players);
    this.checkWinCondition();
    this.hud.update(this.players, this.statusText);
  }

  private handlePlayer(player: Player, input: PlayerInput): void {
    if (!player.alive) return;

    let dc = 0;
    let dr = 0;
    if (input.left.isDown) dc = -1;
    else if (input.right.isDown) dc = 1;
    else if (input.up.isDown) dr = -1;
    else if (input.down.isDown) dr = 1;

    if (dc !== 0 || dr !== 0) {
      player.tryMove({ dc, dr }, this.grid, (col, row) => this.bombs.hasBombAt(col, row));
    }

    if (Phaser.Input.Keyboard.JustDown(input.bomb)) {
      this.bombs.place(player);
    }
  }

  private checkWinCondition(): void {
    const alive = this.players.filter((p) => p.alive);
    if (alive.length > 1) return;

    this.roundOver = true;
    if (alive.length === 1) {
      this.statusText = `${alive[0].name} 승리!  —  R 키로 재시작`;
    } else {
      this.statusText = '무승부!  —  R 키로 재시작';
    }
    this.hud.update(this.players, this.statusText);
  }

  /** Observable snapshot for the headless smoke test (no gameplay effect). */
  debugSnapshot(): {
    blocks: number;
    roundOver: boolean;
    players: Array<{ name: string; col: number; row: number; alive: boolean; bombs: number; range: number }>;
  } {
    return {
      blocks: this.grid.blockCount(),
      roundOver: this.roundOver,
      players: this.players.map((p) => ({
        name: p.name,
        col: p.col,
        row: p.row,
        alive: p.alive,
        bombs: p.maxBombs,
        range: p.range,
      })),
    };
  }
}
