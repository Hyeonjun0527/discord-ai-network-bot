import { Room, Client } from '@colyseus/core';
import {
  BombState,
  ExplosionState,
  GameState,
  ItemState,
  PlayerState,
} from './state';
import {
  BOMB_FUSE_MS,
  CELL_BLOCK,
  CELL_FLOOR,
  CELL_SOLID,
  COLS,
  EXPLOSION_MS,
  ITEM_DROP_CHANCE,
  MAX_BOMBS,
  MAX_PLAYERS,
  MAX_RANGE,
  PATCH_MS,
  ROWS,
  SPAWNS,
  START_BOMBS,
  START_RANGE,
  TICK_MS,
  TURN_TOLERANCE,
  cellToX,
  cellToY,
  inBounds,
  speedForLevel,
  xToCol,
  yToRow,
  type ItemKind,
} from '../../shared/constants';
import { generateRandomMap } from '../../shared/maps';
import {
  MSG_INPUT,
  MSG_PLACE_BOMB,
  type Direction,
  type InputMessage,
} from '../../shared/messages';

// Per-connection runtime input + movement bookkeeping (not synced — server-internal).
interface PlayerRuntime {
  dir: Direction | null; // currently held direction, or null
  speedLevel: number; // speed power-ups collected (raises continuous velocity)
}

// A live bomb's fuse, tracked server-side (the synced BombState only carries position).
interface BombRuntime {
  state: BombState;
  fuseMs: number;
}

// A burning cell with its remaining lethal window. Mirrors GameState.explosions.
interface ExplosionRuntime {
  state: ExplosionState;
  ttlMs: number;
}

// Join options a client may send. `instanceId` (set by the Discord Activity bootstrap)
// is used by the matchmaker (filterBy) to co-locate everyone from the same Activity
// instance; `name` is the player's Discord display name. Both optional — the plain
// local/web build sends none.
interface JoinOptions {
  instanceId?: string;
  name?: string;
}

const DIR_VECTORS: Record<Direction, { dc: number; dr: number }> = {
  up: { dc: 0, dr: -1 },
  down: { dc: 0, dr: 1 },
  left: { dc: -1, dr: 0 },
  right: { dc: 1, dr: 0 },
};

// Authoritative Bomberman room. The server owns the entire simulation; clients send
// only `input` / `placeBomb` and render the synced GameState.
export class CrazyRoom extends Room<GameState> {
  maxClients = MAX_PLAYERS;

  private readonly runtimes = new Map<string, PlayerRuntime>();
  private readonly bombRuntimes: BombRuntime[] = [];
  private readonly explosionRuntimes: ExplosionRuntime[] = [];
  private restartTimer: ReturnType<typeof setTimeout> | null = null;

  onCreate(): void {
    this.state = new GameState();
    this.resetMap();

    // Flush synced deltas at 30Hz (default is 20Hz) so the continuous px/py stream is
    // dense enough for smooth client interpolation.
    this.setPatchRate(PATCH_MS);

    this.onMessage<InputMessage>(MSG_INPUT, (client, msg) => {
      const rt = this.runtimes.get(client.sessionId);
      if (!rt) return;
      const dir = msg?.dir ?? null;
      rt.dir = dir && DIR_VECTORS[dir] ? dir : null;
    });

    this.onMessage(MSG_PLACE_BOMB, (client) => this.placeBomb(client.sessionId));

    this.setSimulationInterval((dt) => this.tick(dt), TICK_MS);
  }

  onJoin(client: Client, options?: JoinOptions): void {
    const slot = this.firstFreeSlot();
    const spawn = SPAWNS[slot];
    const p = new PlayerState();
    p.id = client.sessionId;
    p.name = (options?.name ?? '').slice(0, 32); // cap to keep the HUD label sane
    p.slot = slot;
    p.color = spawn.color;
    p.col = spawn.col;
    p.row = spawn.row;
    p.px = cellToX(spawn.col);
    p.py = cellToY(spawn.row);
    p.alive = true;
    p.maxBombs = START_BOMBS;
    p.range = START_RANGE;
    p.activeBombs = 0;
    this.state.players.set(client.sessionId, p);
    this.runtimes.set(client.sessionId, { dir: null, speedLevel: 0 });

    if (!this.state.roundOver) {
      this.state.status = this.state.players.size < 2 ? '상대를 기다리는 중…' : '대결 시작!';
    }
  }

