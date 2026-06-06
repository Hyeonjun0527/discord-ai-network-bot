// 02 모델 화면 — 목록·기본모델·카탈로그·없는 모델 실패·설치 토스트(실제 클릭).
const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
  await page.click('.nav-item[data-view="models"]');
});

test('모델 목록·기본 모델·집계가 렌더된다', async ({ page }) => {
  await expect(page.locator('#modelList .model-row')).toHaveCount(4);
  await expect(page.locator('#defaultModelSel option')).toHaveCount(3); // 제공 중인 모델만
  await expect(page.locator('#modelFoot')).toContainText('제공 중');
});

test('모델 토글 시 변경 적용 배너(D5)가 뜬다', async ({ page }) => {
  await expect(page.locator('#modelApply')).toBeHidden();
  await page.locator('#modelList .model-switch').first().click();
  await expect(page.locator('#modelApply')).toBeVisible();
});

test('추천 카탈로그(카테고리 그룹·고사양 변형) + 직접 입력 형식 검증', async ({ page }) => {
  await page.click('#catalogBtn');
  await expect(page.locator('#installCard [data-cat]')).toHaveCount(38);
  // 카테고리 그룹 헤더로 묶여 렌더된다
  await expect(page.locator('#installCard .cat-group')).toHaveCount(7);
  // 고사양 변형이 포함된다(사용자 요청: exaone/qwen/deepseek 더 큰 사이즈)
  await expect(page.locator('#installCard [data-cat="exaone3.5:32b"]')).toBeVisible();
  await expect(page.locator('#installCard [data-cat="qwen2.5:72b"]')).toBeVisible();
  await expect(page.locator('#installCard [data-cat="deepseek-r1:70b"]')).toBeVisible();
  await expect(page.locator('#catManual')).toBeVisible();
  // 형식 오류
  await page.fill('#catManual', '!! 이상한 이름');
  await page.click('#catManualBtn');
  await expect(page.locator('#toast .toast .tm')).toContainText('올바른 모델명');
});

test('없는 모델은 설치 실패하고 목록에 추가되지 않는다', async ({ page }) => {
  await page.click('#catalogBtn');
  await page.fill('#catManual', 'nonexist:99b');
  await page.click('#catManualBtn');
  await expect(page.locator('#toast .toast')).toContainText('설치 실패', { timeout: 3000 });
  await expect(page.locator('#modelList .m-name', { hasText: 'nonexist' })).toHaveCount(0);
});

test('설치 토스트: 프로그레스바 + 닫기(✕)가 실제로 클릭된다 (pointer-events 회귀 방지)', async ({ page }) => {
  // Ollama 미설치 → 설치(모달 없이 바로 토스트)
  await page.evaluate(() => { window.setRuntime('Ollama', 'absent'); });
  // 홈으로 가서 카드 설치 버튼
  await page.click('.nav-item[data-view="home"]');
  await page.locator('.prov-card[data-runtime="Ollama"] .prov-cta').click();
  const toastEl = page.locator('#toast .toast.has-bar');
  await expect(toastEl.locator('.toast-bar')).toBeVisible();
  // ✕ 실제 클릭(pointer-events:none 이면 Playwright 가 클릭 불가로 실패) → 토스트 사라짐
  await toastEl.locator('.toast-x').click();
  await expect(page.locator('#toast .toast')).toHaveCount(0);
});
