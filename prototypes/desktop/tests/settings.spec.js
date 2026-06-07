import { test, expect } from '@playwright/test';

test('설정 화면: 그룹·토글·버전 표시', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="settings"]');

  // 그룹 4개
  await expect(page.locator('#settingsBody')).toContainText('실행 동작');
  await expect(page.locator('#settingsBody')).toContainText('업데이트');
  await expect(page.locator('#settingsBody')).toContainText('연결');
  await expect(page.locator('#settingsBody')).toContainText('계정');

  // 실행 동작 토글들
  await expect(page.locator('[data-toggle="autostart"]')).toBeVisible();
  await expect(page.locator('[data-toggle="autoConnect"]')).toHaveClass(/on/); // mock 기본 켜짐
  await expect(page.locator('[data-toggle="autostart"]')).not.toHaveClass(/on/); // mock 기본 꺼짐

  // 토글 동작
  await page.click('[data-toggle="autostart"]');
  await expect(page.locator('[data-toggle="autostart"]')).toHaveClass(/on/);

  // 버전 + 연결(읽기 전용) + 계정
  await expect(page.locator('#settingsBody')).toContainText('최신 상태');
  await expect(page.locator('#settingsBody')).toContainText('wss://discord-ai.yeon.world/agent');
  await expect(page.locator('#setLogout')).toBeVisible();
});
