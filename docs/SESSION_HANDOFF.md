# 작업 핸드오프 — 2026-06-07

다음 세션(Codex/Claude)이 이어받기 위한 인수인계. 세션 시작 전 루트 `AGENTS.md` 먼저 읽을 것.

## 현재 상태
- 브랜치: `fix/per-guild-daily-limit` (main + 약 37커밋, **미머지**).
- 작업 트리: 이 문서 커밋 시점에 깨끗.
- 오늘 작업의 핵심: **데스크톱 앱을 "프로토타입 이식" 방식으로 재구현**(아래 SSOT 원칙) + 니아/전역 프롬프트셋/가드레일 백엔드 + codegraph 도입.

## SSOT 원칙 (반드시 준수 — AGENTS.md 에도 명문화됨)
- **데스크톱 앱 UI = `prototypes/desktop` 단일 SSOT.** 직접 webui_assets/인라인 HTML 고치지 말고 `prototypes/desktop` 에서만 수정 → `make sync-desktop`(= `scripts/sync_desktop_app.py`)이 `provider-agent/src/provider_agent/webui_assets/` 로 복제(USE_MOCK=false 치환·세션키 주입). webui_assets 는 생성물(.gitignore) — 빌드/실행 전 sync 필수.
- **백엔드 로직**: 실구현 Python(provider-agent) ↔ 프로토타입 JS mock(adapter.js)은 코드 공유 불가 → `prototypes/desktop/contract.js`(계약)로 묶고 mock 이 실 동작을 모사.
- **새 기능/화면은 프로토타입 + 실구현 + mock 동시 개발**(한쪽만 추가 금지 — 드리프트).

## 오늘 완료(커밋됨)
1. 니아 기본 페르소나(`NexaIdentity`)·NEXA 가드레일을 `/ask` 전 경로 주입·전문 비공개(preview만). 길드 전역 프롬프트셋 도메인(`globalpromptset`, 서버 기본 성격 1회 세팅·없으면 니아)·웹+데스크톱(Gap-M) 관리.
2. 프로토타입 신규 화면: 로그·로컬 실행·설정. 통합 설정 API `/api/settings`.
3. **데스크톱 앱 = 프로토타입 이식 (Phase 2 전체)**: sync 인프라·webui 정적 서빙·세션 인증·부팅 자동 분기(hasToken)·adapter 실응답 정규화·connect OAuth 실 흐름(브라우저 위임+waiting 폴링)·패키징 CI sync·인라인 `_PAGE_TEMPLATE` 제거.
4. 실기동 버그 수정: 온보딩 토글 백엔드 전달·설치 % 표시·connect waiting 갇힘·새로고침 시 설치 토스트 복원·홈 미연결 명확화·온보딩 1단계 "나중에" 제거·FOUC(메인 깜빡임) 제거.
5. SD 다운로드 **이어받기(Range)** + 실시간 진행률(35~95% 매핑). ollama 는 데몬 자체 resume.
6. codegraph 도입(로컬 코드 지식그래프 + MCP). `~/.claude.json` 등록(npx 실행). **Claude Code 재시작 후** 영향분석/탐색 활용. 인덱스 갱신은 `npx @colbymchenry/codegraph sync`.

## 남은 후속 작업 (우선순위)

### A. 데스크톱 앱 실기동 검증 (진행 중)
- `make sync-desktop` 후 webui 기동해 눈으로 확인. 온보딩은 검증됨. **남은 화면: connect(토큰 추가)·메인·로그·로컬 실행·설정** 실제 동작 확인.
- 실 OAuth(connect 로그인)는 이 환경에서 relay OAuth 미설정(`connectEnabled=false`)이라 "토큰으로 추가" 안내까지만. 실제 Discord 연결은 relay OAuth 설정 필요.
- 기동(임시 빈 설정 = 온보딩 상태, 실제 ~/.config/nexa 보존):
  ```
  make sync-desktop
  cd provider-agent && XDG_CONFIG_HOME=/tmp/nexa-onboard-test ../.venv/bin/python -c "import time; from provider_agent import webui; app=webui.build_app('devkey'); print(webui._start_server_thread(app,'127.0.0.1',8799)); time.sleep(36000)"
  ```
  → 브라우저로 http://127.0.0.1:8799/ (실제 설정으로 메인을 보려면 XDG_CONFIG_HOME 빼고 기동).

