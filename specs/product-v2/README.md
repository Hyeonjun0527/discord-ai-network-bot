# Product v2 명세 — 커뮤니티 로컬 AI Provider Pool

이 디렉토리는 **Discord 커뮤니티 로컬 AI Provider Pool** 기능의 기획 명세 모음이다.
구현 로드맵([`docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md`](../../docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md))의 Phase B
(항목 301~674)를 "무엇을·왜·어떻게" 수준에서 정의하며, 설계 결정은 ADR
([`docs/adr/0002-remote-agent-byollm.md`](../../docs/adr/0002-remote-agent-byollm.md),
ADR 0003 Provider Pool 예정)을 따른다.

## 한 줄 정의

> Discord 커뮤니티 구성원들이 각자 감당 가능한 로컬 LLM 자원을 등록하고, 중앙 봇이
> 권한·요청 무게·모델 부담 수준·기여 한도·공정성을 기준으로 요청을 분배하는
> **커뮤니티형 로컬 AI 협동 시스템.**

판매/구매/가격표/수수료/정산/마켓플레이스는 **비-목표**다. 중심 개념은
**기여(contribution)·동의(consent)·수용량(capacity)·가용성(availability)·공정성(fairness).**

## 5분서 체계

| # | 문서 | 역할 | 파일 |
|---|---|---|---|
| 1 | 요구사항 명세서 | 왜 필요하고 무엇을 만족해야 하는가 | `domains/community-provider-pool/requirements.md` |
| 2 | 도메인 모델 명세서 | 어떤 개념/상태/규칙이 존재하는가 | `domains/community-provider-pool/domain-model.md` |
| 3 | 화면 정의서 | 유저가 어떤 화면/명령/메시지를 보는가 | `domains/community-provider-pool/screens.md` |
| 4 | 네비게이션 명세서 | 어떤 흐름으로 이동/상태가 바뀌는가 | `domains/community-provider-pool/navigation.md` |
| 5 | API 명세서 | 봇/에이전트/서버/대시보드가 어떻게 통신하는가 | `domains/community-provider-pool/api.md` |

작성·읽기 순서: 1 → 2 → 3 → 4 → 5. 각 문서는 아래로 ID 를 넘겨 물린다.

```
requirements ──REQ-###──▶ domain-model ──DM-###──▶ screens ──SCR-###──▶ navigation ──FLOW-###──▶ api ──API-###
```

모든 문서 공통 필수 섹션: **문서 목적 · 문서 범위 · 관련 문서 · 추적성 ID.**

## 추적성 ID 규약

| 접두사 | 대상 | 예 |
|---|---|---|
| `REQ-` | 기능/정책/비기능 요구사항 | `REQ-510` 요청 라우팅 |
| `SCN-` | 핵심 시나리오 | `SCN-05` 일반 유저 질문 요청 |
| `DM-E-` | 도메인 엔티티 | `DM-E-Provider` |
| `DM-V-` | 값 객체 | `DM-V-ModelBurdenLevel` |
| `DM-S-` | 상태(머신) | `DM-S-ProviderState` |
| `DM-R-` | 도메인 규칙 | `DM-R-07` light는 heavy에 우선 배정 안 함 |
| `DM-EV-` | 도메인 이벤트 | `DM-EV-ProviderSelected` |
| `SCR-` | 화면/메시지 | `SCR-404` AI 답변 메시지 |
| `FLOW-` | 네비게이션 플로우 | `FLOW-08` 요청 라우팅 흐름 |
| `API-` | API/메시지 타입 | `API-ASK` `/ask`, `API-WS-INFER` |
| `ERR-` | 에러 코드 | `ERR-PROVIDER-OFFLINE` |

ID 번호 부여 원칙: **요구사항 ID 는 목차의 절 번호를 그대로 사용**한다(요구사항 5.10 →
`REQ-510`). 화면/플로우/API 는 문서 내 일련번호. 한 번 부여한 ID 는 재사용/변경하지 않는다.

## 정식 어휘 (Canonical Vocabulary)

