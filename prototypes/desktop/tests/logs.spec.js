import { test, expect } from '@playwright/test';

test('로그 화면: 라인 렌더 + 레벨 분류 + 오류 필터', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="logs"]');

  // 로그 라인이 렌더되고, 레벨이 파싱돼 색 클래스가 붙는다.
  await expect(page.locator('#logView .log-line').first()).toBeVisible();
  await expect(page.locator('#logView')).toContainText('에이전트 시작');
  await expect(page.locator('#logView .log-line.error')).toHaveCount(1); // mock 에 ERROR 1줄
  await expect(page.locator('#logView .log-line.warn')).toHaveCount(1); // mock 에 WARN 1줄

  // '오류'만 필터 → 에러 라인만 남고 정보 라인은 사라진다.
  await page.click('#logFilters [data-level="error"]');
  await expect(page.locator('#logView .log-line')).toHaveCount(1);
  await expect(page.locator('#logView .log-line.error')).toHaveCount(1);
  await expect(page.locator('#logView')).not.toContainText('에이전트 시작');

  // '전체'로 복귀하면 다시 모두 보인다.
  await page.click('#logFilters [data-level="all"]');
  await expect(page.locator('#logView')).toContainText('에이전트 시작');
});

test('로그 화면: 출처(SD/Ollama/연결) 배지 + 출처 필터', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="logs"]');
  // 출처 배지가 줄마다 붙는다(에이전트/Ollama/SD/연결).
  await expect(page.locator('#logView .lsrc-sd').first()).toBeVisible();
  await expect(page.locator('#logView .lsrc-ollama').first()).toBeVisible();

  // 'Stable Diffusion'만 필터 → SD 라인만, Ollama 연결 라인은 사라진다.
  await page.click('#logSources [data-source="sd"]');
  await expect(page.locator('#logView')).toContainText('ComfyUI 준비됨');
  await expect(page.locator('#logView')).not.toContainText('Ollama 연결됨');
  await expect(page.locator('#logView .lsrc-ollama')).toHaveCount(0);

  // 'Ollama'만 필터 → Ollama 라인만, SD 준비 라인은 사라진다.
  await page.click('#logSources [data-source="ollama"]');
  await expect(page.locator('#logView')).toContainText('Ollama 연결됨');
  await expect(page.locator('#logView')).not.toContainText('ComfyUI 준비됨');
  await expect(page.locator('#logView .lsrc-sd')).toHaveCount(0);

  // 출처 '전체'로 복귀.
  await page.click('#logSources [data-source="all"]');
  await expect(page.locator('#logView')).toContainText('ComfyUI 준비됨');
  await expect(page.locator('#logView')).toContainText('Ollama 연결됨');
});
