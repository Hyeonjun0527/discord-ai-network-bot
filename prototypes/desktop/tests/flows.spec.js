// 온보딩(A)·서버(03) 핵심 흐름.
const { test, expect } = require('@playwright/test');

test.beforeEach(async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
});

test('온보딩 4단계 → 연동(연결 stage)', async ({ page }) => {
  await page.evaluate(() => window.setOnbStep(1));
  await expect(page.locator('#wiz .wiz-title')).toContainText('환영');
  // 2단계: 런타임 선택 — Ollama(텍스트)만. 이미지(ComfyUI)는 로컬 실행 탭에서 설치(온보딩에 SD 모델 선택 없음).
  await page.evaluate(() => window.setOnbStep(2));
  await expect(page.locator('#wiz [data-opt="ollama"]')).toBeVisible();
  await expect(page.locator('#wiz .opt-seg')).toHaveCount(0); // SD 모델 선택 패널 제거됨
  await expect(page.locator('#wiz')).toContainText('이미지 생성(ComfyUI)은 설치 후');
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

test('07 서버 상세: 관리자 서버 → 기여현황·내모델·앱 내 관리 진입', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await expect(page.locator('#serverDetail .dtitle h1')).toHaveText('한국어 개발 길드');
  await expect(page.locator('#serverDetail .dchip')).toHaveCount(3); // 내 모델 3종
  await expect(page.locator('#serverDetail #dManageBtn')).toBeVisible(); // 관리자 → 앱 내 관리
  await expect(page.locator('#serverDetail')).not.toContainText('웹 대시보드'); // 웹 위임 폐기
  await expect(page.locator('#serverDetail #dPauseBtn')).toBeVisible();
  await page.click('#serverDetail #dBack');
  await expect(page.locator('#serverListWrap')).toBeVisible();
});

test('07b 서버 상세: 이 서버에 제공할 AI — 채팅 칩(로컬/클라우드) + 이미지 토글', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  const card = page.locator('#dModelsCard');
  await expect(card).toContainText('이 서버에 제공할 AI');
  // 채팅 칩 — async 로드 후(로컬 모델 4 + 클라우드 Gemini 1)
  await expect(card.locator('#dChatChips .dchip-tog')).toHaveCount(5);
  await expect(card.locator('.dchip-b.cloud')).toBeVisible();      // Gemini = ☁ 클라우드
  await expect(card.locator('.dchip-b.local').first()).toBeVisible(); // 로컬 배지
  await expect(card.locator('#dImgTog')).toBeVisible();             // 이미지 제공 토글
  // 정책 모달엔 더 이상 모델이 없다(분리됨)
  await page.click('#serverDetail #dPolicyBtn');
  const policyModal = page.locator('.modal-layer', { hasText: '내 제공 정책' });
  await expect(policyModal).toBeVisible();
  await expect(policyModal).not.toContainText('제공할 모델');
});

test('07 서버 상세: 기부자 서버 → 권한 안내(관리 버튼 없음)', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1002"]').click();
  await expect(page.locator('#serverDetail')).toContainText('관리자 권한이 필요');
  await expect(page.locator('#serverDetail #dManageBtn')).toHaveCount(0);
});

test('07 서버 상세: 이름 변경 + 이 서버 제공 그만두기', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1002"]').click();

  await page.click('#serverDetail #dRenameBtn');
  await page.locator('.modal-layer #rnInput').fill('새 서버 이름');
  await page.click('.modal-layer #rnSave');
  await expect(page.locator('#serverDetail .dtitle h1')).toHaveText('새 서버 이름');

  await page.click('#serverDetail #dRemoveBtn');
  await expect(page.locator('.modal-layer #rmGo')).toBeVisible();
  await page.click('.modal-layer #rmGo');
  await expect(page.locator('#serverListWrap')).toBeVisible();
  await expect(page.locator('#serverList .srv-item')).toHaveCount(3);
  await expect(page.locator('#serverList .srv-item[data-guild="1002"]')).toHaveCount(0);
});

test('07 서버 상세: PENDING 서버 → 승인 대기만, 기여현황·관리·정책 전부 숨김', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1004"]').click();
  await expect(page.locator('#serverDetail')).toContainText('관리자 승인을 기다리는 중');
  // 승인 전엔 기여현황·관리·정책·일시중지 모두 숨김(모순 제거)
  await expect(page.locator('#serverDetail .dstats')).toHaveCount(0);
  await expect(page.locator('#serverDetail #dManageBtn')).toHaveCount(0);
  await expect(page.locator('#serverDetail #dPauseBtn')).toHaveCount(0);
  await expect(page.locator('#serverDetail #dPolicyBtn')).toHaveCount(0);
});

