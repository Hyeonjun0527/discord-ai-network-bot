import Phaser from 'phaser';
import { Room } from 'colyseus.js';
import {
  CELL_BLOCK,
  CELL_FLOOR,
  CELL_SOLID,
  COLS,
  ROWS,
  TILE,
  cellToX,
  cellToY,
  LOCAL_KEYS,
  type ItemKind,
} from '../config';
import { generateTextures, TEX } from '../systems/textures';
import { NetClient } from '../net/NetClient';
import { Hud, type HudPlayer } from '../ui/Hud';
import type { Direction } from '../../shared/messages';

const ITEM_TEX: Record<ItemKind, string> = {
  bomb: TEX.itemBomb,
  range: TEX.itemRange,
  speed: TEX.itemSpeed,
};

// Pure renderer scene. It owns NO gameplay logic: it connects to the authoritative
// Colyseus room, forwards local keyboard input, and draws whatever the synced server
// state says. Player pixel positions are interpolated toward the server's px/py target
// each frame for smooth motion.
export class GameScene extends Phaser.Scene {
  private net!: NetClient;
  private room: Room | null = null;

  private hud!: Hud;
  private keys!: Record<keyof typeof LOCAL_KEYS, Phaser.Input.Keyboard.Key[]>;
  private lastDir: Direction | null = null;

  // Sprite mirrors of synced collections, keyed so we can add/remove on changes.
  // cellSprites: grid index -> wall/crate image for non-floor cells (server-owned).
  private cellSprites = new Map<number, Phaser.GameObjects.Image>();
  private playerSprites = new Map<string, Phaser.GameObjects.Image>();
  private playerLabels = new Map<string, Phaser.GameObjects.Text>();
  private bombSprites = new Map<string, Phaser.GameObjects.Image>(); // "col,row" -> bomb
  private explosionSprites = new Map<string, Phaser.GameObjects.Image>();
  private itemSprites = new Map<string, Phaser.GameObjects.Image>();

  private connecting = true;
  private connectError: string | null = null;
  private stateReady = false; // first full state has arrived; safe to render

  constructor() {
    super('game');
  }

  preload(): void {
    const S = 'assets/sokoban';
    this.load.image(TEX.floor, `${S}/Ground/ground_01.png`);
    this.load.image(TEX.wall, `${S}/Blocks/block_01.png`);
    this.load.image(TEX.block, `${S}/Crates/crate_42.png`);
    this.load.image(TEX.player1, `${S}/Player/player_01.png`);
    this.load.image(TEX.player2, `${S}/Player/player_05.png`);
  }

  create(): void {
    generateTextures(this);
    this.drawStaticBackground();
    this.hud = new Hud(this);
    this.bindKeys();

    this.net = new NetClient();
    this.net
      .connect()
      .then((room) => {
        this.room = room;
        this.connecting = false;
        room.onStateChange.once(() => {
          this.stateReady = true;
        });
      })
      .catch((err: unknown) => {
        this.connecting = false;
        this.connectError = err instanceof Error ? err.message : String(err);
        // eslint-disable-next-line no-console
        console.error('[crazy-nia] connect failed', err);
      });

    this.events.once(Phaser.Scenes.Events.SHUTDOWN, () => this.net?.leave());
    this.events.once(Phaser.Scenes.Events.DESTROY, () => this.net?.leave());

    // Expose for the headless smoke test (harmless in production).
    (this as unknown as { debugSnapshot: () => unknown }).debugSnapshot = () =>
      this.snapshot();
  }

  // Floor + solid walls never change, so draw them once. Crates are dynamic (server
  // destroys them) and handled per-state in renderGrid().
  private drawStaticBackground(): void {
    for (let row = 0; row < ROWS; row++) {
      for (let col = 0; col < COLS; col++) {
        this.add.image(cellToX(col), cellToY(row), TEX.floor).setDisplaySize(TILE, TILE).setDepth(0);
      }
    }
  }

