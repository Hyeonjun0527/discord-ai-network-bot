// ════════════════════════════════════════════════════════════════════════
// Adapter — 데이터 접근 레이어. UI 는 이 api 만 호출한다.
// 실제 전환: USE_MOCK = false 로 바꾸면 webui.py 엔드포인트로 fetch.
// mock 데이터는 백엔드 응답 shape(camelCase·ProviderState)를 그대로 따른다.
//
// ⚠ `/* @proto-only */ … /* @end-proto-only */` 로 감싼 부분은 **프로토타입(시안) 전용 mock** 이다.
//   make sync-desktop(scripts/sync_desktop_app.py)이 실 앱(webui_assets)으로 이식할 때 이 구간을
//   통째로 제거한다 → 실 데스크톱 앱에는 mock 코드/데이터가 전혀 들어가지 않는다(실 HTTP 만).
// ════════════════════════════════════════════════════════════════════════
import { ProviderState, Role, ENDPOINTS } from './contract.js';

export const USE_MOCK = true;

/* @proto-only */
const delay = (ms) => new Promise((r) => setTimeout(r, ms));
const _setup = {}; // mock 설치 진행 상태(runtime → { start, model })
// mock 전용: 카탈로그/설치된 모델 외 임의 모델은 "없는 모델"로 간주해 실패시킨다(없는 모델 UX 데모).
// 실 백엔드에선 ollama pull 의 404/에러가 곧 이 분기 — 형식이 맞아도 라이브러리에 없으면 실패.
const _isUnknownModel = (name) => {
  // HuggingFace GGUF(hf.co/…)는 레지스트리 밖이라도 유효 — 데모에서 실패 처리하지 않는다.
  if (/^hf\.co\//i.test(name)) return false;
  const known = new Set([...MOCK.catalog.map((m) => m.name), ...MOCK.models.map((m) => m.name)]);
  return name !== 'image' && name !== 'ollama' && !known.has(name);
};
/* @end-proto-only */

// 실 앱(provider-agent 서빙)에선 window.__SESSION_KEY 가 주입되어 /api/* 호출에 X-Session 헤더가 붙는다.
const _sessionHeaders = () => {
  const key = (typeof window !== 'undefined' && window.__SESSION_KEY) || '';
  return key ? { 'X-Session': key } : {};
};
const http = async (ep, opts) => {
  const o = { ...(opts || {}), headers: { ...(opts && opts.headers), ..._sessionHeaders() } };
  const r = await fetch(ep, o);
  return r.json();
};
const post = (ep, body) => http(ep, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body || {}) });

// ── 실 경로 공통(getServers/getServerDetail 공유) ──────────────────────────────
// 서버 목록 항목 → ProviderState 매핑(일시중지 > 연결됨 > 오프라인).
const _serverState = (s) =>
  s.paused ? ProviderState.PAUSED : (s.connected ? ProviderState.ONLINE_IDLE : ProviderState.OFFLINE);
// 관리 권한 probe: serverManage 응답 ok=true 면 ADMIN(상세 화면과 동일 기준). probe 실패·미연결은
// PROVIDER 로(낙관적 승격 금지). 64bit guildId 는 문자열로만 다룬다.
const _probeAdminRole = async (guildId) => {
  try {
    const mg = await http(ENDPOINTS.serverManage(guildId));
    return (mg && mg.ok) ? Role.ADMIN : Role.PROVIDER;
  } catch (e) {
    console.warn('서버 관리 권한 probe 실패(guild ' + guildId + ') — PROVIDER 로 처리:', e);
    return Role.PROVIDER;
  }
};

