import { test } from 'node:test';
import assert from 'node:assert/strict';
import { diffSfx, emptySnapshot, type SfxSnapshot } from './sfxDiff';

function snap(over: Partial<SfxSnapshot> = {}): SfxSnapshot {
  return { ...emptySnapshot(), ...over };
}

test('no change yields no events', () => {
  assert.deepEqual(diffSfx(snap(), snap()), []);
});

test('a new bomb key fires bombPlace', () => {
  const events = diffSfx(snap(), snap({ bombKeys: ['3,4'] }));
  assert.deepEqual(events, ['bombPlace']);
});

test('a vanished bomb key fires bombExplode', () => {
  const events = diffSfx(snap({ bombKeys: ['3,4'] }), snap());
  assert.deepEqual(events, ['bombExplode']);
});

test('item gone under a living player fires pickup', () => {
  const prev = snap({ itemCells: ['5,5'], playerCells: ['1,1'] });
  const next = snap({ itemCells: [], playerCells: ['5,5'] });
  assert.deepEqual(diffSfx(prev, next), ['pickup']);
});

test('item gone with NO player on the cell does not fire pickup (burned by blast)', () => {
  const prev = snap({ itemCells: ['5,5'], playerCells: ['1,1'] });
  const next = snap({ itemCells: [], playerCells: ['1,1'] });
  assert.deepEqual(diffSfx(prev, next), []);
});

test('rising dead count fires death', () => {
  assert.deepEqual(diffSfx(snap({ deadCount: 0 }), snap({ deadCount: 1 })), ['death']);
});

test('roundOver false -> true fires win', () => {
  assert.deepEqual(diffSfx(snap({ roundOver: false }), snap({ roundOver: true })), ['win']);
});

test('roundOver true -> false fires roundStart', () => {
  assert.deepEqual(diffSfx(snap({ roundOver: true }), snap({ roundOver: false })), ['roundStart']);
});

test('multiple simultaneous changes fire all matching events', () => {
  const prev = snap({ bombKeys: ['1,1'], deadCount: 0, roundOver: false });
  const next = snap({ bombKeys: ['2,2'], deadCount: 1, roundOver: true });
  const events = diffSfx(prev, next);
  assert.ok(events.includes('bombPlace'));
  assert.ok(events.includes('bombExplode'));
  assert.ok(events.includes('death'));
  assert.ok(events.includes('win'));
});
