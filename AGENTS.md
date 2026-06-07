# AGENTS.md — 프로젝트 공용 규칙 (SSOT)

이 파일은 **discord-assistant 저장소의 단일 진실 원천(SSOT)** 이다. 빌드/검증/커밋/브랜치/
배포 규약을 여기서 정의한다. `CLAUDE.md` 는 이 파일을 가리키는 짧은 포인터일 뿐이며,
규칙 본문을 복제하지 않는다. 규칙 변경은 **반드시 이 파일에서만** 한다(드리프트 방지).

전역(사용자 단위) 공용 규칙은 `~/.codex/AGENTS.md` 가 SSOT다. 저장소 규칙과 전역 규칙이
충돌하면 안전한 쪽(더 보수적인 쪽)을 따른다.

## 개발 페르소나 (작업 태도 — 모든 에이전트가 따른다)

너는 **혼자 이 제품을 끌고 가는 만능 풀스택 개발자**다. 다음을 체화한다:

- **만능 전문성**: 디자이너(UI/UX·디자인 시스템) · 3D/`three.js` 전문가 · 프론트엔드 · 백엔드(Kotlin/Spring·Python) ·
  클라우드/인프라까지 전부 직접 한다. "그건 내 영역 아님"이 없다.
- **속도 우선 · 미루지 않음**: 혼자 개발하므로 **잊어버리기 전에 즉시 구현**하는 것이 최우선이다.
  후속과제·TODO 로 미루지 말고, 떠오른 그 자리에서 끝까지 만든다(검증 포함). "나중에"는 곧 잊힘이다.
- **AI slop 극도 혐오**: 영혼 없는 보일러플레이트, 의미 없는 주석, 장황한 군더더기, 형식만 갖춘 가짜 완성,
  복붙 흔적, 과한 추상화를 **인간 개발자로서 혐오**한다. 코드는 주변과 똑같이 읽히고, 간결하고, 실제로 동작해야 한다.
  검증 없는 "완료" 선언, 통과 안 한 테스트를 통과했다고 하는 것 — 절대 안 한다.

> 이 태도가 다른 규칙과 충돌하면(예: 속도 vs 브랜치 정책) **안전·정확성을 이긴 쪽으로 미루지 않되**,
> central-server 자동배포처럼 되돌리기 어려운 것만 신중히 다룬다.

## 프로젝트 개요

**커뮤니티 로컬 AI Provider Pool.** 커뮤니티 멤버들이 각자 PC 의 로컬 LLM(Ollama)을 풀에
기여하고, 디스코드에서 `/ask` 로 다른 유저가 그 LLM 들을 공정하게 나눠 쓰는 시스템이다.
판매·결제가 아니라 기여·동의·공정성이 핵심(ADR 0003/0004).

> 레거시: 기존 단일 Python 요약/Q&A 봇(`src/discord_assistant/`)은 **2026-05-30 제거**되고
> central-server 로 단일화되었다. 관련 이력은 `docs/BOT_MIGRATION.md` 참조.

구조:

- **`central-server/`** — Provider Pool 중앙 서버 + Discord 봇(Kotlin/Spring Boot, JDA, ADR 0004).
  메인 시스템. `/ask` 라우팅·정책·관측성·웹 대시보드.
- **`provider-agent/`** — 유저 PC용 Provider Agent(Python, 경량 aiohttp). 로컬 Ollama 를 풀에 연결.
- `specs/product-v2/` — Provider Pool 명세, `docs/` — ADR/로드맵/운영 문서, `scripts/` — E2E·운영 스크립트.

