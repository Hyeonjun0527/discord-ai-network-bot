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
  onboardApply: '/api/onboard-apply',
  connectOpen: '/api/connect-open',
  sdStatus: '/api/sd/status',
  sdSetup: '/api/sd/setup',
  ollamaSetup: '/api/ollama/setup',
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
