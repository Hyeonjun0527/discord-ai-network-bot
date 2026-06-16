// Multiplayer sync smoke test. Requires BOTH the Colyseus server (npm run server)
// and the client dev server (npm run dev) to be running.
//
// It opens two browser sessions that join the same authoritative room, then:
//   1) asserts both sessions see each other (2 players in synced state),
//   2) drives player A to move until adjacent to a destructible block, then drops a bomb,
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

// Extra settle: wait until me.col/row is populated and game is running.
await waitUntil(async () => {
  const a = await snapshot(pageA);
  return a?.me != null && a.roundOver === false;
}, 5000);

const aInit = await snapshot(pageA);
const bInit = await snapshot(pageB);

// Identify player A's session id, find that player as observed BY session B.
const aId = aInit?.sessionId;
const aSeenByBBefore = bInit?.players.find((p) => p.id === aId);

// Drive A: navigate toward the map interior to find a cell adjacent to a block.
// Hold each direction long enough to cross 3+ cells (~1000ms at BASE_SPEED=153px/s,
// 3 cells = 144px takes ~940ms). After each burst, let the state settle, then check
// snapshot.me for a neighbouring block. Try multiple directions until one is found.
await pageA.bringToFront();

const SEARCH_DIRS = [
  // Prefer interior-facing directions first (varies by spawn, but Down+Left covers most)
  { key: 'ArrowDown',  blockProp: 'blockDown'  },
  { key: 'ArrowLeft',  blockProp: 'blockLeft'  },
  { key: 'ArrowRight', blockProp: 'blockRight' },
  { key: 'ArrowUp',    blockProp: 'blockUp'    },
];

let foundBlock = false;
let sA = await snapshot(pageA);

// Check spawn position first.
if (sA?.me) {
  foundBlock = [sA.me.blockUp, sA.me.blockDown, sA.me.blockLeft, sA.me.blockRight].some(Boolean);
}

if (!foundBlock) {
  for (const { key } of SEARCH_DIRS) {
    // Hold direction for ~3 cell transits worth of time.
    await pressOnce(pageA, key, 1000);
    // Settle: wait for server tick + patch + client render cycle.
    await pageA.waitForTimeout(200);
    sA = await snapshot(pageA);
    if (sA?.me) {
      foundBlock = [sA.me.blockUp, sA.me.blockDown, sA.me.blockLeft, sA.me.blockRight].some(Boolean);
      if (foundBlock) break;
    }
  }
}

// Verify movement was observed by B (A has moved from its spawn position).
const aMovedSeenByB = await waitUntil(async () => {
  const b = await snapshot(pageB);
  const aSeen = b?.players.find((p) => p.id === aId);
  return (
    aSeen &&
    aSeenByBBefore &&
    (aSeen.col !== aSeenByBBefore.col || aSeen.row !== aSeenByBBefore.row)
  );
}, 6000);

// Bomb: drop and wait for block destruction. Track the minimum block count seen so a
// round-reset (which regenerates the map) doesn't hide a genuine destruction that
// already happened on both clients.
const blocksBeforeA = (await snapshot(pageA))?.blocks ?? 0;
const blocksBeforeB = (await snapshot(pageB))?.blocks ?? 0;
let minBlocksA = blocksBeforeA;
let minBlocksB = blocksBeforeB;
await pressOnce(pageA, 'Space', 60);

// fuse=2200ms + explosion=420ms ≈ 2620ms total; poll for 7s to cover fuse+explosion.
const blocksDropped = await waitUntil(async () => {
  const a = await snapshot(pageA);
  const b = await snapshot(pageB);
  if (a && a.blocks < minBlocksA) minBlocksA = a.blocks;
  if (b && b.blocks < minBlocksB) minBlocksB = b.blocks;
  // Both sides must have seen a drop AND agreed on the same minimum (sync check).
  return minBlocksA < blocksBeforeA && minBlocksB < blocksBeforeB && minBlocksA === minBlocksB;
}, 7000);

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
  minBlocksA,
  minBlocksB,
  blocksDropped,
  foundBlockNeighbour: foundBlock,
  statusA: aFinal?.status,
  consoleErrors: errors,
};
console.log(JSON.stringify(result, null, 2));

const pass = bothConnected && aMovedSeenByB && blocksDropped && errors.length === 0;
console.log(pass ? '\nMP SMOKE: PASS' : '\nMP SMOKE: FAIL');
process.exit(pass ? 0 : 1);
