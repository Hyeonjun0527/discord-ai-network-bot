import { test, expect } from '@playwright/test';

// 실 앱 모드 = window.__SESSION_KEY 주입(webui 가 index 서빙 시 치환). 데모 도구 제거 + 부팅 자동 분기.
test('세션키 모드: PROTO 데모 제거 + 토큰 보유 시 메인 부팅', async ({ page }) => {
  await page.addInitScript(() => { window.__SESSION_KEY = 'test-key'; });
  await page.goto('/index.html');
  // PROTO 데모 오버레이는 실 앱에서 자가 제거된다.
  await expect(page.locator('#proto')).toHaveCount(0);
  // mock status.hasToken=true → 메인 stage 자동.
  await expect(page.locator('.app')).toBeVisible();
  await expect(page.locator('.view[data-view="home"]')).toHaveClass(/active/);
});

test('프로토타입 모드(세션키 없음): PROTO 데모 유지', async ({ page }) => {
  await page.goto('/index.html');
  await expect(page.locator('#proto')).toHaveCount(1); // 시안 전환 도구 존재
});
