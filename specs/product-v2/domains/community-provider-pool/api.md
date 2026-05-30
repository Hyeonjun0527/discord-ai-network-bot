# API 명세서 — 커뮤니티 로컬 AI Provider Pool

> 5분서 체계의 5번 문서. 봇·에이전트·중앙 서버·대시보드가 **어떻게 통신하는가**를 통신
> 계약(contract) 수준으로 정의한다. 정식 출처: [`README.md`](../../README.md)(ID 규약·정식
> 어휘·API 4종·에러), [`SOURCE_BRIEF.md`](./SOURCE_BRIEF.md)(특히 §12 명령어·§8 선택 규칙),
> [`docs/adr/0002-remote-agent-byollm.md`](../../../../docs/adr/0002-remote-agent-byollm.md)
> (메시지 프로토콜), [`docs/ROADMAP_REMOTE_AGENT.md`](../../../../docs/ROADMAP_REMOTE_AGENT.md)
> (차수 17 프로토콜·차수 25~27 명령어). 충돌 시 README 규약 + SOURCE_BRIEF 우선.

---

## 1. 문서 개요

### 1.1 목적
중앙 봇·Provider Agent·중앙 서버(라우터/릴레이)·Web Dashboard 사이의 모든 통신을 ID 가
부여된 API 단위로 정의한다. 각 API 는 Method/Protocol, 엔드포인트 또는 메시지 타입, 호출
주체, 인증·권한, Request/Response JSON, 에러, 상태 변화, 추적성 ID 를 갖는다. 이 문서는
로드맵 Phase B(차수 13~32)의 통신 계약을 빠짐없이 뒷받침한다 — 특히 차수 17(프로토콜
확장)과 차수 25~27(명령어)을 직접 구현 가능한 수준으로 규정한다.

### 1.2 API 범위
README §"API 4종" 을 8개 그룹으로 세분한다.

| 그룹 | API 4종 분류 | 호출 주체 | 전송 방식 |
|---|---|---|---|
| §5 Discord Command API | 1. Discord Command API | 일반 유저·관리자·프로바이더 | Discord 슬래시 인터랙션 |
| §6 Guild Settings API | 2. Web Dashboard REST API | 관리자(대시보드·봇 내부) | HTTPS REST |
| §7 Provider Management API | 2. Web Dashboard REST API | 관리자·프로바이더 | HTTPS REST |
| §8 Provider Agent WebSocket Protocol | 3. WebSocket Protocol | Provider Agent ↔ 중앙 릴레이 | `wss` JSON 프레임 |
| §9 Request Routing API | 4. 내부 Routing/State API | 중앙 서버 내부 | in-process 호출 |
| §10 Usage·Log API | 2. REST / 4. 내부 | 관리자·내부 | HTTPS REST |
| §11 Admin Dashboard API | 2. Web Dashboard REST API | 관리자(Operator 포함) | HTTPS REST |
| §12 Health Check API | 2. REST / 4. 내부 | Operator·모니터링 | HTTPS REST / 내부 |

**비-범위**: 판매/구매/가격표/수수료/정산/마켓플레이스 관련 API 는 정의하지 않는다(README
"비-목표").

### 1.3 API ID 규칙
README 추적성 규약(`API-`)을 다음 4종 하위 접두사로 구체화한다.

| 접두사 | 대상 | 예 |
|---|---|---|
| `API-CMD-<NAME>` | Discord 슬래시 명령 | `API-CMD-ASK`(`/ask`), `API-CMD-PROVIDER-JOIN` |
| `API-REST-<영역>-<동작>` | Web Dashboard REST | `API-REST-GUILD-GET`, `API-REST-PROVIDER-APPROVE` |
| `API-WS-<TYPE>` | WebSocket 프레임 | `API-WS-PROVIDER-HELLO`, `API-WS-INFER` |
| `API-INT-<동작>` | 내부 Routing/State | `API-INT-SELECT`, `API-INT-FALLBACK` |

한 번 부여한 ID 는 재사용·변경하지 않는다(README ID 원칙).

### 1.4 관련 문서

| 문서 | 넘겨받는 ID | 본 문서가 참조 |
|---|---|---|
| requirements.md | — | `REQ-###`(절 번호 = ID) |
| domain-model.md | `REQ-###` | `DM-E-*`·`DM-V-*`·`DM-S-*`·`DM-R-*`·`DM-EV-*` |
| screens.md | `DM-###` | `SCR-###` |
| navigation.md | `SCR-###` | `FLOW-###` |
| **api.md(이 문서)** | `FLOW-###` | `API-###` 생성·게시 |
| ADR 0002 | — | WS 프레임 베이스(`auth/infer/result/error/ping/pong/chunk/cancel`) |
| ADR 0003(예정) | — | Provider Pool 결정 |
| ROADMAP_REMOTE_AGENT.md | — | 차수 17·25~27 |

### 1.5 공통 응답 형식
REST(§6·§7·§10·§11·§12) 와 내부 API 의 공통 봉투(envelope)는 다음 표 형식을 따른다. 성공·실패
모두 동일 봉투를 사용한다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `ok` | boolean | 예 | 성공 여부 |
| `data` | object \| null | 성공 시 | 결과 페이로드(API별 정의) |
| `error` | object \| null | 실패 시 | `{ code, message }` — `code` 는 §13 `ERR-` |
| `request_id` | string(`req_*`) | 예 | 상관관계 ID(`DM-V-RequestId`) |
| `trace_id` | string | 아니오 | 분산 추적용(내부) |
| `ts` | string(ISO-8601 UTC) | 예 | 응답 생성 시각 |

성공 예시:
```json
{ "ok": true, "data": { /* ... */ }, "error": null, "request_id": "req_01HZX...", "ts": "2026-05-30T09:10:00Z" }
```
실패 예시:
```json
{ "ok": false, "data": null, "error": { "code": "ERR-PROVIDER-OFFLINE", "message": "선택된 프로바이더가 오프라인입니다." }, "request_id": "req_01HZX...", "ts": "2026-05-30T09:10:00Z" }
```

Discord Command API(§5)는 인터랙션 응답(임베드/ephemeral)으로 표현되므로 위 봉투 대신
`SCR-` 메시지로 사용자에게 노출되며, 내부적으로는 동일한 `ERR-` 코드와 `request_id` 를 갖는다.
WebSocket(§8)은 프레임 자체가 계약이므로 봉투 대신 §8 프레임 스키마를 따른다.

---

## 2. API 그룹

### 2.1 Discord Command (`API-CMD-*`)
일반 유저·관리자·프로바이더가 쓰는 슬래시 명령. SOURCE_BRIEF §12 의 모든 명령을 포함한다.
상세는 §5. 권한 가드는 §3.

### 2.2 Guild Settings (`API-REST-GUILD-*`)
서버 LLM 정책: 허용/금지 채널, 역할별 모델 부담 수준 정책, 프로바이더 승인 방식, Privacy
Notice 모드. 상세는 §6. 도메인: `DM-E-Guild`·`DM-E-GuildPolicy`·`DM-E-AllowedChannel`·
`DM-E-RolePolicy`.

### 2.3 Provider Management (`API-REST-PROVIDER-*`)
프로바이더 등록 요청·승인·거절·제거·목록·상세·기여 정책·모델·한도·pause/resume. 상세는 §7.
도메인: `DM-E-Provider`·`DM-E-ProviderApproval`·`DM-E-ProviderCapability`·
`DM-E-ProviderContributionPolicy`.

### 2.4 Provider Agent WebSocket (`API-WS-*`)
중앙 릴레이 ↔ Provider Agent 의 `wss` JSON 프레임. ADR 0002 의 8 프레임을 Provider Pool 용
`provider_hello`·`provider_status` 등으로 확장. 상세는 §8. 보안 불변식(outbound only,
임의 URL/shell 금지) 명시.

### 2.5 Request Routing (`API-INT-*`)
중앙 서버 내부의 요청 생성→무게 계산→후보 조회→필터→선택→전송→수신→fallback→취소→
상태 조회. 상세는 §9. SOURCE_BRIEF §7 의 19단계와 §8 의 10기준을 구현한다.

### 2.6 Usage·Log (`API-REST-USAGE-*` / `API-INT-LOG-*`)
사용자·Guild·Provider 사용량·기여량 및 요청/실패/health 로그 조회. 상세는 §10. 도메인:
`DM-E-UsageLog`·`DM-E-ProviderHealth`.

### 2.7 Admin Dashboard (`API-REST-ADMIN-*`)
Operator/관리자용 서버 목록·대시보드·Pool 상태·Provider 상세·라우팅 로그·정책 변경 이력.
상세는 §11.

### 2.8 Health Check (`API-REST-HEALTH-*` / `API-INT-HEALTH-*`)
중앙 서버·WS 릴레이·Provider Session·Ollama 연결·모델 가용성 점검. 상세는 §12.

---

## 3. 인증·인가

### 3.1 Discord 사용자 인증
Discord Command API(§5)는 Discord 인터랙션 서명으로 신원이 보장된다. 봇은 `interaction.user.id`
(`DM-V-UserId`)·`interaction.guild_id`(`DM-V-GuildId`)·`interaction.channel_id`(`DM-V-ChannelId`)
를 신뢰 입력으로 사용한다. Web Dashboard REST(§6·§7·§11)는 Discord OAuth2 로 발급한 세션
쿠키 또는 `Authorization: Bearer <dashboard_jwt>` 를 사용한다.

### 3.2 관리자 권한
**서버 관리자(Admin)** 판정: Discord `Manage Guild` 권한 보유 또는 서버 정책의 `admin_role`
보유. 관리자 전용 명령/엔드포인트(`/llm-settings`, `/llm-allow-channel`, `/provider-approve`,
`/provider-remove`, §6·§7 의 변경계, §11 전체)는 이 판정을 통과해야 한다. 실패 시
`ERR-PERMISSION-DENIED`(§13.1).

### 3.3 Provider 등록 토큰
`/provider-join` 승인(`API-REST-PROVIDER-APPROVE`) 시 **일회용·단기 만료 페어링 토큰**을
발급해 프로바이더에게 DM 한다(README 보안 불변식). 토큰은 평문 미저장(해시만 저장), TTL
경과 또는 1회 소비 시 폐기, 상수시간 비교(ROADMAP 차수 6 토큰 정책 재사용). owner 바인딩:
`(provider_id, guild_id)`.

