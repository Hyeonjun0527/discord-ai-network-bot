# 데스크톱 앱 완성 + 라이브 봇 버그 수정 — 실행 계획서

작성: 2026-06-08 · 브랜치 `fix/per-guild-daily-limit`(미머지) 위에 이어서 작업.

이 문서는 **라이브 디스코드 봇에서 보고된 실제 버그 2건**과 **앞서 정직하게 남겨둔 한계 6건**을
하나도 빠짐없이, 실수 없이 끝내기 위한 단일 진실 원천(SSOT) 계획이다. 각 태스크는 근본 원인
(파일:라인), 변경 내용, 손댈 파일, 검증 방법, 위험, 규모를 명시한다.

---

## 0. 대원칙 (모든 태스크 공통)

- **SSOT 준수**: 데스크톱 UI 는 `prototypes/desktop` 에서만 수정 → `make sync-desktop`. webui_assets 직접 수정 금지.
  mock/데모는 `@proto-only` 로 격리. 새 엔드포인트는 `contract.js`·adapter(mock+real)·webui 라우트/핸들러를 **한 커밋**에서 맞춘다.
- **central 변경 = 자동배포**: `central-server/**` 가 `main` 에 들어가면 GHCR 빌드 → self-hosted 배포가 자동 실행된다.
  따라서 central 변경은 **로컬 `gradlew build` 그린 확인 후**에만 쌓고, main 머지는 마지막에 한 번에(사용자 승인).
- **가짜 금지**: 낙관적 전환·저장만 하고 미적용·하드코딩 지표 금지. 적용 안 되면 정직하게(`needsRestart`/원복/안내).
- **검증 게이트(커밋 전 필수)**:
  - provider-agent: `../.venv/bin/ruff check src tests` + `mypy src` + `pytest -q`(커버리지 ≥70%)
  - central: `JAVA_HOME=$(/usr/libexec/java_home -v 21) central-server/gradlew -p central-server build`(test+ktlint+ArchUnit+Kover)
  - desktop: `make sync-desktop` → `make desktop-check`(엔드포인트·shape·누수) → `cd prototypes/desktop && npx playwright test` → 실 앱 헤드리스 스모크
- **실 앱 기동(검증용)**: `cd provider-agent && ../.venv/bin/python -c "import time; from provider_agent import webui; app=webui.build_app('devkey'); webui._start_server_thread(app,'127.0.0.1',8799); time.sleep(36000)"` (세션키 `devkey`)

---

## 1. 라이브 봇 버그 (최우선)

### B1. `/그림`(imagine) — "이미지 생성 가능한 프로바이더가 없습니다"

**증상**: 앱에서 "이미지 요청 받기" 토글 ON 후 디스코드 `/그림` 요청 시 위 메시지.

**근본 원인** (코드 확정):
- central 구조는 정상. `AskCommandHandler.imagine()`(`central-server/.../command/AskCommandHandler.kt:124-147`)이
  `registry.byGuild(guildId).filter { "image" in it.capability.capabilities }` 로 거른다. hello 의 capabilities 에
  `"image"` 가 있어야 인식(`ProviderSession.applyHello` `relay/ProviderSession.kt:103-115`).
- **provider-agent 측 문제**: 에이전트가 `enable_image=False` 로 시작하면 `self._sd is None`(`agent.py:202-209`)이 되어
  이후 토글해도 **라이브로 SD 클라이언트가 생기지 않는다**. hello capabilities 의 "image" 는 `_image_ready`(SD health)
  일 때만 붙는다(`agent.py:285, _build_hello`). 즉 토글 ON 은 config 만 저장하고 실행 중 에이전트엔 무반영.
- 추가 전제: SD(A1111)가 **설치+healthy** 여야 함. 미설치면 `_boot_sd`(`agent.py:843-866`)가 조기 반환 → image 불가.

**변경**:
1. `agent.py` — `async def set_image_enabled(self, on: bool)` 신설:
   - on: `self._sd` 없으면 `SDClient(cfg.sd_url, cfg.request_timeout)` 생성 → `self._image_ready = await sd.health()`;
     미준비 & 설치돼 있으면 `_boot_sd` 태스크 기동(설치 자동 구동→준비 시 재광고); `await self._readvertise()`.
   - off: `_sd=None`, `_image_ready=False`, boot 태스크 취소, `await self._readvertise()`.
   - `self._cfg.enable_image` 상태 동기화(또는 별도 플래그).
2. `webui.py` `/api/setup`(이미지 토글 경로) — GUI 인-프로세스 에이전트 실행 중이면 `agent.set_image_enabled(on)` **라이브 호출**
   (현재는 `set_models` 만 라이브, enableImage 는 저장만). 백그라운드 서비스면 기존 `kickstart`(재기동) 유지.
