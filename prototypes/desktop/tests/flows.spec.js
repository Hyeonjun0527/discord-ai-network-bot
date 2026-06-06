// 온보딩(A)·서버(03) 핵심 흐름.
const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
});

test('온보딩 4단계 + SD 모델 선택 → 연동(연결 stage)', async ({ page }) => {
  await page.evaluate(() => window.setOnbStep(1));
  await expect(page.locator('#wiz .wiz-title')).toContainText('환영');
  // 2단계: SD 모델 선택 인라인
  await page.evaluate(() => window.setOnbStep(2));
  await expect(page.locator('#wiz .opt-seg button')).toHaveCount(2);
  // 4단계 → 연동하기 → connect(onboarding) 로그인
  await page.evaluate(() => window.setOnbStep(4));
  await page.locator('#wiz [data-go="connect"]').click();
  await expect(page.locator('#connectWiz #cxLogin')).toBeVisible();
});

test('연결: 자동승인 서버 선택 → 연결 완료 결과', async ({ page }) => {
  await page.evaluate(() => window.setConnectSub('select'));
  // '바로 연결돼요'(autoApprove) 후보 클릭
  const auto = page.locator('#connectWiz .cx-cand', { hasText: '바로 연결돼요' }).first();
  await auto.click();
  await expect(page.locator('#connectWiz .wiz-title')).toContainText('연결됐어요', { timeout: 3000 });
});

test('연결: 승인 필요 서버 → 승인 대기 결과', async ({ page }) => {
  await page.evaluate(() => window.setConnectSub('select'));
  const pending = page.locator('#connectWiz .cx-cand', { hasText: '관리자 승인 후 연결' }).first();
  await pending.click();
  await expect(page.locator('#connectWiz .wiz-title')).toContainText('승인 대기', { timeout: 3000 });
});

test('03 서버: 목록·권한 배지·집계', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await expect(page.locator('#serverList .srv-item')).toHaveCount(4);
  await expect(page.locator('#serverList .srv-role.admin').first()).toBeVisible();
  await expect(page.locator('#serverSummary')).toContainText('승인 대기');
});

test('홈: 제공 상태 전환(정상↔문제) + 진단', async ({ page }) => {
  await page.evaluate(() => window.setHeroState('error'));
  await expect(page.locator('#heroInfo .ready')).toContainText('제공 중단됨');
  await page.locator('#heroInfo .hero-actions button', { hasText: '진단' }).click();
  await expect(page.locator('#diagPanel .diag-row')).toHaveCount(4);
});