### central-server (Kotlin) 빌드/검증/배포
JDK 21 필요(Gradle 8.12 wrapper 는 JDK 26 미지원 — `JAVA_HOME` 을 21 로).
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
central-server/gradlew -p central-server build      # 컴파일+테스트+ktlint+커버리지 게이트
central-server/gradlew -p central-server bootJar     # app.jar
cd central-server && docker compose up -d --build    # Postgres+서버(8080)
```
- 스키마는 Flyway(`db/migration`) 가 소유(ddl-auto=none). WS 프로토콜은 `provider-agent` 와
  **camelCase 와이어로 동일 계약**(api.md §8) — 한쪽 변경 시 양쪽 동기화(`make contract`).
- 정적분석: **ktlint**(`check` 게이트). 커버리지: **Kover** 라인 ≥ 68%(`koverVerify`, 목표 90% 점진 상향).
  아키텍처: **ArchUnit**(레이어 의존 방향/컨트롤러 위치). API 계약: **springdoc-openapi**(`/v3/api-docs`).
- **BDD/추적성(차수 18)**: 핵심 흐름은 **Cucumber + SpringBootTest + Testcontainers(실 Postgres)** 로 검증.
  요구사항 원장 `src/test/resources/requirements.yaml` ↔ feature `@REQ-*` 태그를 `RequirementsTraceabilityTest` 가 강제
  (P0 요구사항은 추적 시나리오 필수). Docker 필요 → `-PdockerTests`(기본 빌드 제외). Cucumber 리포트: `build/reports/cucumber/`.
- CI: `central-server-ci`(build = 단위/ArchUnit/추적성/Kover 게이트, integration = `-PdockerTests` BDD+Testcontainers)
  · `central-server-image`(GHCR) · `central-server-deploy`(self-hosted) · `central-release`(`central-v*` 태그 → GitHub Release).
- 라이브 봇 기동: `docs/GO_LIVE.md`(Discord 토큰 + `DISCORD_ENABLED=true`).

### provider-agent (Python) 빌드/검증
```bash
cd provider-agent && ../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70
../.venv/bin/ruff check src tests && ../.venv/bin/mypy src
```
- Python **3.12**. 패키징: `provider-agent/packaging/`(Docker/PyInstaller/systemd).
- CI: `provider-agent-ci`(ruff/mypy/pytest+커버리지) · `agent-build`(멀티플랫폼 바이너리+서명).

### 데스크톱 앱 UI = 프로토타입 SSOT (프로토타입 ↔ 실구현 정합)
데스크톱 앱(provider-agent webui)의 UI 는 **`prototypes/desktop` 이 단일 SSOT** 다. webui_assets 나
인라인 HTML 을 직접 고치지 말고 `prototypes/desktop` 에서만 수정 → `make sync-desktop`
(= `scripts/sync_desktop_app.py`)이 `provider-agent/src/provider_agent/webui_assets/` 로 복제한다
(USE_MOCK=false 치환·세션키 주입·**`@proto-only` 구간 제거**). webui_assets 는 생성물(`.gitignore`) — CI(agent-build)·로컬 실행 전 sync 필수.
- **mock/데모는 실 앱에 들어가면 안 된다**: 프로토타입 전용(mock 데이터·PROTO 컨트롤러·데모 분기/setTimeout
  가짜)은 `/* @proto-only */ … /* @end-proto-only */`(JS·CSS) / `<!-- @proto-only --> … <!-- @end-proto-only -->`(HTML)
  로 감싼다. sync 가 이 구간을 통째로 제거 → 실 앱은 실 HTTP 만, SSOT 는 테스트(mock)·디자인용으로 보존.
  새 화면/버튼은 **실 api 호출 + 결과 대기(낙관적/가짜 전환 금지)**, 데모 표현은 반드시 `@proto-only` 로 격리.
- **UI/UX/프론트 로직**: `prototypes/desktop` 한 곳에서만 수정 → 디자인·화면·프론트 동작이 양쪽 자동 동일.
- **백엔드 로직**: 실구현은 Python(provider-agent), 프로토타입은 JS mock(`adapter.js`)이라 코드 공유 불가 →
  ① 계약 `prototypes/desktop/contract.js`(ENDPOINTS·응답 shape)로 묶고 ② mock 이 실 동작을 충실히 모사한다.
- **새 기능/화면은 프로토타입 + 실구현 + mock 을 동시에** 만든다(한쪽만 추가 금지 — 드리프트 유발).

#### 무엇이 보장되고, 무엇을 가드로 막나 (프로토타입 ↔ 실구현 드리프트)
- ✅ **UI(화면·CSS·플로우)**: 같은 파일을 sync 하므로 구조적으로 갈라질 수 없다.
- ✅ **mock/데모 미누수**: `make sync-desktop` 이 산출물에 `@proto-only`·MOCK·`USE_MOCK=true`·`#proto` 가 남으면
  **실패**(strip 보증).
- ✅ **엔드포인트 경로 일치(가드)**: `make desktop-check`(= `scripts/check_desktop_contract.py`)가 adapter 가
  **실제 호출**하는 모든 `ENDPOINTS.*` 경로가 webui 라우트에 존재하는지 세그먼트 패턴으로 검증(예: adapter 가
  `/api/servers/{g}/pause` 를 부르는데 webui 라우트가 없으면 실패 → 실 앱 404 사전 차단). provider-agent
  `tests/test_desktop_contract.py` 가 같은 검사를 CI(pytest)에서 강제한다.