  onLeave(client: Client): void {
    this.state.players.delete(client.sessionId);
    this.runtimes.delete(client.sessionId);
    if (!this.state.roundOver && this.state.players.size < 2) {
      this.state.status = '상대를 기다리는 중…';
    }
  }

  private firstFreeSlot(): number {
    const used = new Set<number>();
    this.state.players.forEach((p) => used.add(p.slot));
    for (let i = 0; i < MAX_PLAYERS; i++) if (!used.has(i)) return i;
    return 0;
  }

  // --- Simulation -----------------------------------------------------------

  private tick(dt: number): void {
    this.advancePlayers(dt);
    this.advanceBombs(dt);
    this.advanceExplosions(dt);
    this.applyHazards();
    this.checkWinCondition();
  }

  // Continuous constant-velocity movement (no per-cell stepping / cooldown / lerp).
  // px/py are the authoritative position; we slide them at a fixed px/sec in the held
  // direction, snapping to the lane and stopping at the cell center when the next cell
  // is blocked. col/row are derived from px/py (round) for bomb/item/blast logic.
  private advancePlayers(dt: number): void {
    const dtSec = dt / 1000;
    this.state.players.forEach((p, id) => {
      const rt = this.runtimes.get(id);
      if (!rt || !p.alive) return;

      // Keep the synced cell in sync with the current pixel position every tick.
      p.col = this.clampCol(xToCol(p.px));
      p.row = this.clampRow(yToRow(p.py));

      if (!rt.dir) return;
      const v = DIR_VECTORS[rt.dir];
      const speed = speedForLevel(rt.speedLevel);
      const dist = speed * dtSec;

      if (v.dc !== 0) this.moveHorizontal(p, v.dc, dist);
      else this.moveVertical(p, v.dr, dist);

      // Re-derive the cell after moving so placeBomb/pickups use the up-to-date cell.
      p.col = this.clampCol(xToCol(p.px));
      p.row = this.clampRow(yToRow(p.py));
    });
  }

  // Slide horizontally by `dist` px in direction `dc` (-1/+1). Snaps onto the row lane
  // (with cornering tolerance) and stops at the current cell center if the next cell is
  // blocked.
  private moveHorizontal(p: PlayerState, dc: number, dist: number): void {
    const row = this.clampRow(yToRow(p.py));
    const laneY = cellToY(row);
    // Pull toward the lane center so a near-aligned player corners smoothly; bail if too
    // far off-lane to turn onto (shouldn't happen in normal axis-locked play).
    if (Math.abs(p.py - laneY) > TURN_TOLERANCE) return;
    p.py = laneY;

    const col = this.clampCol(xToCol(p.px));
    const nextCol = col + dc;
    const centerX = cellToX(col);
    if (!this.cellOpen(nextCol, row)) {
      // Next cell blocked: advance only up to this cell's center, then stop.
      if (dc > 0) p.px = Math.min(centerX, p.px + dist);
      else p.px = Math.max(centerX, p.px - dist);
      return;
    }
    p.px += dc * dist;
  }

  // Vertical mirror of moveHorizontal.
  private moveVertical(p: PlayerState, dr: number, dist: number): void {
    const col = this.clampCol(xToCol(p.px));
    const laneX = cellToX(col);
    if (Math.abs(p.px - laneX) > TURN_TOLERANCE) return;
    p.px = laneX;

    const row = this.clampRow(yToRow(p.py));
    const nextRow = row + dr;
    const centerY = cellToY(row);
    if (!this.cellOpen(col, nextRow)) {
      if (dr > 0) p.py = Math.min(centerY, p.py + dist);
      else p.py = Math.max(centerY, p.py - dist);
      return;
    }
    p.py += dr * dist;
  }

