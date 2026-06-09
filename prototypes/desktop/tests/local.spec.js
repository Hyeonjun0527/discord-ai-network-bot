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

test('로컬 실행: ComfyUI 모델 카탈로그 — 목록에서 골라 설치·진행률·자동 적용(데모 mock)', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="local"]');
  // 카탈로그 버튼은 ComfyUI 실행 중일 때 노출 — mock 을 설치·실행 상태로 패치하고 재렌더.
  await page.evaluate(() => window.__mockComfy({ installed: true, running: true, active: null }));
  await page.click('.nav-item[data-view="local"]');
  await expect(page.locator('#comfyCatalogBtn')).toBeVisible();
  await page.click('#comfyCatalogBtn');

  const modal = page.locator('.cat-back .cat-modal');
  await expect(modal).toBeVisible();
  await expect(modal.locator('.cat-card')).toHaveCount(5); // 큐레이션 5종
  await expect(modal).toContainText('Illustrious XL v2.0'); // 애니(유저 요청 Illustrious 계열)
  await expect(modal).toContainText('Juggernaut XL v9');    // 실사
  await expect(modal.locator('.cat-section', { hasText: '애니' })).toBeVisible();
  await expect(modal.locator('.cat-section', { hasText: '실사' })).toBeVisible();

  // 첫 모델 설치 → 진행률 → 완료 시 '설치됨' 배지(자동 활성화)
  const first = modal.locator('.cat-card').first();
  await first.locator('.cat-act button').click();
  await expect(first.locator('.cat-prog')).toBeVisible();
  await expect(first.locator('.cat-installed')).toBeVisible({ timeout: 9000 }); // mock 다운로드 ~4s
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
