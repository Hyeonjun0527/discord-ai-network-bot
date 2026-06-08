import { test, expect } from '@playwright/test';

test('로컬 실행 화면: 실행 상태 + 런타임 + 시작/중지 토글', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');

  // 실행 상태 카드 — mock 은 실행 중
  await expect(page.locator('#localRunCard')).toHaveClass(/on/);
  await expect(page.locator('#localRunCard')).toContainText('실행 중');
  await expect(page.locator('#localRunCard')).toContainText('처리 12건');
  await expect(page.locator('#localToggleBtn')).toHaveText('중지');

  // 런타임 카드 — Ollama / Stable Diffusion
  await expect(page.locator('#localRuntimes')).toContainText('Ollama');
  await expect(page.locator('#localRuntimes')).toContainText('Stable Diffusion');
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

test('로컬 실행 화면: 이미지 요청 받기 토글', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  const sw = page.locator('#localImgSw');
  await expect(sw).toHaveClass(/on/); // mock enableImage=true
  await sw.click();
  await expect(sw).not.toHaveClass(/on/);
});

test('로컬 실행: 처리 건수는 이 PC 기준 표기', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  await expect(page.locator('#localRunCard')).toContainText('처리 12건(이 PC)');
});

test('로컬 실행: 런타임 ⋯ 메뉴(연결 점검·출력 폴더, WebUI 미노출)', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  await expect(page.locator('#localRuntimes .rt-menu-btn')).toHaveCount(2); // Ollama + SD
  const sdRow = page.locator('#localRuntimes .rt-row', { hasText: 'Stable Diffusion' });
  await sdRow.locator('.rt-menu-btn').click();
  const menu = sdRow.locator('.rt-more .menu');
  await expect(menu).toContainText('연결 점검');
  await expect(menu).toContainText('출력 폴더');
  // SD :7860 Gradio WebUI 는 노출하지 않음(REST 만 사용 · gradio queue 버그로 UI 결과 미표시).
  await expect(menu).not.toContainText('WebUI');
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

test('로컬 실행: SD 설치됐는데 미준비 → 시작 버튼(데모 mock)', async ({ page }) => {
  await page.goto('/index.html');
  await page.evaluate(() => window.__mockPatch({ sdInstalled: true, imageReady: false }));
  await page.click('.nav-item[data-view="local"]');
  await expect(page.locator('#localSdStart')).toBeVisible();
  await expect(page.locator('#localRuntimes')).toContainText('설치됨 · 준비 중');
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