3. 데스크톱 UI — 이미지 토글 ON 시 **SD 미설치면 설치 안내/플로우**로 유도(기존 SD 설치 플로우 재사용). 토글 결과를
   `status.imageReady` 로 readback(준비되기 전엔 "준비 중" 정직 표기).

**파일**: `provider-agent/src/provider_agent/agent.py`, `webui.py`, `prototypes/desktop/index.html`(+adapter 필요 시).
**검증**: agent 단위테스트(set_image_enabled 가 capability 토글+readvertise), webui 테스트(라이브 호출), 실기기에서 SD 설치+토글 후
`/그림` 왕복 또는 최소한 `status.imageReady=true` + hello capabilities 에 image. **central 무수정.**
**위험**: SD 부팅은 느리고 비동기 — 블로킹 금지(백그라운드 태스크). 실 이미지 생성 E2E 는 SD 설치 필요.
**규모**: 중.

### B2. `/프로바이더목록` 이 raw userId 표시 → 서버 닉네임으로

**증상**: `provider #1081249251213967411: 기여 20회 · LIMITED` 처럼 raw ID. 서버 닉네임이 보여야 함.

**근본 원인**: `GuildAdminCommandHandler.providers()` `central-server/.../command/GuildAdminCommandHandler.kt:226` 가
`"· provider #${it.providerId}: ..."` 로 Long 을 그대로 보간. (바로 옆 `fairness()` 240행은 이미 `<@$pid>` 멘션 사용 — 일관성 결함.)

**변경**: 226행을 `<@${it.providerId}>` 멘션으로 변경(디스코드가 닉네임으로 렌더). `fairness` 와 동일 패턴, 동기 흐름 유지.
(대안: `BotGuildLister.memberName(guildId,userId)` 주입해 닉네임 텍스트 — 캐시 동기 조회, 미스 시 `<@id>` 폴백. 단 의존성 추가.)
→ **권장: 멘션(`<@id>`) 1줄 수정.**

**파일**: `central-server/.../command/GuildAdminCommandHandler.kt`(+ 해당 테스트).
**검증**: `gradlew build` + providers() 출력이 `<@id>` 포함·`provider #` 미포함 단언 테스트.
**위험**: 낮음. **central 자동배포.**
**규모**: 소(1줄+테스트).

---

## 2. 남은 한계 6건

### L1. 백그라운드 상주 · 자동 연결 토글 — 적용 시점

**현재**: `background`(tray)·`autoConnect`(auto_connect) 는 저장은 즉시, 효과는 다음 실행/창 닫을 때(시작 시점 config 만 읽음).
(autostart 는 이미 즉시 적용으로 수정됨 — `service.install/uninstall_service`.)

**변경(실용적·정직)**:
- `autoConnect` ON & 에이전트 실행 중 → **즉시 동기화 트리거**(`agent._sync_joins_once`/sync_loop 1회 강제)해 승인된 서버에 지금 연결.
- `background` → 본질상 "창 닫을 때 유지" 동작이라 즉시 효과가 없다. UI 에서 "창을 닫으면 트레이로 계속 제공" 의미를 명확히
  표기(거짓 즉시반영 안내 금지). 토글 자체는 저장+다음 창 닫기/실행에 반영(현 동작 유지, 문구만 정직화).
- 둘 다 토글 핸들러는 이미 await+실패 원복으로 수정됨(이전 커밋). 추가로 autoConnect 즉시 sync 만 구현.

**파일**: `provider-agent/src/provider_agent/agent.py`(sync 트리거 메서드 노출), `webui.py`(autoConnect=true 시 호출), `prototypes/desktop/index.html`(문구).
**검증**: webui 테스트(autoConnect on → sync 호출), 실 앱 스모크. **central 무수정.**
**위험**: 낮음. **규모**: 소~중.

### L2. 이미지 받기 즉시 적용 — **B1 로 해결**(상동). 별도 작업 아님.

### L3. 서버 제거 / 이름변경 UI

**현재**: webui 라우트 `/api/server-remove`(`webui.py:693`)·`/api/server-rename`(`webui.py:910`) 존재(로컬 연결/config 만 정리,
central 호출 없음). adapter 메서드·UI 버튼 **부재**. 또한 라우트가 **index 기반**(guildId 아님)이라 매핑 필요.

