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
// 실 앱(provider-agent 서빙)에선 window.__SESSION_KEY 가 주입되어 /api/* 호출에 X-Session 헤더가 붙는다.
// 프로토타입(8777 서버)은 키가 없어 헤더 없이 기존과 동일하게 동작한다(mock·E2E 보존).
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

// ── Mock store — 실 백엔드 응답과 동일 필드명/enum ──
const MOCK = {
  // webui.py /api/servers 는 현재 {guildId, guildName, connected} 만 → 확장 필드는 contract.js 의 ⚠ 표시 참고
  // 서버 상세 추가 필드(myModels·avgMs·policy·webUrl)는 백엔드 Gap-S/P/W — contract.js ⚠ 참고.
  // policy = 이 서버에 대한 "내" self-service 정책(/provider-limit·scope). scope: ALL|TRUSTED|ADMIN.
  servers: [
    { guildId: 1001, guildName: '한국어 개발 길드', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.ADMIN, models: 3, today: 0, members: 1240, avgMs: 0, myModels: ['llama3.1:8b', 'qwen2.5:14b', 'gemma2:2b'], policy: { dailyLimit: 50, maxConcurrency: 1, maxSeconds: 600, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1001' },
    { guildId: 1002, guildName: '게임 커뮤니티', iconUrl: null, state: ProviderState.ONLINE_IDLE, role: Role.PROVIDER, models: 2, today: 0, members: 8530, avgMs: 0, myModels: ['llama3.1:8b', 'qwen2.5:14b'], policy: { dailyLimit: 100, maxConcurrency: 2, maxSeconds: 600, scope: 'TRUSTED' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1002' },
    { guildId: 1003, guildName: '디자인 스튜디오', iconUrl: null, state: ProviderState.PAUSED, role: Role.PROVIDER, models: 1, today: 0, members: 312, avgMs: 0, myModels: ['llama3.1:8b'], policy: { dailyLimit: 10, maxConcurrency: 1, maxSeconds: 300, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1003' },
    { guildId: 1004, guildName: '신규 서버', iconUrl: null, state: ProviderState.PENDING, role: Role.ADMIN, models: 0, today: 0, members: 47, avgMs: 0, myModels: [], policy: { dailyLimit: 50, maxConcurrency: 1, maxSeconds: 600, scope: 'ALL' }, webUrl: 'https://discord-ai.yeon.world/dashboard/1004' },
  ],
  // webui.py /api/status — 실제 응답 필드 전부(camelCase).
  status: {
    running: true, connected: true, processed: 12, imageReady: true, enableImage: true, sdInstalled: true,
    models: ['exaone3.5:7.8b', 'llama3.1:8b', 'qwen2.5-coder:7b'],
    hasToken: true, relayUrl: 'wss://discord-ai.yeon.world/agent', backgroundRunning: false, connectEnabled: true,
  },
  // webui.py /api/logs — 최근 로그 라인(최대 200). 형식 "HH:MM:SS LEVEL | message".
  logs: [
    '09:12:03 INFO | 에이전트 시작 (Nexa v0.31.0)',
    '09:12:03 INFO | 중앙 서버 연결: wss://discord-ai.yeon.world/agent',
    '09:12:04 INFO | Ollama 연결됨 — 모델 3개 제공 (exaone3.5:7.8b, llama3.1:8b, qwen2.5-coder:7b)',
    '09:12:04 INFO | Stable Diffusion 준비됨 — 이미지 생성 가능',
    '09:12:05 INFO | 서버 연결: 한국어 개발 길드',
    '09:13:21 INFO | /ask 처리 완료 (llama3.1:8b · 1.4s · 한국어 개발 길드)',
    '09:14:08 INFO | /ask 처리 완료 (exaone3.5:7.8b · 0.9s · 게임 커뮤니티)',
    '09:15:02 WARN | 일일 한도 근접 — 한국어 개발 길드 48/50',
    '09:15:47 INFO | /imagine 처리 완료 (Stable Diffusion · 6.2s)',
    '09:16:40 ERROR | Stable Diffusion 응답 지연(타임아웃) — 재시도 1/2',
    '09:16:52 INFO | Stable Diffusion 재시도 성공',
    '09:18:10 INFO | /ask 처리 완료 (qwen2.5-coder:7b · 2.1s · 한국어 개발 길드)',
  ],
  // 앱 설정(config_file.py 항목 일부). ⚠ 통합 설정 GET 은 백엔드에 없음 — status + config 조합 필요(Gap).
  settings: {
    autostart: false, background: false, autoConnect: true, autoUpdate: true,
    ollamaUrl: 'http://localhost:11434',
  },
  // 업데이트 정보 — webui.py /api/update-info
  updateInfo: { current: '0.31.0', latest: '0.31.0', outdated: false, supported: true },
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
  // 서버 관리(관리자 전용) — 앱 내 관리. Provider 채널은 ✅ 구현(/provider/admin/*).
  //   채널·역할/채널AI/RAG/프리셋/다중응답 탭은 프로토타입 mock(흐름 검증). 실연동은 Gap-M 채널 단계 확장.
  // guildId → { policy, pending, roster, channels, channelAi, rag, presets, multi }.
  manage: {
    1001: {
      policy: { autoApprove: false, defaultDailyLimit: 50, scope: 'ALL' },
      pending: [{ providerUserId: 5001, name: 'user_lee', models: 2, since: '5분 전' }],
      roster: [
        { providerUserId: 0, name: '나 (이 PC)', isMe: true, state: ProviderState.ONLINE_IDLE, models: 3, today: 0, avgMs: 0 },
        { providerUserId: 5002, name: 'user_kim', isMe: false, state: ProviderState.ONLINE_IDLE, models: 1, today: 0, avgMs: 0 },
        { providerUserId: 5003, name: 'user_park', isMe: false, state: ProviderState.PAUSED, models: 2, today: 0, avgMs: 0 },
      ],
      // 전역 프롬프트셋(서버 전체 기본 시스템 프롬프트). 봇 추가 시 기본은 '니아' 페르소나.
      // ⚠ builtin(NEXA 기본 페르소나)·가드레일 전문은 영업·안전상 비공개 — 클라이언트엔 preview 만 내린다
      //   (content 미전송). F12 로도 전문 확인 불가. 사용자 작성 셋(builtin:false)만 content 전체 보유.
      prompts: [
        { id: 'nia', name: '니아 (기본 페르소나)', builtin: true, isDefault: true,
          preview: '당신은 「니아」, NEXA 네트워크의 안내자예요. 차분하고 다정하게, 사용자의 질문을 알맞은 AI에게 연결하고 모르면 솔직히 모른다고 말해요…' },
        { id: 'formal', name: '정중한 비서', builtin: false, isDefault: false,
          content: '당신은 정중하고 간결한 비서입니다. 존댓말로 핵심만 명료하게 전달합니다.' },
      ],
      // 08 채널 정책(v1: 채널별 AI 허용만. 역할별 사용 정책은 v1 범위 외)
      //   channelId 는 64bit Discord ID 라 문자열(실 백엔드 ManageChannelDto 와 동일 shape).
      channels: {
        defaultModel: 'llama3.1:8b', defaultLang: '한국어',
        list: [
          { channelId: '9001', name: 'general', aiAllowed: true }, { channelId: '9002', name: 'ai-chat', aiAllowed: true },
          { channelId: '9003', name: '코드리뷰', aiAllowed: true }, { channelId: '9004', name: '공지', aiAllowed: false },
        ],
      },
      // 09 채널 AI(채널별 성격)
      channelAi: [
        { channel: 'ai-chat', model: 'llama3.1:8b', tone: '친근', on: true },
        { channel: '코드리뷰', model: 'qwen2.5-coder:7b', tone: '간결', on: true },
        { channel: 'general', model: null, tone: null, on: false },
      ],
      // 10 RAG(지식 문서)
      rag: {
        docs: [
          { name: '온보딩 가이드.pdf', status: 'indexed', chunks: 12, when: '2일 전' },
          { name: 'FAQ.md', status: 'indexed', chunks: 8, when: '오늘' },
          { name: 'API문서.txt', status: 'indexing', chunks: 0, when: '-' },
        ],
        applyChannels: ['ai-chat', '코드리뷰'],
      },
      // 안전 — 콘텐츠 신고 큐(서버 관리자 대응). 책임=서버 관리자, 불법 red-line=NEXA 무관용.
      safety: {
        reports: [
          { id: 'r1', target: '#ai-chat 답변', reason: '부적절한 표현', reporter: 'user_choi', when: '10분 전', status: 'open' },
          { id: 'r2', target: '전역 프롬프트 「정중한 비서」', reason: '스팸/광고 유도', reporter: 'user_han', when: '1시간 전', status: 'open' },
        ],
      },
      // 11 프리셋(설정 묶음)
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
    // 정규화: 실 /api/servers 는 {servers:[{index,guildId,guildName,connected}]} 만 준다(Gap-S).
    // UI 가 읽는 state/role 과 확장 통계는 백엔드 미보강이라 안전 기본값으로 최소 표시만.
    const real = (await http(ENDPOINTS.servers)).servers || [];
    return real.map((s) => ({
      guildId: s.guildId,
      guildName: s.guildName,
      iconUrl: null,
      connected: !!s.connected,
      state: s.connected ? ProviderState.ONLINE_IDLE : ProviderState.OFFLINE,
      role: Role.PROVIDER,
      models: 0, today: 0, members: 0, avgMs: 0,
      myModels: [], policy: null, webUrl: null,
    }));
  },
  /** 서버 상세(기부자 관점) — Gap-S/P/W. 실 백엔드엔 전용 상세 API 가 없어 목록+권한 probe 로 구성. */
  async getServerDetail(guildId) {
    if (USE_MOCK) { await delay(60); const s = MOCK.servers.find((x) => x.guildId === guildId); return s ? structuredClone(s) : null; }
    // 실: /api/servers/{g} 전용 라우트가 없다(Gap-S). 목록 항목으로 기본을 채우고, 관리 권한은
    //   /manage 응답 ok 로 판정한다(contract.js: "앱은 serverManage 응답 ok 로 관리자 여부를 판정").
    //   기여 통계(models/today/avgMs/myModels)는 앱 경로 미보강이라 안전 기본값으로 최소 표시.
    const list = (await http(ENDPOINTS.servers)).servers || [];
    const s = list.find((x) => Number(x.guildId) === Number(guildId));
    if (!s) return null;
    let isAdmin = false;
    try { const mg = await http(ENDPOINTS.serverManage(guildId)); isAdmin = !!(mg && mg.ok); } catch { isAdmin = false; }
    return {
      guildId: Number(s.guildId), guildName: s.guildName, iconUrl: null,
      connected: !!s.connected,
      state: s.connected ? ProviderState.ONLINE_IDLE : ProviderState.OFFLINE,
      role: isAdmin ? Role.ADMIN : Role.PROVIDER,
      models: 0, today: 0, members: 0, avgMs: 0,
      myModels: [],
      policy: { dailyLimit: 0, maxConcurrency: 1, maxSeconds: 600, scope: 'ALL' },
      webUrl: null,
    };
  },
  /** 서버 관리(관리자) — 승인 대기·로스터·정책. ⚠ Gap-M(앱↔central 관리 채널). 비관리자는 ok=false. */
  async getServerManage(guildId) {
    if (USE_MOCK) { await delay(60); const m = MOCK.manage[guildId]; return m ? { ok: true, ...structuredClone(m) } : { ok: true, policy: { autoApprove: false, defaultDailyLimit: 50, scope: 'ALL' }, pending: [], roster: [] }; }
    return http(ENDPOINTS.serverManage(guildId));
  },
  /** Provider 승인(관리자 → /provider-approve). 승인 시 로스터로 이동. */
  async approveProvider(guildId, providerUserId) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) {
      await delay(80);
      if (m) { const i = m.pending.findIndex((p) => p.providerUserId === providerUserId); if (i >= 0) { const p = m.pending.splice(i, 1)[0]; m.roster.push({ providerUserId: p.providerUserId, name: p.name, isMe: false, state: ProviderState.ONLINE_IDLE, models: p.models, today: 0, avgMs: 0 }); } }
      return { ok: true };
    }
    return post(ENDPOINTS.providerApprove(guildId), { providerUserId });
  },
  /** Provider 거절(관리자 → /provider-reject). 승인 대기에서 제거. */
  async rejectProvider(guildId, providerUserId) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(80); if (m) { const i = m.pending.findIndex((p) => p.providerUserId === providerUserId); if (i >= 0) m.pending.splice(i, 1); } return { ok: true }; }
    return post(ENDPOINTS.providerReject(guildId), { providerUserId });
  },
  /** Provider 제거(관리자 → /provider-remove). 로스터에서 제거(나 제외). */
  async removeProvider(guildId, providerUserId) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(80); if (m) { const i = m.roster.findIndex((p) => p.providerUserId === providerUserId && !p.isMe); if (i >= 0) m.roster.splice(i, 1); } return { ok: true }; }
    return post(ENDPOINTS.providerRemove(guildId), { providerUserId });
  },
  /** 서버 제공 정책(관리자) — 신규 자동 승인·기본 한도·공개 대상. */
  async setManagePolicy(guildId, policy) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(80); if (m) m.policy = { ...m.policy, ...policy }; return { ok: true, policy: m && m.policy }; }
    return post(ENDPOINTS.serverManagePolicy(guildId), policy);
  },
  // ── 전역 프롬프트셋(서버 전체 기본 AI 성격) — 관리자. 응답 {ok, sets:[{id,name,builtin,isDefault,preview,content}]}.
  //   builtin(니아)은 preview 만(전문 비공개). webui → central /provider/admin/prompt-sets{,/add,/default,/delete}.
  /** 전역 프롬프트셋 목록 조회. */
  async getPromptSets(guildId) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(60); return { ok: true, sets: m ? structuredClone(m.prompts) : [] }; }
    return http(ENDPOINTS.serverPrompts(guildId));
  },
  /** 전역 프롬프트셋 추가(사용자 작성). 추가만으로 기본이 되지는 않는다. */
  async addPromptSet(guildId, name, content) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(80); if (m) m.prompts.push({ id: 'p' + Date.now(), name, builtin: false, isDefault: false, content }); return { ok: true, sets: m ? structuredClone(m.prompts) : [] }; }
    return post(ENDPOINTS.serverPromptAdd(guildId), { name, content });
  },
  /** 기본 셋 지정. id='nia' 면 NEXA 기본 정체성(니아)으로 되돌린다. */
  async setDefaultPromptSet(guildId, id) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(80); if (m) m.prompts.forEach((p) => { p.isDefault = (p.id === id); }); return { ok: true, sets: m ? structuredClone(m.prompts) : [] }; }
    return post(ENDPOINTS.serverPromptDefault(guildId), { id });
  },
  /** 전역 프롬프트셋 삭제. 기본이던 셋을 지우면 니아로 되돌아간다. builtin(니아)은 삭제 불가. */
  async deletePromptSet(guildId, id) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) {
      await delay(80);
      if (m) {
        const wasDefault = m.prompts.find((p) => p.id === id)?.isDefault;
        m.prompts = m.prompts.filter((p) => p.id !== id);
        if (wasDefault && !m.prompts.some((p) => p.isDefault)) { const nia = m.prompts.find((p) => p.builtin); if (nia) nia.isDefault = true; }
      }
      return { ok: true, sets: m ? structuredClone(m.prompts) : [] };
    }
    return post(ENDPOINTS.serverPromptDelete(guildId), { id });
  },
  // ── 채널 AI 허용(관리 화면 08) — 관리자. 빈 허용 목록 = 전체 채널 허용. channelId 는 문자열(64bit).
  //   webui → central /provider/admin/channels{,/toggle}. PolicyService(GuildChannelPolicy) 재사용.
  /** 채널 AI 허용 목록 조회. 실 응답 {ok, channels:[{channelId,name,aiAllowed}]}. */
  async getChannels(guildId) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) { await delay(60); return { ok: true, channels: m && m.channels ? structuredClone(m.channels.list) : [] }; }
    return http(ENDPOINTS.serverChannels(guildId));
  },
  /** 채널 AI 허용/금지 토글. allow=원하는 새 상태. 응답에 갱신된 채널 목록 포함. */
  async toggleChannel(guildId, channelId, allow) {
    const m = MOCK.manage[guildId];
    if (USE_MOCK) {
      await delay(80);
      if (m && m.channels) { const ch = m.channels.list.find((c) => String(c.channelId) === String(channelId)); if (ch) ch.aiAllowed = allow; }
      return { ok: true, channels: m && m.channels ? structuredClone(m.channels.list) : [] };
    }
    return post(ENDPOINTS.serverChannelToggle(guildId), { channelId: String(channelId), allow });
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
    if (USE_MOCK) { await delay(60); return structuredClone(MOCK.status); }
    return http(ENDPOINTS.status);
  },
  /** 최근 로그 라인 — webui.py /api/logs. @returns {Promise<import('./contract.js').AgentLogs>} */
  async getLogs() {
    if (USE_MOCK) { await delay(60); return { lines: [...MOCK.logs] }; }
    return http(ENDPOINTS.logs);
  },
  /** 에이전트 실행 시작 — webui.py /api/start (내부적으로 setup→연결). */
  async startAgent() {
    if (USE_MOCK) { await delay(220); MOCK.status.running = true; MOCK.status.connected = true; return { ok: true }; }
    return post(ENDPOINTS.start);
  },
  /** 에이전트 중지 — webui.py /api/stop. */
  async stopAgent() {
    if (USE_MOCK) { await delay(200); MOCK.status.running = false; MOCK.status.connected = false; return { ok: true }; }
    return post(ENDPOINTS.stop);
  },
  /** 이미지 요청 수신 토글(enableImage). ⚠ 백엔드 단일 토글 API 없음 — /api/setup 재호출로 반영(contract 참고). */
  async setImageReceiving(on) {
    if (USE_MOCK) { await delay(80); MOCK.status.enableImage = on; return { ok: true, enableImage: on }; }
    return post(ENDPOINTS.setup, { enableImage: on });
  },
  /** 통합 설정 조회 — webui.py GET /api/settings(저장 설정+상태를 camelCase 로 통합). */
  async getSettings() {
    if (USE_MOCK) {
      await delay(60);
      return { ...MOCK.settings, enableImage: MOCK.status.enableImage, relayUrl: MOCK.status.relayUrl, hasToken: MOCK.status.hasToken };
    }
    return http(ENDPOINTS.settings);
  },
  /** 설정 변경 — webui.py POST /api/settings {key:value}. 단일 엔드포인트로 통합(반환 {ok, needsRestart}). */
  async setSetting(key, value) {
    if (USE_MOCK) { await delay(80); if (key === 'enableImage') MOCK.status.enableImage = value; else MOCK.settings[key] = value; return { ok: true }; }
    return post(ENDPOINTS.settings, { [key]: value });
  },
  /** 업데이트 정보 — webui.py /api/update-info. */
  async getUpdateInfo() {
    if (USE_MOCK) { await delay(60); return { ...MOCK.updateInfo }; }
    return http(ENDPOINTS.updateInfo);
  },
  /** 연결 해제(로그아웃) — webui.py /api/logout. 토큰·서버 연결을 비우고 온보딩으로. */
  async logout() {
    if (USE_MOCK) { await delay(120); MOCK.status.hasToken = false; MOCK.status.connected = false; return { ok: true }; }
    return post(ENDPOINTS.logout);
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

  /**
   * 설치 진행률 폴링 — webui.py *-setup-progress. 응답: { phase, percent, message, error }.
   * mock 은 시간 경과로 % 를 단조 증가시켜 다운로드를 시뮬한다. 실제 백엔드(sd_setup._download)는
   * 받은 바이트 비율을 다운로드 구간(35~95%)으로 매핑해 같은 shape 로 보고하며, 끊기면 .part 로
   * 이어받기(HTTP Range)한다 — 끊김/이어받기 시뮬은 백엔드 책임이라 mock 에서는 재현하지 않는다.
   */
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
    if (USE_MOCK) { await delay(60); return structuredClone(MOCK.catalog); }
    // 정규화: 실 {models:[{id,name,desc,size,installed,selected}]} → UI {name,size,desc,cat}.
    //  name 은 설치(ollama pull) 대상 id 로 둔다(UI 가 data-cat=name 값을 그대로 pull 에 넘김).
    //  cat 은 실 응답에 없어 단일 분류('추천')로(UI 가 cat 으로 드롭다운 그룹을 만들기 때문).
    const real = (await http(ENDPOINTS.ollamaCatalog)).models || [];
    return real.map((m) => ({ name: m.id || m.name, size: m.size || '', desc: m.desc || '', cat: '추천' }));
  },

  /** Discord 연결 후보 목록 — central ProviderConnectOnboardingService (OAuth 콜백이 제공) */
  async getConnectCandidates() {
    if (USE_MOCK) { await delay(140); return structuredClone(MOCK.candidates); }
    // 실 앱은 후보 API 가 없다(설계상 위임): connect-open 이 시스템 브라우저로 relay OAuth 를 열고
    // 후보 선택·승인은 브라우저에서 끝난다. relay 가 /connect/callback 으로 토큰·guild 를 리디렉트하면
    // connect_callback 이 토큰 저장 + 자동 연결을 처리한다. 따라서 앱 내 후보 조회는 항상 빈 배열.
    return [];
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
    // 실 앱: POST /api/server-add-token {token} → {ok} (백엔드가 토큰 검증·저장·자동 연결까지 처리).
    // ok 면 승인된 서버로 발급된 토큰이므로 APPROVED, 아니면 PENDING 으로 정규화(UI shape 통일).
    const r = await post(ENDPOINTS.serverAddToken, { token });
    return {
      ok: !!r.ok,
      state: r.ok ? ProviderState.APPROVED : ProviderState.PENDING,
      guildId: r.guildId ?? null,
      guildName: r.guildName || '',
    };
  },
};
