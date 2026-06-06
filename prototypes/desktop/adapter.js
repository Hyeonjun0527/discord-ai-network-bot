// ════════════════════════════════════════════════════════════════════════
// Adapter — 데이터 접근 레이어. UI 는 이 api 만 호출한다.
// 실제 전환: USE_MOCK = false 로 바꾸면 webui.py 엔드포인트로 fetch.
// mock 데이터는 백엔드 응답 shape(camelCase·ProviderState)를 그대로 따른다.
// ════════════════════════════════════════════════════════════════════════
import { ProviderState, Role, ENDPOINTS } from './contract.js';

export const USE_MOCK = true;

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
const _setup = {}; // mock 설치 진행 상태(runtime → { start, model })

// SD 설치 가능 모델 — 단일 소스(온보딩 A2 모델 선택·설치 모달이 공유). webui.py /api/sd/models 미러.
export const SD_MODELS = [
  { id: 'sd15', name: 'Stable Diffusion 1.5', short: 'SD 1.5', size: '4GB', desc: '가볍고 빠름 · 범용' },
  { id: 'sdxl', name: 'Stable Diffusion XL', short: 'SD XL', size: '6.6GB', desc: '고품질 · GPU 권장' },
];
const http = async (ep, opts) => { const r = await fetch(ep, opts); return r.json(); };
const post = (ep, body) => http(ep, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body || {}) });

// ── Mock store — 실 백엔드 응답과 동일 필드명/enum ──
const MOCK = {
  // webui.py /api/servers 는 현재 {guildId, guildName, connected} 만 → 확장 필드는 contract.js 의 ⚠ 표시 참고
  servers: [
    { guildId: 1001, guildName: '한국어 개발 길드', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.ADMIN, models: 3, today: 0 },
    { guildId: 1002, guildName: '게임 커뮤니티', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.PROVIDER, models: 2, today: 0 },
    { guildId: 1003, guildName: '디자인 스튜디오', iconUrl: null, state: ProviderState.PAUSED, role: Role.PROVIDER, models: 1, today: 0 },
    { guildId: 1004, guildName: '신규 서버', iconUrl: null, state: ProviderState.PENDING, role: Role.ADMIN, models: 0, today: 0 },
  ],
  // webui.py /api/status
  status: { running: true, connected: true, processed: 0, imageReady: true, enableImage: true, sdInstalled: true },
  // 런타임 점검 응답시간(목)
  runtimePing: { 'Ollama': 28, 'Stable Diffusion': 400 },
  // Discord 연결 후보(봇 있는 길드 ∩ 내 길드) — central ProviderConnectOnboardingService.candidates
  // autoApprove 로 결과 분기(APPROVED 즉시 연결 / PENDING 승인 대기) — AutoApprovePolicy
  candidates: [
    { guildId: 2001, guildName: '우리 동아리', iconUrl: null, autoApprove: true },
    { guildId: 2002, guildName: '학교 AI Lab', iconUrl: null, autoApprove: false },
    { guildId: 2003, guildName: '사이드프로젝트 모임', iconUrl: null, autoApprove: true },
  ],
};

