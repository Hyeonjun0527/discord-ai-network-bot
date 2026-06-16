// WebAudio-synthesised sound effects — no asset downloads, so it's reliable offline and
// inside the Discord Activity sandbox. Each SFX is a short synthesised blip built from
// oscillators + a gain envelope. The manager also owns mute state and the autoplay
// unlock (browsers require a user gesture before an AudioContext can start).
//
// Testability: the pure event->voice mapping and the mute gate live in `voiceFor` and
// `play` and DO NOT touch WebAudio unless an AudioContext is actually present, so the
// unit tests can drive `play` with a fake context and assert which voice fired.

export type SfxEvent =
  | 'bombPlace'
  | 'bombExplode'
  | 'pickup'
  | 'death'
  | 'roundStart'
  | 'win';

// A voice is a recipe the synth renders: one or more tones with start offset, frequency
// sweep, duration, gain, and waveform. Pure data — easy to assert in tests.
export interface Tone {
  type: OscillatorType;
  freq: number; // starting frequency (Hz)
  freqEnd?: number; // optional linear ramp target by the tone's end
  start: number; // seconds from play() time
  duration: number; // seconds
  gain: number; // peak gain (0..1)
}

export interface Voice {
  readonly tones: ReadonlyArray<Tone>;
}

// SSOT for what each game event sounds like. Short + punchy (Crazy-Arcade flavour).
const VOICES: Record<SfxEvent, Voice> = {
  // crisp downward tick when a bomb is dropped
  bombPlace: { tones: [{ type: 'square', freq: 440, freqEnd: 220, start: 0, duration: 0.09, gain: 0.18 }] },
  // low boom + noisy-ish high crackle
  bombExplode: {
    tones: [
      { type: 'sawtooth', freq: 180, freqEnd: 40, start: 0, duration: 0.32, gain: 0.3 },
      { type: 'square', freq: 90, freqEnd: 30, start: 0, duration: 0.28, gain: 0.22 },
      { type: 'triangle', freq: 1200, freqEnd: 300, start: 0, duration: 0.12, gain: 0.12 },
    ],
  },
  // bright two-note up arpeggio for collecting a power-up
  pickup: {
    tones: [
      { type: 'triangle', freq: 660, start: 0, duration: 0.08, gain: 0.2 },
      { type: 'triangle', freq: 990, start: 0.07, duration: 0.1, gain: 0.2 },
    ],
  },
  // descending sad blip on death
  death: {
    tones: [
      { type: 'sawtooth', freq: 400, freqEnd: 120, start: 0, duration: 0.35, gain: 0.22 },
    ],
  },
  // short rising fanfare to start a round
  roundStart: {
    tones: [
      { type: 'square', freq: 523, start: 0, duration: 0.1, gain: 0.16 },
      { type: 'square', freq: 659, start: 0.1, duration: 0.1, gain: 0.16 },
      { type: 'square', freq: 784, start: 0.2, duration: 0.16, gain: 0.18 },
    ],
  },
  // triumphant three-note victory jingle
  win: {
    tones: [
      { type: 'square', freq: 659, start: 0, duration: 0.12, gain: 0.18 },
      { type: 'square', freq: 784, start: 0.12, duration: 0.12, gain: 0.18 },
      { type: 'square', freq: 1047, start: 0.24, duration: 0.24, gain: 0.2 },
    ],
  },
};

// Minimal subset of the WebAudio API we use — lets tests inject a fake context.
export interface AudioContextLike {
  readonly currentTime: number;
  readonly destination: AudioNode;
  readonly state: string;
  resume(): Promise<void>;
  createOscillator(): OscillatorNode;
  createGain(): GainNode;
}

export type AudioContextFactory = () => AudioContextLike | null;

// Pure lookup so callers/tests can introspect what an event maps to without playing it.
export function voiceFor(event: SfxEvent): Voice {
  return VOICES[event];
}

function defaultFactory(): AudioContextLike | null {
  if (typeof window === 'undefined') return null;
  const Ctor =
    (window as unknown as { AudioContext?: new () => AudioContextLike }).AudioContext ??
    (window as unknown as { webkitAudioContext?: new () => AudioContextLike }).webkitAudioContext;
  return Ctor ? new Ctor() : null;
}

export class AudioManager {
  private ctx: AudioContextLike | null = null;
  private muted = false;
  private readonly master: number;
  private readonly factory: AudioContextFactory;

  constructor(opts: { masterGain?: number; factory?: AudioContextFactory } = {}) {
    this.master = opts.masterGain ?? 0.8;
    this.factory = opts.factory ?? defaultFactory;
  }

  get isMuted(): boolean {
    return this.muted;
  }

  setMuted(muted: boolean): void {
    this.muted = muted;
  }

  toggleMute(): boolean {
    this.muted = !this.muted;
    return this.muted;
  }

  // Create + resume the AudioContext after a user gesture (autoplay policy). Idempotent.
  unlock(): void {
    if (!this.ctx) this.ctx = this.factory();
    if (this.ctx && this.ctx.state === 'suspended') void this.ctx.resume();
  }

  // Play an event's voice. No-ops when muted or when no AudioContext is available
  // (server/tests without WebAudio). Returns true if it actually scheduled audio.
  play(event: SfxEvent): boolean {
    if (this.muted) return false;
    if (!this.ctx) this.ctx = this.factory();
    const ctx = this.ctx;
    if (!ctx) return false;
    const voice = VOICES[event];
    const now = ctx.currentTime;
    for (const tone of voice.tones) this.renderTone(ctx, tone, now);
    return true;
  }

  private renderTone(ctx: AudioContextLike, tone: Tone, now: number): void {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = tone.type;
    const t0 = now + tone.start;
    const t1 = t0 + tone.duration;
    osc.frequency.setValueAtTime(tone.freq, t0);
    if (tone.freqEnd !== undefined) osc.frequency.linearRampToValueAtTime(tone.freqEnd, t1);
    const peak = tone.gain * this.master;
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.linearRampToValueAtTime(peak, t0 + Math.min(0.01, tone.duration * 0.3));
    gain.gain.exponentialRampToValueAtTime(0.0001, t1);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(t0);
    osc.stop(t1 + 0.02);
  }
}