test('13 서버 관리: Provider 승인 → 로스터 이동 / 제거(확인 모달)', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await page.click('#serverDetail #dManageBtn');
  // 관리 화면 — Provider 탭이 기본, 승인 대기 1 + 로스터 3
  await expect(page.locator('#serverManage .dtitle h1')).toHaveText('서버 관리');
  await expect(page.locator('#serverManage .mtab.active')).toContainText('Provider');
  await expect(page.locator('#serverManage')).toContainText('승인 대기 (1)');
  await expect(page.locator('#serverManage')).toContainText('user_kim'); // 로스터 이름 보강
  await expect(page.locator('#serverManage .prov-row')).toHaveCount(5); // 대기1 + 로스터3 + 정책1
  // 승인 → 대기 0, 로스터 4
  await page.click('#serverManage [data-approve="5001"]');
  await expect(page.locator('#serverManage')).toContainText('연결된 Provider (4)');
  await expect(page.locator('#serverManage [data-approve]')).toHaveCount(0);
  // 제거 → 확인 모달 → 제거
  await page.click('#serverManage [data-remove="5002"]');
  await expect(page.locator('.modal-layer .modal', { hasText: '제거할까요' })).toBeVisible();
  await page.click('#rmYes');
  await expect(page.locator('#serverManage')).toContainText('연결된 Provider (3)');
  // 뒤로 → 서버 상세
  await page.click('#serverManage #mBack');
  await expect(page.locator('#serverDetail')).toBeVisible();
});

test('13 서버 관리: 신규 자동 승인 토글', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await page.click('#serverDetail #dManageBtn');
  await expect(page.locator('#serverManage')).toContainText('꺼짐 — 관리자 승인');
  await page.click('#serverManage [data-autotoggle="1"]'); // 켜기
  await expect(page.locator('#serverManage')).toContainText('켜짐 — 승인 없이');
});

test('서버 전환 시 관리 화면이 잔류하지 않는다(관리자 관리 → 기부자 상세)', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  // 관리자(1001) → 관리 화면 진입
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await page.click('#serverDetail #dManageBtn');
  await expect(page.locator('#serverManage')).toBeVisible();
  // 관리 → 상세 → 목록
  await page.click('#serverManage #mBack');
  await page.click('#serverDetail #dBack');
  // 기부자(1002) 상세: 관리 화면이 남아있으면 안 됨
  await page.locator('#serverList .srv-item[data-guild="1002"]').click();
  await expect(page.locator('#serverDetail')).toBeVisible();
  await expect(page.locator('#serverManage')).toBeHidden();
  await expect(page.locator('#serverDetail')).toContainText('관리자 권한이 필요');
  await expect(page.locator('#serverDetail #dManageBtn')).toHaveCount(0); // 기부자엔 관리 진입 버튼 없음
});

test('v1 관리 탭: 채널/채널AI/RAG/프리셋 렌더(역할정책·다중응답 제외)', async ({ page }) => {
  await page.goto('/index.html#/servers/1001/manage');
  await page.waitForFunction(() => !!window.App);
  // v1 범위 외 탭은 아예 없음
  await expect(page.locator('#serverManage .mtab[data-mtab="multi"]')).toHaveCount(0);
  await page.click('#serverManage .mtab[data-mtab="channels"]');
  await expect(page.locator('#serverManage')).toContainText('채널별 AI 허용');
  await expect(page.locator('#serverManage')).not.toContainText('역할별 사용 정책');
  await page.click('#serverManage .mtab[data-mtab="channelai"]');
  await expect(page.locator('#serverManage')).toContainText('# ai-chat');
  await page.click('#serverManage .mtab[data-mtab="rag"]');
  await expect(page.locator('#serverManage')).toContainText('색인 완료');
  await page.click('#serverManage .mtab[data-mtab="preset"]');
  await expect(page.locator('#serverManage')).toContainText('번역봇');
});

