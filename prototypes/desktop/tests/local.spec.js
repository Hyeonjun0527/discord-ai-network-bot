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
