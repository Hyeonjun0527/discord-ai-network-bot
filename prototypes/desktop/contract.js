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
  logs: '/api/logs',          // GET → { lines: [string] }  라인 형식 "HH:MM:SS LEVEL | message"
  models: '/api/models',
  servers: '/api/servers',
  // ⚠ 서버 상세(기부자 관점) — Gap-S/P/W. 아래 3개는 백엔드 신설 필요.
  serverDetail: (g) => '/api/servers/' + g,         // GET — 서버별 내 기여 통계·myModels·policy(Gap-S/P)
  serverPause: (g) => '/api/servers/' + g + '/pause',   // POST {paused} — provider self-service(/provider-pause·resume)
  serverPolicy: (g) => '/api/servers/' + g + '/policy', // GET 저장값 readback · POST {dailyLimit,maxConcurrency,maxSeconds} 저장 (/provider-limit)
  // 서버 관리(관리자) — 앱 내 직접 관리. ✅ 채널 구현됨(2026-06-07): webui → central /provider/admin/*.
  //   central 이 durable 토큰 신원 + JDA 관리자 판정(MANAGE_SERVER|ADMINISTRATOR) 후 ProviderRegistrationService 실행.
  //   role 전달은 별도 불필요 — 앱은 serverManage 응답 ok 로 "내가 관리자인지" 판정(비관리자는 ok=false).
  serverManage: (g) => '/api/servers/' + g + '/manage',            // GET — 승인 대기(pending)·로스터(roster)
  providerApprove: (g) => '/api/servers/' + g + '/providers/approve', // POST {providerUserId} — /provider-approve
  providerReject: (g) => '/api/servers/' + g + '/providers/reject',  // POST {providerUserId} — /provider-reject
  providerRemove: (g) => '/api/servers/' + g + '/providers/remove',  // POST {providerUserId} — /provider-remove
  // ✅ 서버 정책 토글(신규 자동 승인) — central /provider/admin/manage/policy. PolicyService.setAutoApprove 재사용.
  serverManagePolicy: (g) => '/api/servers/' + g + '/manage/policy', // POST {autoApprove}
  // ✅ 전역 프롬프트셋(길드 기본 AI 성격) — central + 데스크톱 앱 연동 구현됨(2026-06-07). 매 질문마다
  //   고르는 게 아니라 "서버 전체 기본 성격"을 한 번 세팅하는 구조. 기본 지정된 셋이 없으면 NEXA 기본 정체성(니아).
  //   · 데스크톱 앱 경로(provider-agent webui): GET serverPrompts·POST serverPromptAdd·serverPromptDefault·serverPromptDelete.
  //   · webui 는 durable 토큰으로 central /provider/admin/prompt-sets{,/add,/default,/delete} 로 프록시(Gap-M, 관리자 판정은 central).
  //   · 웹 대시보드 직접 경로(OAuth/admin-token): /api/ai-network/guild-prompt-set/{guildId}.
  //   니아 외형(이름·아바타)은 v1 고정 — 디스코드 봇은 서버별 프로필 API 가 없다(닉네임만). 글로벌 프로필은 NEXA 운영자 소유.
  // ⚠ builtin(NEXA 기본 페르소나)·가드레일 전문은 응답에 포함하지 않는다(영업·안전 비공개). preview 만 내려보내
  //   F12 로도 전문 확인 불가. 사용자 작성 셋만 content 전체 반환. central GlobalPromptSetService 가 동일 정책 강제.
  serverPrompts: (g) => '/api/servers/' + g + '/prompts',              // GET 목록(builtin 은 preview 만)
  serverPromptAdd: (g) => '/api/servers/' + g + '/prompts/add',        // POST {name, content}
  serverPromptDefault: (g) => '/api/servers/' + g + '/prompts/default', // POST {id} (id='nia'→니아 복귀)
  serverPromptDelete: (g) => '/api/servers/' + g + '/prompts/delete',  // POST {id}
  // ✅ 채널 AI 허용(관리 화면 08) — central /provider/admin/channels{,/toggle}. PolicyService(GuildChannelPolicy) 재사용.
  //   빈 허용 목록 = 전체 채널 허용(제한 없음) 의미를 central 이 보존한다. channelId 는 64bit → 문자열로 다룬다.
  serverChannels: (g) => '/api/servers/' + g + '/channels',          // GET → {ok, channels:[{channelId,name,aiAllowed}]}
  serverChannelToggle: (g) => '/api/servers/' + g + '/channels/toggle', // POST {channelId, allow}
  // ✅ 읽기 전용 관리 탭(09 채널AI·10 RAG·11 프리셋) — central 도메인 서비스(채널AI/knowledge/preset) read 브리지.
  //   추가·편집은 Discord 명령·웹 대시보드 경유(앱은 안내). 64bit id 는 문자열.
  serverChannelAi: (g) => '/api/servers/' + g + '/channel-ai', // GET → {ok, items:[{channelId,name,tone,purpose}]}
  serverKnowledge: (g) => '/api/servers/' + g + '/knowledge',  // GET → {ok, docs:[{id,title,status,riskLevel,addedAt,indexedAt}]}
  serverPresets: (g) => '/api/servers/' + g + '/presets',      // GET → {ok, presets:[{id,name,category,status,summary}]}
  serverAddToken: '/api/server-add-token',  // POST {token} → {ok} (실 앱: 토큰으로 서버 추가 + 자동 연결)
  onboardApply: '/api/onboard-apply',
  // 통합 설정 — GET → 저장 설정+상태(camelCase, {autostart,background,autoConnect,autoUpdate,enableImage,ollamaUrl,relayUrl,allowRemoteOllama,hasToken}).
  //   POST {key:value}(부분 변경, 1개 이상) → {ok, needsRestart}. setup/onboard-apply/auto-update 를 단일화.
  settings: '/api/settings',
  connectOpen: '/api/connect-open',
  sdStatus: '/api/sd/status',
  sdModels: '/api/sd/models',
  sdSetup: '/api/sd/setup',
  sdStart: '/api/sd/start',          // POST — 이미 설치된 SD 를 기동만(clone/다운로드 없음)
  sdSetupProgress: '/api/sd/setup-progress',
  ollamaSetup: '/api/ollama/setup',
  ollamaSetupProgress: '/api/ollama/setup-progress',
  ollamaCatalog: '/api/ollama/catalog',
  setup: '/api/setup',
  start: '/api/start',
  stop: '/api/stop',
  logout: '/api/logout',
  // 설정/업데이트 — webui.py.  통합 설정은 /api/settings(GET/POST) 로 단일화됨(위 settings 참조).
  //   autoUpdate 는 통합 POST /api/settings 로도 바꿀 수 있으나, 단독 토글 호환을 위해 아래 엔드포인트도 유지.
  autoUpdate: '/api/auto-update',     // POST {autoUpdate} → {ok, autoUpdate}
  updateInfo: '/api/update-info',     // GET → {current, latest, outdated, supported, autoUpdate, error?}
  updateProgress: '/api/update-progress',
  updateApply: '/api/update',         // POST → 업데이트 시작(진행률은 updateProgress 폴링)
  installInfo: '/api/install-info',   // GET → {platform, label, supported, installed, reason?}
  openFolder: '/api/open-folder',     // POST {which} → OS 파일 탐색기로 로컬 폴더 열기
});

