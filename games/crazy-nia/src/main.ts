import Phaser from 'phaser';
import { GAME_WIDTH, GAME_HEIGHT, SERVER_URL } from './config';
import { GameScene } from './scenes/GameScene';
import { bootstrapDiscord, type DiscordSession } from './discord/bootstrap';

// Boot the Discord Activity integration BEFORE the game starts. Outside Discord this
// resolves immediately to a no-op session (standalone local/web build untouched);
// inside Discord it authenticates and resolves the per-instance room + user info,
// which we hand to the scene via the game registry.
async function start(): Promise<void> {
  let session: DiscordSession;
  try {
    session = await bootstrapDiscord(SERVER_URL);
  } catch (err) {
    // Inside Discord, a failed auth/token exchange is fatal — show a friendly screen
    // instead of a blank canvas. Outside Discord bootstrap never throws.
    renderFatal(err);
    return;
  }

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
  game.registry.set('discordSession', session);

  // Expose the game for the headless smoke test (harmless in production).
  (window as unknown as { __crazyNia?: Phaser.Game }).__crazyNia = game;
}

// Minimal fatal-error screen for the in-Discord auth/token-exchange failure path.
function renderFatal(err: unknown): void {
  const msg = err instanceof Error ? err.message : String(err);
  const host = document.getElementById('game');
  if (host) {
    host.innerHTML = `<div style="padding:24px;max-width:520px;color:#ffd0d6;font:14px/1.6 system-ui,sans-serif;text-align:center">
      <div style="font-size:32px;margin-bottom:8px">⚠️</div>
      <div style="font-weight:600;margin-bottom:6px">Discord Activity 를 시작할 수 없습니다</div>
      <div style="opacity:.8">${msg}</div>
    </div>`;
  }
  // eslint-disable-next-line no-console
  console.error('[crazy-nia] discord bootstrap failed', err);
}

void start();
