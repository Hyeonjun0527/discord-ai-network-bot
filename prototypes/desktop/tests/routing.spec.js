// Hash 라우터 — URL 직접 진입(딥링크/새로고침) + 화면 전환 시 URL 갱신(양방향).
const { test, expect } = require('@playwright/test');

test('URL 직접 진입: #/models → 모델 탭 활성', async ({ page }) => {
  await page.goto('/index.html#/models');
  await page.waitForFunction(() => !!window.App);
  await expect(page.locator('.nav-item[data-view="models"]')).toHaveClass(/active/);
  await expect(page.locator('.view[data-view="models"]')).toHaveClass(/active/);
});

test('URL 직접 진입: #/servers/1001 → 서버 상세', async ({ page }) => {
  await page.goto('/index.html#/servers/1001');
  await page.waitForFunction(() => !!window.App);
  await expect(page.locator('#serverDetail .dtitle h1')).toHaveText('한국어 개발 길드');
});

test('URL 직접 진입: #/servers/1001/manage → 관리 화면', async ({ page }) => {
  await page.goto('/index.html#/servers/1001/manage');
  await page.waitForFunction(() => !!window.App);
  await expect(page.locator('#serverManage .dtitle h1')).toHaveText('서버 관리');
  await expect(page.locator('#serverManage .mtab.active')).toContainText('Provider');
});

test('화면 전환 시 URL 갱신: 모델 탭 클릭 → #/models', async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
  await page.click('.nav-item[data-view="models"]');
  await expect(page).toHaveURL(/#\/models$/);
});

test('화면 전환 시 URL 갱신: 서버 카드 클릭 → #/servers/1001', async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await expect(page).toHaveURL(/#\/servers\/1001$/);
});

test('뒤로/앞으로: 브라우저 히스토리로 화면 복원', async ({ page }) => {
  await page.goto('/index.html#/models');
  await page.waitForFunction(() => !!window.App);
  await page.click('.nav-item[data-view="servers"]');
  await expect(page).toHaveURL(/#\/servers$/);
  await page.goBack();
  await expect(page.locator('.nav-item[data-view="models"]')).toHaveClass(/active/);
});