  private bindKeys(): void {
    const kb = this.input.keyboard!;
    const make = (names: string[]) => names.map((n) => kb.addKey(n));
    this.keys = {
      up: make(LOCAL_KEYS.up),
      down: make(LOCAL_KEYS.down),
      left: make(LOCAL_KEYS.left),
      right: make(LOCAL_KEYS.right),
      bomb: make(LOCAL_KEYS.bomb),
    };
  }

  update(): void {
    this.pollInput();
    if (this.room && this.stateReady) this.render();
    this.renderHud();
  }

  // --- Input ----------------------------------------------------------------

  private pollInput(): void {
    if (!this.room || !this.stateReady) return;
    const me = (this.room.state as any).players?.get(this.room.sessionId);
    const held = (keys: Phaser.Input.Keyboard.Key[]) => keys.some((k) => k.isDown);

    let dir: Direction | null = null;
    if (me?.alive) {
      if (held(this.keys.left)) dir = 'left';
      else if (held(this.keys.right)) dir = 'right';
      else if (held(this.keys.up)) dir = 'up';
      else if (held(this.keys.down)) dir = 'down';
    }
    if (dir !== this.lastDir) {
      this.lastDir = dir;
      this.net.sendDir(dir);
    }

    if (me?.alive && this.keys.bomb.some((k) => Phaser.Input.Keyboard.JustDown(k))) {
      this.net.placeBomb();
    }
  }

  // --- Rendering (state -> sprites) -----------------------------------------

  private render(): void {
    const state = this.room!.state as any;
    this.renderGrid(state.grid);
    this.renderPlayers(state.players);
    this.renderCollection(state.bombs, this.bombSprites, TEX.bomb, 4, (b: any) => `${b.col},${b.row}`);
    this.renderCollection(
      state.explosions,
      this.explosionSprites,
      TEX.flame,
      6,
      (e: any) => `${e.col},${e.row}`,
    );
    this.renderItems(state.items);
  }

  // Draw walls + crates from the synced grid. Walls never change but are server-owned
  // (no client-side map generation), so we render them the same way as crates: add a
  // sprite when a cell becomes non-floor, remove it when a crate is destroyed.
  private renderGrid(grid: { length: number; [i: number]: number }): void {
    for (let i = 0; i < grid.length; i++) {
      const cell = grid[i];
      const existing = this.cellSprites.get(i);
      if (cell === CELL_FLOOR) {
        if (existing) {
          existing.destroy();
          this.cellSprites.delete(i);
        }
        continue;
      }
      if (!existing) {
        const col = i % COLS;
        const row = Math.floor(i / COLS);
        const tex = cell === CELL_SOLID ? TEX.wall : TEX.block;
        const img = this.add
          .image(cellToX(col), cellToY(row), tex)
          .setDisplaySize(TILE, TILE)
          .setDepth(2);
        this.cellSprites.set(i, img);
      }
    }
  }

  private renderPlayers(players: { forEach: (cb: (p: any, id: string) => void) => void }): void {
    const seen = new Set<string>();
    players.forEach((p: any, id: string) => {
      seen.add(id);
      let sprite = this.playerSprites.get(id);
      if (!sprite) {
        const tex = p.slot % 2 === 0 ? TEX.player1 : TEX.player2;
        sprite = this.add.image(p.px, p.py, tex).setDisplaySize(TILE, TILE).setDepth(5);
        sprite.setTint(p.color);
        this.playerSprites.set(id, sprite);
        const label = this.add
          .text(p.px, p.py - TILE * 0.55, `P${p.slot + 1}${id === this.room!.sessionId ? ' (나)' : ''}`, {
            fontFamily: 'system-ui, sans-serif',
            fontSize: '11px',
            color: '#ffffff',
          })
          .setOrigin(0.5)
          .setDepth(7);
        this.playerLabels.set(id, label);
      }

      // Interpolate toward the server's pixel target for smooth movement.
      sprite.x = Phaser.Math.Linear(sprite.x, p.px, 0.35);
      sprite.y = Phaser.Math.Linear(sprite.y, p.py, 0.35);
      sprite.setAlpha(p.alive ? 1 : 0.25);
      const label = this.playerLabels.get(id);
      if (label) {
        label.x = sprite.x;
        label.y = sprite.y - TILE * 0.55;
        label.setAlpha(p.alive ? 1 : 0.25);
      }
    });

    for (const [id, sprite] of this.playerSprites) {
      if (!seen.has(id)) {
        sprite.destroy();
        this.playerLabels.get(id)?.destroy();
        this.playerSprites.delete(id);
        this.playerLabels.delete(id);
      }
    }
  }