**변경**:
1. webui — 라우트를 `guildId` 기반으로 받도록 보강(또는 adapter 가 서버 목록에서 index 해석). 64bit guildId 문자열 유지.
2. `contract.js` + adapter(mock+real) — `serverRemove(guildId)`·`serverRename(guildId,name)` 추가.
3. 데스크톱 UI — 서버 상세에 "이 서버 제공 그만두기(연결 제거)"·"이름 변경" 컨트롤 + 확인 모달.
4. (선택) "제공 그만두기" 를 central 에도 반영하려면 durable-token `/provider/admin/remove`(자기 자신) 호출 추가 검토.
   기본은 로컬 제거(=그 서버에 더는 연결 안 함). 문구로 "내 연결만 정리(관리자 풀 등록과 별개)" 정직 표기.

**파일**: `webui.py`, `prototypes/desktop/{contract.js,adapter.js,index.html}`.
**검증**: desktop-check(엔드포인트), webui 테스트, playwright(버튼·모달), 실 앱 스모크. **central 변경은 선택(있으면 자동배포).**
**위험**: 중(index→guildId 매핑 정확성, 정밀도). **규모**: 중.

### L4. 안전(신고) 탭 — 실동작화

**현재**: central 에 신고 전체 lifecycle 존재 — `PresetRegistryService.listReports/reviewReport`, HTTP `PresetRegistryController`
(`/api/ai-network/presets/reports`, `POST .../reports/{id}/review`), Discord 명령까지. **단 `/api/ai-network` 경로는 dashboard-admin
인증** 이라 데스크톱(durable-token)으로 접근 불가. 데스크톱 앱은 현재 "준비 중"(MSOON) 표기.

**변경**:
1. central `ProviderAdminController`(durable-token `/provider/admin`) 에 **브리지 추가**: `GET/POST` 신고 조회·결정.
   기존 `authedAdmin(durableToken, guildId)` 게이트 후 `PresetRegistryService.listReports/reviewReport` 위임(신규 로직 없음).
   - 길드 범위 한정 주의: 신고/프리셋이 guild-scoped 인지 확인 후, 그 길드 관리자만 그 길드 신고를 보게 가드.
2. `webui.py` — 위 durable-token 엔드포인트로 프록시(`agent.admin_reports/admin_review` + `_post_provider_admin_*`).
3. `contract.js`+adapter — `getReports(guildId)`·`reviewReport(guildId, reportId, decision)`.
4. 데스크톱 UI — 안전 탭의 MSOON 을 실제 신고 목록 + "무시(dismiss)/숨김(suspend·remove)" 액션으로 교체.

**파일**: central `ProviderAdminController.kt`(+DTO), `webui.py`, `agent.py`, `prototypes/desktop/*`, central 테스트.
**검증**: gradlew build(브리지+가드 테스트), desktop-check, webui 테스트, playwright. **central 자동배포.**
**위험**: 중(권한 가드 — 길드 격리·관리자 한정 정확성). **규모**: 중.

### L5. 채널AI / RAG(지식) / 프리셋 — 읽기전용 → 쓰기(추가/편집/삭제)

**현재**: central 에 쓰기 풀세트 존재하나 전부 `/api/ai-network/**`(dashboard-admin). 데스크톱(durable-token)
`ProviderAdminController` 엔 **list 만**(359행 주석 "추가/편집은 Discord/웹 경유"). 앱은 읽기전용.
- 채널AI: `ChannelAiCustomizationService`/`...Controller`(wizard·create·rollback·approve/reject·ai-admin-roles)
- RAG: `KnowledgeIngestionService`/`...Controller`(createSpace·addSource·approveSource·remove/reject·index-job)
- 프리셋: `PresetRegistryService`/`...Controller`(create·update·publish·import·delete)

**변경**(가장 큰 작업 — 단계적 가능하나 목표는 전부):
1. central `ProviderAdminController` 에 durable-token **브리지 쓰기 메서드** 추가(각 도메인 핵심 작업):
   - 채널AI: 추가/수정/삭제(또는 wizard create + delete + ai-admin-roles 설정)
   - RAG: 소스 추가/승인/제거 + 스페이스 관리
   - 프리셋: 생성/수정/삭제/공개여부
   모두 `authedAdmin` 게이트 후 기존 서비스 위임(신규 비즈니스 로직 없음). **길드 스코프·소유권 가드 필수**.
2. `webui.py`+`agent.py` — durable-token 프록시 메서드 + 라우트.
3. `contract.js`+adapter — 각 쓰기 메서드(mock+real).
4. 데스크톱 UI — 읽기전용 탭을 추가/편집/삭제 컨트롤로 확장(폼·모달·확인). RAG 파일 업로드/소스 추가 UX 포함.