### 3.4 Agent 세션 인증
Provider Agent 는 `wss` 연결 직후 첫 프레임으로 §8.2 `provider_hello`(=확장 `auth`)를 보내야
한다. 릴레이는 토큰을 검증해 owner 를 확정하고 `DM-E-ProviderSession`(`DM-S-ProviderState`)을
생성한다. 검증 실패 시 §8.3 `auth_err` 후 연결 종료(`ERR-AGENT-AUTH-FAILED`).

### 3.5 WebSocket 인증
- 전송은 **`wss`(TLS) 강제** — 평문 `ws` 금지(ADR 0002 트레이드오프).
- 연결 직후 N초(설정 `agent_token_ttl`/핸드셰이크 타임아웃) 내 첫 프레임이 인증이 아니면
  강제 종료.
- 세션 유지는 §8.6 heartbeat(`ping`/`pong`). `relay_heartbeat_seconds` 초과 미수신 시 좀비
  연결로 간주해 `offline` 전이 + 연결 정리.
- 동일 owner 중복 연결 시 이전 연결을 graceful close(이전 축출).

### 3.6 권한 실패 응답
공통 형식(§1.5)으로 반환. 명령은 ephemeral 임베드(`SCR-` 거절 메시지)로 노출.

```json
{ "ok": false, "data": null,
  "error": { "code": "ERR-PERMISSION-DENIED", "message": "이 작업에는 서버 관리자 권한이 필요합니다." },
  "request_id": "req_...", "ts": "2026-05-30T09:10:00Z" }
```

---

## 4. 공통 데이터 타입
README 정식 어휘(값 객체·상태·엔티티)와 글자 그대로 일치시킨다.