/* @proto-only */
// ── Mock store — 실 백엔드 응답과 동일 필드명/enum (프로토타입 전용, 실 앱 이식 시 제거됨) ──
const MOCK = {
  servers: [
    { guildId: 1001, guildName: '한국어 개발 길드', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.ADMIN, models: 3, today: 0, members: 1240, avgMs: 0, myModels: ['llama3.1:8b', 'qwen2.5:14b', 'gemma2:2b'], policy: { dailyLimit: 50, maxConcurrency: 1, maxSeconds: 600, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1001' },
    { guildId: 1002, guildName: '게임 커뮤니티', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.PROVIDER, models: 2, today: 0, members: 8530, avgMs: 0, myModels: ['llama3.1:8b', 'qwen2.5:14b'], policy: { dailyLimit: 100, maxConcurrency: 2, maxSeconds: 600, scope: 'TRUSTED' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1002' },
    { guildId: 1003, guildName: '디자인 스튜디오', iconUrl: null, state: ProviderState.PAUSED, role: Role.PROVIDER, models: 1, today: 0, members: 312, avgMs: 0, myModels: ['llama3.1:8b'], policy: { dailyLimit: 10, maxConcurrency: 1, maxSeconds: 300, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1003' },
    { guildId: 1004, guildName: '신규 서버', iconUrl: null, state: ProviderState.PENDING, role: Role.ADMIN, models: 0, today: 0, members: 47, avgMs: 0, myModels: [], policy: { dailyLimit: 50, maxConcurrency: 1, maxSeconds: 600, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1004' },
  ],
  status: {
    running: true, connected: true, processed: 12, imageReady: true, enableImage: true,
    models: ['exaone3.5:7.8b', 'llama3.1:8b', 'qwen2.5-coder:7b'],
    hasToken: true, relayUrl: 'wss://discord-ai.yeon.world/agent', backgroundRunning: false, background: false, connectEnabled: true,
    version: '0.31.0', geminiConfigured: false, comfyUrl: '', hfConfigured: false, civitaiConfigured: false,
    comfyPush: { enabled: false, guildId: null, channelId: null },
  },
  // ComfyUI 라이프사이클 상태는 별도 엔드포인트(/api/comfy/status)라 status shape 와 분리한다.
  comfy: { installed: false, running: false, active: null },
  // 큐레이션 모델 카탈로그(/api/comfy/catalog) — 실 백엔드 comfy_setup.CATALOG 와 같은 shape.
  comfyCatalog: [
    { id: 'illustrious-xl-v2', name: 'Illustrious XL v2.0', category: 'anime', base: 'SDXL', desc: 'Danbooru 애니 일러스트 최신 베이스. WAI 등 인기 파인튠의 토대.', size: '6.9GB', url: 'https://huggingface.co/OnomaAIResearch/Illustrious-XL-v2.0/resolve/main/Illustrious-XL-v2.0.safetensors', filename: 'Illustrious-XL-v2.0.safetensors', installed: false },
    { id: 'animagine-xl-4', name: 'Animagine XL 4.0', category: 'anime', base: 'SDXL', desc: '미소녀·일본 애니 특화. 깔끔한 SFW 일러스트.', size: '6.9GB', url: 'https://huggingface.co/cagliostrolab/animagine-xl-4.0/resolve/main/animagine-xl-4.0.safetensors', filename: 'animagine-xl-4.0.safetensors', installed: false },
    { id: 'pony-v6-xl', name: 'Pony Diffusion V6 XL', category: 'anime', base: 'SDXL', desc: '애니·만화까지 가장 범용적인 인기 SDXL. score_9 프롬프트 권장.', size: '6.9GB', url: 'https://huggingface.co/LyliaEngine/Pony_Diffusion_V6_XL/resolve/main/ponyDiffusionV6XL_v6StartWithThisOne.safetensors', filename: 'ponyDiffusionV6XL_v6StartWithThisOne.safetensors', installed: false },
    { id: 'realvis-xl-v5', name: 'RealVisXL V5.0', category: 'realistic', base: 'SDXL', desc: '실사 표준급 고품질. 인물·풍경 안정적.', size: '6.9GB', url: 'https://huggingface.co/SG161222/RealVisXL_V5.0/resolve/main/RealVisXL_V5.0_fp16.safetensors', filename: 'RealVisXL_V5.0_fp16.safetensors', installed: false },
    { id: 'juggernaut-xl-v9', name: 'Juggernaut XL v9', category: 'realistic', base: 'SDXL', desc: '세계 최다 다운로드 실사 SDXL. 포토리얼리즘 강력.', size: '7.1GB', url: 'https://huggingface.co/RunDiffusion/Juggernaut-XL-v9/resolve/main/Juggernaut-XL_v9_RunDiffusionPhoto_v2.safetensors', filename: 'Juggernaut-XL_v9_RunDiffusionPhoto_v2.safetensors', installed: false },
  ],
  // Civitai 둘러보기(/api/comfy/civitai) — 하트 많은 순 인기 체크포인트. 실 백엔드 civitai_popular 와 같은 shape.
  civitai: [
    { name: 'Pony Diffusion V6 XL', base: 'Pony', hearts: 75982, downloads: 991281, url: 'https://civitai.com/api/download/models/290640', filename: 'ponyDiffusionV6XL_v6StartWithThisOne.safetensors', image: null, nsfw: false, sizeMb: 6616 },
    { name: 'DreamShaper', base: 'SD 1.5', hearts: 57946, downloads: 1625305, url: 'https://civitai.com/api/download/models/128713', filename: 'dreamshaper_8.safetensors', image: null, nsfw: false, sizeMb: 2034 },
    { name: 'majicMIX realistic', base: 'SD 1.5', hearts: 62844, downloads: 1220175, url: 'https://civitai.com/api/download/models/176425', filename: 'majicmixRealistic_v7.safetensors', image: null, nsfw: false, sizeMb: 2034 },
  ],
  logs: [
    '09:12:03 INFO | 에이전트 시작 (Nexa v0.31.0)',
    '09:12:03 INFO | 중앙 서버 연결: wss://discord-ai.yeon.world/agent',
    '09:12:04 INFO | Ollama 연결됨 — 모델 3개 제공 (exaone3.5:7.8b, llama3.1:8b, qwen2.5-coder:7b)',
    '09:12:04 INFO | ComfyUI 준비됨 — 이미지 생성 가능',
    '09:12:05 INFO | 서버 연결: 한국어 개발 길드',
    '09:13:21 INFO | /ask 처리 완료 (llama3.1:8b · 1.4s · 한국어 개발 길드)',
    '09:14:08 INFO | /ask 처리 완료 (exaone3.5:7.8b · 0.9s · 게임 커뮤니티)',
    '09:15:02 WARN | 일일 한도 근접 — 한국어 개발 길드 48/50',
    '09:15:47 INFO | /imagine 처리 완료 (ComfyUI · 6.2s)',
    '09:16:40 ERROR | ComfyUI 응답 지연(타임아웃) — 재시도 1/2',
    '09:18:10 INFO | /ask 처리 완료 (qwen2.5-coder:7b · 2.1s · 한국어 개발 길드)',
  ],
  settings: {
    autostart: false, background: false, autoConnect: true, autoUpdate: true,
    ollamaUrl: 'http://localhost:11434', geminiConfigured: false, comfyUrl: '',
  },
  updateInfo: { current: '0.31.0', latest: '0.31.0', outdated: false, supported: true },
  runtimePing: { 'Ollama': 28, 'ComfyUI': 400 },
  models: [
    { name: 'exaone3.5:7.8b', size: '4.8GB', tags: ['한국어', '기본'], on: true, lastUsed: '방금' },
    { name: 'llama3.1:8b', size: '4.7GB', tags: ['한국어', '일반'], on: true, lastUsed: '2분 전' },
    { name: 'qwen2.5-coder:7b', size: '4.7GB', tags: ['코딩'], on: true, lastUsed: '어제' },
    { name: 'gemma2:2b', size: '1.6GB', tags: ['가벼움'], on: false, lastUsed: '3일 전' },
  ],
  defaultModel: 'exaone3.5:7.8b',
  manage: {
    1001: {
      policy: { autoApprove: false, defaultDailyLimit: 50, scope: 'ALL' },
      pending: [{ providerUserId: 5001, name: 'user_lee', models: 2, since: '5분 전' }],
      roster: [
        { providerUserId: 0, name: '나 (이 PC)', isMe: true, state: ProviderState.ONLINE_IDLE, models: 3, today: 0, avgMs: 0 },
        { providerUserId: 5002, name: 'user_kim', isMe: false, state: ProviderState.ONLINE_IDLE, models: 1, today: 0, avgMs: 0 },
        { providerUserId: 5003, name: 'user_park', isMe: false, state: ProviderState.PAUSED, models: 2, today: 0, avgMs: 0 },
      ],
      prompts: [
        { id: 'nia', name: '니아 (기본 페르소나)', builtin: true, isDefault: true,
          preview: '당신은 「니아」, NEXA 네트워크의 안내자예요. 차분하고 다정하게, 사용자의 질문을 알맞은 AI에게 연결하고 모르면 솔직히 모른다고 말해요…' },
        { id: 'formal', name: '정중한 비서', builtin: false, isDefault: false,
          content: '당신은 정중하고 간결한 비서입니다. 존댓말로 핵심만 명료하게 전달합니다.' },
      ],
      channels: {
        defaultModel: 'llama3.1:8b', defaultLang: '한국어',
        list: [
          { channelId: '9001', name: 'general', aiAllowed: true }, { channelId: '9002', name: 'ai-chat', aiAllowed: true },
          { channelId: '9003', name: '코드리뷰', aiAllowed: true }, { channelId: '9004', name: '공지', aiAllowed: false },
        ],
      },
      channelAi: [
        { channel: 'ai-chat', model: 'llama3.1:8b', tone: '친근', on: true },
        { channel: '코드리뷰', model: 'qwen2.5-coder:7b', tone: '간결', on: true },
        { channel: 'general', model: null, tone: null, on: false },
      ],
      rag: {
        docs: [
          { name: '온보딩 가이드.pdf', status: 'indexed', chunks: 12, when: '2일 전' },
          { name: 'FAQ.md', status: 'indexed', chunks: 8, when: '오늘' },
          { name: 'API문서.txt', status: 'indexing', chunks: 0, when: '-' },
        ],
        applyChannels: ['ai-chat', '코드리뷰'],
      },
      safety: {
        reports: [
          { id: 'r1', target: '#ai-chat 답변', reason: '부적절한 표현', reporter: 'user_choi', when: '10분 전', status: 'open' },
          { id: 'r2', target: '전역 프롬프트 「정중한 비서」', reason: '스팸/광고 유도', reporter: 'user_han', when: '1시간 전', status: 'open' },
        ],
      },
      presets: [
        { name: '번역봇', model: 'llama3.1:8b', tone: '친근', applied: 'general', on: true },
        { name: '코드도우미', model: 'qwen2.5-coder:7b', tone: '간결', applied: '코드리뷰', on: true },
        { name: '요약봇', model: 'llama3.1:8b', tone: '중립', applied: null, on: false },
      ],
    },
    1004: {
      policy: { autoApprove: true, defaultDailyLimit: 50, scope: 'ALL' },
      pending: [],
      roster: [{ providerUserId: 0, name: '나 (이 PC)', isMe: true, state: ProviderState.PENDING, models: 0, today: 0, avgMs: 0 }],
      prompts: [{ id: 'nia', name: '니아 (기본 페르소나)', builtin: true, isDefault: true, preview: '당신은 「니아」, NEXA 네트워크의 안내자예요. 차분하고 다정하게, 사용자의 질문을 알맞은 AI에게 연결하고 모르면 솔직히 모른다고 말해요…' }],
      channels: { defaultModel: 'exaone3.5:7.8b', defaultLang: '한국어', list: [{ channelId: '9101', name: 'general', aiAllowed: true }] },
      channelAi: [], rag: { docs: [], applyChannels: [] }, presets: [], safety: { reports: [] },
    },
  },
  catalog: [
    // 2026-06 ollama.com/library 현행. released = 출시 시점(구형 추천 방지). 한국어 강한 것 우선.
    { name: 'exaone3.5:7.8b', size: '4.8GB', released: '2024-12', desc: '한국어 최강 기본 권장(LG AI)', cat: '한국어' },
    { name: 'exaone3.5:32b', size: '19GB', released: '2024-12', desc: '한국어 고품질(고사양)', cat: '한국어' },
    { name: 'gemma3:12b', size: '8.1GB', released: '2025-03', desc: 'Google 다국어(한국어)·비전. Gemma 2 대체', cat: '범용' },
    { name: 'gemma4:12b', size: '7.6GB', released: '2026-04', desc: 'Google 최신 멀티모달·256K', cat: '범용' },
    { name: 'qwen2.5:7b', size: '4.7GB', released: '2024-09', desc: 'Qwen 경량 다국어(한국어)·가성비', cat: '범용' },
    { name: 'qwen3.6:27b', size: '17GB', released: '2026-04', desc: 'Qwen 최신 고성능 다국어(고사양)', cat: '범용' },
    { name: 'llama3.3:70b', size: '43GB', released: '2024-12', desc: 'Meta 최상위 범용(서버급)', cat: '범용' },
    { name: 'mistral-small3.2:24b', size: '15GB', released: '2025-06', desc: 'Mistral 최신 범용', cat: '범용' },
    { name: 'qwen2.5-coder:7b', size: '4.7GB', released: '2024-11', desc: '코딩 특화 경량', cat: '코딩' },
    { name: 'qwen3-coder:30b', size: '19GB', released: '2025-07', desc: 'Qwen 최신 코딩(고사양)', cat: '코딩' },
    { name: 'deepseek-r1:8b', size: '5.2GB', released: '2025-01', desc: '수학·코딩·논리 추론 특화', cat: '추론' },
    { name: 'deepseek-r1:32b', size: '20GB', released: '2025-01', desc: '추론 고품질(고사양)', cat: '추론' },
    { name: 'gpt-oss:20b', size: '14GB', released: '2025-08', desc: 'OpenAI 오픈웨이트 추론', cat: '추론' },
    { name: 'gemma3:12b', size: '8.1GB', released: '2025-03', desc: 'Gemma 3 비전(이미지 이해)·다국어', cat: '비전' },
    { name: 'llama3.2-vision:11b', size: '7.9GB', released: '2024-11', desc: 'Meta 비전(이미지 이해)', cat: '비전' },
    { name: 'llama3.2:3b', size: '2.0GB', released: '2024-09', desc: '저사양·경량 범용', cat: '경량' },
    { name: 'gemma3:1b', size: '0.8GB', released: '2025-03', desc: 'Google 초경량 다국어', cat: '경량' },
    { name: 'qwen2.5:3b', size: '1.9GB', released: '2024-09', desc: 'Qwen 초경량 다국어', cat: '경량' },
    { name: 'nomic-embed-text', size: '0.3GB', released: '2024-02', desc: '임베딩(검색·RAG)', cat: '임베딩' },
    { name: 'mxbai-embed-large', size: '0.7GB', released: '2024-03', desc: '임베딩 고품질', cat: '임베딩' },
  ],
  candidates: [
    { guildId: 2001, guildName: '우리 동아리', iconUrl: null, autoApprove: true },
    { guildId: 2002, guildName: '학교 AI Lab', iconUrl: null, autoApprove: false },
    { guildId: 2003, guildName: '사이드프로젝트 모임', iconUrl: null, autoApprove: true },
  ],
};
// 프로토타입 데모: PROTO 컨트롤러/테스트가 mock 상태를 흔들어 조건부 UI(재연결·SD 시작 등)를 시연·검증.
if (typeof window !== 'undefined') { window.__mockPatch = (patch) => { Object.assign(MOCK.status, patch || {}); }; window.__mockComfy = (patch) => { Object.assign(MOCK.comfy, patch || {}); }; }
/* @end-proto-only */

export const api = {
  /** @returns {Promise<import('./contract.js').ServerConn[]>} */
  async getServers() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return structuredClone(MOCK.servers); } /* @end-proto-only */
    // 정규화: 실 /api/servers 는 {servers:[{index,guildId,guildName,connected}]} 만 준다(Gap-S).
    // UI 가 읽는 state 와 확장 통계는 백엔드 미보강이라 안전 기본값으로 최소 표시만.
    const [list, st] = await Promise.all([http(ENDPOINTS.servers), http(ENDPOINTS.status).catch(() => ({}))]);
    const real = list.servers || [];
    const advertised = (st && st.models) || []; // 에이전트가 (연결된) 서버에 광고하는 모델 집합 = 실제 제공 모델
    // role 배지: 서버별로 central 이 관리자 여부를 판정한다(상세 화면과 동일 기준 — manage probe ok=true 면 ADMIN).
    //   연결 안 된 서버는 probe 불가 → PROVIDER.
    const roles = await Promise.all(real.map((s) => (s.connected ? _probeAdminRole(s.guildId) : Promise.resolve(Role.PROVIDER))));
    return real.map((s, i) => ({
      guildId: s.guildId,
      guildName: s.guildName,
      iconUrl: null,
      connected: !!s.connected,
      state: _serverState(s),
      role: roles[i],
      models: s.connected ? advertised.length : 0, // 실제 제공 모델 수(연결된 서버엔 광고 집합 전부)
      today: null, members: null, avgMs: null,      // 길드별 처리/멤버수/평균지연은 미추적 — 가짜 0 금지(null=미표시)
      myModels: s.connected ? [...advertised] : [],
      policy: null, webUrl: null,
    }));
  },
  /** 서버 상세(기부자 관점) — Gap-S/P/W. 실 백엔드엔 전용 상세 API 가 없어 목록+권한 probe 로 구성. */
  async getServerDetail(guildId) {
    // guildId 는 64bit Discord ID — 문자열로만 비교/전달한다(Number 화 금지, 정밀도 손실).
    /* @proto-only */ if (USE_MOCK) { await delay(60); const s = MOCK.servers.find((x) => String(x.guildId) === String(guildId)); return s ? structuredClone(s) : null; } /* @end-proto-only */
    // 실: /api/servers/{g} 전용 라우트가 없다(Gap-S). 목록 항목으로 기본을 채우고, 관리 권한은
    //   /manage 응답 ok 로 판정한다(contract.js: "앱은 serverManage 응답 ok 로 관리자 여부를 판정").
    const [listRes, st] = await Promise.all([http(ENDPOINTS.servers), http(ENDPOINTS.status).catch(() => ({}))]);
    const list = listRes.servers || [];
    const s = list.find((x) => String(x.guildId) === String(guildId));
    if (!s) return null;
    const role = await _probeAdminRole(guildId);
    // 내 제공 정책은 **저장값을 그대로 읽어** 표시한다(하드코딩 금지 — 백엔드 강제값과 화면 일치).
    let policy = { dailyLimit: 0, maxConcurrency: 1, maxSeconds: 0 };
    try { const pr = await http(ENDPOINTS.serverPolicy(guildId)); if (pr && pr.policy) policy = pr.policy; } catch (e) { console.warn('서버 정책 조회 실패(guild ' + guildId + ') — 기본값 사용:', e); }
    const advertised = (st && st.models) || [];
    return {
      guildId: String(s.guildId), guildName: s.guildName, iconUrl: null,
      connected: !!s.connected,
      state: _serverState(s),
      role,
      models: s.connected ? advertised.length : 0,   // 실제 제공 모델 수
      today: null, members: null, avgMs: null,        // 길드별 처리/멤버/평균지연 미추적 — null(미표시)
      myModels: s.connected ? [...advertised] : [],
      policy,
      webUrl: null,
    };
  },
  /** 이 서버에 제공할 모델 설정 readback — /api/servers/{g}/models. */
  async getServerModels(guildId) {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { ok: true, available: [...MOCK.models.map((m) => m.name), 'gemini-2.5-flash-lite'], chatModels: [], imageEnabled: true, imageReady: true }; } /* @end-proto-only */
    return http(ENDPOINTS.serverModels(guildId));
  },
  /** 이 서버에 제공할 채팅 모델·이미지 여부 저장·적용 — POST /api/servers/{g}/models. chatModels 빈=전체. */
  async setServerModels(guildId, chatModels, imageEnabled) {
    /* @proto-only */ if (USE_MOCK) { await delay(80); return { ok: true }; } /* @end-proto-only */
    return http(ENDPOINTS.serverModels(guildId), { method: 'POST', body: JSON.stringify({ chatModels, imageEnabled }) });
  },
  /** 서버 관리(관리자) — 승인 대기·로스터·정책. ⚠ Gap-M(앱↔central 관리 채널). 비관리자는 ok=false. */
  async getServerManage(guildId) {
    /* @proto-only */ if (USE_MOCK) { await delay(60); const m = MOCK.manage[guildId]; return m ? { ok: true, ...structuredClone(m) } : { ok: true, policy: { autoApprove: false, defaultDailyLimit: 50, scope: 'ALL' }, pending: [], roster: [] }; } /* @end-proto-only */
    return http(ENDPOINTS.serverManage(guildId));
  },
  /** Provider 승인(관리자 → /provider-approve). 승인 시 로스터로 이동. */
  async approveProvider(guildId, providerUserId) {
    /* @proto-only */
    if (USE_MOCK) {
      const m = MOCK.manage[guildId];
      await delay(80);
      if (m) { const i = m.pending.findIndex((p) => String(p.providerUserId) === String(providerUserId)); if (i >= 0) { const p = m.pending.splice(i, 1)[0]; m.roster.push({ providerUserId: p.providerUserId, name: p.name, isMe: false, state: ProviderState.ONLINE_IDLE, models: p.models, today: 0, avgMs: 0 }); } }
      return { ok: true };
    }
    /* @end-proto-only */
    return post(ENDPOINTS.providerApprove(guildId), { providerUserId });
  },
  /** Provider 거절(관리자 → /provider-reject). 승인 대기에서 제거. */
  async rejectProvider(guildId, providerUserId) {
    /* @proto-only */
    if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(80); if (m) { const i = m.pending.findIndex((p) => String(p.providerUserId) === String(providerUserId)); if (i >= 0) m.pending.splice(i, 1); } return { ok: true }; }
    /* @end-proto-only */
    return post(ENDPOINTS.providerReject(guildId), { providerUserId });
  },
  /** Provider 제거(관리자 → /provider-remove). 로스터에서 제거(나 제외). */
  async removeProvider(guildId, providerUserId) {
    /* @proto-only */
    if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(80); if (m) { const i = m.roster.findIndex((p) => String(p.providerUserId) === String(providerUserId) && !p.isMe); if (i >= 0) m.roster.splice(i, 1); } return { ok: true }; }
    /* @end-proto-only */
    return post(ENDPOINTS.providerRemove(guildId), { providerUserId });
  },
  /** 서버 제공 정책(관리자) — 신규 자동 승인·기본 한도·공개 대상. */
  async setManagePolicy(guildId, policy) {
    /* @proto-only */
    if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(80); if (m) m.policy = { ...m.policy, ...policy }; return { ok: true, policy: m && m.policy }; }
    /* @end-proto-only */
    return post(ENDPOINTS.serverManagePolicy(guildId), policy);
  },
  // ── 전역 프롬프트셋(서버 전체 기본 AI 성격) — 관리자. 응답 {ok, sets:[{id,name,builtin,isDefault,preview,content}]}.
  //   builtin(니아)은 preview 만(전문 비공개). webui → central /provider/admin/prompt-sets{,/add,/default,/delete}.
  /** 전역 프롬프트셋 목록 조회. */
  async getPromptSets(guildId) {
    /* @proto-only */ if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(60); return { ok: true, sets: m ? structuredClone(m.prompts) : [] }; } /* @end-proto-only */
    return http(ENDPOINTS.serverPrompts(guildId));
  },
  /** 전역 프롬프트셋 추가(사용자 작성). 추가만으로 기본이 되지는 않는다. */
  async addPromptSet(guildId, name, content) {
    /* @proto-only */ if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(80); if (m) m.prompts.push({ id: 'p' + Date.now(), name, builtin: false, isDefault: false, content }); return { ok: true, sets: m ? structuredClone(m.prompts) : [] }; } /* @end-proto-only */
    return post(ENDPOINTS.serverPromptAdd(guildId), { name, content });
  },
  /** 기본 셋 지정. id='nia' 면 NEXA 기본 정체성(니아)으로 되돌린다. */
  async setDefaultPromptSet(guildId, id) {
    /* @proto-only */ if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(80); if (m) m.prompts.forEach((p) => { p.isDefault = (p.id === id); }); return { ok: true, sets: m ? structuredClone(m.prompts) : [] }; } /* @end-proto-only */
    return post(ENDPOINTS.serverPromptDefault(guildId), { id });
  },
  /** 전역 프롬프트셋 삭제. 기본이던 셋을 지우면 니아로 되돌아간다. builtin(니아)은 삭제 불가. */
  async deletePromptSet(guildId, id) {
    /* @proto-only */
    if (USE_MOCK) {
      const m = MOCK.manage[guildId];
      await delay(80);
      if (m) {
        const wasDefault = m.prompts.find((p) => p.id === id)?.isDefault;
        m.prompts = m.prompts.filter((p) => p.id !== id);
        if (wasDefault && !m.prompts.some((p) => p.isDefault)) { const nia = m.prompts.find((p) => p.builtin); if (nia) nia.isDefault = true; }
      }
      return { ok: true, sets: m ? structuredClone(m.prompts) : [] };
    }
    /* @end-proto-only */
    return post(ENDPOINTS.serverPromptDelete(guildId), { id });
  },
  // ── 채널 AI 허용(관리 화면 08) — 관리자. 빈 허용 목록 = 전체 채널 허용. channelId 는 문자열(64bit).
  //   webui → central /provider/admin/channels{,/toggle}. PolicyService(GuildChannelPolicy) 재사용.
  /** 채널 AI 허용 목록 조회. 실 응답 {ok, channels:[{channelId,name,aiAllowed}]}. */
  async getChannels(guildId) {
    /* @proto-only */ if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(60); return { ok: true, channels: m && m.channels ? structuredClone(m.channels.list) : [] }; } /* @end-proto-only */
    return http(ENDPOINTS.serverChannels(guildId));
  },
  /** 채널 AI 허용/금지 토글. allow=원하는 새 상태. 응답에 갱신된 채널 목록 포함. */
  async toggleChannel(guildId, channelId, allow) {
    /* @proto-only */
    if (USE_MOCK) {
      const m = MOCK.manage[guildId];
      await delay(80);
      if (m && m.channels) { const ch = m.channels.list.find((c) => String(c.channelId) === String(channelId)); if (ch) ch.aiAllowed = allow; }
      return { ok: true, channels: m && m.channels ? structuredClone(m.channels.list) : [] };
    }
    /* @end-proto-only */
    return post(ENDPOINTS.serverChannelToggle(guildId), { channelId: String(channelId), allow });
  },
  // ── 읽기 전용 관리 탭(채널AI/RAG/프리셋) — 관리자. 추가·편집은 Discord 명령·웹 대시보드.
  /** 채널 AI 프로필 목록 — {ok, items:[{channelId,name,tone,purpose}]}. */
  async getChannelAi(guildId) {
    /* @proto-only */
    if (USE_MOCK) {
      const m = MOCK.manage[guildId];
      await delay(60);
      const list = (m && m.channelAi) || [];
      return { ok: true, items: list.filter((c) => c.on).map((c) => ({ channelId: 'c_' + c.channel, name: c.channel, tone: c.tone || '-', purpose: 'general_assistant' })) };
    }
    /* @end-proto-only */
    return http(ENDPOINTS.serverChannelAi(guildId));
  },
  /** 지식 소스(RAG) 목록 — {ok, docs:[{id,title,status,riskLevel,addedAt,indexedAt}]}. */
  async getKnowledge(guildId) {
    /* @proto-only */
    if (USE_MOCK) {
      const m = MOCK.manage[guildId];
      await delay(60);
      const docs = (m && m.rag && m.rag.docs) || [];
      return { ok: true, docs: docs.map((d, i) => ({ id: 'd' + i, title: d.name, status: d.status, riskLevel: 'low', addedAt: d.when, indexedAt: d.status === 'indexed' ? d.when : null })) };
    }
    /* @end-proto-only */
    return http(ENDPOINTS.serverKnowledge(guildId));
  },
  /** 지식 소스(RAG) 삭제(관리자) — central 이 길드 소유권 가드. 성공 시 갱신 목록 반환. */
  async deleteKnowledge(guildId, sourceId) {
    /* @proto-only */ if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(100); if (m && m.rag && m.rag.docs) { const i = parseInt(String(sourceId).replace('d', ''), 10); if (i >= 0) m.rag.docs.splice(i, 1); } return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.serverKnowledgeDelete(guildId), { sourceId: String(sourceId) });
  },
  /** 프리셋 목록 — {ok, presets:[{id,name,category,status,summary}]}. */
  async getPresets(guildId) {
    /* @proto-only */
    if (USE_MOCK) {
      const m = MOCK.manage[guildId];
      await delay(60);
      const ps = (m && m.presets) || [];
      return { ok: true, presets: ps.map((p, i) => ({ id: 'p' + i, name: p.name, category: 'channel_ai', status: p.on ? 'active' : 'draft', summary: p.model + ' · ' + p.tone })) };
    }
    /* @end-proto-only */
    return http(ENDPOINTS.serverPresets(guildId));
  },
  /** 프리셋 삭제(관리자) — central 이 길드 소유권 가드. 성공 시 갱신 목록 반환. */
  async deletePreset(guildId, presetId) {
    /* @proto-only */ if (USE_MOCK) { const m = MOCK.manage[guildId]; await delay(100); if (m && m.presets) { const i = parseInt(String(presetId).replace('p', ''), 10); if (i >= 0) m.presets.splice(i, 1); } return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.serverPresetDelete(guildId), { presetId: String(presetId) });
  },
  /** 이 서버에 대한 내 제공 일시중지/재개 — provider self-service(/provider-pause·resume). */
  async setServerPaused(guildId, paused) {
    /* @proto-only */
    if (USE_MOCK) { const s = MOCK.servers.find((x) => String(x.guildId) === String(guildId)); await delay(80); if (s) s.state = paused ? ProviderState.PAUSED : ProviderState.ONLINE_IDLE; return { ok: true, state: s && s.state }; }
    /* @end-proto-only */
    return post(ENDPOINTS.serverPause(guildId), { paused });
  },
  /** 이 서버 제공 그만두기(내 로컬 연결 정리). 중앙 관리자 등록 제거와 별개 — 세션이 오프라인이 됨. */
  async removeServer(guildId) {
    /* @proto-only */ if (USE_MOCK) { const i = MOCK.servers.findIndex((x) => String(x.guildId) === String(guildId)); await delay(120); if (i >= 0) MOCK.servers.splice(i, 1); return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.serverRemove, { guildId: String(guildId) });
  },
  /** 서버 표시 이름 변경(로컬 라벨). */
  async renameServer(guildId, name) {
    /* @proto-only */ if (USE_MOCK) { const s = MOCK.servers.find((x) => String(x.guildId) === String(guildId)); await delay(80); if (s) s.guildName = name; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.serverRename, { guildId: String(guildId), name });
  },
  /** 이 서버에 대한 내 self-service 정책 변경(/provider-limit·scope). */
  async setServerPolicy(guildId, policy) {
    /* @proto-only */
    if (USE_MOCK) { const s = MOCK.servers.find((x) => String(x.guildId) === String(guildId)); await delay(80); if (s) s.policy = { ...s.policy, ...policy }; return { ok: true, policy: s && s.policy }; }
    /* @end-proto-only */
    return post(ENDPOINTS.serverPolicy(guildId), policy);
  },
  /** @returns {Promise<import('./contract.js').AgentStatus>} */
  async getStatus() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return structuredClone(MOCK.status); } /* @end-proto-only */
    return http(ENDPOINTS.status);
  },
  /** 최근 로그 라인 — webui.py /api/logs. @returns {Promise<import('./contract.js').AgentLogs>} */
  async getLogs() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { lines: [...MOCK.logs] }; } /* @end-proto-only */
    return http(ENDPOINTS.logs);
  },
  /** 에이전트 실행 시작 — webui.py /api/start (내부적으로 setup→연결). */
  async startAgent() {
    /* @proto-only */ if (USE_MOCK) { await delay(220); MOCK.status.running = true; MOCK.status.connected = true; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.start);
  },
  /** 에이전트 중지 — webui.py /api/stop. */
  async stopAgent() {
    /* @proto-only */ if (USE_MOCK) { await delay(200); MOCK.status.running = false; MOCK.status.connected = false; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.stop);
  },
  /** 이미지 요청 수신 토글(enableImage) — **전용** /api/image. 모델 선택을 건드리지 않고 라이브 적용
   *  (GUI 실행 중이면 ComfyUI health·재광고, 백그라운드 서비스면 재기동). 반환 {ok,on,imageReady,applied}. */
  async setImageReceiving(on) {
    /* @proto-only */ if (USE_MOCK) { await delay(80); MOCK.status.enableImage = on; MOCK.status.imageReady = on && !!MOCK.comfy.running; return { ok: true, on, imageReady: MOCK.status.imageReady, applied: 'live' }; } /* @end-proto-only */
    return post(ENDPOINTS.image, { on });
  },
  /** 클라우드 AI 설정 — Gemini 키(관리자 1개로 서버 무료 제공)·ComfyUI 주소. webui.py POST /api/cloud. */
  async setCloud({ geminiApiKey, comfyUrl, hfToken, civitaiToken }) {
    /* @proto-only */ if (USE_MOCK) { await delay(120); const o = { ok: true }; if (geminiApiKey !== undefined) { MOCK.status.geminiConfigured = !!geminiApiKey; o.geminiConfigured = !!geminiApiKey; o.geminiValid = !!geminiApiKey; } if (comfyUrl !== undefined) { MOCK.status.comfyUrl = comfyUrl; o.comfyUrl = comfyUrl; o.needsRestart = true; } if (hfToken !== undefined) { MOCK.status.hfConfigured = !!hfToken; o.hfConfigured = !!hfToken; } if (civitaiToken !== undefined) { MOCK.status.civitaiConfigured = !!civitaiToken; o.civitaiConfigured = !!civitaiToken; } return o; } /* @end-proto-only */
    const body = {}; if (geminiApiKey !== undefined) body.geminiApiKey = geminiApiKey; if (comfyUrl !== undefined) body.comfyUrl = comfyUrl; if (hfToken !== undefined) body.hfToken = hfToken; if (civitaiToken !== undefined) body.civitaiToken = civitaiToken;
    return post(ENDPOINTS.cloud, body);
  },
  /** 제공할 텍스트 모델 선택 적용 — webui.py POST /api/setup {models, enableImage, applyToBackground}.
   *  실행 중 서비스에 즉시 반영(applyToBackground=true)해 풀에 새 구성을 광고한다. enableImage 는 현재값
   *  을 보존(부분 저장이 이미지 토글을 끄지 않도록). */
  async applyModels(models, defaultModel) {
    /* @proto-only */ if (USE_MOCK) { await delay(220); MOCK.status.models = [...models]; if (defaultModel) MOCK.defaultModel = defaultModel; return { ok: true }; } /* @end-proto-only */
    const st = await http(ENDPOINTS.status);
    return post(ENDPOINTS.setup, { models, default: defaultModel || '', enableImage: !!st.enableImage, applyToBackground: true });
  },
  /** 통합 설정 조회 — webui.py GET /api/settings(저장 설정+상태를 camelCase 로 통합). */
  async getSettings() {
    /* @proto-only */
    if (USE_MOCK) {
      await delay(60);
      return { ...MOCK.settings, enableImage: MOCK.status.enableImage, relayUrl: MOCK.status.relayUrl, hasToken: MOCK.status.hasToken };
    }
    /* @end-proto-only */
    return http(ENDPOINTS.settings);
  },
  /** 설정 변경 — webui.py POST /api/settings {key:value}. 단일 엔드포인트로 통합(반환 {ok, needsRestart}). */
  async setSetting(key, value) {
    /* @proto-only */ if (USE_MOCK) { await delay(80); if (key === 'enableImage') MOCK.status.enableImage = value; else MOCK.settings[key] = value; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.settings, { [key]: value });
  },
  /** 업데이트 정보 — webui.py /api/update-info. */
  async getUpdateInfo() {
    /* @proto-only */ if (USE_MOCK) { if (globalThis.__HANG_UPDATE_INFO) return new Promise(() => {}); await delay(60); return { ...MOCK.updateInfo }; } /* @end-proto-only */
    return http(ENDPOINTS.updateInfo);
  },
  /** 인앱 업데이트 시작 — webui.py POST /api/update. 진행률은 getUpdateProgress 폴링. */
  async applyUpdate() {
    /* @proto-only */ if (USE_MOCK) { await delay(120); return { ok: true, started: true }; } /* @end-proto-only */
    return post(ENDPOINTS.updateApply);
  },
  /** 업데이트 진행률 — webui.py /api/update-progress. {phase,percent,message,error}. */
  async getUpdateProgress() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { phase: 'idle', percent: 0, message: '' }; } /* @end-proto-only */
    return http(ENDPOINTS.updateProgress);
  },
  /** 연결 해제(로그아웃) — webui.py /api/logout. 토큰·서버 연결을 비우고 온보딩으로. */
  async logout() {
    /* @proto-only */ if (USE_MOCK) { await delay(120); MOCK.status.hasToken = false; MOCK.status.connected = false; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.logout);
  },
  /** 로컬 폴더 열기(⋯ '출력 폴더 열기') — webui.py POST /api/open-folder?which=. 같은 PC 파일 탐색기. */
  async openFolder(which) {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { ok: true, path: '~/.local/share/nexa/stable-diffusion-webui/outputs' }; } /* @end-proto-only */
    return post(ENDPOINTS.openFolder, { which });
  },
  /** 온보딩 설정 적용 — webui.py /api/onboard-apply */
  async applyOnboarding(cfg) {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.onboardApply, cfg);
  },
  /** Discord OAuth 시작(브라우저 열기) — webui.py /api/connect-open */
  async connectOpen() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.connectOpen, { origin: location.origin });
  },
  /** 런타임 연결 점검(핑) — 실제론 status/health 조회 */
  async checkRuntime(name) {
    /* @proto-only */ if (USE_MOCK) { await delay(900); return { ok: true, ms: MOCK.runtimePing[name] ?? 0 }; } /* @end-proto-only */
    const s = await http(ENDPOINTS.status);
    return { ok: !!s.connected, ms: 0 };
  },

  /** Ollama 모델 설치 시작 — webui.py /api/ollama/setup (진행은 getSetupProgress 폴링). 이미지는 ComfyUI 경로. */
  async startSetup(runtime, model) { // runtime: 'ollama' (이미지 엔진은 ComfyUI 전용 메서드)
    /* @proto-only */ if (USE_MOCK) { _setup[runtime] = { start: Date.now(), model }; await delay(50); return { ok: true, started: true }; } /* @end-proto-only */
    return post(ENDPOINTS.ollamaSetup, model ? { model } : {});
  },

  // ── ComfyUI(1급 이미지 엔진) — 앱이 설치/실행/정지/웹UI 직접 관리 ──────────────
  /** ComfyUI 상태 — {installed, running, busy}. */
  async comfyStatus() {
    /* @proto-only */ if (USE_MOCK) { await delay(40); return { installed: !!MOCK.comfy.installed, running: !!MOCK.comfy.running, busy: false }; } /* @end-proto-only */
    return http(ENDPOINTS.comfyStatus);
  },
  /** ComfyUI 설치 시작(핀 clone→3.13 venv→torch/deps→기동). 진행은 comfySetupProgress 폴링. */
  async installComfy() {
    /* @proto-only */ if (USE_MOCK) { _setup.comfy = { start: Date.now() }; await delay(50); return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.comfySetup);
  },
  /** ComfyUI 설치 진행률 — { phase, percent, message, error }. */
  async comfySetupProgress() {
    /* @proto-only */
    if (USE_MOCK) {
      const s = _setup.comfy; if (!s) return { phase: 'idle', percent: 0, message: '' };
      // 모델 다운로드(s.model)와 엔진 설치를 구분 — 둘 다 동일 진행률 엔드포인트로 폴링됨(실 백엔드도 comfy_setup._state 공유).
      if (s.model) {
        const dsec = (Date.now() - s.start) / 1000, dpct = Math.min(100, Math.round(dsec / 4 * 100));
        if (dpct >= 100) { const e = MOCK.comfyCatalog.find((m) => m.filename === s.model); if (e) e.installed = true; MOCK.comfy.active = s.model; _setup.comfy = null; return { phase: 'done', percent: 100, message: '이미지 모델 준비 완료' }; }
        return { phase: 'downloading', percent: Math.max(1, dpct), message: s.model + ' 내려받는 중… ' + dpct + '%' };
      }
      const sec = (Date.now() - s.start) / 1000, pct = Math.min(100, Math.round(sec / 8 * 100));
      if (pct >= 100) { MOCK.comfy.installed = true; MOCK.comfy.running = true; return { phase: 'done', percent: 100, message: 'ComfyUI 준비 완료' }; }
      if (pct >= 90) return { phase: 'starting', percent: pct, message: 'ComfyUI 시작 중' };
      if (pct >= 45) return { phase: 'installing', percent: pct, message: 'PyTorch·의존성 설치 중' };
      return { phase: 'installing', percent: pct, message: 'ComfyUI 내려받는 중' };
    }
    /* @end-proto-only */
    return http(ENDPOINTS.comfySetupProgress);
  },
  /** 설치된 ComfyUI 기동. */
  async comfyStart() {
    /* @proto-only */ if (USE_MOCK) { await delay(80); MOCK.comfy.running = true; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.comfyStart);
  },
  /** ComfyUI 정지. */
  async comfyStop() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); MOCK.comfy.running = false; return { ok: true }; } /* @end-proto-only */
    return post(ENDPOINTS.comfyStop);
  },
  /** ComfyUI 웹UI 를 시스템 브라우저로 연다. */
  async comfyOpen() {
    /* @proto-only */ if (USE_MOCK) { await delay(40); return { ok: !!MOCK.comfy.running, error: MOCK.comfy.running ? undefined : 'ComfyUI 가 실행 중이 아니에요. 먼저 시작하세요.' }; } /* @end-proto-only */
    return post(ENDPOINTS.comfyOpen);
  },
  /** ComfyUI 체크포인트 목록 + 활성(폴더 스캔 = 아무 .safetensors) — {models, active}. */
  async comfyModels() {
    /* @proto-only */ if (USE_MOCK) { await delay(50); const models = ['Anything V5.safetensors', 'animagine-xl-4.0.safetensors', 'Illustrious-XL-v0.1.safetensors']; return { models, active: MOCK.comfy.active || models[0] }; } /* @end-proto-only */
    return http(ENDPOINTS.comfyModels);
  },
  /** 큐레이션 이미지 모델 카탈로그 — {models:[{id,name,category,base,desc,size,url,filename,installed}]}. */
  async comfyCatalog() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { models: structuredClone(MOCK.comfyCatalog) }; } /* @end-proto-only */
    return http(ENDPOINTS.comfyCatalog);
  },
  /** Civitai 인기 체크포인트 둘러보기 — 하트 많은 순. {models:[{name,base,hearts,downloads,url,filename,image,nsfw}], needsKey}. */
  async comfyCivitai(query = '', sort = 'liked', nsfw = false) {
    /* @proto-only */ if (USE_MOCK) { await delay(120); return { models: structuredClone(MOCK.civitai), needsKey: !MOCK.status.civitaiConfigured }; } /* @end-proto-only */
    const qs = '?q=' + encodeURIComponent(query) + '&sort=' + encodeURIComponent(sort) + '&nsfw=' + (nsfw ? '1' : '0');
    return http(ENDPOINTS.comfyCivitai + qs);
  },
  /** ComfyUI 웹UI 실행 결과 자동 게시 대상 설정 — POST /api/comfy/push {enabled?,guildId?,channelId?}.
   *  반환 {ok, enabled, guildId, channelId}. 실 게시는 에이전트 브리지가 central 로(관리자·채널소속 가드). */
  async setComfyPush({ enabled, guildId, channelId }) {
    /* @proto-only */ if (USE_MOCK) { await delay(80); const cp = MOCK.status.comfyPush || (MOCK.status.comfyPush = {}); if (enabled !== undefined) cp.enabled = !!enabled; if (guildId !== undefined) cp.guildId = String(guildId); if (channelId !== undefined) cp.channelId = String(channelId); return { ok: true, ...cp }; } /* @end-proto-only */
    const body = {}; if (enabled !== undefined) body.enabled = enabled; if (guildId !== undefined) body.guildId = guildId; if (channelId !== undefined) body.channelId = channelId;
    return post(ENDPOINTS.comfyPush, body);
  },
  /** 활성 ComfyUI 체크포인트 전환 — POST /api/comfy/select {model}. */
  async comfySelectModel(model) {
    /* @proto-only */ if (USE_MOCK) { await delay(80); MOCK.comfy.active = model; return { ok: true, active: model }; } /* @end-proto-only */
    return post(ENDPOINTS.comfySelect, { model });
  },
  /** 모델 URL(.safetensors)을 ComfyUI 폴더로 다운로드(카탈로그·임의 URL 공용) — POST /api/comfy/install-model {url}.
   *  대용량이라 백그라운드로 받고 즉시 반환 — 진행률은 comfySetupProgress 폴링, 완료 후 comfySelectModel 로 활성화. */
  async installComfyModel(url, filename) {
    /* @proto-only */ if (USE_MOCK) { const fn = filename || (url.split('/').pop() || '').split('?')[0]; const ok = /\.(safetensors|ckpt)$/.test(fn); if (!ok) { await delay(80); return { ok: false, error: '.safetensors/.ckpt 모델인지 확인하세요.' }; } _setup.comfy = { start: Date.now(), model: fn }; await delay(60); return { ok: true, started: true }; } /* @end-proto-only */
    return post(ENDPOINTS.comfyInstallModel, filename ? { url, filename } : { url });
  },

  /**
   * 설치 진행률 폴링 — webui.py *-setup-progress. 응답: { phase, percent, message, error }.
   * 실제 백엔드(sd_setup._download)는 받은 바이트 비율을 다운로드 구간(35~95%)으로 매핑해 보고하며,
   * 끊기면 .part 로 이어받기(HTTP Range)한다.
   */
  async getSetupProgress(runtime) {
    /* @proto-only */
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
      const pct = Math.min(100, Math.round((sec / 5) * 100));
      if (pct >= 100) return { phase: 'done', percent: 100, message: '설치 완료' };
      if (pct >= 70) return { phase: 'starting', percent: pct, message: '서비스 시작 중' };
      if (pct >= 20) return { phase: 'downloading', percent: pct, message: '기본 모델 내려받는 중' };
      return { phase: 'installing', percent: pct, message: '설치 준비 중' };
    }
    /* @end-proto-only */
    return http(ENDPOINTS.ollamaSetupProgress);
  },

  /** 로컬 모델 목록 + 기본 모델 — webui.py /api/models */
  async getModels() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return { models: structuredClone(MOCK.models), defaultModel: MOCK.defaultModel }; } /* @end-proto-only */
    // 정규화: 실 {models:[str], modelsDetail:[{name,size,family}], selected:[str], default}
    //  → UI {models:[{name,size,tags,on,lastUsed}], defaultModel}.
    const r = await http(ENDPOINTS.models);
    const detail = new Map((r.modelsDetail || []).map((d) => [d.name, d]));
    const selected = new Set(r.selected || []);
    const models = (r.models || []).map((name) => {
      const d = detail.get(name) || {};
      return {
        name,
        size: d.size || '', // 용량 미상이면 빈 값(UI m-size 가 비어도 무방)
        tags: d.family ? [d.family] : [], // family 있으면 태그로, 없으면 빈 배열
        on: selected.has(name), // selected 면 제공 중(켜짐)
        lastUsed: '', // 백엔드 마지막 사용 미제공 — 빈 값
      };
    });
    return { models, defaultModel: r.default || '' };
  },

  /** 추천 설치 모델 카탈로그 — webui.py /api/ollama/catalog */
  async ollamaCatalog() {
    /* @proto-only */ if (USE_MOCK) { await delay(60); return structuredClone(MOCK.catalog); } /* @end-proto-only */
    // 정규화: 실 {models:[{id,name,desc,size,released,installed,selected}]} → UI {name,size,desc,cat,released}.
    const real = (await http(ENDPOINTS.ollamaCatalog)).models || [];
    return real.map((m) => ({ name: m.id || m.name, size: m.size || '', desc: m.desc || '', cat: '추천', released: m.released || '' }));
  },

  /** Discord 연결 후보 목록 — central ProviderConnectOnboardingService (OAuth 콜백이 제공) */
  async getConnectCandidates() {
    /* @proto-only */ if (USE_MOCK) { await delay(140); return structuredClone(MOCK.candidates); } /* @end-proto-only */
    // 실 앱은 후보 API 가 없다(설계상 위임): connect-open 이 시스템 브라우저로 relay OAuth 를 열고
    // 후보 선택·승인은 브라우저에서 끝난다. relay 가 /connect/callback 으로 토큰·guild 를 리디렉트하면
    // connect_callback 이 토큰 저장 + 자동 연결을 처리한다. 따라서 앱 내 후보 조회는 항상 빈 배열.
    return [];
  },

  /** 서버 참여 요청 → 결과 — central ProviderRegistrationService.requestJoin */
  async requestJoin(guildId) {
    /* @proto-only */
    if (USE_MOCK) {
      await delay(800);
      const c = MOCK.candidates.find((x) => String(x.guildId) === String(guildId));
      return { state: c && c.autoApprove ? ProviderState.APPROVED : ProviderState.PENDING, guildId, guildName: c ? c.guildName : '' };
    }
    /* @end-proto-only */
    // 실 앱은 후보 참여를 브라우저 OAuth 로 위임(getConnectCandidates 가 빈 배열이라 이 경로는 도달하지 않음).
    return {};
  },

  /** 참여 토큰으로 연결 — webui.py /api/server-add-token.
   *  토큰에 guildId 가 담겨 있어 검증 시 (providerId, guildId)·guildName 을 복원한다. */
  async joinByToken(token) {
    /* @proto-only */
    if (USE_MOCK) {
      await delay(700);
      return { ok: !!token, state: ProviderState.APPROVED, guildId: 2099, guildName: '코딩 스터디' };
    }
    /* @end-proto-only */
    // 실 앱: POST /api/server-add-token {token} → {ok} (백엔드가 토큰 검증·저장·자동 연결까지 처리).
    const r = await post(ENDPOINTS.serverAddToken, { token });
    return {
      ok: !!r.ok,
      state: r.ok ? ProviderState.APPROVED : ProviderState.PENDING,
      guildId: r.guildId ?? null,
      guildName: r.guildName || '',
    };
  },
};