  private cellOpen(col: number, row: number): boolean {
    return this.isWalkable(col, row) && !this.bombAt(col, row);
  }

  private clampCol(col: number): number {
    return col < 0 ? 0 : col > COLS - 1 ? COLS - 1 : col;
  }

  private clampRow(row: number): number {
    return row < 0 ? 0 : row > ROWS - 1 ? ROWS - 1 : row;
  }

  private advanceBombs(dt: number): void {
    for (const b of [...this.bombRuntimes]) {
      b.fuseMs -= dt;
      if (b.fuseMs <= 0) this.detonate(b);
    }
  }

  private advanceExplosions(dt: number): void {
    for (let i = this.explosionRuntimes.length - 1; i >= 0; i--) {
      const e = this.explosionRuntimes[i];
      e.ttlMs -= dt;
      if (e.ttlMs <= 0) {
        const idx = this.state.explosions.indexOf(e.state);
        if (idx >= 0) this.state.explosions.splice(idx, 1);
        this.explosionRuntimes.splice(i, 1);
      }
    }
  }

  // Kill players standing in a burning cell; burn items caught in the blast.
  private applyHazards(): void {
    const burning = new Set<string>();
    for (const e of this.explosionRuntimes) burning.add(`${e.state.col},${e.state.row}`);

    this.state.players.forEach((p) => {
      if (p.alive && burning.has(`${p.col},${p.row}`)) p.alive = false;
    });

    for (let i = this.state.items.length - 1; i >= 0; i--) {
      const it = this.state.items[i];
      if (burning.has(`${it.col},${it.row}`)) this.state.items.splice(i, 1);
    }

    // Pick up items the (living) player stands on.
    this.state.players.forEach((p, id) => {
      if (!p.alive) return;
      for (let i = this.state.items.length - 1; i >= 0; i--) {
        const it = this.state.items[i];
        if (it.col === p.col && it.row === p.row) {
          this.collect(p, id, it.kind as ItemKind);
          this.state.items.splice(i, 1);
        }
      }
    });
  }

  private collect(p: PlayerState, id: string, kind: ItemKind): void {
    if (kind === 'bomb') p.maxBombs = Math.min(MAX_BOMBS, p.maxBombs + 1);
    else if (kind === 'range') p.range = Math.min(MAX_RANGE, p.range + 1);
    else if (kind === 'speed') {
      const rt = this.runtimes.get(id);
      if (rt) rt.speedLevel += 1;
    }
  }

  // --- Bombs ----------------------------------------------------------------

  private placeBomb(id: string): void {
    const p = this.state.players.get(id);
    if (!p || !p.alive || p.activeBombs >= p.maxBombs) return;
    if (this.bombAt(p.col, p.row)) return;

    p.activeBombs += 1;
    const b = new BombState();
    b.col = p.col;
    b.row = p.row;
    b.range = p.range;
    b.ownerId = id;
    this.state.bombs.push(b);
    this.bombRuntimes.push({ state: b, fuseMs: BOMB_FUSE_MS });
  }

  private detonate(bomb: BombRuntime): void {
    // Remove from synced + runtime lists first so chain reactions don't re-enter.
    const sIdx = this.state.bombs.indexOf(bomb.state);
    if (sIdx >= 0) this.state.bombs.splice(sIdx, 1);
    const rIdx = this.bombRuntimes.indexOf(bomb);
    if (rIdx >= 0) this.bombRuntimes.splice(rIdx, 1);

    const owner = this.state.players.get(bomb.state.ownerId);
    if (owner) owner.activeBombs = Math.max(0, owner.activeBombs - 1);

    const { col, row, range } = bomb.state;
    const flameCells: Array<{ col: number; row: number }> = [{ col, row }];

    const dirs = [
      { dc: 1, dr: 0 },
      { dc: -1, dr: 0 },
      { dc: 0, dr: 1 },
      { dc: 0, dr: -1 },
    ];
    for (const d of dirs) {
      for (let step = 1; step <= range; step++) {
        const c = col + d.dc * step;
        const r = row + d.dr * step;
        const cell = this.cellAt(c, r);
        if (cell === CELL_SOLID) break;
        flameCells.push({ col: c, row: r });
        if (cell === CELL_BLOCK) {
          this.setCell(c, r, CELL_FLOOR);
          this.maybeDropItem(c, r);
          break; // crate absorbs the rest of this arm
        }
      }
    }

    for (const fc of flameCells) {
      this.spawnExplosion(fc.col, fc.row);
      // Chain-detonate any bomb caught in the blast.
      const caught = this.bombRuntimes.find(
        (b) => b.state.col === fc.col && b.state.row === fc.row,
      );
      if (caught) this.detonate(caught);
    }
  }