// ── 응답 shape(참고용 JSDoc) — webui.py 와 동일 필드명(camelCase) ──
/**
 * @typedef {Object} AgentStatus  webui.py /api/status 의 실제 필드(전부).
 * @property {boolean} running            에이전트 실행 중
 * @property {boolean} connected          중앙 서버(릴레이) 연결됨
 * @property {number}  processed          처리한 요청 수(세션 누적)
 * @property {boolean} imageReady         이미지(SD) 제공 가능
 * @property {boolean} enableImage        이미지 요청 수신 토글
 * @property {boolean} sdInstalled        Stable Diffusion 설치됨
 * @property {string[]} models            현재 제공(advertise) 중인 모델명(실행 중일 때)
 * @property {boolean} hasToken           연결 토큰 보유(온보딩 완료)
 * @property {string}  relayUrl           중앙 서버 URL
 * @property {boolean} backgroundRunning  백그라운드(트레이) 상주 중
 * @property {boolean} connectEnabled     Discord 연결 가능 상태
 */
/** @typedef {{lines:string[]}} AgentLogs  webui.py /api/logs — 최근 로그 라인(최대 200). "HH:MM:SS LEVEL | message". */
/** @typedef {{phase:string, percent:number, message:string, error?:string}} SetupProgress  런타임 설치 진행률(ollama/sd). */
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
 * @typedef {Object} ProviderPolicy  이 서버에 대한 "내" 정책(/provider-limit 의 GUI)
 * @property {number} dailyLimit     하루 처리 한도(건)
 * @property {number} maxConcurrency 동시 처리 수
 * @property {number} maxSeconds     요청 최대 처리 시간(초)
 * 공개 대상(scope)은 제거됨 — '서버 멤버 누구나'(ALL)는 길드별 라우팅 격리로 이미 보장되고
 * 세분화는 강제되지 않아 노출하지 않는다(가짜 컨트롤 금지).
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