**파일**: central `ProviderAdminController.kt`(+다수 DTO·서비스 주입), `webui.py`, `agent.py`, `prototypes/desktop/*`, central 테스트.
**검증**: gradlew build, desktop-check, webui 테스트, playwright, 실 앱 스모크. **central 자동배포.**
**위험**: 높음(표면 넓음·권한 가드·UX). **규모**: 대 — 하위 3개(채널AI/RAG/프리셋)로 분할 권장. 각각 독립 커밋.

### L6. 법적 링크 — 프로토타입 요약 → 정식 문서

**현재**: 약관/개인정보/콘텐츠 정책 모달이 하드코딩 요약문(`index.html` openLegal, "프로토타입 요약" 명시).

**변경**:
1. 정식 ko 본문 작성(약관·개인정보처리방침·콘텐츠 정책). (en/ja 는 i18n 정책상 후속 가능하나 SSOT 는 `i18n/messages.json`
   또는 별도 정적 문서 — 분량이 커서 정적 마크다운/HTML 권장.)
2. 데스크톱 앱이 정식 문서를 표시(모달 스크롤 또는 외부 링크). 랜딩(central static)과 동일 출처면 더 좋음(SSOT).

**파일**: 법적 문서(신규), `prototypes/desktop/index.html`(렌더), 필요 시 central static.
**검증**: 링크/렌더 확인, playwright. **central 변경은 정적 문서면 자동배포(주의).**
**위험**: 낮음(기술)·중(법적 문구 정확성 — 사용자 검토 필요). **규모**: 중(콘텐츠 중심).

---

## 3. 실행 순서 (단계)

각 단계 끝에서 해당 영역 검증 게이트 통과 + 커밋. central 은 로컬 빌드 그린만, **main 머지는 전 단계 후 한 번에**.

**Phase A — 즉효·저위험 (provider-agent + central 소)**
- [ ] B2 닉네임 멘션(central, 1줄+테스트)
- [ ] B1 이미지 라이브 적용(agent `set_image_enabled` + webui 라이브 + UI SD 설치 유도)
- [ ] L1 autoConnect 즉시 sync + background/문구 정직화

**Phase B — provider-agent + desktop UI (central 무관)**
- [ ] L3 서버 제거/이름변경 UI(webui guildId 보강 + adapter + UI + 모달)

**Phase C — central durable-token 브리지 + desktop UI**
- [ ] L4 안전(신고) 브리지 + 프록시 + UI
- [ ] L5-1 채널AI 쓰기 브리지 + UI
- [ ] L5-2 RAG(지식) 쓰기 브리지 + UI
- [ ] L5-3 프리셋 쓰기 브리지 + UI

**Phase D — 콘텐츠**
- [ ] L6 정식 법적 문서 + 렌더

**Phase E — 통합·머지**
- [ ] 전 영역 검증 재실행(pytest/gradlew/playwright/desktop-check)
- [ ] 전수 감사 재실행(토글·버튼 등급 ✅ 확인)
- [ ] 사용자 승인 후 `main` 머지 = central 자동배포 + 데스크톱 릴리스(별도 태그)

---

## 4. 위험·주의 모음

- **central 자동배포**: B2·L4·L5(·L6 정적) 는 main 머지 시 실서버 즉시 반영. 단계별 `gradlew build` 그린 필수, 머지 직전 통합 재검증.
- **권한 가드(L4·L5)**: durable-token 브리지는 반드시 `authedAdmin(durableToken, guildId)` + **길드 스코프**로 막아
  타 길드 데이터 접근·비관리자 쓰기를 차단. central 테스트로 가드 검증.
- **64bit guildId/providerId**: JS 경계는 문자열 유지(Number 화 금지). L3 의 index→guildId 매핑 주의.
- **SSOT 드리프트**: 새 엔드포인트마다 contract·adapter(mock+real)·webui·(central) 동시 변경 + `make desktop-check`.
- **이미지 E2E**: SD 미설치 환경에선 capability 까지만 검증 가능. 실 이미지 생성은 SD 설치 후.
- **법적 문구(L6)**: 기술 외 — 사용자(서비스 운영자) 검토·승인 필요.

---

## 5. 완료 정의 (DoD)

- 라이브 봇: `/그림` 이 SD 켠 프로바이더로 라우팅되어 이미지 생성, `/프로바이더목록` 이 닉네임 표시.
- 데스크톱: 모든 토글·버튼이 ✅(실동작) 또는 명시적·정직한 상태(설치 필요/다음 실행 등). "준비 중"·no-op·가짜 잔존 0.
- 안전·채널AI·RAG·프리셋: 앱에서 조회+쓰기 동작(권한 가드 통과).
- 법적 링크: 정식 문서.
- 전 검증 게이트 그린 + 전수 감사 재통과 + 사용자 승인 머지.