  private spawnExplosion(col: number, row: number): void {
    const e = new ExplosionState();
    e.col = col;
    e.row = row;
    this.state.explosions.push(e);
    this.explosionRuntimes.push({ state: e, ttlMs: EXPLOSION_MS });
  }

  private maybeDropItem(col: number, row: number): void {
    if (Math.random() > ITEM_DROP_CHANCE) return;
    const kinds: ItemKind[] = ['bomb', 'range', 'speed'];
    const it = new ItemState();
    it.col = col;
    it.row = row;
    it.kind = kinds[Math.floor(Math.random() * kinds.length)];
    this.state.items.push(it);
  }

  // --- Round flow -----------------------------------------------------------

  private checkWinCondition(): void {
    if (this.state.roundOver) return;
    // A round only "ends" once at least two players have joined the battle.
    if (this.state.players.size < 2) return;

    const alive: PlayerState[] = [];
    this.state.players.forEach((p) => {
      if (p.alive) alive.push(p);
    });
    if (alive.length > 1) return;

    this.state.roundOver = true;
    this.state.status =
      alive.length === 1
        ? `${this.label(alive[0])} 승리!  —  잠시 후 새 라운드`
        : '무승부!  —  잠시 후 새 라운드';

    this.restartTimer = setTimeout(() => this.resetRound(), 4000);
  }

  private resetRound(): void {
    this.resetMap();
    let slot = 0;
    this.state.players.forEach((p, id) => {
      const spawn = SPAWNS[p.slot] ?? SPAWNS[slot % SPAWNS.length];
      slot++;
      p.col = spawn.col;
      p.row = spawn.row;
      p.px = cellToX(spawn.col);
      p.py = cellToY(spawn.row);
      p.alive = true;
      p.maxBombs = START_BOMBS;
      p.range = START_RANGE;
      p.activeBombs = 0;
      const rt = this.runtimes.get(id);
      if (rt) {
        rt.dir = null;
        rt.speedLevel = 0;
      }
    });
    this.state.roundOver = false;
    this.state.status = this.state.players.size >= 2 ? '대결 시작!' : '상대를 기다리는 중…';
  }

  private resetMap(): void {
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    this.bombRuntimes.length = 0;
    this.explosionRuntimes.length = 0;
    this.state.bombs.clear();
    this.state.explosions.clear();
    this.state.items.clear();
    const grid = generateRandomMap();
    this.state.grid.clear();
    this.state.grid.push(...grid);
  }

  onDispose(): void {
    if (this.restartTimer) clearTimeout(this.restartTimer);
  }

  // --- Grid helpers ---------------------------------------------------------

  private cellAt(col: number, row: number): number {
    if (!inBounds(col, row)) return CELL_SOLID;
    return this.state.grid[row * COLS + col];
  }

  private setCell(col: number, row: number, kind: number): void {
    if (inBounds(col, row)) this.state.grid[row * COLS + col] = kind;
  }

  private isWalkable(col: number, row: number): boolean {
    return this.cellAt(col, row) === CELL_FLOOR;
  }

  private bombAt(col: number, row: number): boolean {
    return this.bombRuntimes.some((b) => b.state.col === col && b.state.row === row);
  }

  private label(p: PlayerState): string {
    return p.name.length > 0 ? p.name : `P${p.slot + 1}`;
  }
}
