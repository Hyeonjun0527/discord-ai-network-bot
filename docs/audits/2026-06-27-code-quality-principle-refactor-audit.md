# Code Quality Principle Refactor Audit — 2026-06-27

입력: `/Users/osuma/.codex/attachments/5e98bb35-74da-4b0a-a8de-1a170b79fc62/pasted-text-1.txt`

목표: 첨부된 전역 코드 검증 원칙 기준으로 원칙 위배 50곳 이상을 현재 코드 근거로 식별하고,
동작 보존·최소 변경·테스트 검증을 우선해 전부 리팩토링한다.

완료 기준:

- 위배 항목이 50개 이상이다.
- 각 항목은 첨부 원칙 중 최소 하나에 연결된다.
- 각 항목은 코드/테스트/운영 스크립트/문서 중 하나 이상의 현재 변경으로 해소된다.
- `TODO`/`VERIFY` 상태가 남지 않는다.
- repository 검증 게이트가 현재 worktree에서 통과한다.

## Summary

- 발견/수정 항목: 50개
- 상태: 50 `DONE`, 0 `TODO`, 0 `VERIFY`
- 주 검증: central build, provider ruff/mypy/pytest, docs+i18n verifier, protocol verifier, SSOT viewer check
- 상세 구현 backlog: `docs/audits/2026-06-27-policy-state-refactor-backlog.md`

## Refactor Matrix

