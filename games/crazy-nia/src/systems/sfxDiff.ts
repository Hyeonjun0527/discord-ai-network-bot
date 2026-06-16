// Pure mapping from a before/after snapshot of the synced game state to the sound
// effects that should fire this frame. Kept pure (no Phaser, no WebAudio) so the
// event-detection logic is unit-testable in isolation from the renderer/audio.

import type { SfxEvent } from './AudioManager';

// The minimal slice of synced state the SFX detector needs. The renderer builds this
// each frame from the Colyseus state; tests construct it by hand.
export interface SfxSnapshot {
  bombKeys: string[]; // "col,row" of live bombs
  itemCells: string[]; // "col,row" of items currently on the board
  playerCells: string[]; // "col,row" of LIVING players (for pickup detection)
  deadCount: number; // number of players currently dead
  roundOver: boolean;
}

export function emptySnapshot(): SfxSnapshot {
  return { bombKeys: [], itemCells: [], playerCells: [], deadCount: 0, roundOver: false };
}

/**
 * Compares the previous and current snapshots and returns the SFX events to play:
 *  - bombPlace  : a new bomb key appeared
 *  - bombExplode: a bomb key vanished (detonated)
 *  - pickup     : an item vanished while a living player stood on its cell
 *  - death      : the dead-player count rose
 *  - roundStart : roundOver went true -> false (a fresh round began)
 *  - win        : roundOver went false -> true (round ended)
 */
export function diffSfx(prev: SfxSnapshot, next: SfxSnapshot): SfxEvent[] {
  const events: SfxEvent[] = [];

  const prevBombs = new Set(prev.bombKeys);
  const nextBombs = new Set(next.bombKeys);
  if (next.bombKeys.some((k) => !prevBombs.has(k))) events.push('bombPlace');
  if (prev.bombKeys.some((k) => !nextBombs.has(k))) events.push('bombExplode');

  const nextItems = new Set(next.itemCells);
  const livingNow = new Set(next.playerCells);
  // An item that disappeared AND a living player is on that cell = a pickup (vs burned).
  const pickedUp = prev.itemCells.some((cell) => !nextItems.has(cell) && livingNow.has(cell));
  if (pickedUp) events.push('pickup');

  if (next.deadCount > prev.deadCount) events.push('death');

  if (prev.roundOver && !next.roundOver) events.push('roundStart');
  if (!prev.roundOver && next.roundOver) events.push('win');

  return events;
}
