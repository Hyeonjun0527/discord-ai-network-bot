import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  AudioManager,
  voiceFor,
  type AudioContextLike,
  type SfxEvent,
} from './AudioManager';

// Fake WebAudio context that records how many oscillators were created (= tones played).
function fakeCtx(state = 'running') {
  let resumed = false;
  let oscillators = 0;
  const param = () => ({
    setValueAtTime() {},
    linearRampToValueAtTime() {},
    exponentialRampToValueAtTime() {},
  });
  const ctx: AudioContextLike & { resumed: boolean; oscillators: number } = {
    currentTime: 0,
    destination: {} as AudioNode,
    get state() {
      return state;
    },
    async resume() {
      resumed = true;
      state = 'running';
    },
    createOscillator() {
      oscillators++;
      return {
        type: 'sine',
        frequency: param(),
        connect() {},
        start() {},
        stop() {},
      } as unknown as OscillatorNode;
    },
    createGain() {
      return { gain: param(), connect() {} } as unknown as GainNode;
    },
    get resumed() {
      return resumed;
    },
    get oscillators() {
      return oscillators;
    },
  };
  return ctx;
}

const ALL_EVENTS: SfxEvent[] = ['bombPlace', 'bombExplode', 'pickup', 'death', 'roundStart', 'win'];

test('voiceFor returns a non-empty voice for every event', () => {
  for (const e of ALL_EVENTS) {
    assert.ok(voiceFor(e).tones.length > 0, `${e} should have tones`);
  }
});

test('play schedules one oscillator per tone of the event voice', () => {
  const ctx = fakeCtx();
  const am = new AudioManager({ factory: () => ctx });
  const ok = am.play('bombExplode');
  assert.equal(ok, true);
  assert.equal(ctx.oscillators, voiceFor('bombExplode').tones.length);
});

test('muted play is a no-op (no oscillators, returns false)', () => {
  const ctx = fakeCtx();
  const am = new AudioManager({ factory: () => ctx });
  am.setMuted(true);
  assert.equal(am.play('win'), false);
  assert.equal(ctx.oscillators, 0);
});

test('toggleMute flips and reports the new state', () => {
  const am = new AudioManager({ factory: () => fakeCtx() });
  assert.equal(am.isMuted, false);
  assert.equal(am.toggleMute(), true);
  assert.equal(am.isMuted, true);
  assert.equal(am.toggleMute(), false);
});

test('play is a no-op when no AudioContext is available', () => {
  const am = new AudioManager({ factory: () => null });
  assert.equal(am.play('pickup'), false);
});

test('unlock resumes a suspended context', () => {
  const ctx = fakeCtx('suspended');
  const am = new AudioManager({ factory: () => ctx });
  am.unlock();
  assert.equal(ctx.resumed, true);
});

test('each event plays the correct number of tones (event -> voice mapping)', () => {
  for (const e of ALL_EVENTS) {
    const ctx = fakeCtx();
    const am = new AudioManager({ factory: () => ctx });
    am.play(e);
    assert.equal(ctx.oscillators, voiceFor(e).tones.length, `${e} tone count`);
  }
});