  // Generic add/remove sync for position-keyed collections (bombs, explosions).
  private renderCollection(
    coll: { forEach: (cb: (e: any) => void) => void },
    sprites: Map<string, Phaser.GameObjects.Image>,
    tex: string,
    depth: number,
    keyOf: (e: any) => string,
  ): void {
    const seen = new Set<string>();
    coll.forEach((e: any) => {
      const key = keyOf(e);
      seen.add(key);
      if (!sprites.has(key)) {
        const img = this.add
          .image(cellToX(e.col), cellToY(e.row), tex)
          .setDisplaySize(TILE, TILE)
          .setDepth(depth);
        sprites.set(key, img);
      }
    });
    for (const [key, img] of sprites) {
      if (!seen.has(key)) {
        img.destroy();
        sprites.delete(key);
      }
    }
  }

  private renderItems(items: { forEach: (cb: (e: any) => void) => void }): void {
    const seen = new Set<string>();
    items.forEach((it: any) => {
      const key = `${it.col},${it.row}`;
      seen.add(key);
      if (!this.itemSprites.has(key)) {
        const img = this.add
          .image(cellToX(it.col), cellToY(it.row), ITEM_TEX[it.kind as ItemKind] ?? TEX.itemBomb)
          .setDisplaySize(TILE, TILE)
          .setDepth(3);
        this.itemSprites.set(key, img);
      }
    });
    for (const [key, img] of this.itemSprites) {
      if (!seen.has(key)) {
        img.destroy();
        this.itemSprites.delete(key);
      }
    }
  }

  private renderHud(): void {
    if (this.connecting) {
      this.hud.update([], '서버에 접속 중…');
      return;
    }
    if (this.connectError || !this.room) {
      this.hud.update([], `서버 미접속 — 게임 서버를 먼저 실행하세요 (npm run server)`);
      return;
    }
    if (!this.stateReady) {
      this.hud.update([], '게임 상태 동기화 중…');
      return;
    }
    const state = this.room.state as any;
    const players: HudPlayer[] = [];
    state.players.forEach((p: any) => {
      players.push({
        name: `P${p.slot + 1}`,
        maxBombs: p.maxBombs,
        range: p.range,
        alive: p.alive,
        color: p.color,
      });
    });
    this.hud.update(players, state.status ?? '');
  }

  /** Observable snapshot for the headless smoke test (no gameplay effect). */
  private snapshot(): unknown {
    if (!this.room) {
      return { connected: false, connecting: this.connecting, error: this.connectError };
    }
    const state = this.room.state as any;
    let blocks = 0;
    for (let i = 0; i < state.grid.length; i++) if (state.grid[i] === CELL_BLOCK) blocks++;
    const players: any[] = [];
    state.players.forEach((p: any) =>
      players.push({ id: p.id, slot: p.slot, col: p.col, row: p.row, alive: p.alive, bombs: p.maxBombs, range: p.range }),
    );
    return {
      connected: true,
      sessionId: this.room.sessionId,
      blocks,
      bombs: state.bombs.length,
      explosions: state.explosions.length,
      items: state.items.length,
      status: state.status,
      roundOver: state.roundOver,
      players,
    };
  }
}
