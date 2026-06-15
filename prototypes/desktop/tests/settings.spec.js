import { test, expect } from '@playwright/test';

test('설정 화면: 그룹·토글·버전 표시', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="settings"]');

  // 설정 그룹
  await expect(page.locator('#settingsBody')).toContainText('실행 동작');
  await expect(page.locator('#settingsBody')).toContainText('업데이트');
  await expect(page.locator('#settingsBody')).toContainText('연결');
  await expect(page.locator('#settingsBody')).toContainText('라이선스');
  await expect(page.locator('#settingsBody')).toContainText('계정');

  // 실행 동작 토글들
  await expect(page.locator('[data-toggle="autostart"]')).toBeVisible();
  await expect(page.locator('[data-toggle="autoConnect"]')).toHaveClass(/on/); // mock 기본 켜짐
  await expect(page.locator('[data-toggle="autostart"]')).not.toHaveClass(/on/); // mock 기본 꺼짐

  // 토글 동작
  await page.click('[data-toggle="autostart"]');
  await expect(page.locator('[data-toggle="autostart"]')).toHaveClass(/on/);

  // 버전 + 연결(편집 가능: 중앙서버·Ollama 주소) + 계정
  await expect(page.locator('#settingsBody')).toContainText('최신 상태');
  await expect(page.locator('#setRelay')).toHaveValue('wss://discord-ai.yeon.world/agent'); // 중앙 서버 주소 편집칸
  await expect(page.locator('#setOllama')).toBeVisible(); // Ollama 주소 편집칸(다른 포트/호스트 가능)
  await expect(page.locator('#settingsBody')).toContainText('Nexa 라이선스 · 체험 중');
  await expect(page.locator('#setLicenseRefresh')).toBeVisible();
  await expect(page.locator('#setLogout')).toBeVisible();
});

test('설정 화면: 니아 페르소나 카드(관리자 전용) — 전문·복사 버튼 표시', async ({ page }) => {
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="settings"]');
  // mock(USE_MOCK) = 관리자 가정(ok:true) → 카드가 보이고 전문(시연용 가짜)이 읽기 전용으로 표시된다.
  await expect(page.locator('#settingsBody')).toContainText('니아 페르소나 (관리자 전용)');
  await expect(page.locator('#settingsBody')).toContainText('시연용 가짜');
  await expect(page.locator('#niaCopyPersona')).toBeVisible();
  await expect(page.locator('#niaCopyFewshot')).toBeVisible();
});

test('설정 화면: 니아 페르소나 카드는 비관리자(403)면 숨겨지고 전문이 노출되지 않는다', async ({ page }) => {
  // getNiaPersona 가 ok:false(비관리자) 를 돌려주도록 mock 패치 → 카드 자체가 없어야 한다.
  await page.addInitScript(() => { globalThis.__NIA_FORBIDDEN = true; });
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="settings"]');
  await expect(page.locator('#settingsBody')).toContainText('계정'); // 본문은 정상 렌더
  await expect(page.locator('#settingsBody')).not.toContainText('니아 페르소나 (관리자 전용)');
  await expect(page.locator('#niaCopyPersona')).toHaveCount(0);
});

test('언어 전환(i18n): 설정에서 ko→en 바꾸면 네비(정적)와 설정(재렌더)이 영어로 바뀐다', async ({ page }) => {
  await page.goto('/index.html');
  await expect(page.locator('.nav-item[data-view="home"]')).toContainText('홈'); // ko-KR 로케일 기본
  await page.click('.nav-item[data-view="settings"]');
  await expect(page.locator('#settingsBody')).toContainText('실행 동작');
  await page.selectOption('#langSel', 'en');
  await expect(page.locator('.nav-item[data-view="home"]')).toContainText('Home'); // 정적 라벨(data-i18n) 전환
  await expect(page.locator('#settingsBody')).toContainText('App behavior'); // JS 렌더 화면 재렌더
  await page.selectOption('#langSel', 'ja');
  await expect(page.locator('.nav-item[data-view="settings"]')).toContainText('設定'); // 일본어 전환
});

