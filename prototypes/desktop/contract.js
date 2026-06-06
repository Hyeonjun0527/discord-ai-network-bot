// ════════════════════════════════════════════════════════════════════════
// NEXA 도메인 계약 (SSOT 미러) — 백엔드와 "같은 언어"로 말하기 위한 상수.
// 임의로 바꾸지 말 것. 백엔드가 바뀌면 여기를 맞춘다(드리프트 가드).
//
// 출처:
//  · ProviderState  — central-server .../provider/domain/ProviderState.kt
//  · API(ENDPOINTS) — provider-agent/src/provider_agent/webui.py (로컬 HTTP, camelCase 경계)
//  · Wire 상수      — protocol/wire-contract.json
//  · Capability     — ProviderHelloFrame.capabilities (text/image)
// ════════════════════════════════════════════════════════════════════════

// 프로바이더(에이전트) 상태머신 — central ProviderState.kt 와 1:1
export const ProviderState = Object.freeze({
  PENDING: 'PENDING',          // 승인 대기
  APPROVED: 'APPROVED',        // 승인됨(아직 미연결)
  ONLINE_IDLE: 'ONLINE_IDLE',  // 연결·대기
  ONLINE_BUSY: 'ONLINE_BUSY',  // 요청 처리 중
  PAUSED: 'PAUSED',            // 일시중지
  LIMITED: 'LIMITED',          // 한도/부하 제한
  OFFLINE: 'OFFLINE',          // 미연결
  UNHEALTHY: 'UNHEALTHY',      // 불건강
  REMOVED: 'REMOVED',          // 제거됨
});

// 제공 능력 — ProviderHelloFrame.capabilities
export const Capability = Object.freeze({ TEXT: 'text', IMAGE: 'image' });

// 앱 관점의 내 권한(서버별). central 권한 매핑(관리자 Discord 권한 / 일반 기여자)
export const Role = Object.freeze({ ADMIN: 'admin', PROVIDER: 'provider' });

// 와이어 계약 상수 — protocol/wire-contract.json
export const Wire = Object.freeze({
  protocolVersion: '1.0',
  maxPromptChars: 100000,
  allowedOptionKeys: ['temperature', 'num_predict', 'num_ctx', 'top_p', 'top_k', 'stop', 'seed'],
});

// 로컬 에이전트 HTTP 엔드포인트 — webui.py 라우터와 1:1 (전환 시 그대로 fetch)
export const ENDPOINTS = Object.freeze({
  status: '/api/status',
  models: '/api/models',
  servers: '/api/servers',
  // ⚠ 서버 상세(기부자 관점) — Gap-S/P/W. 아래 3개는 백엔드 신설 필요.
  serverDetail: (g) => '/api/servers/' + g,         // GET — 서버별 내 기여 통계·myModels·policy(Gap-S/P)
  serverPause: (g) => '/api/servers/' + g + '/pause',   // POST {paused} — provider self-service(/provider-pause·resume)
  serverPolicy: (g) => '/api/servers/' + g + '/policy', // POST {dailyLimit,maxConcurrency,maxSeconds,scope} — (/provider-limit·scope)
  // 서버 관리(관리자) — 앱 내 직접 관리. ✅ 채널 구현됨(2026-06-07): webui → central /provider/admin/*.
  //   central 이 durable 토큰 신원 + JDA 관리자 판정(MANAGE_SERVER|ADMINISTRATOR) 후 ProviderRegistrationService 실행.
  //   role 전달은 별도 불필요 — 앱은 serverManage 응답 ok 로 "내가 관리자인지" 판정(비관리자는 ok=false).
  serverManage: (g) => '/api/servers/' + g + '/manage',            // GET — 승인 대기(pending)·로스터(roster)
  providerApprove: (g) => '/api/servers/' + g + '/providers/approve', // POST {providerUserId} — /provider-approve
  providerReject: (g) => '/api/servers/' + g + '/providers/reject',  // POST {providerUserId} — /provider-reject
  providerRemove: (g) => '/api/servers/' + g + '/providers/remove',  // POST {providerUserId} — /provider-remove
  // ⚠ 후속: 관리 정책 토글(autoApprove 등)은 아직 슬래시 명령만 — 관리 API 미노출(다음 단계).
  serverManagePolicy: (g) => '/api/servers/' + g + '/manage/policy',
  onboardApply: '/api/onboard-apply',
  connectOpen: '/api/connect-open',
  sdStatus: '/api/sd/status',
  sdModels: '/api/sd/models',
  sdSetup: '/api/sd/setup',
  sdSetupProgress: '/api/sd/setup-progress',
  ollamaSetup: '/api/ollama/setup',
  ollamaSetupProgress: '/api/ollama/setup-progress',
  ollamaCatalog: '/api/ollama/catalog',
  setup: '/api/setup',
  start: '/api/start',
  stop: '/api/stop',
  logout: '/api/logout',
});

