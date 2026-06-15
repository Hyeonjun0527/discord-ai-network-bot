// Headless smoke test: loads the dev server, asserts the canvas renders with
// zero console errors, and that core game logic actually runs (movement +
// bomb explosion destroying a block). Run: node smoke.mjs  (dev server must be up)
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

async function pressOnce(page, key, holdMs = 120) {
  await page.keyboard.down(key);
  await page.waitForTimeout(holdMs);
  await page.keyboard.up(key);
}

// Prefer the bundled headless shell; fall back to the system Chrome channel so
// the smoke test runs without a fresh `playwright install`.
let browser;
try {
  browser = await chromium.launch();
} catch {
  browser = await chromium.launch({ channel: 'chrome' });
}
const page = await browser.newPage();
// Ignore the favicon 404 (cosmetic, browser auto-requests /favicon.ico).
page.on('console', (m) => {
  if (m.type() === 'error' && !/favicon\.ico/.test(m.text())) errors.push(m.text());
});
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('requestfailed', (r) => {
  if (!/favicon\.ico/.test(r.url())) errors.push(`requestfailed: ${r.url()}`);
});
page.on('response', (r) => {
  if (r.status() >= 400 && !/favicon\.ico/.test(r.url())) {
    errors.push(`http ${r.status()}: ${r.url()}`);
  }
});

await page.goto(URL, { waitUntil: 'networkidle' });

// 1) Canvas rendered?
await page.waitForSelector('#game canvas', { timeout: 10000 });
const canvas = await page.$('#game canvas');
const box = await canvas.boundingBox();
const canvasOk = !!box && box.width > 100 && box.height > 100;

// 2) Scene + snapshot available?
await page.waitForFunction(
  () => window.__crazyNia?.scene?.getScene?.('game')?.debugSnapshot,
  { timeout: 10000 },
);
const initial = await snapshot(page);

// 3) Movement: P1 moves right one cell.
const beforeMove = await snapshot(page);
await pressOnce(page, 'ArrowRight', 200);
await page.waitForTimeout(120);
const afterMove = await snapshot(page);
const movedOk = afterMove.players[0].col !== beforeMove.players[0].col;

// 4) Bomb + explosion destroys at least one block.
// P1 spawn (1,1) has open elbow at (2,1)/(1,2); blocks surround. Drop a bomb at
// spawn and wait past the fuse + explosion window, then compare block counts.
await page.keyboard.press('Home'); // no-op safe key to ensure focus
const blocksBefore = (await snapshot(page)).blocks;
await pressOnce(page, 'Space', 60);
await page.waitForTimeout(3200); // fuse (~2.2s) + explosion (~0.42s) + margin
const after = await snapshot(page);
const blocksDestroyedOk = after.blocks < blocksBefore;

if (process.env.SMOKE_SHOT) {
  await page.screenshot({ path: process.env.SMOKE_SHOT });
}

await browser.close();

const result = {
  url: URL,
  canvasOk,
  canvasBox: box,
  sceneLoaded: !!initial,
  movedOk,
  blocksBefore,
  blocksAfter: after.blocks,
  blocksDestroyedOk,
  consoleErrors: errors,
};
console.log(JSON.stringify(result, null, 2));

const pass = canvasOk && initial && movedOk && blocksDestroyedOk && errors.length === 0;
console.log(pass ? '\nSMOKE: PASS' : '\nSMOKE: FAIL');
process.exit(pass ? 0 : 1);