5개 문서는 아래 명칭을 **글자 그대로** 사용한다(동의어 금지). 새 개념은 이 README 에 먼저 등록.

### 사용자 유형
- **일반 유저(User)** — `/ask` 로 질문. 프로바이더 PC 에 직접 접근 불가.
- **서버 관리자(Admin)** — 서버 LLM 정책·채널·역할·프로바이더 승인 관리.
- **프로바이더(Provider)** — 자기 PC 로컬 LLM 자원을 커뮤니티에 기여하는 사람.
- **시스템 관리자(Operator)** — 중앙 봇/서버 운영자.

### 모델 부담 수준 (`DM-V-ModelBurdenLevel`)
`light` · `standard` · `heavy` · `restricted` — 가격 등급이 아니라 **처리 부담도**.

### 프로바이더 상태 (`DM-S-ProviderState`, 10)
`unregistered` · `pending` · `approved` · `online_idle` · `online_busy` · `paused` ·
`limited` · `offline` · `unhealthy` · `removed`

### 요청 상태 (`DM-S-RequestState`, 10)
`received` · `policy_checked` · `routing` · `queued` · `sent_to_provider` · `running` ·
`completed` · `failed` · `fallback_running` · `rejected`

### 핵심 엔티티 (도메인 모델 §4)
`Guild` · `GuildPolicy` · `AllowedChannel` · `RolePolicy` · `Provider` · `ProviderApproval` ·
`ProviderSession` · `ProviderCapability` · `ProviderContributionPolicy` · `ModelProfile` ·
`AiRequest` · `RoutingCandidate` · `RoutingDecision` · `RequestExecution` · `UsageLog` ·
`ProviderHealth`

### 도메인 이벤트 (도메인 모델 §10)
`ProviderRegistered` · `ProviderApproved` · `ProviderAgentConnected` ·
`ProviderAgentDisconnected` · `ProviderPaused` · `ProviderResumed` · `AiRequestReceived` ·
`ProviderSelected` · `RequestSentToProvider` · `ProviderResponseReceived` ·
`RequestCompleted` · `RequestFailed` · `FallbackStarted` · `ProviderMarkedUnhealthy`

### API 4종
1. **Discord Command API** (슬래시 명령 인터랙션)
2. **Web Dashboard API** (REST, 관리자)
3. **Provider Agent WebSocket Protocol** (중앙 ↔ 에이전트)
4. **내부 Routing/State API** (중앙 서버 내부)

## 보안·프라이버시 불변식 (모든 문서에서 위반 금지)
- 외부 유저는 프로바이더 PC/Ollama 에 **직접 접근 불가** — 경로는 `봇 → 중앙 서버 →
  인증된 WebSocket → Provider Agent → localhost Ollama` 뿐.
- Provider Agent 는 **outbound 연결만**, inbound 포트 미개방, 임의 shell/파일/URL 금지.
- 인증은 **일회용·단기 만료 토큰** + 세션 heartbeat.
- 질문 내용이 프로바이더 PC 로 전송될 수 있음 → **프라이버시 고지 필수**(모드 A/B/C, 기본 C).

## 로드맵 매핑

| Phase B 차수 | 주제 | 주요 문서 |
|---|---|---|
| 13 | ADR 0003 · 도메인 확정 | domain-model, requirements |
| 14~15 | 데이터 모델 · 부담 수준 | domain-model |
| 16~19 | 등록/승인·세션·정책 | requirements, navigation, api |
| 20~24 | 무게/라우팅/큐/보호 | domain-model, navigation, api |
| 25~27 | 명령어(유저/관리자/프로바이더) | screens, navigation, api |
| 28~29 | 프라이버시·기록 | requirements, screens |
| 30~32 | 보안·테스트·문서 | requirements, api |

## ID 정합 규칙 (SSOT) & 결정사항

5개 문서가 병렬 작성되며 ID 체계가 분기했다(감사 P0/P1). 아래를 **정합의 단일 기준**으로 둔다.