export const api = {
  /** @returns {Promise<import('./contract.js').ServerConn[]>} */
  async getServers() {
    if (USE_MOCK) { await delay(60); return structuredClone(MOCK.servers); }
    return (await http(ENDPOINTS.servers)).servers;
  },
  /** @returns {Promise<import('./contract.js').AgentStatus>} */
  async getStatus() {
    if (USE_MOCK) { await delay(60); return { ...MOCK.status }; }
    return http(ENDPOINTS.status);
  },
  /** 온보딩 설정 적용 — webui.py /api/onboard-apply */
  async applyOnboarding(cfg) {
    if (USE_MOCK) { await delay(60); return { ok: true }; }
    return post(ENDPOINTS.onboardApply, cfg);
  },
  /** Discord OAuth 시작(브라우저 열기) — webui.py /api/connect-open */
  async connectOpen() {
    if (USE_MOCK) { await delay(60); return { ok: true }; }
    return post(ENDPOINTS.connectOpen, { origin: location.origin });
  },
  /** 런타임 연결 점검(핑) — 실제론 status/health 조회 */
  async checkRuntime(name) {
    if (USE_MOCK) { await delay(900); return { ok: true, ms: MOCK.runtimePing[name] ?? 0 }; }
    const s = await http(ENDPOINTS.status);
    return { ok: !!s.connected, ms: 0 };
  },

  /** 런타임 설치 시작 — webui.py /api/ollama/setup · /api/sd/setup (+진행률 폴링) */
  async startSetup(runtime, model) { // runtime: 'ollama' | 'image', model: SD 모델 id(선택)
    if (USE_MOCK) { _setup[runtime] = { start: Date.now(), model }; await delay(50); return { ok: true, started: true }; }
    return post(runtime === 'image' ? ENDPOINTS.sdSetup : ENDPOINTS.ollamaSetup, model ? { model } : {});
  },

  /** 설치 진행률 폴링 — webui.py *-setup-progress. 응답: { phase, percent, message, error } */
  async getSetupProgress(runtime) {
    if (USE_MOCK) {
      const s = _setup[runtime];
      if (!s) return { phase: 'idle', percent: 0, message: '' };
      const sec = (Date.now() - s.start) / 1000;
      const total = runtime === 'image' ? 7 : 5; // SD 가 더 오래
      const pct = Math.min(100, Math.round((sec / total) * 100));
      if (pct >= 100) return { phase: 'done', percent: 100, message: '설치 완료' };
      if (pct >= 70) return { phase: 'starting', percent: pct, message: '서비스 시작 중' };
      if (pct >= 20) return { phase: 'downloading', percent: pct, message: runtime === 'image' ? '모델 내려받는 중' : '기본 모델 내려받는 중' };
      return { phase: 'installing', percent: pct, message: '설치 준비 중' };
    }
    return http(runtime === 'image' ? ENDPOINTS.sdSetupProgress : ENDPOINTS.ollamaSetupProgress);
  },

  /** SD 설치 가능한 모델 목록 — webui.py /api/sd/models (단일 소스 SD_MODELS) */
  async sdModels() {
    if (USE_MOCK) { await delay(60); return SD_MODELS; }
    return (await http(ENDPOINTS.sdModels)).models || [];
  },

  /** Discord 연결 후보 목록 — central ProviderConnectOnboardingService (OAuth 콜백이 제공) */
  async getConnectCandidates() {
    if (USE_MOCK) { await delay(140); return structuredClone(MOCK.candidates); }
    return []; // 실제: connect-open OAuth → 콜백에서 후보 전달(프론트 단독 조회 아님)
  },

  /** 서버 참여 요청 → 결과 — central ProviderRegistrationService.requestJoin */
  async requestJoin(guildId) {
    if (USE_MOCK) {
      await delay(800);
      const c = MOCK.candidates.find((x) => x.guildId === guildId);
      return { state: c && c.autoApprove ? ProviderState.APPROVED : ProviderState.PENDING, guildId, guildName: c ? c.guildName : '' };
    }
    return {};
  },

  /** 참여 토큰으로 연결 — webui.py /api/server-add-token.
   *  토큰에 guildId 가 담겨 있어 검증 시 (providerId, guildId)·guildName 을 복원한다.
   *  ⚠ 백엔드 계약: 토큰 검증 응답에 guildName 포함 필요(결과 화면에 서버명 표시용). contract.js 참고. */
  async joinByToken(token) {
    if (USE_MOCK) {
      await delay(700);
      // 목: 토큰은 항상 승인된 서버로 발급되므로 APPROVED + 서버명(검증 응답의 guildName)
      return { ok: !!token, state: ProviderState.APPROVED, guildId: 2099, guildName: '코딩 스터디' };
    }
    return {}; // 실제: POST /api/server-add-token { token } → { ok, guildId, guildName }
  },
};
