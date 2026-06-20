# 작업 핸드오프 — 2026-06-20

## 현재 세션 WIP: NEXA runtime plan compatibility + L3 server management + L4 safety reports bridge

다음 Codex 세션은 먼저 루트 `AGENTS.md` 와 `.omx/notepad.md` 를 읽을 것.

### 요청/상태
- 사용자 목표: `git pull` 후 `docs/nexa` 폴더의 자료와 "NEXA 500 step master plan"을 읽고 전부 수행.
- 현재 증거: `git pull --ff-only` 는 두 번 시도됨. sandbox 에서는 `.git/FETCH_HEAD` read-only 로 실패했고, 승인 후에는 GitHub HTTPS 인증 부재로 `fatal: could not read Username for 'https://github.com': No such device or address` 실패. `gh auth status` 도 미로그인.
- 따라서 이 세션에서는 새로 생길 `docs/nexa` 자료를 아직 받지 못함. 로컬에는 `docs/nexa` 디렉터리가 없고, 사용 가능한 NEXA 문서는 `docs/NEXA_*.md`, `docs/plans/NEXA_DESKTOP_RUNTIME_HEALTH_AND_REOPEN_PLAN.md`, `docs/plans/NEXA_DESKTOP_FIXES_FINAL_VERIFICATION.md`.

### 이번 변경
- `provider-agent/src/provider_agent/webui.py`
  - 문서화된 `GET /api/runtime-health` 추가.
  - runtime health probe 예외가 endpoint 전체 500 으로 번지지 않도록 Ollama/ComfyUI 조회 실패를 fallback 상태로 반환.
  - 현재 ComfyUI 이미지 엔진 상태를 generic SD 계약으로 보여주는 `GET /api/sd/status`, `GET /api/sd/models/installed` 추가.
  - 계획서 명칭 alias 추가: `POST /api/sd/model-install` → 기존 ComfyUI model download 경로 재사용.
  - 계획서 명칭 alias 추가: `POST /api/ollama/model-install`, `GET /api/ollama/model-install-progress`, `POST /api/image-provider`.
  - 모델 선택 전용 `POST /api/models/select` 추가. `/api/setup` 처럼 토큰/이미지 설정을 건드리지 않고 텍스트 모델 선택만 저장·라이브 재광고.
- `provider-agent/tests/test_webui.py`
  - 위 endpoint 계약 회귀 테스트 추가.
  - runtime health probe 실패 시 200 fallback 응답 회귀 테스트 추가.
  - L3 서버 관리 회귀 테스트 보강: 64bit Discord guildId 를 문자열로 받은 상태에서 서버 이름 변경과 제거가
    정확한 connection 을 대상으로 동작하는지 검증.
- `prototypes/desktop/contract.js`
  - 계획서 endpoint 명칭을 SSOT 계약에 추가: `runtimeHealth`, `sdStatus`, `sdModelsInstalled`, `sdModelInstall`,
    `ollamaModelInstall`, `ollamaModelInstallProgress`, `modelsSelect`, `imageProvider`.
- `prototypes/desktop/adapter.js`
  - 실 앱 호출을 계획서 alias 로 연결: 이미지 토글은 `/api/image-provider`, 텍스트 모델 선택은 `/api/models/select`,
    Ollama 설치/진행률은 `/api/ollama/model-install*`, ComfyUI 모델 설치는 `/api/sd/model-install`.
  - 프로토타입 mock 에 `getRuntimeHealth()` 응답을 추가해 실 UI 와 mock UI 가 같은 health shape 를 쓴다.
- `prototypes/desktop/stage-manager.js`, `prototypes/desktop/screen-local.js`
  - 홈/로컬 실행 화면이 `runtime-health` 를 우선 사용해 설치됨/ready/광고됨/선택 모델을 분리해서 표시한다.
  - `runtime-health` 가 실패하면 기존 `/api/status`, `/api/models`, `/api/comfy/*` 기반 동작으로 fallback 한다.
- `prototypes/desktop/tests/flows.spec.js`
  - L3 서버 상세 회귀 테스트 추가: 서버 이름 변경 후 상세 제목 갱신, "이 서버 제공 그만두기" 확인 후 목록에서
    해당 서버가 제거되는지 검증.
- `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiQualityFeedbackService.kt`
  - 기존 AI 품질 피드백 리뷰 기능을 Provider Admin HTTP 경로에서 재사용할 수 있도록 `GuildQualityReports`
    좁은 포트를 추가.
- `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt`
  - durable token + Discord 관리자 게이트를 그대로 쓰는 안전 신고 큐 브리지 추가:
    `POST /provider/admin/quality/reports`, `POST /provider/admin/quality/reports/review`.
  - 데스크톱에서 64bit ID 정밀도 손실이 나지 않도록 report/channel/request ID 를 문자열로 반환.
- `central-server/src/test/kotlin/com/discordassistant/central/provider/ProviderAdminControllerTest.kt`
  - 안전 신고 큐 조회/처리와 비관리자 거부 회귀 테스트 추가.