| # | 타입 | 표현 | 비고 / 도메인 |
|---|---|---|---|
| 4.1 | `GuildId` | string(snowflake) | `DM-V-GuildId`, Discord 서버 단위(`guild_id`) |
| 4.2 | `UserId` | string(snowflake) | `DM-V-UserId`, 일반 유저/관리자/프로바이더 식별 |
| 4.3 | `ChannelId` | string(snowflake) | `DM-V-ChannelId`, 허용/금지 채널 판정 |
| 4.4 | `ProviderId` | string(`prv_*`) | `DM-E-Provider` 식별자 |
| 4.5 | `RequestId` | string(`req_*`) | `DM-V-RequestId`, 상관관계·로그 키 |
| 4.6 | `ModelName` | string | 예 `llama3:8b`, `qwen32b`(SOURCE_BRIEF §5) |
| 4.7 | `ModelBurdenLevel` | enum | `DM-V-ModelBurdenLevel`: `light`·`standard`·`heavy`·`restricted` |
| 4.8 | `ProviderState` | enum(10) | `DM-S-ProviderState`: `unregistered`·`pending`·`approved`·`online_idle`·`online_busy`·`paused`·`limited`·`offline`·`unhealthy`·`removed` |
| 4.9 | `RequestState` | enum(10) | `DM-S-RequestState`: `received`·`policy_checked`·`routing`·`queued`·`sent_to_provider`·`running`·`completed`·`failed`·`fallback_running`·`rejected` |
| 4.10 | `RoleId` | string(snowflake) | `DM-V-RoleId` — RolePolicy 의 **저장 키**. 정책은 `role_id → {허용 부담수준, 일일한도}` 로 모델링(README §P1 #8) |
| 4.10b | `RoleTier` | enum(파생 표현값) | `member`(일반)·`trusted`(신뢰 멤버)·`admin`(관리자) — `DM-V-RoleTier`. role_id 집합에서 파생되는 **표현용 라벨**(저장 키 아님), 응답에 덧붙임 |
| 4.11 | `PrivacyMode` | enum | `DM-V-PrivacyMode`: `A_ANONYMOUS`(익명)·`B_PARTIAL`(부분 공개)·`C_ADMIN_ONLY`(관리자만, 기본·추천) — SOURCE_BRIEF §10, README §P1 #9 |
| 4.12 | `ErrorCode` | enum | §13 의 `ERR-*` 값 집합 |

보조 타입: `Capability { model: ModelName, burden: ModelBurdenLevel, max_context: int,
est_speed_tps: float }`(`DM-E-ProviderCapability`); `ContributionPolicy { model, allowed_roles:
RoleId[], allowed_channels: ChannelId[], requester_scope: enum(all/trusted/admin),
daily_limit: int, max_concurrency: int, max_seconds: int, long_prompt_allowed: bool,
prompt_char_limit: int }`(`DM-E-ProviderContributionPolicy`; `allowed_roles` 는 `DM-V-RoleId`
저장, tier 라벨은 표현값); `Usage { prompt_tokens, completion_tokens }`.

---

## 5. Discord Command API
모든 명령은 §14 의 13개 필드를 채운다. 공통: **Protocol** = Discord Interaction(Application
Command), **호출 주체** = Discord 사용자, **인증** = §3.1, 응답은 ephemeral 임베드(`SCR-`)이며
내부 실패는 §13 `ERR-` 로 매핑된다. SOURCE_BRIEF §12 의 명령 전부를 포함한다.

### 5.1 `/ask` — `API-CMD-ASK`
- **Method/Protocol**: Discord Interaction
- **Endpoint·메시지 타입**: `/ask question:<str> [model_level:<ModelBurdenLevel>]`
- **설명**: 일반 유저가 커뮤니티 Provider Pool 에 질문. 내부적으로 §9 라우팅을 트리거.
- **호출 주체**: 일반 유저
- **인증·권한**: §3.1. 채널/역할 정책 통과 필요(§9.4).
- **Request**
```json
{ "type": "API-CMD-ASK", "guild_id": "G1", "user_id": "U1", "channel_id": "C1",
  "question": "이 코드 리뷰해줘 ...", "model_level": null }
```
- **Response**(처리 후 답변 메시지 `SCR-404`)
```json
{ "ok": true, "data": { "request_id": "req_1", "answer": "...", "model_level": "standard",
  "privacy_notice": "커뮤니티 로컬 AI 풀에서 처리됨. 모델 수준: standard" }, "error": null,
  "request_id": "req_1", "ts": "..." }
```
- **Error Response**: `ERR-CHANNEL-NOT-ALLOWED`·`ERR-PERMISSION-DENIED`·`ERR-LIMIT-EXCEEDED`·
  `ERR-NO-PROVIDER`·`ERR-PROVIDER-OFFLINE`·`ERR-MODEL-UNSUPPORTED`·`ERR-TIMEOUT`·`ERR-FALLBACK-FAILED`
- **상태 변화**: `DM-S-RequestState` `received`→…→`completed`/`failed`/`rejected`; 이벤트
  `DM-EV-AiRequestReceived`→`DM-EV-ProviderSelected`→`DM-EV-RequestSentToProvider`→
  `DM-EV-ProviderResponseReceived`→`DM-EV-RequestCompleted`
- **관련 REQ/SCR/DM**: `REQ-510`(라우팅), `SCR-401`(입력)·`SCR-404`(답변), `DM-E-AiRequest`

### 5.2 `/models` — `API-CMD-MODELS`
- **Method/Protocol**: Discord Interaction · **Endpoint**: `/models`
- **설명**: 이 서버에서 호출자의 역할로 현재 사용 가능한 모델 부담 수준·예시 모델 요약.
- **호출 주체**: 일반 유저 · **인증·권한**: §3.1
- **Request**: `{ "type": "API-CMD-MODELS", "guild_id": "G1", "user_id": "U1" }`
- **Response**
```json
{ "ok": true, "data": { "allowed_levels": ["light","standard"],
  "examples": { "light": ["llama3:8b"], "standard": ["mistral:7b"] }, "your_tier": "trusted" },
  "error": null, "request_id": "req_2", "ts": "..." }
```
- **Error Response**: `ERR-CHANNEL-NOT-ALLOWED`
- **상태 변화**: 없음(읽기) · **관련**: `REQ-509`(부담수준 분류)·`REQ-504`(역할 허용), `SCR-407`, `DM-E-ModelProfile`

### 5.3 `/my-usage` — `API-CMD-MY-USAGE`
- **Protocol**: Discord Interaction · **Endpoint**: `/my-usage`
- **설명**: 오늘 내 요청 수·역할 한도 잔여 표시(데이터 소스 §10.1).
- **호출 주체**: 일반 유저 · **인증**: §3.1
- **Request**: `{ "type": "API-CMD-MY-USAGE", "guild_id": "G1", "user_id": "U1" }`
- **Response**: `{ "ok": true, "data": { "today_count": 7, "daily_limit": 30, "remaining": 23 }, "error": null, "request_id":"req_3","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`(길드 밖 호출)
- **상태 변화**: 없음 · **관련**: `REQ-513`(사용량 기록), `SCR-408`, `DM-E-UsageLog`

### 5.4 `/privacy` — `API-CMD-PRIVACY`
- **Protocol**: Discord Interaction · **Endpoint**: `/privacy`
- **설명**: 서버의 Privacy Notice(모드 A/B/C)와 민감정보 금지 고지를 표시(SOURCE_BRIEF §10).
- **호출 주체**: 일반 유저 · **인증**: §3.1
- **Request**: `{ "type": "API-CMD-PRIVACY", "guild_id": "G1", "user_id": "U1" }`
- **Response**: `{ "ok": true, "data": { "privacy_mode": "C_ADMIN_ONLY", "notice": "이 서버는 커뮤니티 로컬 AI Provider Pool 을 사용합니다. ... 민감 정보는 입력하지 마세요." }, "error": null, "request_id":"req_4","ts":"..." }`
- **Error Response**: 없음 · **상태 변화**: 없음 · **관련**: `REQ-514`(프라이버시 안내), `SCR-409`, `DM-V-PrivacyMode`

### 5.4b `/help` — `API-CMD-HELP`
- **Protocol**: Discord Interaction · **Endpoint**: `/help`
- **설명**: 호출자 권한에 맞는 명령 카탈로그·사용법·풀/프라이버시 안내 표시(로드맵 547).
- **호출 주체**: 모든 사용자 · **인증**: §3.1
- **Request**: `{ "type": "API-CMD-HELP", "guild_id": "G1", "user_id": "U1" }`
- **Response**: `{ "ok": true, "data": { "commands": ["/ask","/models","/my-usage","/privacy", "..."], "your_role": "user" }, "error": null, "request_id":"req_4b","ts":"..." }`
- **Error Response**: `ERR-RATE-LIMITED`(쿨다운) · **상태 변화**: 없음 · **관련**: `REQ-501`~`REQ-515`, `SCR-410`, `DM-E-GuildPolicy`

### 5.5 `/llm-settings` — `API-CMD-LLM-SETTINGS`
- **Protocol**: Discord Interaction · **Endpoint**: `/llm-settings`(관리자 패널 진입)
- **설명**: 서버 LLM 정책 종합 패널. 내부적으로 §6.1/§6.2 호출. 버튼/셀렉트로 §6 의
  하위 설정으로 분기.
- **호출 주체**: 관리자 · **인증·권한**: §3.2
- **Request**: `{ "type": "API-CMD-LLM-SETTINGS", "guild_id": "G1", "user_id": "U_admin" }`
- **Response**: `{ "ok": true, "data": { "policy": { /* §6.1 GuildPolicy 스냅샷 */ } }, "error": null, "request_id":"req_5","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`
- **상태 변화**: 없음(읽기 진입) · **관련**: `REQ-502`(서버 설정), `SCR-501`, `DM-E-GuildPolicy`

### 5.6 `/providers` — `API-CMD-PROVIDERS`
- **Protocol**: Discord Interaction · **Endpoint**: `/providers`
- **설명**: 관리자가 풀의 프로바이더 목록·상태·기여량 확인(§7.5 호출).
- **호출 주체**: 관리자 · **인증·권한**: §3.2
- **Request**: `{ "type": "API-CMD-PROVIDERS", "guild_id": "G1", "user_id": "U_admin" }`
- **Response**
```json
{ "ok": true, "data": { "providers": [
  { "provider_id": "prv_1", "owner_user_id": "U2", "state": "online_idle",
    "models": ["llama3:8b"], "today_contributions": 12 } ] },
  "error": null, "request_id":"req_6","ts":"..." }
```
- **Error Response**: `ERR-PERMISSION-DENIED`
- **상태 변화**: 없음 · **관련**: `REQ-515`(관리자 모니터링), `SCR-504`, `DM-E-Provider`

### 5.7 `/provider-join` — `API-CMD-PROVIDER-JOIN`
- **Protocol**: Discord Interaction · **Endpoint**: `/provider-join [models:<csv>]`
- **설명**: 프로바이더 등록 요청. §7.1 을 트리거해 `pending` 생성. 승인 시 §3.3 토큰 DM.
- **호출 주체**: 프로바이더(지원자) · **인증·권한**: §3.1 + 프로바이더 동의 고지(프롬프트가
  내 PC 로 전송됨, ROADMAP 368)
- **Request**: `{ "type": "API-CMD-PROVIDER-JOIN", "guild_id": "G1", "user_id": "U2", "models": ["llama3:8b"] }`
- **Response**: `{ "ok": true, "data": { "provider_id": "prv_1", "state": "pending", "approval_mode": "manual" }, "error": null, "request_id":"req_7","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-PROVIDER-ALREADY-REGISTERED`
- **상태 변화**: `DM-S-ProviderState` `unregistered`→`pending`; 이벤트 `DM-EV-ProviderRegistered`
- **관련**: `REQ-505`(프로바이더 등록), `SCR-601`, `DM-E-Provider`·`DM-E-ProviderApproval`

### 5.8 `/provider-status` — `API-CMD-PROVIDER-STATUS`
- **Protocol**: Discord Interaction · **Endpoint**: `/provider-status`
- **설명**: 프로바이더 본인의 연결 상태·부하·모델·한도 잔여·기여량 종합(§7.6 + §8.4 스냅샷).
- **호출 주체**: 프로바이더(본인) · **인증·권한**: §3.1 + 본인 소유권(ROADMAP 577)
- **Request**: `{ "type": "API-CMD-PROVIDER-STATUS", "guild_id": "G1", "user_id": "U2" }`
- **Response**
```json
{ "ok": true, "data": { "provider_id": "prv_1", "state": "online_idle", "load": "idle",
  "battery": "charging", "models": ["llama3:8b","mistral:7b"], "max_concurrency": 1,
  "remaining_daily_requests": 42, "today_contributions": 8 }, "error": null,
  "request_id":"req_8","ts":"..." }
```
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: 없음 · **관련**: `REQ-515`(상태 조회)·`REQ-603`(보호), `SCR-608`, `DM-E-ProviderSession`·`DM-E-ProviderHealth`

### 5.9 `/provider-pause` — `API-CMD-PROVIDER-PAUSE`
- **Protocol**: Discord Interaction · **Endpoint**: `/provider-pause`
- **설명**: 요청 수신 중단. 즉시 라우팅 후보에서 제외(§9.4). 진행 중 요청은 보호 정책 따름.
- **호출 주체**: 프로바이더(본인) · **인증·권한**: §3.1 + 본인 소유권
- **Request**: `{ "type": "API-CMD-PROVIDER-PAUSE", "guild_id":"G1", "user_id":"U2" }`
- **Response**: `{ "ok": true, "data": { "provider_id": "prv_1", "state": "paused" }, "error": null, "request_id":"req_9","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: `online_idle`/`online_busy`→`paused`; 이벤트 `DM-EV-ProviderPaused`. 릴레이는
  §8.10 `pause` 프레임을 Agent 로 송신.
- **관련**: `REQ-603`(프로바이더 보호), `SCR-609`, `DM-S-ProviderState`

### 5.10 `/provider-resume` — `API-CMD-PROVIDER-RESUME`
- **Protocol**: Discord Interaction · **Endpoint**: `/provider-resume`
- **설명**: pause 해제, 라우팅 후보 재편입.
- **호출 주체**: 프로바이더(본인) · **인증·권한**: §3.1 + 본인 소유권
- **Request**: `{ "type": "API-CMD-PROVIDER-RESUME", "guild_id":"G1", "user_id":"U2" }`
- **Response**: `{ "ok": true, "data": { "provider_id": "prv_1", "state": "online_idle" }, "error": null, "request_id":"req_10","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`, `ERR-PROVIDER-OFFLINE`(Agent 미연결 시 resume 불가)
- **상태 변화**: `paused`→`online_idle`; 이벤트 `DM-EV-ProviderResumed`. §8.10 `resume` 프레임 송신.
- **관련**: `REQ-603`(프로바이더 보호), `SCR-610`, `DM-S-ProviderState`

### 5.11 `/provider-leave` — `API-CMD-PROVIDER-LEAVE`
- **Protocol**: Discord Interaction · **Endpoint**: `/provider-leave`
- **설명**: 프로바이더 본인이 풀에서 이탈. 세션 종료·토큰 폐기·라우팅 제외.
- **호출 주체**: 프로바이더(본인) · **인증·권한**: §3.1 + 본인 소유권
- **Request**: `{ "type": "API-CMD-PROVIDER-LEAVE", "guild_id":"G1", "user_id":"U2" }`
- **Response**: `{ "ok": true, "data": { "provider_id": "prv_1", "state": "removed" }, "error": null, "request_id":"req_11","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: `*`→`removed`; 이벤트 `DM-EV-ProviderAgentDisconnected`. §8.11 종료 프레임 송신.
- **관련**: `REQ-603`(프로바이더 보호·이탈), `SCR-611`, `DM-S-ProviderState`

### 5.12 관리자 채널/역할/승인 명령 (`API-CMD-LLM-ALLOW-CHANNEL` 외)
SOURCE_BRIEF §12 의 나머지 관리자 명령. 각 명령은 대응 REST(§6)를 호출하는 얇은 래퍼이며,
권한은 모두 §3.2.

| 명령 | API ID | 위임 REST | 상태/이벤트 | 관련 |
|---|---|---|---|---|
| `/llm-allow-channel` | `API-CMD-LLM-ALLOW-CHANNEL` | §6.3 `API-REST-GUILD-CHANNEL-ADD` | `DM-E-AllowedChannel` 추가 | `REQ-503`,`SCR-502` |
| `/llm-deny-channel` | `API-CMD-LLM-DENY-CHANNEL` | §6.4 `API-REST-GUILD-CHANNEL-REMOVE` | `DM-E-AllowedChannel` 제거 | `REQ-503`,`SCR-502` |
| `/llm-role-policy` | `API-CMD-LLM-ROLE-POLICY` | §6.5/§6.6 | `DM-E-RolePolicy` 변경 | `REQ-504`,`SCR-503` |
| `/provider-approve` | `API-CMD-PROVIDER-APPROVE` | §7.2 `API-REST-PROVIDER-APPROVE` | `pending`→`approved`, `DM-EV-ProviderApproved`, 토큰 DM | `REQ-506`,`SCR-504` |
| `/provider-remove` | `API-CMD-PROVIDER-REMOVE` | §7.4 `API-REST-PROVIDER-REMOVE` | `*`→`removed` | `REQ-515`,`SCR-506` |

예시(`/provider-approve`):
```json
// Request
{ "type": "API-CMD-PROVIDER-APPROVE", "guild_id":"G1", "user_id":"U_admin", "provider_id":"prv_1" }
// Response
{ "ok": true, "data": { "provider_id":"prv_1", "state":"approved",
  "join_token_sent": true, "token_ttl_seconds": 600 }, "error": null, "request_id":"req_12","ts":"..." }
```
Error Response 공통: `ERR-PERMISSION-DENIED`, `ERR-PROVIDER-NOT-FOUND`.

### 5.13 프로바이더 기여 정책 명령 (`API-CMD-PROVIDER-MODELS` 외)
SOURCE_BRIEF §12 프로바이더 명령 잔여분. 본인 소유권(§3.1) 필요. 대응 REST 는 §7.

| 명령 | API ID | 위임 REST | 변경 도메인 | 관련 |
|---|---|---|---|---|
| `/provider-models` | `API-CMD-PROVIDER-MODELS` | §7.8 `API-REST-PROVIDER-MODELS-UPDATE` | `DM-E-ProviderCapability` | `REQ-507`,`SCR-605` |
| `/provider-limit` | `API-CMD-PROVIDER-LIMIT` | §7.9 `API-REST-PROVIDER-LIMIT-UPDATE` | `DM-E-ProviderContributionPolicy`(daily/concurrency/max_seconds) | `REQ-507`,`SCR-607` |
| `/provider-scope` | `API-CMD-PROVIDER-SCOPE` | §7.7 `API-REST-PROVIDER-POLICY-UPDATE` | `DM-E-ProviderContributionPolicy`(allowed_roles/channels) | `REQ-507`,`SCR-606` |

예시(`/provider-limit`):
```json
// Request
{ "type":"API-CMD-PROVIDER-LIMIT", "guild_id":"G1", "user_id":"U2",
  "model":"qwen32b", "daily_limit":20, "max_concurrency":1, "max_seconds":60 }
// Response
{ "ok": true, "data": { "provider_id":"prv_1", "policy_updated": true }, "error": null, "request_id":"req_13","ts":"..." }
```
정책 변경은 §7 에서 즉시 세션에 반영(ROADMAP 429/579). Error: `ERR-PROVIDER-NOT-FOUND`,
`ERR-POLICY-INVALID`.

---

## 6. Guild Settings API (`API-REST-GUILD-*`)
공통: **Protocol** = HTTPS REST, **호출 주체** = 관리자(대시보드) 또는 §5.5/§5.12 명령 위임,
**인증·권한** = §3.2, 응답 봉투 = §1.5. 베이스 경로 `/api/v1/guilds/{guild_id}`.

### 6.1 정책 조회 — `API-REST-GUILD-GET`
- **Method**: `GET /api/v1/guilds/{guild_id}/policy`
- **설명**: `DM-E-GuildPolicy` 전체 스냅샷 조회.
- **Request**: 경로 파라미터 `guild_id`. body 없음.
- **Response**
```json
{ "ok": true, "data": { "guild_id":"G1", "allowed_channels":["C1","C2"], "denied_channels":[],
  "role_policies": [ { "role_id":"R1","tier":"member","levels":["light"],"daily_limit":20 },
    { "role_id":"R2","tier":"trusted","levels":["light","standard"],"daily_limit":30 },
    { "role_id":"R3","tier":"admin","levels":["light","standard","heavy"],"daily_limit":null } ],
  "provider_approval_mode":"manual", "privacy_mode":"C_ADMIN_ONLY" }, "error": null, "request_id":"req_g1","ts":"..." }
```
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-GUILD-NOT-FOUND`
- **상태 변화**: 없음 · **관련**: `REQ-502`, `SCR-501`, `DM-E-GuildPolicy`

### 6.2 정책 수정 — `API-REST-GUILD-UPDATE`
- **Method**: `PATCH /api/v1/guilds/{guild_id}/policy`
- **설명**: 정책 필드 부분 수정. 검증 통과분만 반영(존재하지 않는 역할/채널 거부).
- **Request**: `{ "provider_approval_mode":"auto", "privacy_mode":"B_PARTIAL" }`
- **Response**: `{ "ok": true, "data": { "updated": ["provider_approval_mode","privacy_mode"] }, "error": null, "request_id":"req_g2","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-POLICY-INVALID`
- **상태 변화**: 없음(정책 갱신, audit 기록) · **관련**: `REQ-502`, `SCR-501`, `DM-E-GuildPolicy`

### 6.3 허용 채널 추가 — `API-REST-GUILD-CHANNEL-ADD`
- **Method**: `POST /api/v1/guilds/{guild_id}/allowed-channels`
- **설명**: `DM-E-AllowedChannel` 추가(`/llm-allow-channel` 위임).
- **Request**: `{ "channel_id":"C3" }`
- **Response**: `{ "ok": true, "data": { "allowed_channels":["C1","C2","C3"] }, "error": null, "request_id":"req_g3","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-POLICY-INVALID`(존재하지 않는 채널)
- **상태 변화**: 없음 · **관련**: `REQ-503`, `SCR-502`, `DM-E-AllowedChannel`

### 6.4 허용 채널 제거 — `API-REST-GUILD-CHANNEL-REMOVE`
- **Method**: `DELETE /api/v1/guilds/{guild_id}/allowed-channels/{channel_id}`
- **설명**: 허용 채널 제거(`/llm-deny-channel`).
- **Request**: 경로 파라미터.
- **Response**: `{ "ok": true, "data": { "allowed_channels":["C1","C2"] }, "error": null, "request_id":"req_g4","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-CHANNEL-NOT-ALLOWED`(미등록 채널)
- **상태 변화**: 없음 · **관련**: `REQ-503`, `SCR-502`, `DM-E-AllowedChannel`

### 6.5 역할 정책 조회 — `API-REST-GUILD-ROLE-GET`
- **Method**: `GET /api/v1/guilds/{guild_id}/role-policies`
- **설명**: 역할별 허용 부담 수준·일일 한도 조회. **저장 키는 `role_id`(`DM-V-RoleId`)**, `tier`
  는 파생 표현 라벨(README §P1 #8).
- **Request**: 경로 파라미터.
- **Response**: `{ "ok": true, "data": { "role_policies":[ {"role_id":"R1","tier":"member","levels":["light"],"daily_limit":20} ] }, "error": null, "request_id":"req_g5","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`
- **상태 변화**: 없음 · **관련**: `REQ-504`, `SCR-503`, `DM-E-RolePolicy`·`DM-V-RoleId`

### 6.6 역할 정책 수정 — `API-REST-GUILD-ROLE-UPDATE`
- **Method**: `PUT /api/v1/guilds/{guild_id}/role-policies/{role_id}`
- **설명**: 한 역할(`DM-V-RoleId`)의 허용 부담 수준·일일 한도 설정(`/llm-role-policy`). 키는
  `role_id`, 응답에 tier 라벨을 덧붙일 수 있다(저장 키 아님).
- **Request**: `{ "levels":["light","standard"], "daily_limit":30 }`
- **Response**: `{ "ok": true, "data": { "role_id":"R2", "tier":"trusted", "levels":["light","standard"], "daily_limit":30 }, "error": null, "request_id":"req_g6","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-POLICY-INVALID`
- **상태 변화**: 없음 · **관련**: `REQ-504`, `SCR-503`, `DM-E-RolePolicy`·`DM-V-RoleId`·`DM-V-ModelBurdenLevel`

### 6.7 승인 방식 설정 — `API-REST-GUILD-APPROVAL-SET`
- **Method**: `PUT /api/v1/guilds/{guild_id}/provider-approval-mode`
- **설명**: 프로바이더 등록 승인 방식(`auto`/`manual`) 설정. `manual` 시 §7.2 관리자 승인 필요.
- **Request**: `{ "mode":"manual" }`
- **Response**: `{ "ok": true, "data": { "provider_approval_mode":"manual" }, "error": null, "request_id":"req_g7","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-POLICY-INVALID`
- **상태 변화**: 신규 등록의 초기 상태 결정(`pending` vs 즉시 `approved`) · **관련**: `REQ-515`, `SCR-501`, `DM-E-GuildPolicy`

### 6.8 Privacy Notice 설정 — `API-REST-GUILD-PRIVACY-SET`
- **Method**: `PUT /api/v1/guilds/{guild_id}/privacy-mode`
- **설명**: Privacy 표시 모드 `A_ANONYMOUS`/`B_PARTIAL`/`C_ADMIN_ONLY` 설정(기본·추천
  `C_ADMIN_ONLY`, `DM-V-PrivacyMode`). `/privacy`·`/ask` 응답 고지에 반영.
- **Request**: `{ "mode":"C_ADMIN_ONLY" }`
- **Response**: `{ "ok": true, "data": { "privacy_mode":"C_ADMIN_ONLY", "notice_preview":"커뮤니티 로컬 AI 풀에서 처리됨. 모델 수준: standard" }, "error": null, "request_id":"req_g8","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-POLICY-INVALID`
- **상태 변화**: 없음 · **관련**: `REQ-514`(프라이버시 안내), `SCR-510`(관리자 프라이버시 정책 설정), `DM-V-PrivacyMode`

---

## 7. Provider Management API (`API-REST-PROVIDER-*`)
공통: **Protocol** = HTTPS REST, **인증·권한** = 등록/승인/거절/제거/목록/상세는 §3.2(관리자),
기여 정책/모델/한도/pause·resume 는 본인 소유권(§3.1) 또는 관리자. 봉투 = §1.5. 베이스 경로
`/api/v1/guilds/{guild_id}/providers`.

### 7.1 등록 요청 — `API-REST-PROVIDER-REGISTER`
- **Method**: `POST /api/v1/guilds/{guild_id}/providers`
- **설명**: `/provider-join` 위임. `pending`(또는 승인 자동 시 `approved`) 생성. 중복 등록 거부.
- **호출 주체**: 프로바이더 지원자 · **인증**: §3.1
- **Request**: `{ "owner_user_id":"U2", "models":["llama3:8b"] }`
- **Response**: `{ "ok": true, "data": { "provider_id":"prv_1", "state":"pending" }, "error": null, "request_id":"req_p1","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-ALREADY-REGISTERED`, `ERR-PERMISSION-DENIED`
- **상태 변화**: `unregistered`→`pending`; `DM-EV-ProviderRegistered` · **관련**: `REQ-505`, `SCR-601`, `DM-E-Provider`

### 7.2 승인 — `API-REST-PROVIDER-APPROVE`
- **Method**: `POST /api/v1/guilds/{guild_id}/providers/{provider_id}/approve`
- **설명**: 관리자 승인. 일회용 Agent 토큰 발급·DM(§3.3). owner 바인딩.
- **호출 주체**: 관리자 · **인증**: §3.2
- **Request**: body 없음(경로 파라미터).
- **Response**: `{ "ok": true, "data": { "provider_id":"prv_1", "state":"approved", "token_ttl_seconds":600 }, "error": null, "request_id":"req_p2","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: `pending`→`approved`; `DM-EV-ProviderApproved` · **관련**: `REQ-506`, `SCR-505`, `DM-E-ProviderApproval`

### 7.3 거절 — `API-REST-PROVIDER-REJECT`
- **Method**: `POST /api/v1/guilds/{guild_id}/providers/{provider_id}/reject`
- **설명**: 관리자 거절. `pending` 종료(`removed` 처리), 토큰 미발급.
- **호출 주체**: 관리자 · **인증**: §3.2
- **Request**: `{ "reason":"중복 등록" }`(선택)
- **Response**: `{ "ok": true, "data": { "provider_id":"prv_1", "state":"removed" }, "error": null, "request_id":"req_p3","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: `pending`→`removed` · **관련**: `REQ-506`, `SCR-505`, `DM-E-ProviderApproval`

### 7.4 제거 — `API-REST-PROVIDER-REMOVE`
- **Method**: `DELETE /api/v1/guilds/{guild_id}/providers/{provider_id}`
- **설명**: 관리자가 풀에서 제거(`/provider-remove`). 활성 세션 강제 종료.
- **호출 주체**: 관리자 · **인증**: §3.2
- **Request**: 경로 파라미터.
- **Response**: `{ "ok": true, "data": { "provider_id":"prv_1", "state":"removed" }, "error": null, "request_id":"req_p4","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: `*`→`removed`; `DM-EV-ProviderAgentDisconnected`(세션 있을 때). §8.11 종료 프레임 송신.
- **관련**: `REQ-515`, `SCR-506`, `DM-E-Provider`

### 7.5 목록 — `API-REST-PROVIDER-LIST`
- **Method**: `GET /api/v1/guilds/{guild_id}/providers`
- **설명**: 풀 목록·상태·기여량(`/providers`). `state` 쿼리 필터 지원.
- **호출 주체**: 관리자 · **인증**: §3.2
- **Request**: `?state=online_idle`(선택)
- **Response**: `{ "ok": true, "data": { "providers":[ {"provider_id":"prv_1","state":"online_idle","models":["llama3:8b"],"today_contributions":12} ] }, "error": null, "request_id":"req_p5","ts":"..." }`
- **Error Response**: `ERR-PERMISSION-DENIED`
- **상태 변화**: 없음 · **관련**: `REQ-515`, `SCR-504`, `DM-E-Provider`

### 7.6 상세 — `API-REST-PROVIDER-GET`
- **Method**: `GET /api/v1/guilds/{guild_id}/providers/{provider_id}`
- **설명**: capability·기여 정책·상태·기여량 상세(관리자 또는 본인).
- **인증**: §3.2 또는 본인 소유권
- **Request**: 경로 파라미터.
- **Response**
```json
{ "ok": true, "data": { "provider_id":"prv_1","owner_user_id":"U2","state":"online_idle",
  "capabilities":[{"model":"llama3:8b","burden":"light","max_context":8192,"est_speed_tps":35.0}],
  "policy":{"model":"llama3:8b","allowed_roles":["member","trusted","admin"],
    "allowed_channels":["C1"],"daily_limit":50,"max_concurrency":1,"max_seconds":60,
    "long_prompt_allowed":false,"prompt_char_limit":4000} }, "error": null, "request_id":"req_p6","ts":"..." }
```
- **Error Response**: `ERR-PERMISSION-DENIED`, `ERR-PROVIDER-NOT-FOUND`
- **상태 변화**: 없음 · **관련**: `REQ-515`, `SCR-505`, `DM-E-ProviderCapability`·`DM-E-ProviderContributionPolicy`

### 7.7 기여 정책 수정 — `API-REST-PROVIDER-POLICY-UPDATE`
- **Method**: `PATCH /api/v1/guilds/{guild_id}/providers/{provider_id}/policy`
- **설명**: 허용 역할·채널·요청자 범위·긴 프롬프트 허용(`/provider-scope`). 즉시 세션 반영.
- **인증**: 본인 소유권 또는 관리자
- **Request**: `{ "model":"qwen72b", "allowed_roles":["admin"], "allowed_channels":["C2"], "long_prompt_allowed":true }`
- **Response**: `{ "ok": true, "data": { "policy_updated": true }, "error": null, "request_id":"req_p7","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`, `ERR-POLICY-INVALID`
- **상태 변화**: 없음(정책 갱신) · **관련**: `REQ-507`, `SCR-605`, `DM-E-ProviderContributionPolicy`

### 7.8 모델 목록 수정 — `API-REST-PROVIDER-MODELS-UPDATE`
- **Method**: `PUT /api/v1/guilds/{guild_id}/providers/{provider_id}/models`
- **설명**: 제공 모델 목록·부담 수준 설정(`/provider-models`). Agent capability 와 정합 확인.
- **인증**: 본인 소유권 또는 관리자
- **Request**: `{ "capabilities":[ {"model":"llama3:8b","burden":"light"}, {"model":"qwen32b","burden":"standard"} ] }`
- **Response**: `{ "ok": true, "data": { "models":["llama3:8b","qwen32b"] }, "error": null, "request_id":"req_p8","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`, `ERR-MODEL-UNSUPPORTED`(Agent 미보고 모델)
- **상태 변화**: capability 갱신(§8.5 와 연동) · **관련**: `REQ-507`, `SCR-605`, `DM-E-ProviderCapability`

### 7.9 한도 수정 — `API-REST-PROVIDER-LIMIT-UPDATE`
- **Method**: `PATCH /api/v1/guilds/{guild_id}/providers/{provider_id}/limits`
- **설명**: 모델별 일일 한도·동시 한도·최대 처리 시간·프롬프트 길이 상한(`/provider-limit`).
- **인증**: 본인 소유권 또는 관리자
- **Request**: `{ "model":"qwen32b", "daily_limit":20, "max_concurrency":1, "max_seconds":60, "prompt_char_limit":8000 }`
- **Response**: `{ "ok": true, "data": { "limit_updated": true }, "error": null, "request_id":"req_p9","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`, `ERR-POLICY-INVALID`
- **상태 변화**: 없음 · **관련**: `REQ-507`, `SCR-605`, `DM-E-ProviderContributionPolicy`

### 7.10 pause·resume — `API-REST-PROVIDER-PAUSE` / `API-REST-PROVIDER-RESUME`
- **Method**: `POST /api/v1/guilds/{guild_id}/providers/{provider_id}/pause` · `.../resume`
- **설명**: 수신 중단/재개(`/provider-pause`·`/provider-resume`, §5.9/§5.10 위임). 관리자
  강제 pause 도 동일 엔드포인트(`forced:true`).
- **인증**: 본인 소유권 또는 관리자
- **Request**: `{ "forced": false }`
- **Response**: `{ "ok": true, "data": { "provider_id":"prv_1", "state":"paused" }, "error": null, "request_id":"req_p10","ts":"..." }`
- **Error Response**: `ERR-PROVIDER-NOT-FOUND`, `ERR-PROVIDER-OFFLINE`(resume 시 Agent 미연결)
- **상태 변화**: `online_idle`↔`paused`; `DM-EV-ProviderPaused`/`DM-EV-ProviderResumed`. §8.10 프레임 송신.
- **관련**: `REQ-603`/`REQ-603`, `SCR-609`, `DM-S-ProviderState`

---

## 8. Provider Agent WebSocket Protocol (`API-WS-*`)
**Protocol**: JSON 프레임 over **`wss`(TLS 강제)**. **호출 주체**: Provider Agent(프로바이더 PC)
↔ 중앙 릴레이. ADR 0002 의 8 프레임(`auth/infer/result/error/ping/pong/chunk/cancel`)을
Provider Pool 용으로 확장한다(ROADMAP 차수 17: `provider_hello`·`provider_status`).

**보안 불변식(README §보안·SOURCE_BRIEF §16, 위반 금지)**:
- Provider Agent 는 **outbound 연결만** 연다. inbound 포트 미개방. 외부에서 직접 접속받지 않는다.
- Agent 는 **임의 URL/shell/파일 접근 금지**. 중앙 서버에서 온 허용 `infer` 요청만 처리하고,
  호출 대상은 **`localhost:11434` Ollama 로 고정**(임의 엔드포인트 호출 불가) → SSRF 원천 차단.
- 모든 프레임에 `protocol_version`. 인증은 일회용·단기 만료 토큰 + heartbeat. 토큰은 로그 미노출.
- 프레임 최대 크기·프롬프트 길이 상한 강제(차수 30 보안 하드닝).

### 8.1 연결 URL — `API-WS-CONNECT`
- **메시지 타입**: WebSocket 업그레이드(프레임 아님)
- **Endpoint**: `wss://<relay_host>:<relay_port><relay_path>` (기본 `relay_path=/agent`)
- **설명**: Agent 가 릴레이로 outbound `wss` 연결을 연다. 연결 직후 N초 내 §8.2 프레임 필수.
- **상태 변화**: 연결 수립(미인증 상태) · **관련**: `REQ-508`(차수 17), `DM-E-ProviderSession`

### 8.2 인증 메시지(provider_hello) — `API-WS-PROVIDER-HELLO`
ADR 0002 `auth` 를 확장. 토큰 + capability + 동시 한도 + 일일 잔여 보고를 한 번에 한다.
```json
{ "type":"provider_hello", "protocol_version":"1.0", "token":"ABC-123-XYZ",
  "agent_version":"0.4.0", "platform":"win32",
  "capabilities":[ {"model":"llama3:8b","burden":"light","max_context":8192,"est_speed_tps":35.0},
                   {"model":"mistral:7b","burden":"standard","max_context":8192,"est_speed_tps":22.0} ],
  "max_concurrency":1, "remaining_daily_requests":42 }
```
- **호출 주체**: Provider Agent → 릴레이 · **인증**: §3.4(토큰 검증)
- **상태 변화**: 검증 성공 시 `approved`→`online_idle`; 이벤트 `DM-EV-ProviderAgentConnected`.
  실패 시 §8.3 `auth_err`(`ERR-AGENT-AUTH-FAILED`) 후 연결 종료.
- **관련**: `REQ-508`·`REQ-508`(차수 17), `DM-E-ProviderCapability`·`DM-E-ProviderSession`

### 8.3 인증 성공/실패 — `API-WS-AUTH-OK` / `API-WS-AUTH-ERR`
```json
{ "type":"auth_ok", "session_id":"sess_1", "heartbeat_seconds":20, "request_timeout_seconds":60 }
{ "type":"auth_err", "code":"ERR-AGENT-AUTH-FAILED", "message":"토큰이 만료되었거나 유효하지 않습니다." }
```
- **호출 주체**: 릴레이 → Agent · **상태 변화**: `auth_ok` 시 세션 활성; `auth_err` 시 연결 종료.
- **관련**: `REQ-508`, `DM-S-ProviderState`

### 8.4 상태 보고(provider_status) — `API-WS-PROVIDER-STATUS`
SOURCE_BRIEF §9 의 주기 보고. load·battery·online/busy 를 라우터·보호 로직에 공급.
```json
{ "type":"provider_status", "session_id":"sess_1", "status":"online",
  "load":"idle", "battery":"charging", "cpu_percent":21.0, "gpu_percent":5.0,
  "remaining_daily_requests":42, "active_concurrency":0 }
```
- **호출 주체**: Agent → 릴레이(주기 또는 변화 시)
- **상태 변화**: `online_idle`↔`online_busy`; 보호 트리거 시 `limited`/`paused`/`offline`/`unhealthy`
  (SOURCE_BRIEF §9: 배터리→pause, 절전→offline, 네트워크 불안정→temporarily unavailable).
  이벤트 `DM-EV-ProviderMarkedUnhealthy`(반복 실패 시).
- **관련**: `REQ-603`·`REQ-603`~`REQ-603`, `DM-E-ProviderHealth`·`DM-S-ProviderState`

### 8.5 모델 목록 보고 — `API-WS-CAPABILITY-UPDATE`
런타임 모델 추가/제거 반영(ROADMAP 385).
```json
{ "type":"capability_update", "session_id":"sess_1",
  "capabilities":[ {"model":"qwen72b","burden":"heavy","max_context":32768,"est_speed_tps":8.0} ] }
```
- **호출 주체**: Agent → 릴레이 · **상태 변화**: capability 갱신(§7.8 와 정합)
- **관련**: `REQ-507`, `DM-E-ProviderCapability`

### 8.6 heartbeat — `API-WS-PING` / `API-WS-PONG`
```json
{ "type":"ping", "ts":"2026-05-30T09:10:00Z" }
{ "type":"pong", "ts":"2026-05-30T09:10:00Z" }
```
- **호출 주체**: 릴레이↔Agent(양방향) · **인증**: 세션 유효
- **상태 변화**: `pong` 수신 시 `last_seen` 갱신. `relay_heartbeat_seconds` 초과 미수신 →
  `offline` 전이 + 연결 정리(§3.5, ROADMAP 384).
- **관련**: `REQ-706`·`REQ-706`, `DM-E-ProviderSession`

### 8.7 LLM 요청 수신(infer) — `API-WS-INFER`
릴레이 → Agent. §9.6 전송이 이 프레임을 만든다. options 화이트리스트(temperature/num_predict)만 허용.
```json
{ "type":"infer", "request_id":"req_1", "model":"llama3:8b", "prompt":"...",
  "options":{ "temperature":0.7, "num_predict":512 }, "deadline_seconds":60 }
```
- **호출 주체**: 릴레이 → Agent · **인증**: 세션 유효 + 동시성 슬롯 획득
- **상태 변화**: `RequestState` `sent_to_provider`→`running`; provider `online_idle`→`online_busy`;
  이벤트 `DM-EV-RequestSentToProvider` · **관련**: `REQ-510`, `DM-E-AiRequest`·`DM-E-RequestExecution`

### 8.8 LLM 응답 반환(result) — `API-WS-RESULT`
```json
{ "type":"result", "request_id":"req_1", "text":"...",
  "usage":{ "prompt_tokens":120, "completion_tokens":340 } }
```
- **호출 주체**: Agent → 릴레이 · **상태 변화**: `running`→`completed`; provider→`online_idle`;
  이벤트 `DM-EV-ProviderResponseReceived`→`DM-EV-RequestCompleted`; 슬롯 반환 → §10.2/§10.3 기록.
- **관련**: `REQ-508`·`REQ-513`·`REQ-602`, `DM-E-RequestExecution`·`DM-E-UsageLog`

### 8.9 LLM 실패 반환(error) — `API-WS-ERROR`
```json
{ "type":"error", "request_id":"req_1", "code":"ERR-OLLAMA-FAILED",
  "message":"모델을 찾을 수 없습니다." }
```
- 코드 매핑(ROADMAP 차수 2/24): `OFFLINE`→`ERR-PROVIDER-OFFLINE`, `TIMEOUT`→`ERR-TIMEOUT`,
  `OLLAMA_ERROR`→`ERR-OLLAMA-FAILED`, `BUSY`→`ERR-PROVIDER-BUSY`, `AUTH_FAILED`→`ERR-AGENT-AUTH-FAILED`.
- **상태 변화**: `running`→`failed`(→ §9.8 fallback 시 `fallback_running`); 반복 실패 시
  provider→`unhealthy`(`DM-EV-ProviderMarkedUnhealthy`)·temporarily unavailable.
- **관련**: `REQ-704`·`REQ-603`, `DM-S-RequestState`·`DM-S-ProviderState`

### 8.10 pause·resume — `API-WS-PAUSE` / `API-WS-RESUME`
릴레이 → Agent. §5.9/§5.10/§7.10 또는 자동 보호가 트리거.
```json
{ "type":"pause", "session_id":"sess_1", "reason":"manual" }
{ "type":"resume", "session_id":"sess_1" }
```
- **상태 변화**: provider `online_*`↔`paused`(자동 보호 시 `reason` = `battery`/`overload`/`network`).
  이벤트 `DM-EV-ProviderPaused`/`DM-EV-ProviderResumed`.
- **관련**: `REQ-603`·`REQ-603`·`REQ-603`, `DM-S-ProviderState`

### 8.11 종료·세션 만료 — `API-WS-CANCEL` / `API-WS-CLOSE`
```json
{ "type":"cancel", "request_id":"req_1", "reason":"timeout" }
{ "type":"close", "session_id":"sess_1", "reason":"session_expired" }
```
- `cancel`: 진행 요청 취소(타임아웃/상위 명령 취소). `close`: 세션 만료(heartbeat 초과·토큰
  폐기·`/provider-leave`·관리자 제거)로 연결 종료.
- **상태 변화**: 진행 요청 `running`→`failed`(`ERR-TIMEOUT`); provider→`offline`/`removed`;
  이벤트 `DM-EV-ProviderAgentDisconnected`; 대기 future 실패 처리.
- **관련**: `REQ-508`·`REQ-704`·`REQ-505`, `DM-E-ProviderSession`

### 8.12 재연결 규칙
- 연결이 끊기면 Agent 는 **지수 백오프**로 재연결을 시도한다(ROADMAP 255). inbound 포트는
  열지 않는다.
- 재연결 시 §8.2 `provider_hello` 를 다시 보내 인증·capability 를 재바인딩한다. 토큰이 이미
  소비/만료되었으면 §8.3 `auth_err` → 프로바이더는 `/provider-status` 안내에 따라 재발급
  (ROADMAP 581) 필요.
- 동일 owner 의 새 연결은 이전 연결을 graceful close 로 축출한다(ROADMAP 118).
- **세션 만료**: heartbeat 초과 또는 토큰 폐기 시 릴레이가 `offline` 처리 후 §8.11 `close`.
- **관련**: `REQ-508`·`REQ-706`, `DM-S-ProviderState`

---

## 9. Request Routing API (`API-INT-*`)
중앙 서버 **내부 호출**(in-process). **호출 주체**: 봇 명령 핸들러 → Router. **인증**: 내부
신뢰 경계(외부 노출 없음). SOURCE_BRIEF §7 의 19단계와 §8 의 10기준·§15 공정성 점수를 구현한다.
각 단계는 `RequestId` 로 상관관계가 유지된다.

### 9.1 요청 생성 — `API-INT-CREATE-REQUEST`
- **설명**: `/ask` 등에서 `DM-E-AiRequest` 생성, `RequestId` 부여, 정책 확인 시작.
- **Request**: `{ "guild_id":"G1","user_id":"U1","channel_id":"C1","question":"...","command":"ask" }`
- **Response**: `{ "request_id":"req_1", "state":"received" }`
- **Error**: `ERR-CHANNEL-NOT-ALLOWED`(채널 LLM 비활성) · **상태 변화**: →`received`;
  `DM-EV-AiRequestReceived` · **관련**: SOURCE_BRIEF §7 1~5, `REQ-510`, `FLOW-08`, `DM-E-AiRequest`

### 9.2 무게 계산 — `API-INT-WEIGH`
- **설명**: 프롬프트 길이·첨부·명령 종류로 요청 무게(`DM-V-RequestWeight`: `light`/`medium`/`heavy`)
  → 필요 `DM-V-ModelBurdenLevel` 결정(차수 20). 매핑: light→light, medium→standard, heavy→heavy.
- **Request**: `{ "request_id":"req_1" }`
- **Response**: `{ "request_id":"req_1", "weight":"medium", "required_level":"standard" }`
- **Error**: 없음(경계값은 보수적 medium→standard) · **상태 변화**: →`policy_checked` · **관련**: SOURCE_BRIEF §7 6~7, `REQ-510`, `DM-V-RequestWeight`·`DM-V-ModelBurdenLevel`

### 9.3 후보 조회 — `API-INT-CANDIDATES`
- **설명**: `guild_id → provider_pool[]` 조회로 `DM-E-RoutingCandidate` 목록 구성(SOURCE_BRIEF §7 8).
- **Request**: `{ "request_id":"req_1", "guild_id":"G1" }`
- **Response**: `{ "request_id":"req_1", "candidates":["prv_1","prv_2","prv_3"] }`
- **Error**: `ERR-NO-PROVIDER`(풀 비어 있음) · **상태 변화**: →`routing` · **관련**: `REQ-510`, `DM-E-RoutingCandidate`

### 9.4 필터링 — `API-INT-FILTER`
- **설명**: SOURCE_BRIEF §8 의 10기준(① 모델수준 감당 ② 온라인 ③ idle ④ 요청자 허용
  ⑤ 채널 허용 ⑥ 일일 한도 잔여 ⑦ 동시 한도 ⑧ 최근 과다처리 아님 ⑨ 요청 크기 ≤ 제한
  ⑩ 실패율 낮음) + RESTRICTED 특수 필터(차수 21). 단계별 탈락 사유 기록.
- **Request**: `{ "request_id":"req_1", "candidates":["prv_1","prv_2","prv_3"] }`
- **Response**: `{ "request_id":"req_1", "passed":["prv_1","prv_2"], "dropped":{"prv_3":"daily_limit_exceeded"} }`
- **Error**: `ERR-NO-PROVIDER`(전원 탈락), `ERR-PERMISSION-DENIED`(권한 부족·다운그레이드 신호)
- **상태 변화**: 유지 `routing` · **관련**: SOURCE_BRIEF §8, `REQ-510`~`REQ-510`, `DM-R-07`

### 9.5 선택 — `API-INT-SELECT`
- **설명**: 공정성 점수(SOURCE_BRIEF §15: 적합도+온라인+idle+남은한도+최근 적게 처리 가산
  − 실패율 − 부하 − heavy 낭비 패널티)로 최종 1인 선택. light→light·standard→standard·
  heavy→heavy 우선, heavy 는 대안 없을 때만 light 요청에 예외 사용(`DM-R-07`).
- **Request**: `{ "request_id":"req_1", "passed":["prv_1","prv_2"] }`
- **Response**: `{ "request_id":"req_1", "selected":"prv_1", "score":8.4, "reason":"light 적합·최근 처리량 적음" }`
- **Error**: `ERR-NO-PROVIDER` · **상태 변화**: →`queued`; 이벤트 `DM-EV-ProviderSelected`
- **관련**: SOURCE_BRIEF §15, `REQ-510`~`REQ-510`, `DM-E-RoutingDecision`

### 9.6 전송 — `API-INT-DISPATCH`
- **설명**: 선택 provider 세션으로 §8.7 `infer` 프레임 전송(동시성 슬롯 획득). 큐 초과 시 대기.
- **Request**: `{ "request_id":"req_1", "selected":"prv_1" }`
- **Response**: `{ "request_id":"req_1", "state":"sent_to_provider" }`
- **Error**: `ERR-PROVIDER-OFFLINE`, `ERR-PROVIDER-BUSY`(큐 상한 초과) · **상태 변화**:
  `queued`→`sent_to_provider`→`running`; 이벤트 `DM-EV-RequestSentToProvider`
- **관련**: SOURCE_BRIEF §7 15, `REQ-511`·`REQ-511`, `DM-E-RequestExecution`

### 9.7 응답 수신 — `API-INT-COLLECT`
- **설명**: §8.8 `result` 프레임 수신 → Discord 출력(`SCR-404`) + 기록 트리거.
- **Request**: `{ "request_id":"req_1" }`(future resolve)
- **Response**: `{ "request_id":"req_1", "state":"completed", "text":"...", "usage":{"prompt_tokens":120,"completion_tokens":340} }`
- **Error**: §9.8 로 위임 · **상태 변화**: `running`→`completed`; 이벤트 `DM-EV-ProviderResponseReceived`→`DM-EV-RequestCompleted`
- **관련**: SOURCE_BRIEF §7 17~19, `REQ-508`, `DM-E-RequestExecution`

### 9.8 fallback — `API-INT-FALLBACK`
- **설명**: 실패·타임아웃 시 **동일 조건 다른 provider 로 1회** 재라우팅(원 provider 제외,
  §9.4 재필터). 실패 provider 는 temporarily unavailable(SOURCE_BRIEF §11).
- **Request**: `{ "request_id":"req_1", "failed_provider":"prv_1", "reason":"ERR-TIMEOUT" }`
- **Response**: `{ "request_id":"req_1", "state":"fallback_running", "selected":"prv_2" }`
- **Error**: `ERR-FALLBACK-FAILED`(대체 후보 없음/2차 실패) · **상태 변화**: `failed`→`fallback_running`
  →`completed`/`failed`; 이벤트 `DM-EV-FallbackStarted`·`DM-EV-RequestFailed`
- **관련**: SOURCE_BRIEF §11, `REQ-512`~`REQ-504`, `DM-S-RequestState`

### 9.9 취소 — `API-INT-CANCEL`
- **설명**: 상위 명령 취소/타임아웃 시 진행 요청 취소. §8.11 `cancel` 프레임 송신 + future 취소.
- **Request**: `{ "request_id":"req_1", "reason":"timeout" }`
- **Response**: `{ "request_id":"req_1", "state":"failed", "cancelled":true }`
- **Error**: `ERR-TIMEOUT` · **상태 변화**: `running`/`fallback_running`→`failed`; 슬롯 반환
- **관련**: `REQ-704`·`REQ-505`, `DM-E-RequestExecution`

### 9.10 상태 조회 — `API-INT-REQUEST-STATUS`
- **설명**: `RequestId` 로 현재 `RequestState`·선택 provider·실패 사유 조회(진단/대기 표시).
- **Request**: `{ "request_id":"req_1" }`
- **Response**: `{ "request_id":"req_1", "state":"running", "selected":"prv_1", "queued_position":0 }`
- **Error**: `ERR-REQUEST-NOT-FOUND` · **상태 변화**: 없음 · **관련**: `REQ-510`, `SCR-403`(대기 중), `DM-E-AiRequest`

---

## 10. Usage·Log API
공통: **Protocol** = HTTPS REST(조회) / 내부 기록 트리거, **인증** = 본인(§3.1) 또는 관리자(§3.2),
봉투 = §1.5. SOURCE_BRIEF §14·ROADMAP 차수 29.

### 10.1 사용자 사용량 — `API-REST-USAGE-USER`
- **Method**: `GET /api/v1/guilds/{guild_id}/usage/users/{user_id}`
- **설명**: 일자별 요청 수·역할 한도 잔여(`/my-usage` 데이터 소스).
- **인증**: 본인 또는 관리자
- **Response**: `{ "ok": true, "data": { "user_id":"U1","date":"2026-05-30","count":7,"daily_limit":30,"remaining":23 }, "error": null, "request_id":"req_u1","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-513`·`REQ-606`, `SCR-408`, `DM-E-UsageLog`

### 10.2 Guild 사용량 — `API-REST-USAGE-GUILD`
- **Method**: `GET /api/v1/guilds/{guild_id}/usage`
- **설명**: 서버 전체 일/주 요청 수·모델수준 분포(관리자).
- **인증**: §3.2
- **Response**: `{ "ok": true, "data": { "guild_id":"G1","total_today":140,"by_level":{"light":90,"standard":40,"heavy":10} }, "error": null, "request_id":"req_u2","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-513`(사용량 기록), `SCR-508`, `DM-E-UsageLog`

### 10.3 Provider 기여량 — `API-REST-USAGE-PROVIDER`
- **Method**: `GET /api/v1/guilds/{guild_id}/providers/{provider_id}/contributions`
- **설명**: provider 처리량·공정성 지표(누가 얼마나 도왔는가, SOURCE_BRIEF §15).
- **인증**: 본인 또는 관리자
- **Response**: `{ "ok": true, "data": { "provider_id":"prv_1","today":12,"week":80,"fairness_index":0.78 }, "error": null, "request_id":"req_u3","ts":"..." }`
- **Error**: `ERR-PROVIDER-NOT-FOUND`, `ERR-PERMISSION-DENIED` · **상태 변화**: 없음
- **관련**: `REQ-602`·`REQ-604`·`REQ-513`, `SCR-505`, `DM-E-UsageLog`(contribution)

### 10.4 요청 로그 — `API-REST-LOG-REQUESTS`
- **Method**: `GET /api/v1/guilds/{guild_id}/logs/requests?from=&to=&state=`
- **설명**: 요청 단위 로그(상태·선택 provider·소요). 프롬프트 본문은 미포함(프라이버시, ROADMAP 612).
- **인증**: §3.2
- **Response**: `{ "ok": true, "data": { "logs":[ {"request_id":"req_1","state":"completed","provider_id":"prv_1","level":"standard","ms":2200} ] }, "error": null, "request_id":"req_l1","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-510`·`REQ-513`, `SCR-805`, `DM-E-RequestExecution`

### 10.5 실패 로그 — `API-REST-LOG-FAILURES`
- **Method**: `GET /api/v1/guilds/{guild_id}/logs/failures`
- **설명**: 실패/fallback/timeout 로그(`ERR-` 코드별 집계).
- **인증**: §3.2
- **Response**: `{ "ok": true, "data": { "failures":[ {"request_id":"req_9","code":"ERR-TIMEOUT","provider_id":"prv_2"} ], "by_code":{"ERR-TIMEOUT":3,"ERR-OLLAMA-FAILED":1} }, "error": null, "request_id":"req_l2","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-704`·`REQ-512`, `SCR-808`, `DM-S-RequestState`

### 10.6 health 로그 — `API-REST-LOG-HEALTH`
- **Method**: `GET /api/v1/guilds/{guild_id}/logs/provider-health`
- **설명**: provider 상태 전이·보호 트리거 이력(배터리/부하/네트워크/unhealthy).
- **인증**: §3.2
- **Response**: `{ "ok": true, "data": { "events":[ {"provider_id":"prv_1","from":"online_idle","to":"paused","reason":"battery","ts":"..."} ] }, "error": null, "request_id":"req_l3","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-603`(프로바이더 보호), `SCR-808`, `DM-E-ProviderHealth`

---

## 11. Admin Dashboard API (`API-REST-ADMIN-*`)
공통: **Protocol** = HTTPS REST(Web Dashboard), **호출 주체** = 관리자/Operator, **인증** =
§3.1 OAuth 세션 + §3.2 관리자 권한, 봉투 = §1.5. 베이스 경로 `/api/v1/dashboard`.

### 11.1 서버 목록 — `API-REST-ADMIN-GUILDS`
- **Method**: `GET /api/v1/dashboard/guilds`
- **설명**: 호출 관리자가 관리하는 길드 목록·요약(provider 수·오늘 요청 수).
- **Response**: `{ "ok": true, "data": { "guilds":[ {"guild_id":"G1","name":"동아리방","providers":3,"today_requests":140} ] }, "error": null, "request_id":"req_d1","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-515`, `SCR-801`, `DM-E-Guild`

### 11.2 대시보드 — `API-REST-ADMIN-DASHBOARD`
- **Method**: `GET /api/v1/dashboard/guilds/{guild_id}`
- **설명**: 한 길드의 종합 대시보드(정책·pool 헬스·사용량·최근 실패).
- **Response**: `{ "ok": true, "data": { "policy":{ /* §6.1 */ }, "pool":{ "online":2,"busy":1,"offline":1 }, "usage":{ "today":140 }, "recent_failures":2 }, "error": null, "request_id":"req_d2","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED`, `ERR-GUILD-NOT-FOUND` · **상태 변화**: 없음 · **관련**: `REQ-515`, `SCR-802`, `DM-E-GuildPolicy`

### 11.3 Pool 상태 — `API-REST-ADMIN-POOL`
- **Method**: `GET /api/v1/dashboard/guilds/{guild_id}/pool`
- **설명**: provider 상태 분포·실시간 부하(`DM-S-ProviderState` 카운트).
- **Response**: `{ "ok": true, "data": { "states":{"online_idle":2,"online_busy":1,"paused":0,"offline":1,"unhealthy":0}, "active_requests":1 }, "error": null, "request_id":"req_d3","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-515`, `SCR-803`, `DM-S-ProviderState`

### 11.4 Provider 상세 — `API-REST-ADMIN-PROVIDER`
- **Method**: `GET /api/v1/dashboard/guilds/{guild_id}/providers/{provider_id}`
- **설명**: §7.6 와 동일 페이로드 + 기여량(§10.3) + health 이력(§10.6) 합본.
- **Response**: §7.6 + `{ "contributions":{...}, "health_events":[...] }`
- **Error**: `ERR-PROVIDER-NOT-FOUND`, `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-515`, `SCR-804`, `DM-E-Provider`

### 11.5 라우팅 로그 — `API-REST-ADMIN-ROUTING-LOG`
- **Method**: `GET /api/v1/dashboard/guilds/{guild_id}/routing-log`
- **설명**: §9 선택 결정(`DM-E-RoutingDecision`)·단계별 탈락 사유·fallback 추적.
- **Response**: `{ "ok": true, "data": { "decisions":[ {"request_id":"req_1","selected":"prv_1","score":8.4,"dropped":{"prv_3":"daily_limit_exceeded"}} ] }, "error": null, "request_id":"req_d5","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-510`(라우팅 결정 로깅), `SCR-805`, `DM-E-RoutingDecision`

### 11.6 정책 변경 이력 — `API-REST-ADMIN-POLICY-HISTORY`
- **Method**: `GET /api/v1/dashboard/guilds/{guild_id}/policy-history`
- **설명**: 정책/승인/제거 audit_log(누가·언제·무엇을, ROADMAP 409·563).
- **Response**: `{ "ok": true, "data": { "history":[ {"actor":"U_admin","action":"provider.approve","target":"prv_1","ts":"..."} ] }, "error": null, "request_id":"req_d6","ts":"..." }`
- **Error**: `ERR-PERMISSION-DENIED` · **상태 변화**: 없음 · **관련**: `REQ-502`, `SCR-501`, `DM-E-GuildPolicy`

---

## 12. Health Check API
공통: **Protocol** = HTTPS REST(Operator/모니터링) 또는 내부 점검. 인증은 §3.2(또는 운영
네트워크 제한). 봉투 = §1.5(`data.status` = `ok`/`degraded`/`down`).

### 12.1 중앙 서버 — `API-REST-HEALTH-CENTRAL`
- **Method**: `GET /api/v1/health`
- **설명**: 봇·중앙 서버 프로세스 생존(기존 `health.py` 패턴 재사용).
- **Response**: `{ "ok": true, "data": { "status":"ok", "uptime_seconds":86400 }, "error": null, "request_id":"req_h1","ts":"..." }`
- **Error**: `ERR-HEALTH-DEGRADED` · **상태 변화**: 없음 · **관련**: `REQ-707`, `DM-E-ProviderHealth`(중앙측)

### 12.2 WS 릴레이 — `API-REST-HEALTH-RELAY`
- **Method**: `GET /api/v1/health/relay`
- **설명**: 릴레이 활성 연결 수·대기 큐 길이(ROADMAP 115 메트릭).
- **Response**: `{ "ok": true, "data": { "status":"ok", "active_connections":3, "pending_requests":1 }, "error": null, "request_id":"req_h2","ts":"..." }`
- **Error**: `ERR-WS-CONNECT-FAILED` · **상태 변화**: 없음 · **관련**: `REQ-707`, `DM-E-ProviderSession`

### 12.3 Provider Session — `API-INT-HEALTH-SESSION`
- **Method**: 내부 점검(주기 태스크) / `GET /api/v1/health/sessions`(관리자)
- **설명**: 세션별 `last_seen`·heartbeat 만료 여부 점검 → 좀비 정리(ROADMAP 389).
- **Response**: `{ "ok": true, "data": { "sessions":[ {"session_id":"sess_1","provider_id":"prv_1","last_seen_ms_ago":1200,"alive":true} ] }, "error": null, "request_id":"req_h3","ts":"..." }`
- **Error**: `ERR-PROVIDER-OFFLINE` · **상태 변화**: 만료 시 →`offline` · **관련**: `REQ-706`, `DM-E-ProviderSession`

### 12.4 Ollama 연결 — `API-WS-OLLAMA-PING` / `API-INT-HEALTH-OLLAMA`
- **Method**: §8.4 `provider_status` 의 Agent 자가 점검 결과를 릴레이가 수집(내부).
- **설명**: Agent 가 `localhost:11434` Ollama 도달성을 점검해 보고. 외부에서 직접 점검 불가
  (보안 불변식).
- **Response**(내부): `{ "provider_id":"prv_1", "ollama_reachable":true }`
- **Error**: `ERR-OLLAMA-FAILED` · **상태 변화**: 미도달 시 →`unhealthy`/`offline` · **관련**: `REQ-508`·`REQ-603`, `DM-E-ProviderHealth`

### 12.5 모델 사용 가능 — `API-INT-HEALTH-MODEL`
- **Method**: 내부 점검 / `GET /api/v1/health/models?guild_id=`
- **설명**: 길드에서 각 부담 수준별로 처리 가능한 online provider 가 1명 이상인지(모델 가용성).
- **Response**: `{ "ok": true, "data": { "available":{"light":true,"standard":true,"heavy":false,"restricted":false} }, "error": null, "request_id":"req_h5","ts":"..." }`
- **Error**: `ERR-MODEL-UNSUPPORTED`(요청 수준 불가), `ERR-NO-PROVIDER` · **상태 변화**: 없음
- **관련**: `REQ-509`·`REQ-512`, `SCR-407`, `DM-E-ProviderCapability`

---

## 13. 에러 코드
README/SOURCE_BRIEF 와 정합. 모든 `ERR-` 는 §1.5 봉투의 `error.code` 또는 §8 프레임 `code` 로 노출.

| § | 코드 | 의미 | 트리거 | 사용자 안내(요지) |
|---|---|---|---|---|
| 13.1 | `ERR-PERMISSION-DENIED` | 권한 부족 | 관리자/본인 권한 실패, 역할 정책 미달 | "이 작업/요청에는 권한이 필요합니다." |
| 13.2 | `ERR-CHANNEL-NOT-ALLOWED` | 채널 정책 위반 | 허용되지 않은/금지 채널에서 호출 | "이 채널에서는 LLM 을 사용할 수 없습니다." |
| 13.3 | `ERR-NO-PROVIDER` | Provider 없음 | 후보 0명/전원 필터 탈락 | "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다." |
| 13.4 | `ERR-PROVIDER-OFFLINE` | 오프라인 | 선택/대상 provider 세션 없음 | "프로바이더가 오프라인입니다. 잠시 후 다시 시도하세요." |
| 13.5 | `ERR-MODEL-UNSUPPORTED` | 모델 미지원 | 필요 부담 수준/모델 제공 불가 | "이 요청 수준을 처리할 수 있는 모델이 없습니다." |
| 13.6 | `ERR-LIMIT-EXCEEDED` | 한도 초과 | 유저 일일 한도/provider 한도 초과 | "오늘 사용 한도를 초과했습니다." |
| 13.7 | `ERR-AGENT-AUTH-FAILED` | Agent 인증 실패 | 토큰 만료/무효/소비됨 | "에이전트 인증에 실패했습니다. 토큰을 재발급하세요." |
| 13.8 | `ERR-WS-CONNECT-FAILED` | WS 연결 실패 | 핸드셰이크/`wss` 실패·릴레이 down | "에이전트 연결에 실패했습니다." |
| 13.9 | `ERR-OLLAMA-FAILED` | Ollama 실패 | 모델 없음·메모리 부족·로컬 오류 | "로컬 AI 처리 중 오류가 발생했습니다." |
| 13.10 | `ERR-TIMEOUT` | 타임아웃 | 요청당 최대 시간 초과 | "응답이 시간 내 오지 않았습니다." |
| 13.11 | `ERR-FALLBACK-FAILED` | fallback 실패 | 대체 후보 없음/2차 실패 | "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다. 더 가벼운 요청으로 시도하세요." |

보조 코드(상기 11종을 보강, 동일 봉투 사용): `ERR-PROVIDER-BUSY`(동시/큐 상한),
`ERR-PROVIDER-NOT-FOUND`, `ERR-PROVIDER-ALREADY-REGISTERED`, `ERR-POLICY-INVALID`,
`ERR-GUILD-NOT-FOUND`, `ERR-REQUEST-NOT-FOUND`, `ERR-HEALTH-DEGRADED`,
`ERR-RATE-LIMITED`(요청/명령 빈도 제한 초과, REQ-701 rate limit, SCR-411 쿨다운 안내).

---

## 14. API별 상세 정의 형식
§5~§12 의 각 API 는 아래 13개 필드를 갖는다(표·축약 항목도 이 형식에 매핑된다).

| # | 필드 | 설명 |
|---|---|---|
| 14.1 | API ID | `API-CMD-*` / `API-REST-*` / `API-WS-*` / `API-INT-*`(§1.3) |
| 14.2 | Method·Protocol | Discord Interaction / HTTPS(GET/POST/PATCH/PUT/DELETE) / `wss` JSON 프레임 / in-process |
| 14.3 | Endpoint·Message Type | REST 경로, 명령 시그니처, 또는 프레임 `type` |
| 14.4 | 설명 | API 의 목적·동작 한 줄 요약 |
| 14.5 | 호출 주체 | 일반 유저 / 관리자 / 프로바이더 / Operator / Provider Agent / 릴레이 / 내부 Router |
| 14.6 | 인증·권한 | §3 의 해당 항목(§3.1~§3.5) + 권한 가드 |
| 14.7 | Request | JSON 예시 코드블록(경로/body/프레임) |
| 14.8 | Response | JSON 예시 코드블록(§1.5 봉투 또는 프레임) |
| 14.9 | Error Response | 발생 가능한 `ERR-` 코드(§13) |
| 14.10 | 상태 변화 | `DM-S-*` 전이 + 발생 이벤트 `DM-EV-*` |
| 14.11 | 관련 요구사항 ID | `REQ-###`(requirements 절 번호) |
| 14.12 | 관련 화면 ID | `SCR-###`(screens) |
| 14.13 | 관련 도메인 모델 ID | `DM-E-*` / `DM-V-*` / `DM-S-*`(domain-model) |

> 표기 규약: §5 명령 묶음(§5.12·§5.13) 및 §6~§12 의 축약 항목은 지면 절약을 위해 14.2·14.3·
> 14.6·14.9·14.10·14.11·14.13 을 표/리스트로 압축 표기했으나, 13개 필드를 모두 보유한다.
> requirements.md/screens.md 작성 시 본 문서의 `REQ-###`·`SCR-###` 참조가 양방향으로 물린다.
