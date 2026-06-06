// 온보딩(A)·서버(03) 핵심 흐름.
const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
});

test('온보딩 4단계 + SD 모델 선택 → 연동(연결 stage)', async ({ page }) => {
  await page.evaluate(() => window.setOnbStep(1));
  await expect(page.locator('#wiz .wiz-title')).toContainText('환영');
  // 2단계: SD 모델 선택 인라인
  await page.evaluate(() => window.setOnbStep(2));
  await expect(page.locator('#wiz .opt-seg button')).toHaveCount(2);
  // 4단계 → 연동하기 → connect(onboarding) 로그인
  await page.evaluate(() => window.setOnbStep(4));
  await page.locator('#wiz [data-go="connect"]').click();
  await expect(page.locator('#connectWiz #cxLogin')).toBeVisible();
});

test('연결: 자동승인 서버 선택 → 연결 완료 결과', async ({ page }) => {
  await page.evaluate(() => window.setConnectSub('select'));
  // '바로 연결돼요'(autoApprove) 후보 클릭
  const auto = page.locator('#connectWiz .cx-cand', { hasText: '바로 연결돼요' }).first();
  await auto.click();
  await expect(page.locator('#connectWiz .wiz-title')).toContainText('연결됐어요', { timeout: 3000 });
});

test('연결: 승인 필요 서버 → 승인 대기 결과', async ({ page }) => {
  await page.evaluate(() => window.setConnectSub('select'));
  const pending = page.locator('#connectWiz .cx-cand', { hasText: '관리자 승인 후 연결' }).first();
  await pending.click();
  await expect(page.locator('#connectWiz .wiz-title')).toContainText('승인 대기', { timeout: 3000 });
});

test('03 서버: 목록·권한 배지·집계', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await expect(page.locator('#serverList .srv-item')).toHaveCount(4);
  await expect(page.locator('#serverList .srv-role.admin').first()).toBeVisible();
  await expect(page.locator('#serverSummary')).toContainText('승인 대기');
});

test('07 서버 상세: 관리자 서버 → 기여현황·내모델·웹 대시보드 위임', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await expect(page.locator('#serverDetail .dtitle h1')).toHaveText('한국어 개발 길드');
  await expect(page.locator('#serverDetail .dchip')).toHaveCount(3); // 내 모델 3종
  await expect(page.locator('#serverDetail #dWebBtn')).toBeVisible(); // 관리자 → 웹 대시보드 위임
  await expect(page.locator('#serverDetail #dPauseBtn')).toBeVisible();
  await page.click('#serverDetail #dBack');
  await expect(page.locator('#serverListWrap')).toBeVisible();
});

test('07 서버 상세: 기부자 서버 → 권한 안내(웹 버튼 없음)', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1002"]').click();
  await expect(page.locator('#serverDetail')).toContainText('관리자 권한이 필요');
  await expect(page.locator('#serverDetail #dWebBtn')).toHaveCount(0);
});

test('07 서버 상세: PENDING 서버 → 승인 대기, 정책·일시중지 숨김', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1004"]').click();
  await expect(page.locator('#serverDetail')).toContainText('관리자 승인을 기다리는 중');
  await expect(page.locator('#serverDetail #dPauseBtn')).toHaveCount(0);
  await expect(page.locator('#serverDetail #dPolicyBtn')).toHaveCount(0);
});

test('07 서버 상세: 일시중지 토글 + 내 self-service 정책 변경 모달', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await page.click('#serverDetail #dPauseBtn');
  await expect(page.locator('#serverDetail #dPauseBtn')).toHaveText('재개');
  // 내 정책 변경 → 하루 한도 0(무제한)
  await page.click('#serverDetail #dPolicyBtn');
  await expect(page.locator('.modal-layer .modal', { hasText: '내 제공 정책' })).toBeVisible();
  await page.fill('#pDaily', '0');
  await page.click('#pSave');
  await expect(page.locator('#serverDetail .dpolicy')).toContainText('무제한');
});

test('홈: 제공 상태 전환(정상↔문제) + 진단', async ({ page }) => {
  await page.evaluate(() => window.setHeroState('error'));
  await expect(page.locator('#heroInfo .ready')).toContainText('제공 중단됨');
  await page.locator('#heroInfo .hero-actions button', { hasText: '진단' }).click();
  await expect(page.locator('#diagPanel .diag-row')).toHaveCount(4);
});

test('빈틈: 서버 0개 빈 상태 → + 서버 추가', async ({ page }) => {
  await page.evaluate(() => window.setEmptyState('servers'));
  await expect(page.locator('#srvList .empty-card')).toContainText('아직 연결된 서버가 없어요');
  await page.locator('#srvAddEmpty').click();
  await expect(page.locator('#connectWiz .wt span')).toHaveText('서버 추가');
});

test('빈틈: 런타임 전부 미설치 → 제공 불가 경고 배너', async ({ page }) => {
  await expect(page.locator('#runtimeWarn')).toBeHidden();
  await page.evaluate(() => window.setEmptyState('noruntime'));
  await expect(page.locator('#runtimeWarn')).toBeVisible();
  await expect(page.locator('#runtimeWarn')).toContainText('제공할 AI 런타임이 없어요');
  // 배너엔 설치 버튼 없음(중복 제거) — 설치는 런타임 카드 CTA 로
  await expect(page.locator('#runtimeWarn button')).toHaveCount(0);
  await expect(page.locator('.prov-card[data-runtime="Ollama"] .prov-cta')).toHaveText('설치');
  await page.evaluate(() => window.setEmptyState('reset'));
  await expect(page.locator('#runtimeWarn')).toBeHidden();
});
