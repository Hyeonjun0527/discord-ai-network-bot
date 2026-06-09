import { test, expect } from '@playwright/test';

test('로컬 실행 화면: 실행 상태 + 런타임 + 시작/중지 토글', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');

  // 실행 상태 카드 — mock 은 실행 중
  await expect(page.locator('#localRunCard')).toHaveClass(/on/);
  await expect(page.locator('#localRunCard')).toContainText('실행 중');
  await expect(page.locator('#localRunCard')).toContainText('처리 12건');
  await expect(page.locator('#localToggleBtn')).toHaveText('중지');

  // 런타임 카드 — Ollama(텍스트) / ComfyUI(이미지). SD.Next 폐기.
  await expect(page.locator('#localRuntimes')).toContainText('Ollama');
  await expect(page.locator('#localRuntimes')).toContainText('ComfyUI');
  await expect(page.locator('#localRuntimes')).toContainText('모델 3개 제공');

  // 중지 → 시작 토글
  await page.click('#localToggleBtn');
  await expect(page.locator('#localToggleBtn')).toHaveText('시작');
  await expect(page.locator('#localRunCard')).not.toHaveClass(/on/);
  await expect(page.locator('#localRunCard')).toContainText('중지됨');

  // 다시 시작
  await page.click('#localToggleBtn');
  await expect(page.locator('#localToggleBtn')).toHaveText('중지');
  await expect(page.locator('#localRunCard')).toHaveClass(/on/);
});

test('로컬 실행: ComfyUI 가 유일한 이미지 엔진(권장)으로 노출 · SD.Next 없음', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  const comfy = page.locator('#localRuntimes .rt-row', { hasText: 'ComfyUI' });
  await expect(comfy).toContainText('권장');
  await expect(comfy.locator('[data-comfy="install"]')).toBeVisible(); // mock 미설치 → 설치 버튼
  // SD.Next 폐기 — 레거시 섹션 없음, 외부 ComfyUI 연결만 고급으로
  await expect(page.locator('#localRuntimes')).not.toContainText('Stable Diffusion');
  await expect(page.locator('summary', { hasText: '외부 ComfyUI' })).toBeVisible();
});

test('로컬 실행: 처리 건수는 이 PC 기준 표기', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  await expect(page.locator('#localRunCard')).toContainText('처리 12건(이 PC)');
});

test('로컬 실행: Ollama ⋯ 메뉴(연결 점검)', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  // SD.Next 폐기 → ⋯ 메뉴는 Ollama 하나(ComfyUI 는 전용 버튼).
  await expect(page.locator('#localRuntimes .rt-menu-btn')).toHaveCount(1);
  const olRow = page.locator('#localRuntimes .rt-row', { hasText: 'Ollama' });
  await olRow.locator('.rt-menu-btn').click();
  await expect(olRow.locator('.rt-more .menu')).toContainText('연결 점검');
});

test('로컬 실행: 중앙서버 연결 끊김 → 재연결 버튼(데모 mock)', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  await expect(page.locator('#localReconnect')).toHaveCount(0); // 연결됨 → 버튼 없음
  await page.evaluate(() => window.__mockPatch({ running: true, connected: false }));
  await page.click('.nav-item[data-view="local"]'); // 재렌더
  await expect(page.locator('#localReconnect')).toBeVisible();
  await expect(page.locator('#localRunCard')).toContainText('연결 중…');
});

test('로컬 실행: ComfyUI 미설치 → 설치 버튼(데모 mock)', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  const comfy = page.locator('#localRuntimes .rt-row', { hasText: 'ComfyUI' });
  await expect(comfy.locator('[data-comfy="install"]')).toBeVisible();
  await expect(comfy).toContainText('미설치');
});

test('모델: 기본 응답 모델 select + 변경 적용', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="models"]');
  await expect(page.locator('#defaultModelSel')).toHaveValue('exaone3.5:7.8b'); // mock 기본
  await expect(page.locator('#modelList')).toContainText('기본'); // 기본 배지
  await page.selectOption('#defaultModelSel', 'llama3.1:8b');
  await expect(page.locator('#modelApply')).toBeVisible();
  await page.click('#modelApplyBtn');
  await expect(page.locator('#modelApply')).toBeHidden(); // 실제 적용 성공 후에만 숨김
});
