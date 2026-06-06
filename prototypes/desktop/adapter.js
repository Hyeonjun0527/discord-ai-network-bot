// ════════════════════════════════════════════════════════════════════════
// Adapter — 데이터 접근 레이어. UI 는 이 api 만 호출한다.
// 실제 전환: USE_MOCK = false 로 바꾸면 webui.py 엔드포인트로 fetch.
// mock 데이터는 백엔드 응답 shape(camelCase·ProviderState)를 그대로 따른다.
// ════════════════════════════════════════════════════════════════════════
import { ProviderState, Role, ENDPOINTS } from './contract.js';

export const USE_MOCK = true;

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
const _setup = {}; // mock 설치 진행 상태(runtime → { start, model })
// mock 전용: 카탈로그/설치된 모델 외 임의 모델은 "없는 모델"로 간주해 실패시킨다(없는 모델 UX 데모).
// 실 백엔드에선 ollama pull 의 404/에러가 곧 이 분기 — 형식이 맞아도 라이브러리에 없으면 실패.
const _isUnknownModel = (name) => {
  const known = new Set([...MOCK.catalog.map((m) => m.name), ...MOCK.models.map((m) => m.name)]);
  return name !== 'image' && name !== 'ollama' && !known.has(name);
};

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
  // 서버 상세 추가 필드(myModels·avgMs·policy·webUrl)는 백엔드 Gap-S/P/W — contract.js ⚠ 참고.
  // policy = 이 서버에 대한 "내" self-service 정책(/provider-limit·scope). scope: ALL|TRUSTED|ADMIN.
  servers: [
    { guildId: 1001, guildName: '한국어 개발 길드', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.ADMIN, models: 3, today: 0, members: 1240, avgMs: 0, myModels: ['llama3.1:8b', 'qwen2.5:14b', 'gemma2:2b'], policy: { dailyLimit: 500, maxConcurrency: 2, maxSeconds: 30, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1001' },
    { guildId: 1002, guildName: '게임 커뮤니티', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.PROVIDER, models: 2, today: 0, members: 8530, avgMs: 0, myModels: ['llama3.1:8b', 'qwen2.5:14b'], policy: { dailyLimit: 200, maxConcurrency: 1, maxSeconds: 30, scope: 'TRUSTED' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1002' },
    { guildId: 1003, guildName: '디자인 스튜디오', iconUrl: null, state: ProviderState.PAUSED, role: Role.PROVIDER, models: 1, today: 0, members: 312, avgMs: 0, myModels: ['llama3.1:8b'], policy: { dailyLimit: 100, maxConcurrency: 1, maxSeconds: 20, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1003' },
    { guildId: 1004, guildName: '신규 서버', iconUrl: null, state: ProviderState.PENDING, role: Role.ADMIN, models: 0, today: 0, members: 47, avgMs: 0, myModels: [], policy: { dailyLimit: 500, maxConcurrency: 2, maxSeconds: 30, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1004' },
  ],
  // webui.py /api/status
  status: { running: true, connected: true, processed: 0, imageReady: true, enableImage: true, sdInstalled: true },
  // 런타임 점검 응답시간(목)
  runtimePing: { 'Ollama': 28, 'Stable Diffusion': 400 },
  // 로컬 모델 — webui.py /api/models { models, selected }. size·tags·lastUsed 는 ⚠ 백엔드 추가 필요.
  models: [
    { name: 'exaone3.5:7.8b', size: '4.8GB', tags: ['한국어', '기본'], on: true, lastUsed: '방금' },
    { name: 'llama3.1:8b', size: '4.7GB', tags: ['한국어', '일반'], on: true, lastUsed: '2분 전' },
    { name: 'qwen2.5-coder:7b', size: '4.7GB', tags: ['코딩'], on: true, lastUsed: '어제' },
    { name: 'gemma2:2b', size: '1.6GB', tags: ['가벼움'], on: false, lastUsed: '3일 전' },
  ],
  defaultModel: 'exaone3.5:7.8b',
  // 추천 설치 카탈로그 — webui.py /api/ollama/catalog. Ollama 가 전체 목록 API 를 안 주므로
  // 대표 모델 + 사이즈 변형(경량~초대형)을 카테고리별로 큐레이션. 전체는 직접 입력(ollama.com/library).
  // 카테고리 순서: 한국어 · 범용 · 코딩 · 추론 · 비전 · 임베딩 · 경량/테스트.
  catalog: [
    // ── 한국어 ──
    { name: 'exaone3.5:2.4b', size: '1.6GB', desc: '한국어 경량', cat: '한국어' },
    { name: 'exaone3.5:7.8b', size: '4.8GB', desc: '한국어 특화 · 기본 권장', cat: '한국어' },
    { name: 'exaone3.5:32b', size: '19GB', desc: '한국어 고품질(고사양)', cat: '한국어' },
    // ── 범용 ──
    { name: 'llama3.2:3b', size: '2.0GB', desc: 'Meta 경량 범용', cat: '범용' },
    { name: 'llama3.1:8b', size: '4.7GB', desc: 'Meta 범용 한국어·영어', cat: '범용' },
    { name: 'llama3.3:70b', size: '43GB', desc: 'Meta 최상위(고사양)', cat: '범용' },
    { name: 'llama3.1:405b', size: '243GB', desc: 'Meta 초대형(서버급)', cat: '범용' },
    { name: 'qwen2.5:7b', size: '4.7GB', desc: 'Qwen 다국어', cat: '범용' },
    { name: 'qwen2.5:14b', size: '9.0GB', desc: 'Qwen 다국어 고품질', cat: '범용' },
    { name: 'qwen2.5:32b', size: '20GB', desc: 'Qwen 고사양', cat: '범용' },
    { name: 'qwen2.5:72b', size: '47GB', desc: 'Qwen 최상위(고사양)', cat: '범용' },
    { name: 'gemma2:9b', size: '5.4GB', desc: 'Google 고품질 경량', cat: '범용' },
    { name: 'gemma2:27b', size: '16GB', desc: 'Google 고사양', cat: '범용' },
    { name: 'mistral:7b', size: '4.1GB', desc: 'Mistral 범용', cat: '범용' },
    { name: 'mistral-nemo:12b', size: '7.1GB', desc: 'Mistral 고품질', cat: '범용' },
    { name: 'mistral-large:123b', size: '73GB', desc: 'Mistral 최상위(서버급)', cat: '범용' },
    { name: 'phi4:14b', size: '9.1GB', desc: 'Microsoft 고품질', cat: '범용' },
    { name: 'command-r:35b', size: '20GB', desc: 'Cohere RAG 특화', cat: '범용' },
    { name: 'command-r-plus:104b', size: '59GB', desc: 'Cohere RAG 최상위', cat: '범용' },
    // ── 코딩 ──
    { name: 'qwen2.5-coder:7b', size: '4.7GB', desc: '코딩 특화', cat: '코딩' },
    { name: 'qwen2.5-coder:32b', size: '20GB', desc: '코딩 고사양', cat: '코딩' },
    { name: 'deepseek-coder-v2:16b', size: '8.9GB', desc: '코딩 고품질', cat: '코딩' },
    { name: 'deepseek-coder-v2:236b', size: '133GB', desc: '코딩 초대형(서버급)', cat: '코딩' },
    { name: 'codellama:7b', size: '3.8GB', desc: 'Meta 코딩', cat: '코딩' },
    { name: 'codellama:34b', size: '19GB', desc: 'Meta 코딩 고사양', cat: '코딩' },
    // ── 추론(reasoning) ──
    { name: 'deepseek-r1:7b', size: '4.7GB', desc: '추론 특화', cat: '추론' },
    { name: 'deepseek-r1:14b', size: '9.0GB', desc: '추론 고품질', cat: '추론' },
    { name: 'deepseek-r1:32b', size: '20GB', desc: '추론 고사양', cat: '추론' },
    { name: 'deepseek-r1:70b', size: '43GB', desc: '추론 최상위(고사양)', cat: '추론' },
    // ── 비전 ──
    { name: 'llava:7b', size: '4.7GB', desc: '비전(이미지 이해)', cat: '비전' },
    { name: 'llava:34b', size: '20GB', desc: '비전 고사양', cat: '비전' },
    { name: 'llama3.2-vision:11b', size: '7.9GB', desc: 'Meta 비전', cat: '비전' },
    { name: 'llama3.2-vision:90b', size: '55GB', desc: 'Meta 비전 최상위', cat: '비전' },
    // ── 임베딩(검색·RAG) ──
    { name: 'nomic-embed-text', size: '0.3GB', desc: '임베딩(검색·RAG)', cat: '임베딩' },
    { name: 'mxbai-embed-large', size: '0.7GB', desc: '임베딩 고품질', cat: '임베딩' },
    // ── 경량/테스트 ──
    { name: 'gemma2:2b', size: '1.6GB', desc: 'Google 초경량', cat: '경량' },
    { name: 'llama3.2:1b', size: '1.3GB', desc: 'Meta 초경량', cat: '경량' },
    { name: 'tinyllama:1.1b', size: '0.6GB', desc: '최소 사양 테스트용', cat: '경량' },
  ],
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
  /** 서버 상세(기부자 관점) — Gap-S/P/W. 백엔드 전환 시 GET /api/servers/{guildId}. */
  async getServerDetail(guildId) {
    if (USE_MOCK) { await delay(60); const s = MOCK.servers.find((x) => x.guildId === guildId); return s ? structuredClone(s) : null; }
    return http(ENDPOINTS.serverDetail(guildId));
  },
  /** 이 서버에 대한 내 제공 일시중지/재개 — provider self-service(/provider-pause·resume). */
  async setServerPaused(guildId, paused) {
    const s = MOCK.servers.find((x) => x.guildId === guildId);
    if (USE_MOCK) { await delay(80); if (s) s.state = paused ? ProviderState.PAUSED : ProviderState.ONLINE_IDLE; return { ok: true, state: s && s.state }; }
    return post(ENDPOINTS.serverPause(guildId), { paused });
  },
  /** 이 서버에 대한 내 self-service 정책 변경(/provider-limit·scope). */
  async setServerPolicy(guildId, policy) {
    const s = MOCK.servers.find((x) => x.guildId === guildId);
    if (USE_MOCK) { await delay(80); if (s) s.policy = { ...s.policy, ...policy }; return { ok: true, policy: s && s.policy }; }
    return post(ENDPOINTS.serverPolicy(guildId), policy);
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
      // 없는 모델 데모: 모델명이 카탈로그·기존 목록에 없으면 잠시 후 실패(실제 ollama pull 404 대응)
      if (s.model && _isUnknownModel(s.model)) {
        const sec0 = (Date.now() - s.start) / 1000;
        if (sec0 < 1.2) return { phase: 'installing', percent: Math.round(sec0 / 1.2 * 30), message: '모델 확인 중' };
        return { phase: 'error', percent: 0, message: '', error: '모델을 찾을 수 없어요. 이름을 확인하세요(예: mistral:7b).' };
      }
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

  /** 로컬 모델 목록 + 기본 모델 — webui.py /api/models */
  async getModels() {
    if (USE_MOCK) { await delay(60); return { models: structuredClone(MOCK.models), defaultModel: MOCK.defaultModel }; }
    return http(ENDPOINTS.models);
  },

  /** 추천 설치 모델 카탈로그 — webui.py /api/ollama/catalog */
  async ollamaCatalog() {
    if (USE_MOCK) { await delay(60); return structuredClone(MOCK.catalog); }
    return (await http(ENDPOINTS.ollamaCatalog)).models || [];
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
