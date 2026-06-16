import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { CrazyRoom } from './CrazyRoom';
import { GameState, PlayerState } from './state';
import { SPAWNS, cellToX, cellToY } from '../../shared/constants';

// Pure round-flow tests. We drive the room's simulation by hand (no Colyseus server)
// so we can assert how `checkWinCondition` reacts to a player dying. The room is built
// with the same field wiring `onCreate`/`onJoin` would do, minus the network plumbing.
type Internal = {
  state: GameState;
  runtimes: Map<string, { dir: null; speedLevel: number }>;
  checkWinCondition(): void;
};

function makePlayer(id: string, slot: number): PlayerState {
  const spawn = SPAWNS[slot];
  const p = new PlayerState();
  p.id = id;
  p.slot = slot;
  p.col = spawn.col;
  p.row = spawn.row;
  p.px = cellToX(spawn.col);
  p.py = cellToY(spawn.row);
  p.alive = true;
  return p;
}

function buildRoom(playerIds: string[]): { room: CrazyRoom; inner: Internal } {
  const room = new CrazyRoom();
  const inner = room as unknown as Internal;
  inner.state = new GameState();
  playerIds.forEach((id, i) => {
    inner.state.players.set(id, makePlayer(id, i));
    inner.runtimes.set(id, { dir: null, speedLevel: 0 });
  });
  return { room, inner };
}

test('solo player who dies is revived after the respawn delay (no permadeath soft-lock)', async () => {
  const { room, inner } = buildRoom(['solo']);
  const me = inner.state.players.get('solo')!;

  // The lone player blows themselves up.
  me.alive = false;
  inner.checkWinCondition();

  // Death is registered and a respawn is scheduled (not an instant ignore).
  assert.equal(inner.state.roundOver, true);

  // After the scheduled reset fires, the solo player is alive again and the soft-lock
  // ("상대를 기다리는 중…") is recoverable rather than permanent.
  await delay(2300);
  assert.equal(me.alive, true);
  assert.equal(inner.state.roundOver, false);

  room.onDispose();
});

test('a single alive solo player does not end the round (still waiting)', () => {
  const { room, inner } = buildRoom(['solo']);
  inner.checkWinCondition();
  assert.equal(inner.state.roundOver, false);
  room.onDispose();
});

test('multiplayer win condition still ends the round when one survivor remains', () => {
  const { room, inner } = buildRoom(['a', 'b']);
  inner.state.players.get('b')!.alive = false;

  inner.checkWinCondition();

  assert.equal(inner.state.roundOver, true);
  assert.match(inner.state.status, /승리/);
  room.onDispose();
});

test('multiplayer round stays live while two players are alive', () => {
  const { room, inner } = buildRoom(['a', 'b']);
  inner.checkWinCondition();
  assert.equal(inner.state.roundOver, false);
  room.onDispose();
});