| No. | Status | 원칙 위배 | 첨부 원칙 | 리팩토링 증거 |
| ---: | --- | --- | --- | --- |
| 1 | DONE | JPA store가 상태 전이 그래프를 우회해 문자열 status를 직접 덮어씀 | 상태 전이 명확화, 캡슐화, 검증 지속성 | `JpaScheduledActionStore`가 도메인 전이 메서드를 통과하고 불법 전이 테스트가 추가됨 |
| 2 | DONE | in-memory fake가 실제 store보다 느슨해 테스트가 불법 전이를 숨김 | 회귀 방지, 테스트 가능성, 상태 전이 명확화 | `ActionSchedulerTestSupport`가 실제 전이 가드를 사용하도록 정리됨 |
| 3 | DONE | due 재평가 후 TYPING 상태가 영속되지 않아 실행 상태가 불명확함 | 상태 전이 명확화, 동시 실행 정책 일관성 | `ActionSchedulerPort.markTyping`과 poller 연결로 상태가 저장됨 |
| 4 | DONE | 부분 전송 후 PARTIALLY_SENT 없이 즉시 cancel되어 복구 판단 근거가 사라짐 | 상태 전이 명확화, 실패 유형 명확화 | `markPartiallySent` 경유 후 cancel하도록 실행 경계가 보강됨 |
| 5 | DONE | REEVALUATING/SCHEDULED action을 실행 서비스가 직접 실행 가능 | Fail Fast, 상태 전이 명확화 | execution/reaction service가 TYPING 입력만 허용함 |
| 6 | DONE | transition API가 missing identity를 조용히 무시함 | Fail Fast, 예외 은닉 금지, 의미 있는 실패 | missing action은 fail-fast, terminal cancel만 idempotent false로 분리됨 |
| 7 | DONE | lease owner 검증 없이 complete/cancel/fail 가능 | 동시 실행 정책 일관성, 서버 권위, 검증 지속성 | `lease_owner` 교차 worker 검증과 JPA 테스트가 추가됨 |
| 8 | DONE | expired lease 회수 쿼리가 terminal 상태까지 회수할 수 있음 | 상태 전이 명확화, 조회 기준 일관성 | 회수 범위를 in-flight 상태로 제한함 |
| 9 | DONE | PARTIALLY_SENT 복구를 COMPLETED로 종결하면서 감사 phase가 없음 | 실패 유형 명확화, 운영 가시성 | `RECOVERED_NO_RESEND` phase와 no-resend reason을 기록함 |
| 10 | DONE | shadow suppress action이 terminal 없이 남을 수 있음 | 상태 전이 명확화, 실패 유형 명확화 | shadow guard suppress 시 audit 후 `CANCELLED`로 종결함 |
| 11 | DONE | 기존 setup 카테고리만 있으면 누락 채널을 복구하지 못함 | 검증 지속성, 상태 복구 정책 | `ensureTextChannel`로 chat/image/member 누락을 재실행 시 복구함 |
| 12 | DONE | auto-created allow-list 등록이 빈 목록의 전체 허용 의미와 충돌 가능 | 정책 단일화, 경계값 처리 | non-empty일 때만 등록하고 empty 전체 허용 테스트를 고정함 |
| 13 | DONE | 채널 생성 후 런타임 정책 적용 중간 실패가 불투명함 | 실패 유형 명확화, 자원 정리, 운영 가시성 | 단계별 audit와 재실행 보정 경로를 추가함 |
| 14 | DONE | guide pin 실패가 warn 로그로만 사라짐 | 예외 은닉 금지, 운영 가시성 | pin 성공/실패 audit를 추가하고 성공 경로와 분리함 |
| 15 | DONE | 기존 자동 채널 재실행 시 profile/auto-respond 보장이 약함 | 검증 지속성, 정책 단일화 | 신규/기존 경로 모두 `ensureChatRuntimePolicy`를 통과함 |
| 16 | DONE | channel delete 후 allow-list/channelAI/participation cleanup이 분산됨 | 정책 단일화, 낮은 결합도, 조회 기준 일관성 | `ProviderPoolReconciliationService.cleanupChannel` 단일 경로로 정리됨 |
| 17 | DONE | 수동 rename된 ai채팅을 이름만으로 찾아 복구 실패 가능 | 경계값 처리, 최소 놀람 | managed topic marker와 과거 profile 기반 fallback 탐색을 추가함 |
| 18 | DONE | i18n 채널명 변경 시 현재 locale 이름만 탐색함 | API 계약 일치, 경계값 처리 | ko/en/ja 후보 이름 전체로 멱등 탐색함 |
| 19 | DONE | 자동 채널 permission overwrite 정책이 미정 | 읽기/쓰기 권한 분리, 서버 권위 | 공개 역할/봇 권한 overwrite를 명시적으로 보정함 |
| 20 | DONE | ai그림을 text LLM allow-list와 섞어 capability 경계가 모호함 | 관심사 분리, API 계약 일치 | ai채팅=text LLM, ai그림=image, 니아수다=participation으로 분리함 |
| 21 | DONE | global LIVE, channel override, excluded 우선순위가 불명확함 | 정책 단일화, 상태 전이 명확화 | participation flag truth table 테스트로 우선순위를 고정함 |
| 22 | DONE | 자동 setup과 관리자 토글의 participation audit 형식이 다름 | 정책 단일화, 운영 가시성 | enable/disable 공통 audit 형식과 source 구분을 도입함 |
| 23 | DONE | auto-respond 채널을 participation assistant channel로 오인 가능 | 관심사 분리, API 계약 일치 | participation request config가 `channelMode=participation`으로 고정됨 |
| 24 | DONE | clear_speak/clear_silent와 hard_policy 우선순위 충돌 가능 | 정책 단일화, 상태 전이 명확화 | attention hard_policy 사상과 decision table을 도입함 |
| 25 | DONE | pingpong 즉답과 rate-limit의 최종 우선순위가 모호함 | 실패 유형 명확화, 서버 권위 | emit 직전 rate-limit을 hard cap으로 고정하는 테스트가 추가됨 |
| 26 | DONE | debounce/min_gap 상수가 환경별로 drift될 가능성 | 환경 설정 분리, API 계약 일치 | core port 고정 상수와 불변식 테스트로 재현성을 보장함 |
| 27 | DONE | thread/forum context가 같은 부모 채널 히스토리에 섞임 | 조회 기준 일관성, 경계값 처리 | ring buffer key를 `channelId + contextBoundaryId`로 확장함 |
| 28 | DONE | reply target이 bot/self/other일 때 발화 정책이 뭉개짐 | 상태 전이 명확화, 정책 단일화 | `replyToNia`/`replyToSelf`/`replyToOtherUser` decision table을 추가함 |
| 29 | DONE | user opt-out이 pending action과 live gate 양쪽에 일관 적용되는지 불명확 | 검증 지속성, 서버 권위 | pending purge subject와 live consent fail-closed 경계를 추가함 |
| 30 | DONE | shadow/canary/live 전환 중 예약 action 실행 권한이 stale 값에 의존 | 서버 권위, 상태 전이 명확화 | 실행 직전 `ActionExecutionModePort`가 현재 모드를 재조회함 |
| 31 | DONE | `PolicyService` 캐시 무효화가 쓰기마다 흩어짐 | 정책 단일화, DRY, 예외 처리 | `writePolicy` 단일 경계와 finally 무효화로 정리됨 |
| 32 | DONE | `allowedChannelIds=[]`의 전체 허용 의미가 list 해석에 숨어 있음 | 경계값 처리, 명확한 이름, 정책 단일화 | `AllowedChannelPolicy` 타입으로 전체 허용/명시 목록을 표현함 |
| 33 | DONE | role `dailyLimit=0`의 무제한 의미가 API/문구마다 재해석됨 | API 계약 일치, 정책 단일화 | `DailyLimitPolicy`와 UI/API 문구 계약을 일치시킴 |
| 34 | DONE | allow-list 변경과 autoRespond cache/policy 충돌을 감지하지 못함 | 동시 실행 정책 일관성, 운영 가시성 | conflict audit를 추가하고 cache 책임을 분리함 |
| 35 | DONE | request rejection을 locale 문자열로 검증해 환경에 취약함 | API 계약 일치, 실패 유형 명확화 | `RequestRejectionCode`를 추가해 문구와 코드 계약을 분리함 |
| 36 | DONE | `RESTRICTED` 역할이 일반 role burden cap에 섞일 수 있음 | 정책 단일화, 서버 권위 | normal max burden에서 RESTRICTED를 제외하고 테스트로 고정함 |
| 37 | DONE | guild default model과 free cloud env fallback이 문서/코드에서 혼동됨 | 환경 설정 분리, API 계약 일치 | `ZAI_FREE_MODEL`/기본값 계약을 코드와 SSOT에 명시함 |
| 38 | DONE | `/질문` free model과 speech default model drift 가능 | API 계약 일치, DRY | `DEFAULT_SPEECH_MODEL`과 free cloud 기본값 일치 테스트를 추가함 |
| 39 | DONE | channel policy 변경 중 running request 처리 정책이 불명확함 | 동시 실행 정책 일관성, 실패 유형 명확화 | request-start admission snapshot 정책과 회귀 테스트를 추가함 |
| 40 | DONE | provider contribution policy와 guild allow-list scope가 충돌 가능 | 관심사 분리, 정책 단일화 | provider policy는 capability/burden만 소유한다고 어댑터와 테스트로 고정함 |
| 41 | DONE | cancelled job 후 runner residue를 수동 감으로 판정함 | 운영 가시성, 실패 유형 명확화 | `diagnose-central-ops.sh`가 runner busy/listener/worker/conflict를 분리 진단함 |
| 42 | DONE | deploy 성공 후 실제 image SHA/health 확인 경로가 표준화되지 않음 | API 계약 일치, 운영 가시성 | SSH 기반 deployed image/local/public health 진단을 표준화함 |
| 43 | DONE | post-health policy audit의 hard-fail 범위와 repair 경계가 불명확함 | 실패 유형 명확화, 읽기/쓰기 권한 분리 | read-only policy audit 3항목과 repair lane 분리를 추가함 |
| 44 | DONE | Cloudflare SSH 장애와 runner session stuck을 같은 장애처럼 다룸 | 실패 유형 명확화, 환경 설정 분리 | SSH/runner/app health 판정을 스크립트와 runbook으로 분리함 |
| 45 | DONE | deploy runner가 build job까지 잡아 배포 대기 구조를 만듦 | 동시 실행 정책 일관성, 운영 안정성 | build는 GitHub-hosted, deploy만 self-hosted로 분리함 |
| 46 | DONE | GitHub API polling이 rate-limit을 고려하지 않음 | 환경 설정 분리, 실패 유형 명확화 | `gh-run-watch-safe.sh`가 480초 기본 간격과 fast override를 강제함 |
| 47 | DONE | generated RAG index와 코드 PR 변경이 섞일 수 있음 | 생성물 SSOT, 제한적 리팩토링 | `check-rag-generated-boundary.sh`를 docs verifier에 연결함 |
| 48 | DONE | 테스트가 locale 의존 응답 문자열에 고정됨 | API 계약 일치, 회귀 방지 | `check-locale-dependent-tests.py`와 구조 검증 테스트로 전환함 |
| 49 | DONE | Testcontainers residue cleanup이 취소 job에서 보장되지 않음 | 자원 정리, 운영 안정성 | label 기반 cleanup 스크립트와 CI `if: always()` 단계를 추가함 |
| 50 | DONE | 운영 DB audit와 repair/migration 권한 경계가 섞임 | 읽기/쓰기 권한 분리, 서버 권위 | audit SQL에 read-only `PGOPTIONS`와 `ON_ERROR_STOP`을 고정함 |

## Completion Evidence

- `docs/audits/2026-06-27-policy-state-refactor-backlog.md` has 50 numbered items, all `DONE`.
- Current code changes cover action runtime, automatic channel setup, participation policy, routing/guild policy, CI/ops boundaries, i18n, and generated artifact guards.
- Verification commands are recorded in the final session report for the same worktree state.

