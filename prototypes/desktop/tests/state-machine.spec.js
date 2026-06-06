// 상태머신 불변식(I1~I3) 검증 — docs/NEXA_STATE_MACHINE.md.
// 이 파일이 "인증된 상태에서 Discord 로그인 노출" 같은 상태 오류의 회귀를 막는다.
const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
});

test('초기 상태: main + authed (I1)', async ({ page }) => {
  const app = await page.evaluate(() => window.App);
  expect(app.stage).toBe('main');
  expect(app.authed).toBe(true);
});

test('I2: 인증된 상태(main)에서는 Discord 로그인 UI 가 어디에도 없다', async ({ page }) => {
  // 서버 화면
  await page.click('.nav-item[data-view="servers"]');
  await expect(page.locator('#srvLoginBtn')).toHaveCount(0);
  // 서버 추가 → 로그인 없이 서버 선택부터(I3)
  await page.evaluate(() => window.enterServerAdd());
  await expect(page.locator('#cxLogin')).toHaveCount(0);
  await expect(page.locator('#connectWiz .wt span')).toHaveText('서버 추가');
  await expect(page.locator('#connectWiz .cx-cand').first()).toBeVisible();
  expect(await page.evaluate(() => window.App.connectOrigin)).toBe('main');
});

test('I1: 온보딩 진입 시 authed=false, 연동 화면에 Discord 로그인 노출(I2)', async ({ page }) => {
  await page.evaluate(() => window.setStage('onboarding'));
  expect(await page.evaluate(() => window.App.authed)).toBe(false);
  await page.evaluate(() => window.setOnbStep(4));
  await page.locator('#wiz [data-go="connect"]').click();
  await expect(page.locator('#cxLogin')).toBeVisible(); // 첫 인증에서만 로그인
  await expect(page.locator('#connectWiz .wt span')).toHaveText('DISCORD 연결');
});

test('I1: 메인 복귀 시 authed=true 로 보정', async ({ page }) => {
  await page.evaluate(() => window.setStage('onboarding'));
  expect(await page.evaluate(() => window.App.authed)).toBe(false);
  await page.evaluate(() => window.setStage('main'));
  expect(await page.evaluate(() => window.App.authed)).toBe(true);
});

test('SSOT 위반 경고가 정상 경로에서 발생하지 않는다', async ({ page }) => {
  const warns = [];
  page.on('console', (m) => { if (m.type() === 'warning' && m.text().includes('SSOT 위반')) warns.push(m.text()); });
  await page.click('.nav-item[data-view="servers"]');
  await page.evaluate(() => window.enterServerAdd());
  await page.evaluate(() => window.setStage('main'));
  expect(warns).toEqual([]);
});