test('설정 본문은 업데이트 확인 네트워크가 느려도 즉시 렌더된다(회귀: 빈 설정창)', async ({ page }) => {
  // getUpdateInfo 를 영원히 pending 으로(느린/막힌 업데이트 채널 재현) → 본문 렌더가 막히면 안 된다.
  await page.addInitScript(() => { globalThis.__HANG_UPDATE_INFO = true; });
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="settings"]');
  // 업데이트 줄은 '확인 중…' 이지만, 토글·연결·계정 본문은 즉시 보여야 한다.
  await expect(page.locator('[data-toggle="autostart"]')).toBeVisible();
  await expect(page.locator('#settingsBody')).toContainText('실행 동작');
  await expect(page.locator('#settingsBody')).toContainText('계정');
  await expect(page.locator('#settingsBody')).toContainText('확인 중');
});

test('업데이트 확인 실패는 최신 상태로 오판하지 않고 유저에게 보인다', async ({ page }) => {
  await page.addInitScript(() => {
    globalThis.__MOCK_UPDATE_INFO = {
      current: '0.52.3',
      latest: null,
      outdated: false,
      supported: true,
      autoUpdate: true,
      error: '업데이트 채널을 찾을 수 없어요(404)',
    };
  });
  await page.goto('/index.html');
  await page.click('.nav-item[data-view="settings"]');
  await expect(page.locator('#settingsBody')).toContainText('업데이트 확인 실패');
  await expect(page.locator('#settingsBody')).not.toContainText('최신 상태');
  await page.click('#setUpdateCheck');
  await expect(page.locator('#toast')).toContainText('업데이트 확인 실패');
});

test('새 버전은 자동 적용하지 않고 중앙 확인 모달에서 승인받는다', async ({ page }) => {
  await page.addInitScript(() => {
    globalThis.__MOCK_UPDATE_INFO = {
      current: '0.52.3',
      latest: '0.52.4',
      outdated: true,
      supported: true,
      autoUpdate: true,
      error: null,
    };
  });
  await page.goto('/index.html');
  const modal = page.locator('#updateModal');
  await expect(modal).toBeVisible();
  await expect(modal).toContainText('업데이트하시겠습니까?');
  await expect(modal).toContainText('v0.52.4');
  await expect(modal).toContainText('잠시 종료');
  await modal.getByText('나중에').click();
  await expect(modal).toHaveCount(0);
});

test('업데이트를 누르면 진행바가 뜨고 진행률이 표시된다', async ({ page }) => {
  await page.addInitScript(() => {
    globalThis.__MOCK_UPDATE_INFO = { current: '0.52.3', latest: '0.52.4', outdated: true, supported: true, autoUpdate: true, error: null };
  });
  await page.goto('/index.html');
  const modal = page.locator('#updateModal');
  await expect(modal).toBeVisible();
  await modal.getByText('업데이트', { exact: true }).click();
  // 진행바(.inst-bar)가 나타나고, 진행률(%)·단계가 표시된 뒤 재시작 단계까지 진행한다.
  await expect(modal.locator('.inst-bar')).toBeVisible();
  await expect(modal).toContainText('%');
  await expect(modal).toContainText('새 버전으로 다시 열려요', { timeout: 8000 });
});

test('업데이트 백그라운드 실패는 진행바 대신 에러+다시 시도로 표시된다', async ({ page }) => {
  await page.addInitScript(() => {
    globalThis.__MOCK_UPDATE_INFO = { current: '0.52.3', latest: '0.52.4', outdated: true, supported: true, autoUpdate: true, error: null };
    globalThis.__UPDATE_MOCK_ERROR = true; // getUpdateProgress 가 phase:error 를 돌려줌
  });
  await page.goto('/index.html');
  const modal = page.locator('#updateModal');
  await expect(modal).toBeVisible();
  await modal.getByText('업데이트', { exact: true }).click();
  await expect(modal).toContainText('업데이트 실패');
  await expect(modal).toContainText('체크섬');
  await expect(modal.getByText('다시 시도')).toBeVisible();
});