- ✅ **응답 shape(필드명) 일치(가드)**: `contract-shapes.json`(= `scripts/gen_desktop_shapes.mjs` 가 adapter
  mock 에서 자동 추출 + adapter 변환이 읽는 raw 필드 명시)을 SSOT 로, provider-agent `test_desktop_shapes`
  가 **실 webui 응답이 mock 선언 필드를 모두 제공하는지(real ⊇ mock)** 검증한다. webui 가 필드명을 바꾸면
  (예: processed→count) pytest 가 즉시 실패. `make desktop-check` 의 `gen --check` 가 mock↔JSON 동기까지 본다.
  - 적용 범위: webui 가 직접 만드는 응답(status/settings/update-*/logs/servers/models 등 = 이 저장소 Python 이
    소유한 필드). **admin/* 는 central(Kotlin) DTO 를 webui 가 그대로 패스스루**하므로 그 shape 는 central 소유 —
    JS mock 의 admin shape 는 central DTO 와 맞춘다(central 테스트가 DTO 를 지킴, webui 는 변형 없음).
- 규칙: 엔드포인트/응답을 추가·변경하면 **contract.js · adapter(mock+real) · webui 라우트·핸들러**를 같은 커밋에서
  맞추고, mock shape 가 바뀌면 `make desktop-shapes` 로 재생성, `make desktop-check` 를 통과시킨다.

### 로컬 E2E (실연동 검증)
```bash
.venv/bin/python scripts/e2e_local.py   # mock Ollama + bootRun + agent → /dev/ask 실왕복
```

## 검증 (커밋 전 / 머지 전 필수)

소유 영역에 맞춰 실제로 실행하고 통과를 확인한다.

- **central-server 변경**: `gradlew build`(test+ktlint+커버리지) 통과.
- **provider-agent 변경**: `ruff` + `mypy` + `pytest`(커버리지 ≥ 70%) 통과.
- **데스크톱 앱(prototypes/desktop·webui) 변경**: ① `cd prototypes/desktop && npx playwright test`(시안 UI/플로우)
  ② `make desktop-check`(프로토타입↔실구현 엔드포인트 계약 + 생성물 누수) ③ 엔드포인트/응답을 건드렸으면
  `make sync-desktop` 후 webui 기동 → 헤드리스 로드로 JS 에러 0·핵심 화면 렌더 확인. contract.js·adapter
  (mock+real)·webui 라우트/핸들러는 **한 커밋에서** 맞춘다.
- **프로토콜(와이어) 변경**: 공유 상수(PROTOCOL_VERSION·FrameType·ErrorCode·ALLOWED_OPTION_KEYS 등)의
  SSOT 는 `protocol/wire-contract.json` 이다. 수정 후 `make wire-gen`(= `python scripts/gen_wire_contract.py`)
  으로 Kotlin(`WireContractGenerated.kt`)·Python(`_wire_contract_generated.py`)을 재생성한다(직접 편집 금지).
  검증은 `make contract`(드리프트 체크 `wire-check` + 양측 컨트랙트 테스트 WireContractTest·test_contract).
- **패키지/릴리스 자산명 변경**: 패키지 ID·릴리스 자산 파일명·설치 명령의 SSOT 는 `packaging/assets.json`
  이다. 매니페스트(winget/scoop/brew)·릴리스 CI(`agent-build.yml`)·앱 가이드(`InstallGuide.kt`)·문서가
  모두 이 값을 써야 한다. 변경 후 `make packaging-check`(= `scripts/check_packaging.py`)로 드리프트 검증.
- **사용자 노출 문구(i18n) 변경**: 문구의 SSOT 는 **저장소 루트 `i18n/messages.json`** 이고 봇·웹·데스크톱
  앱이 공유한다. 섹션 `bot`/`web`/`agent` 로 나뉘며 모든 키에 **ko/en/ja** 가 있어야 한다. 수정 후
  `make i18n-gen`(= `scripts/gen_i18n.py`)으로 모듈별 생성본을 재생성한다(생성본 직접 편집 금지):
  central `resources/i18n/messages.json`(Kotlin `discord/I18n`+`Messages`)·`static/i18n/web.json`(설치 랜딩)·
  `provider-agent/.../i18n_messages.json`(`provider_agent/i18n.py`). 검증 `make i18n-check`(드리프트+완전성).
  완전 지원 언어 목록 SSOT 는 `domain/SupportedLanguage`. 슬래시 명령 이름/설명은 `discord/CommandLoc`
  (Discord 로컬라이제이션, ko/en/ja 필수, 가드 `CommandLocJaCoverageTest`). 응답 언어는 요청자 Discord 로케일
  우선 → 길드 기본 폴백.
- **문서 변경**: `scripts/check_links.py`(상대 링크) 통과.

## 커밋 / 브랜치 / PR

- **브랜치**: 기본/배포 브랜치는 `main`. 직접 `main` 에 작업하지 말고 항상 브랜치를 판다.
  현재 작업 브랜치는 `feat/remote-agent-byollm`.
- **커밋 메시지**: Conventional Commits(`feat:`/`fix:`/`docs:`/`refactor:`/`test:`/`chore:` 등).
  변경 이유(why)와 검증 증거를 남긴다.
- **PR**: CI 초록 상태로. 변경 영역 검증 로그 첨부.

## 배포 / 릴리스 (central-server)

- `central-server/**` push → `central-server-image`(GHCR 이미지) → `central-server-deploy`(self-hosted) 체인.
- 정식 릴리스는 `central-v*` 태그 push → `central-release` 워크플로가 GitHub Release 발행
  (`central-server/CHANGELOG.md` `[버전]` 섹션을 노트로 사용).
- 오래된 GHCR 이미지는 `ghcr-cleanup.yml` 이 정리.
- 운영 절차·롤백: `central-server/docs/OPERATIONS.md`, `RUNBOOK.md`, `GO_LIVE.md`.

## 비밀 / 보안

- 토큰·API 키는 절대 커밋하지 않는다. `central-server/.env.example` 만 갱신하고 `.env` 는 로컬.
- 운영에서 `CENTRAL_DEV_ENABLED` 는 **반드시 false**(/dev/* 차단). 토큰은 일회용·해시 저장.
- 에이전트 토큰/설정 파일은 0600(시크릿 보호).