// ── 응답 shape(참고용 JSDoc) — webui.py 와 동일 필드명(camelCase) ──
/** @typedef {{running:boolean, connected:boolean, processed:number, imageReady:boolean, enableImage:boolean, sdInstalled:boolean}} AgentStatus */
//   webui.py /api/status — 실제 존재 필드.
/**
 * @typedef {Object} ServerConn  서버 연결(프로토타입 확장 shape)
 * @property {number}  guildId    webui.py /api/servers 에 존재
 * @property {string}  guildName  webui.py /api/servers 에 존재
 * @property {boolean} [connected] webui.py /api/servers 에 존재
 * @property {string}  [iconUrl]  ⚠ 백엔드 추가 필요(central AgentSyncJoinDto + JDA Guild.iconUrl)
 * @property {string}  [state]    ⚠ 백엔드 추가 필요(ProviderState — 현재는 connected boolean 만)
 * @property {string}  [role]     ⚠ 백엔드 추가 필요(Role — 서버별 내 권한)
 * @property {number}  [models]   ⚠ 백엔드 추가 필요(이 서버에 제공 중인 모델 수)
 * @property {number}  [today]    ⚠ 백엔드 추가 필요(오늘 처리 건수)
 * @property {number}  [members]  ⚠ Gap-S: Discord 길드 멤버수(JDA Guild.memberCount)
 * @property {number}  [avgMs]    ⚠ Gap-S: 이 서버 오늘 평균 응답 지연(ms)
 * @property {string[]}[myModels] ⚠ Gap-S: 내가 이 서버에 제공 중인 모델명 목록
 * @property {ProviderPolicy} [policy] ⚠ Gap-P: 이 서버에 대한 "내" self-service 정책
 * @property {string}  [webUrl]   ⚠ Gap-W: 관리자용 웹 대시보드 서버 URL(외부 브라우저로 열기)
 */
/**
 * @typedef {Object} ProviderPolicy  이 서버에 대한 "내" 정책(/provider-limit·scope 의 GUI)
 * @property {number} dailyLimit     하루 처리 한도(건)
 * @property {number} maxConcurrency 동시 처리 수
 * @property {number} maxSeconds     요청 최대 처리 시간(초)
 * @property {string} scope          공개 대상 — ALL(모두) | TRUSTED(신뢰 역할) | ADMIN(관리자만). ProviderModelScope 미러.
 */

// 온보딩 적용 페이로드 — webui.py /api/onboard-apply 와 동일 키
/** @typedef {{enableImage:boolean, autostart:boolean, autoConnect:boolean, background:boolean}} OnboardConfig */

// 참여 토큰 연결 결과 — webui.py /api/server-add-token
/**
 * @typedef {Object} JoinByTokenResult
 * @property {boolean} ok
 * @property {string}  state      ProviderState (보통 APPROVED — 토큰은 승인된 서버로 발급)
 * @property {number}  guildId    토큰에서 복원
 * @property {string}  guildName  ⚠ 백엔드 추가 필요: 토큰 검증 시 guildId→guildName 을 응답에 포함해야
 *                                결과 화면에 "○○ 서버에 연결됐어요"를 보여줄 수 있다(별칭 입력 불필요).
 *                                봇이 그 길드에 있으므로 JDA Guild.name 으로 채울 수 있음.
 */