### B. 데스크톱 앱 완성도
- **서버 관리 탭 실 백엔드 연동** (Gap-M HTTP 채널 `/provider/admin/*`, durable-token+JDA 관리자 2단 게이트):
  - ✅ **전역 프롬프트셋**: adapter(getPromptSets/add/default/delete)+UI 실연동 완료(2026-06-07 이번 세션).
    이전엔 백엔드만 있고 프로토타입 UI 는 메모리 mock 변경이라 드리프트였음.
  - ✅ **Provider 승인/거절/제거/자동승인**: 기존 완료.
  - ✅ **채널 AI 허용(channels)**: central PolicyService(좁은 포트 `GuildChannelPolicy`)→ProviderAdminController
    `/channels`,`/channels/toggle` + agent.admin_channels/toggle + webui 라우트 + adapter(getChannels/toggleChannel)
    + UI 실연동 완료(이번 세션). "빈 목록=전체 허용" 의미 보존, channelId 문자열(64bit). 테스트 포함.
  - ⏳ **남은 탭(같은 패턴 확장)**: 채널AI(channelai 도메인 ChannelAiProfileService)·RAG(knowledge 도메인)·
    프리셋(preset 도메인)·안전(ainetwork ProviderSafetyService). central 백엔드는 **이미 존재**(웹 대시보드 OAuth 경로),
    durable-token 브리지만 추가하면 됨. 현재 실 모드에선 `MSOON_REAL` 안내(크래시 방지). 단, channelAi 는
    ChannelAiProfileService 가 behavior version 등으로 풍부해 프로토타입 단순모델과 매핑 설계 먼저 필요.
- 🔴 **(중요) guildId 64bit 정밀도 손실 — 실 길드 관리 흐름 전체에 영향**: 실 `/api/servers` 가 guildId 를
  JSON number(예: 1380395592336805928)로 보내면 JS `r.json()` 이 Number 로 파싱하며 끝자리가 깨진다(>2^53).
  → 그 guildId 로 만든 관리 API URL 이 잘못된 길드를 가리켜 prompts/provider/channels **모두 실 길드에서 실패**.
  채널의 channelId 는 이미 String 으로 보호했으나 guildId 는 기존 패턴(Number)이라 잠재 버그. **수정 레시피**:
  ① webui `servers`/`connections_status` 가 guildId 를 **문자열**로 emit
  ② adapter getServers/getServerDetail 가 guildId 를 문자열로 보존(Number() 금지), 비교는 `String(a)===String(b)`
  ③ mock 은 작은 숫자(1001 등)라 안전 — 그대로 두고 adapter/UI 가 양쪽(숫자/문자열) 모두 통과하게.
  central 배포가 아니라 **데스크톱 앱 릴리스** 쪽 버그라 central main 머지와 무관하게 다음 앱 릴리스에서 처리.
- SD 부트스트랩 안정화(이어받기는 됨, 메모리 `sd_bootstrap_upstream_rot` 참조 — A1111 업스트림 부패 잔존).

### C. 배포/머지
- 이 브랜치(39커밋) → main PR 머지. ⚠️ `central-server/**` push 는 **자동배포** 트리거. AGENTS.md Git/Release 가드 준수.
  - 이번 세션 변경에 central-server(PolicyService·ProviderAdminController) 포함 → main 머지 시 자동배포된다.
    채널 브리지는 기존 PolicyService 재사용·신규 엔드포인트 add-only 라 기존 동작 영향 없음(빌드 그린 확인).

### D. 더 멀리 (메모리 기록)
- 코드 서명/공증(시크릿 부재 보류), B6 CSAM 해시 스캔(변호사 검토), i18n 문구 이관.

## 검증 명령 (커밋/머지 전 필수)
- central-server: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 후 `central-server/gradlew -p central-server build` (test+ktlint+Kover+ArchUnit).
- provider-agent: `cd provider-agent && ../.venv/bin/ruff check src tests && ../.venv/bin/mypy src && ../.venv/bin/python -m pytest -q`.
- 프로토타입 E2E: `cd prototypes/desktop && npx playwright test` (현재 44 통과).
- 패키징 드리프트: `python scripts/check_packaging.py`.

## 함정/주의 (이번 세션에서 겪음)
- KDoc 주석 안의 `/**` 시퀀스(예: `/api/x/**`)는 Kotlin 중첩 블록주석으로 파싱돼 ktlint "Unclosed comment". 경로 주석에 `/**` 금지.
- @SpringBootTest 가 commands.ask 부수효과를 공유 H2 에 커밋 → @DataJpaTest 격리 깨짐. 통합테스트는 전용 길드로.
- @DataJpaTest 승격 시 클래스 선언 멀티라인 `@Autowired constructor(` 스타일이라야 ktlint 들여쓰기 맞음.
- enable_image 등 일부 설정은 실행 중 라이브 전파 경로가 없어 `/api/settings` 가 `needsRestart:true` 로 정직하게 응답(거짓 즉시반영 금지).

## 관련 메모리(자동 로드)
`global_prompt_set_and_nia` · `desktop_app_prototype_port` · `sd_bootstrap_upstream_rot` · `nexa_desktop_4problems_rootcause`.
