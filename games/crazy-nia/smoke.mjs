// Single-client smoke test: with the game server running, loads the dev server,
// asserts the canvas renders with zero console errors, the client connects to the
// authoritative room, and that local input reaches the server (the local player
// moves, and a bomb drop destroys a block in the synced state).
// Run: npm run server (terminal 1) + npm run dev (terminal 2) + node smoke.mjs
import { chromium } from 'playwright';

const URL = process.env.SMOKE_URL || 'http://127.0.0.1:5173/';
const errors = [];

function snapshot(page) {
  return page.evaluate(() => {
    const g = window.__crazyNia;
    const scene = g?.scene?.getScene?.('game');
    return scene?.debugSnapshot ? scene.debugSnapshot() : null;
  });
}

function me(snap) {
  return snap?.players?.find((p) => p.id === snap.sessionId) ?? null;
}

async function pressOnce(page, key, holdMs = 140) {
  await page.keyboard.down(key);
  await page.waitForTimeout(holdMs);
  await page.keyboard.up(key);
}

async function waitUntil(fn, timeoutMs = 10000, stepMs = 150) {
  const start = Date.now();
  for (;;) {
    if (await fn()) return true;
    if (Date.now() - start > timeoutMs) return false;
    await new Promise((r) => setTimeout(r, stepMs));
  }
}

let browser;
try {
  browser = await chromium.launch();
} catch {
  browser = await chromium.launch({ channel: 'chrome' });
}
const page = await browser.newPage();
page.on('console', (m) => {
  if (m.type() === 'error' && !/favicon\.ico/.test(m.text())) errors.push(m.text());
});
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('requestfailed', (r) => {
  if (!/favicon\.ico/.test(r.url())) errors.push(`requestfailed: ${r.url()}`);
});

await page.goto(URL, { waitUntil: 'networkidle' });

// 1) Canvas rendered?
await page.waitForSelector('#game canvas', { timeout: 10000 });
const canvas = await page.$('#game canvas');
const box = await canvas.boundingBox();
const canvasOk = !!box && box.width > 100 && box.height > 100;

// 2) Connected to the authoritative room + state synced?
const connected = await waitUntil(async () => {
  const s = await snapshot(page);
  return s?.connected && s.players?.length >= 1 && s.blocks > 0;
});
const initial = await snapshot(page);

// 3) Movement: the local player moves at least one cell via input -> server.
const before = me(initial);
let movedOk = false;
for (const key of ['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp']) {
  await pressOnce(page, key, 220);
  await page.waitForTimeout(120);
  const now = me(await snapshot(page));
  if (now && before && (now.col !== before.col || now.row !== before.row)) {
    movedOk = true;
    break;
  }
}

// 4) Bomb + explosion destroys at least one block in the synced state.
const blocksBefore = (await snapshot(page)).blocks;
await pressOnce(page, 'Space', 60);
const blocksDestroyedOk = await waitUntil(
  async () => (await snapshot(page)).blocks < blocksBefore,
  6000,
);
const after = await snapshot(page);

if (process.env.SMOKE_SHOT) {
  await page.screenshot({ path: process.env.SMOKE_SHOT });
}

await browser.close();

const result = {
  url: URL,
  canvasOk,
  canvasBox: box,
  connected,
  movedOk,
  blocksBefore,
  blocksAfter: after?.blocks,
  blocksDestroyedOk,
  consoleErrors: errors,
};
console.log(JSON.stringify(result, null, 2));

const pass = canvasOk && connected && movedOk && blocksDestroyedOk && errors.length === 0;
console.log(pass ? '\nSMOKE: PASS' : '\nSMOKE: FAIL');
process.exit(pass ? 0 : 1);
