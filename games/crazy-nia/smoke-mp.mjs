// Multiplayer sync smoke test. Requires BOTH the Colyseus server (npm run server)
// and the client dev server (npm run dev) to be running.
//
// It opens two browser sessions that join the same authoritative room, then:
//   1) asserts both sessions see each other (2 players in synced state),
//   2) drives player A to move + drop a bomb,
//   3) asserts session B observes A's movement AND the block count drop
//      (i.e. state mutated on the server is reflected on every client).
// Zero console errors required. Run: node smoke-mp.mjs
import { chromium } from 'playwright';

const URL = process.env.SMOKE_URL || 'http://127.0.0.1:5173/';

function snapshot(page) {
  return page.evaluate(() => {
    const g = window.__crazyNia;
    const scene = g?.scene?.getScene?.('game');
    return scene?.debugSnapshot ? scene.debugSnapshot() : null;
  });
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

const errors = [];
function watch(page, tag) {
  page.on('console', (m) => {
    if (m.type() === 'error' && !/favicon\.ico/.test(m.text())) errors.push(`[${tag}] ${m.text()}`);
  });
  page.on('pageerror', (e) => errors.push(`[${tag}] pageerror: ${e.message}`));
  page.on('requestfailed', (r) => {
    if (!/favicon\.ico/.test(r.url())) errors.push(`[${tag}] requestfailed: ${r.url()}`);
  });
}

const ctxA = await browser.newContext();
const ctxB = await browser.newContext();
const pageA = await ctxA.newPage();
const pageB = await ctxB.newPage();
watch(pageA, 'A');
watch(pageB, 'B');

await pageA.goto(URL, { waitUntil: 'networkidle' });
await pageB.goto(URL, { waitUntil: 'networkidle' });

// Both clients connect + see each other (2 players in the synced state).
const bothConnected = await waitUntil(async () => {
  const a = await snapshot(pageA);
  const b = await snapshot(pageB);
  return a?.connected && b?.connected && a.players.length >= 2 && b.players.length >= 2;
});

const aInit = await snapshot(pageA);
const bInit = await snapshot(pageB);

// Identify player A's session id, find that player as observed BY session B.
const aId = aInit?.sessionId;
const aSeenByBBefore = bInit?.players.find((p) => p.id === aId);

// Drive A: move right a couple cells, then drop a bomb at a spot that touches a crate.
await pageA.bringToFront();
await pressOnce(pageA, 'ArrowDown', 200);
await pageA.waitForTimeout(120);
await pressOnce(pageA, 'ArrowRight', 200);
await pageA.waitForTimeout(200);

const aMovedSeenByB = await waitUntil(async () => {
  const b = await snapshot(pageB);
  const aSeen = b?.players.find((p) => p.id === aId);
  return (
    aSeen &&
    aSeenByBBefore &&
    (aSeen.col !== aSeenByBBefore.col || aSeen.row !== aSeenByBBefore.row)
  );
}, 6000);

// Bomb: drop and wait past fuse(~2.2s)+explosion(~0.42s); assert blocks drop on BOTH.
const blocksBeforeA = (await snapshot(pageA))?.blocks ?? 0;
const blocksBeforeB = (await snapshot(pageB))?.blocks ?? 0;
await pressOnce(pageA, 'Space', 60);
const blocksDropped = await waitUntil(async () => {
  const a = await snapshot(pageA);
  const b = await snapshot(pageB);
  return a && b && a.blocks < blocksBeforeA && b.blocks < blocksBeforeB && a.blocks === b.blocks;
}, 6000);

const aFinal = await snapshot(pageA);
const bFinal = await snapshot(pageB);

if (process.env.SMOKE_SHOT) {
  await pageB.screenshot({ path: process.env.SMOKE_SHOT });
}

await browser.close();

const result = {
  url: URL,
  bothConnected,
  aSessionId: aId,
  playersSeenByA: aInit?.players.length,
  playersSeenByB: bInit?.players.length,
  aMovedSeenByB,
  blocksBeforeA,
  blocksBeforeB,
  blocksAfterA: aFinal?.blocks,
  blocksAfterB: bFinal?.blocks,
  blocksDropped,
  statusA: aFinal?.status,
  consoleErrors: errors,
};
console.log(JSON.stringify(result, null, 2));

const pass = bothConnected && aMovedSeenByB && blocksDropped && errors.length === 0;
console.log(pass ? '\nMP SMOKE: PASS' : '\nMP SMOKE: FAIL');
process.exit(pass ? 0 : 1);