test('전역 프롬프트 탭: 니아 기본 셋 + 추가/기본/삭제(외형 편집 없음)', async ({ page }) => {
  await page.goto('/index.html#/servers/1001/manage/profile');
  await page.waitForFunction(() => !!window.App);
  await expect(page.locator('#serverManage .mtab.active')).toContainText('전역 프롬프트');
  // 니아 기본 페르소나 + 기본 배지 + 전문 비공개(builtin)
  await expect(page.locator('#serverManage')).toContainText('니아 (기본 페르소나)');
  await expect(page.locator('#serverManage .me').first()).toHaveText('기본');
  await expect(page.locator('#serverManage')).toContainText('전문 비공개'); // builtin 잠금
  await expect(page.locator('#serverManage')).not.toContainText('서버 분위기를 존중'); // 전문 일부 미노출
  // v1: 이름·아바타 편집 UI 없음(외형 고정)
  await expect(page.locator('#profName')).toHaveCount(0);
  await expect(page.locator('#serverManage')).toContainText('NEXA 안전 지침');
  // 다른 셋을 기본으로
  await page.click('#serverManage [data-prompt-default="formal"]');
  await expect(page.locator('#serverManage [data-prompt-default="nia"]')).toBeVisible();
  // 프롬프트셋 추가 모달
  await page.click('#promptAdd');
  await expect(page.locator('.modal-layer .modal', { hasText: '전역 프롬프트셋 추가' })).toBeVisible();
  await page.fill('#npName', '테스트셋');
  await page.fill('#npBody', '당신은 테스트 도우미입니다.');
  await page.click('#npSave');
  await expect(page.locator('#serverManage')).toContainText('테스트셋');
});

test('약관·개인정보 링크: 데스크톱 앱에서 항상 접근 가능', async ({ page }) => {
  await page.goto('/index.html');
  await page.waitForFunction(() => !!window.App);
  await page.locator('.nav-legal button[data-legal="개인정보처리방침"]').click();
  const modal = page.locator('.modal-layer .modal', { hasText: '개인정보처리방침' });
  await expect(modal).toBeVisible();
  await expect(modal).toContainText('저장·로깅하지 않'); // 무보유 원칙
  await expect(modal).toContainText('기부자 PC');
  await modal.locator('.modal-x').click();
  await expect(page.locator('.modal-layer .modal', { hasText: '개인정보처리방침' })).toHaveCount(0);
});

test('안전 탭: 콘텐츠 정책 + 신고 처리(무시/숨김)', async ({ page }) => {
  await page.goto('/index.html#/servers/1001/manage/safety');
  await page.waitForFunction(() => !!window.App);
  await expect(page.locator('#serverManage .mtab.active')).toContainText('안전');
  await expect(page.locator('#serverManage')).toContainText('콘텐츠 정책');
  await expect(page.locator('#serverManage')).toContainText('서버 관리자');
  await expect(page.locator('#serverManage')).toContainText('무관용');
  // 신고 2건 → 1건 숨김 처리 → 신고(1)
  await expect(page.locator('#serverManage')).toContainText('신고 (2)');
  await page.locator('#serverManage [data-report-act]').first().click();
  await expect(page.locator('#serverManage')).toContainText('신고 (1)');
});

test('관리 탭 채널 AI: 읽기 전용 현황(설정됨 + 안내) + URL 동기화', async ({ page }) => {
  await page.goto('/index.html#/servers/1001/manage/channelai');
  await page.waitForFunction(() => !!window.App);
  await expect(page).toHaveURL(/#\/servers\/1001\/manage\/channelai$/);
  await expect(page.locator('#serverManage .mtab.active')).toContainText('채널 AI');
  // 채널 AI 는 읽기 전용 실연동 — 설정된 채널 현황 + 편집은 Discord/웹 안내(앱은 조회).
  await expect(page.locator('#serverManage')).toContainText('# ai-chat');
  await expect(page.locator('#serverManage')).toContainText('설정됨');
  await expect(page.locator('#serverManage')).toContainText('Discord 슬래시 명령');
});

test('07 서버 상세: 일시중지 토글 + 내 self-service 정책 변경 모달', async ({ page }) => {
  await page.click('.nav-item[data-view="servers"]');
  await page.locator('#serverList .srv-item[data-guild="1001"]').click();
  await page.click('#serverDetail #dPauseBtn');
  await expect(page.locator('#serverDetail #dPauseBtn')).toHaveText('재개');
  // 내 정책 변경 모달 — 세그먼트(자유 입력 X) + 시간 스테퍼
  await page.click('#serverDetail #dPolicyBtn');
  await expect(page.locator('.modal-layer .modal', { hasText: '내 제공 정책' })).toBeVisible();
  // 하루 한도: 무제한 칩, 동시 처리: 3 칩
  await page.click('#pDaily button[data-v="0"]');
  await page.click('#pConc button[data-v="3"]');
  // 최대 시간 스테퍼 +30초씩 2번(기본 10분 → 11분)
  await page.click('.pstep button[data-step="1"]');
  await page.click('.pstep button[data-step="1"]');
  await expect(page.locator('#pSecVal')).toHaveText('11분');
  await page.click('#pSave');
  await expect(page.locator('#serverDetail .dpolicy')).toContainText('무제한');
  await expect(page.locator('#serverDetail .dpolicy')).toContainText('11분');
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