### 각 ID 네임스페이스의 SSOT(정의처)
| 접두사 | 정의처(SSOT) | 참조처(이 ID 를 인용) |
|---|---|---|
| `REQ-` / `SCN-` | requirements.md | screens, navigation, api |
| `DM-*` | domain-model.md | screens, navigation, api |
| `SCR-` | screens.md (§10 인덱스) | navigation, api |
| `FLOW-` | navigation.md | requirements(§10.5), api |
| `API-*` | api.md (§1.3 체계) | screens, navigation |
| `ERR-` | api.md (§13) | screens, navigation |

참조처는 **반드시 정의처의 실제 ID** 를 인용한다. 정의처에 없는 ID 를 만들지 않는다(로드맵
항목번호를 REQ 로 인용 금지 — requirements 의 절번호 ID 만 사용). 번호 통일 규칙:
- `REQ-###` = 요구사항 절번호(5.10→REQ-510). FLOW = 2자리(`FLOW-08`), 16진/3자리 금지 → navigation
  의 FLOW-04x~09x 를 2자리 일련번호로 재정렬하고 requirements §10.5 와 일치시킨다.
- `API-*` = api.md §1.3 의 `API-CMD-* / API-REST-* / API-WS-* / API-INT-*` 만 사용
  (`API-ASK` 같은 약식 인용은 `API-CMD-ASK` 로 치환).

### 상태머신 명칭 규칙
모든 상태머신 ID 는 `…State` 접미사로 끝낸다(엔티티 `DM-E-*` 와 구분):
`DM-S-ProviderState · DM-S-ProviderSessionState · DM-S-AgentConnState · DM-S-RequestState ·
DM-S-ApprovalState · DM-S-ProviderHealthState`. (navigation 의 `DM-S-AgentConn` 등 접미사 누락 인용,
domain-model 의 `DM-S-ProviderHealth` 는 위 명칭으로 교정.)

### P1 모델링 결정 (확정)
- **#8 역할 정책 키**: 저장은 실제 `DM-V-RoleId`(snowflake). `RoleTier`(member/trusted/admin)는
  설정된 role_id 들로부터 파생되는 **표현용 추상값**이며 저장 키가 아니다. api 의 RolePolicy 는
  `role_id → {허용 부담수준, 일일한도}` 로 모델링하고, 응답에 tier 라벨을 덧붙일 수 있다.
- **#9 PrivacyMode**: domain-model §5 에 값 객체 `DM-V-PrivacyMode`(enum
  `A_ANONYMOUS / B_PARTIAL / C_ADMIN_ONLY`, 기본 `C_ADMIN_ONLY`)로 추가. api 는 이를 인용.
- **#10 기여 기록**: `DM-E-UsageLog`(요청자 관점)와 별도로 `DM-E-ContributionLog`(프로바이더
  관점)를 둔다(로드맵 323/324). 화면/네비의 `contribution_log` 인용은 `DM-E-ContributionLog` 로.
- **#11 RequestWeight**: 값 집합 `light / medium / heavy` 로 확정(`DM-V-RequestWeight`).
  필요 모델 부담수준 매핑: light→light, medium→standard, heavy→heavy.

### 기타 보강
- **rate limit**(로드맵 627): requirements §7.1 보안 비기능에 명문화(REQ-701 보강 또는 신규 항목).
- **/help · 쿨다운**(로드맵 546~548): screens 에 `/help` 섹션·쿨다운 안내 화면, navigation 에 흐름 추가.
- **설계 근거**: 5분서가 인용하는 "ADR 0003" 은 `docs/adr/0003-community-provider-pool.md` 로 실재.

## 디렉토리 구조

```
specs/product-v2/
  README.md                    ← 이 파일(백본: 규약·어휘·ID·매핑)
  domains/
    community-provider-pool/
      requirements.md           1. 요구사항 명세서
      domain-model.md           2. 도메인 모델 명세서
      screens.md                3. 화면 정의서
      navigation.md             4. 네비게이션 명세서
      api.md                    5. API 명세서
```