- `provider-agent/src/provider_agent/agent.py`, `provider-agent/src/provider_agent/webui.py`
  - central Provider Admin endpoint 를 데스크톱 로컬 API 로 프록시:
    `GET /api/servers/{guildId}/safety/reports`,
    `POST /api/servers/{guildId}/safety/reports/review`.
- `prototypes/desktop/contract.js`, `prototypes/desktop/adapter.js`, `prototypes/desktop/screen-servers.js`
  - 안전 탭의 mock-only 신고 큐를 제거하고 실 API shape 로 조회/처리하도록 연결.
  - 실 앱은 API 응답을 기다린 뒤 큐를 갱신하고, 프로토타입 mock 도 같은 계약을 모사.

### 검증 완료
```bash
cd /root/workspaces/discord-assitant/prototypes/desktop
npm install
npx playwright test
# 61 passed

npx playwright test tests/flows.spec.js --grep '안전 탭'
# 1 passed

cd provider-agent
PYTHONPATH=/tmp/nexa-pydeps:src python3 -m pytest tests/test_webui.py -q
# 90 passed, 3 warnings

PYTHONPATH=/tmp/nexa-pydeps python3 -m ruff check src tests
# All checks passed

PYTHONPATH=/tmp/nexa-pydeps python3 -m mypy src
# Success: no issues found in 34 source files

PYTHONPATH=/tmp/nexa-pydeps:src python3 -m pytest -q --cov=provider_agent --cov-fail-under=70
# 380 passed, 3 warnings, total coverage 72.57%

cd /root/workspaces/discord-assitant
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 central-server/gradlew -p central-server test --tests 'com.discordassistant.central.provider.ProviderAdminControllerTest'
# BUILD SUCCESSFUL

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 central-server/gradlew -p central-server build
# BUILD SUCCESSFUL in 3m 59s

cd /root/workspaces/discord-assitant
node --check prototypes/desktop/adapter.js
node --check prototypes/desktop/contract.js
node --check prototypes/desktop/stage-manager.js
node --check prototypes/desktop/screen-local.js
node --check prototypes/desktop/screen-servers.js
# no output

python3 scripts/check_packaging.py
# ✅ 패키지 자산 SSOT 일치 — 22개 소비처 검증 완료

make sync-desktop
# webui_assets 생성, @proto-only 누수 0 확인

make desktop-check
# ✅ 데스크톱 계약 일치 — adapter 가 호출하는 모든 엔드포인트가 webui 에 존재 · 생성물 누수 없음
# ✅ contract-shapes.json 동기(드리프트 없음)

git diff --check
# no output
```

pytest 경고는 기존 async teardown warning 이며 테스트/coverage 게이트는 통과. `make desktop-check` 의 Node 경고는
`package.json` 에 `"type": "module"` 이 없어 ES module 로 재파싱했다는 성능 경고이고 검사는 통과했다.

### 남은 검증/후속
- 로컬 검증 환경 보정: Node/npm, `python3-aiohttp`, 시스템 Chrome 을 설치했고, 루트 `.venv/pyvenv.cfg` 는
  `include-system-site-packages = true` 로 바꿔 `make desktop-check` 가 `.venv/bin/python` 으로도 `aiohttp` 를 import 하게 했다.
  이 변경은 로컬 환경 파일이며 git tracked 변경은 아니다.
- Playwright 첫 실행은 시스템 Chrome 부재로 전부 launch 전 실패했다. `npx playwright install chrome` 후 재실행해서 61개 통과.
- 원래 목표의 `docs/nexa` master plan 은 GitHub 인증이 풀린 뒤 다시 `git pull --ff-only` 하고 읽어야 한다.

---

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
  - ✅ **채널 AI·RAG·프리셋(read 브리지)**: central 좁은 read 포트(GuildChannelAiQuery/GuildKnowledgeQuery/
    GuildPresetQuery) → ProviderAdminController `/channel-ai`·`/knowledge`·`/presets` + agent + webui + adapter
    + UI(read-only 현황). 추가·편집은 도메인이 풍부(behavior version·space·revision)해 Discord 명령·웹 대시보드가
    SSOT — 앱은 조회 + 안내. 채널AI 가짜 토글 제거(실 "켜기"=프로필 위저드).
  - 🚫 **안전(콘텐츠 신고 큐)**: central 에 해당 백엔드 **없음**(ProviderSafetyService 는 프로바이더 과부하
    보호로 다른 개념). 가짜 연동 대신 `MSOON_REAL` 정직 유지. 신고 큐가 필요하면 신규 도메인 설계 필요.
  - ⏳ **남은 쓰기(write) 작업**: 채널AI 편집/RAG 문서 추가/프리셋 적용을 앱에서 직접 하려면 각 도메인의
    풍부한 입력(위저드)을 앱 UI 로 재설계해야 함(현재는 Discord/웹 위임). 읽기는 전부 실연동됨.
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
- 이 브랜치(42커밋) → main PR 머지. ⚠️ `central-server/**` push 는 **자동배포** 트리거. AGENTS.md Git/Release 가드 준수.
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
