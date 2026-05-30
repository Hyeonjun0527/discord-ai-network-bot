# 도메인 모델 명세서

> 커뮤니티 로컬 AI Provider Pool — 5분서 체계 #2 (요구사항 → **도메인 모델** → 화면 → 네비게이션 → API)
> 정식 출처: `specs/product-v2/README.md`(백본·규약·어휘), `domains/community-provider-pool/SOURCE_BRIEF.md`(원본 기획),
> `docs/adr/0002-remote-agent-byollm.md`, `docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md`(차수 14~24).

---

## 1. 문서 개요

### 1.1 목적

이 문서는 **Discord 커뮤니티 로컬 AI Provider Pool** 기능을 구성하는 도메인 개념·엔티티·값
객체·상태·규칙·이벤트를 단일 어휘로 정의한다. 요구사항 명세서(`REQ-###`)가 "왜·무엇을"을
정의한다면, 이 문서는 그 요구사항을 만족시키기 위해 **시스템이 다루는 정보 구조와 불변
조건**을 확정한다. 화면 정의서(`SCR-###`)·네비게이션 명세서(`FLOW-###`)·API 명세서
(`API-###`)는 이 문서가 정의한 엔티티·상태·이벤트를 글자 그대로 참조한다.

이 문서는 구현 로드맵(`docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md`)의 **차수 14(데이터 모델 & 스토리지
스키마, 항목 317~340)** 를 직접 뒷받침한다. 차수 14의 각 테이블/dataclass/enum 항목은 이
문서의 §4 엔티티·§5 값 객체·§6 상태 모델에 1:1로 대응한다.

### 1.2 도메인 범위

| 포함(In Scope) | 제외(Out of Scope, 비-목표) |
|---|---|
| 길드/채널/역할 단위 LLM 사용 정책 | 판매(seller) / 구매(buyer) |
| 프로바이더 등록·승인·이탈 라이프사이클 | 가격표(price) / 가격 등급 |
| 프로바이더 세션·연결·heartbeat·capability | 수수료(fee) / 정산(payout) |
| 모델 부담 수준 분류와 요청 무게 판단 | 프로바이더 수익 / 마켓플레이스 |
| 요청 수신 → 정책 검증 → 라우팅 → 실행 → 기록 | 결제/구독 도메인 |
| 공정성 점수 기반 분배·쏠림 방지·fallback | 외부 클라우드 LLM 과금 |
| 사용량·기여량 기록(공정성 근거) | — |
| 프라이버시 고지/처리 주체 표시(모드 A/B/C) | — |

중심 개념은 **기여(contribution)·동의(consent)·수용량(capacity)·가용성(availability)·
공정성(fairness)** 다(README 한 줄 정의, SOURCE_BRIEF §0·§14).

### 1.3 관련 문서

| 종류 | 문서 | 이 문서와의 관계 |
|---|---|---|
| 백본 | `specs/product-v2/README.md` | ID 규약·정식 어휘·10상태 목록·보안 불변식의 SSOT |
| 원본 기획 | `domains/community-provider-pool/SOURCE_BRIEF.md` | 본 모델의 권위 있는 입력(충돌 시 브리프+README 우선) |
| 설계 결정 | `docs/adr/0002-remote-agent-byollm.md` | 리버스 터널 에이전트(BYO-LLM) 아키텍처·프로토콜·라우팅 모드 |
| 설계 결정 | ADR 0003 `community-provider-pool` (작성 예정, 로드맵 항목 301) | Phase A 단일 호스트 → 다중 프로바이더 풀 일반화 |
| 구현 로드맵 | `docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md` 차수 13~32 | 본 문서는 특히 차수 14(데이터 모델)를 뒷받침 |
| 상위 분서 | `requirements.md` (`REQ-###`) | 본 문서가 구체화하는 요구사항의 출처 |
| 하위 분서 | `screens.md` / `navigation.md` / `api.md` | 본 문서의 `DM-###` ID 를 소비 |

### 1.4 모델링 원칙

1. **정식 어휘 고정.** README §정식 어휘의 명칭(엔티티 16종, 값객체, 부담수준 4단계,
   Provider 10상태, Request 10상태, 이벤트 14종)을 **글자 그대로** 사용한다. 동의어 금지.
2. **비-목표 모델 배제 (가드).** `billing`·`price`·`seller`·`payout`(및 가격 등급·수수료·
   정산·수익·마켓플레이스) 개념은 **어떤 엔티티·값 객체·필드에도 도입하지 않는다.** 모델
   부담 수준(`ModelBurdenLevel`)은 가격 등급이 아니라 **처리 부담도**다(SOURCE_BRIEF §4,
   §14, 로드맵 항목 335 설계 가드).
3. **부담도 = 처리 비용의 표현, 가격 아님.** 프로바이더는 "감당 가능 범위(capacity)"를
   등록할 뿐, 어떤 금전적 속성도 갖지 않는다.
4. **단일 경로 불변식.** 외부 유저는 프로바이더 PC 에 직접 접근할 수 없다. 도메인 모델은
   `봇 → 중앙 서버 → 인증된 WebSocket → Provider Agent → localhost Ollama` 경로만을
   전제로 한다(README 보안·프라이버시 불변식, ADR 0002).
5. **상태는 명시적 머신.** 모든 라이프사이클(Provider·ProviderSession·Agent 연결·승인·
   Request·ProviderHealth)은 전이표를 가진 상태 머신으로 정의하며 불가 전이는 거부한다.
6. **식별자는 값 객체.** 모든 외부 식별자(Guild/Channel/User/Role)와 내부 식별자
   (Provider/Session/Request)는 §5 값 객체로 정의하고 엔티티는 이를 참조한다.
7. **동의·프라이버시 1급 시민.** 프롬프트가 프로바이더 PC 로 전송될 수 있으므로 동의·고지를
   엔티티(`PrivacyNotice`)와 불변 조건으로 명시한다.

---

## 2. 핵심 도메인 개념

각 소절은 개념의 정의·역할·관련 엔티티(§4)·정식 어휘 출처를 기술한다.

### 2.1 Guild

하나의 Discord 서버 = 하나의 커뮤니티 단위. `guild_id` 로 식별한다. Provider Pool 의
최상위 경계이며, 자신의 정책(`GuildPolicy`)·허용 채널·역할 정책·프로바이더 집합을 소유한다.
`guild_id → provider_pool[]` 관계의 좌변. 관련 엔티티: `DM-E-Guild`, `DM-E-GuildPolicy`.

### 2.2 Channel

길드 내 텍스트 채널. LLM 사용이 허용된 채널에서만 `/ask` 등의 요청이 처리된다(SOURCE_BRIEF
§6 허용 채널). 허용 채널 집합은 `AllowedChannel` 로 모델링한다. 관련 엔티티:
`DM-E-AllowedChannel`. 식별자: `DM-V-ChannelId`.

### 2.3 User

요청을 보내는 사람. README 사용자 유형 중 **일반 유저(User)**·**서버 관리자(Admin)**·
**프로바이더(Provider)**·**시스템 관리자(Operator)** 의 공통 식별 주체다. 일반 유저는
프로바이더 PC 에 직접 접근할 수 없고 오직 봇에게만 요청한다. 한 User 는 동시에 Provider 일
수 있다(자기 PC 를 기여하면서 질문도 함). 식별자: `DM-V-UserId`.

### 2.4 Role

Discord 역할. 길드 관리자가 역할별로 **허용 모델 부담 수준**과 **일일 요청 한도**를
부여한다(SOURCE_BRIEF §6: 일반 멤버=light/20, 신뢰 멤버=light+standard/30, 관리자=
light+standard+heavy). 한 User 는 다중 역할을 가질 수 있고, 허용 수준은 **합집합(최대치)**
으로 해석한다(로드맵 항목 402). 관련 엔티티: `DM-E-RolePolicy`. 식별자: `DM-V-RoleId`.

### 2.5 Provider

자기 PC 의 로컬 LLM(Ollama) 자원을 커뮤니티에 일부 제공하는 사람/주체. "내가 이 커뮤니티를
이 정도까지 도울 수 있다"는 **기여 범위**를 등록하는 사람이다(SOURCE_BRIEF §0, §2).
가격을 정하지 않고 제공 모델·받을 요청 종류·허용 대상·하루 한도·동시 한도·허용 채널·허용
역할·일시정지를 정한다. 길드에 종속된다(`provider.guild_id`). 관련 엔티티: `DM-E-Provider`.

### 2.6 Provider Agent

프로바이더 PC 에서 실행되는 경량 프로그램(ADR 0002 §3). ①중앙 서버로 **outbound**
WebSocket 연결 ②일회용 토큰 인증 ③로컬 Ollama 상태/모델 보고 ④허용된 요청만 수신
⑤`localhost` Ollama 호출 ⑥결과 회신. inbound 포트를 열지 않으며 임의 shell/파일/URL 을
처리하지 않는다. Agent 의 연결 상태는 `DM-S-AgentConnState` 로, 인증된 연결의 결과로
생성되는 도메인 객체는 `ProviderSession`(`DM-E-ProviderSession`)이다.

### 2.7 Provider Session

인증된 Agent 연결 1건에 대응하는 런타임 객체(SOURCE_BRIEF §14 `provider_session`).
연결·heartbeat·온라인/오프라인/바쁨 상태·동시 처리 슬롯·일일 잔여 한도를 보유한다.
ADR 0002 의 페어링 흐름(일회용 토큰 → 연결 → 세션 생성 → 주기 heartbeat)을 따른다. 관련
엔티티: `DM-E-ProviderSession`. 상태: `DM-S-ProviderSessionState`.

### 2.8 Provider Pool

한 길드에 연결된 여러 프로바이더의 집합(SOURCE_BRIEF §2: `guild_id → provider_pool[]`).
별도 영속 엔티티가 아니라 **`guild_id` 기준으로 조회되는 Provider 집합의 논리적 뷰**다.
라우팅은 이 풀에서 후보를 생성·필터·점수화하여 1인을 선택한다(§8). Phase A 의 단일 공유
호스트를 **다중 프로바이더 풀**로 일반화한 개념이다(로드맵 §Phase B, 항목 305).

### 2.9 Model Capability

프로바이더가 제공하는 모델별 능력 정보: 모델명·부담 수준·최대 컨텍스트·예상 속도
(SOURCE_BRIEF §14 `provider_capability`). 라우팅 §8.3(부담수준 필터)·§8.1(후보 생성)의
입력. 관련 엔티티: `DM-E-ProviderCapability`, `DM-E-ModelProfile`. 값 객체:
`DM-V-ModelName`, `DM-V-ModelBurdenLevel`.

### 2.10 Contribution Policy

프로바이더가 모델별로 설정하는 기여 한계: 허용 역할·허용 채널·하루 한도·동시 한도·요청당
최대 처리 시간·긴 프롬프트 허용 여부·프롬프트 길이 상한·요청자 허용 범위(전체/신뢰이상/
관리자만)(SOURCE_BRIEF §5, §14 `provider_contribution_policy`, 로드맵 차수 19). 핵심
원칙: **프로바이더는 모든 요청을 받을 의무가 없다.** 관련 엔티티:
`DM-E-ProviderContributionPolicy`. 값 객체: `DM-V-LimitPolicy`, `DM-V-TimeoutPolicy`.

### 2.11 Request

일반 유저가 보낸 AI 요청(SOURCE_BRIEF §14 `request`). 요청자·guild·channel·메타(프롬프트
길이·첨부)·필요 모델 부담 수준·선택된 provider·상태·실패 사유를 담는다. 상태는
`DM-S-RequestState`(10상태). 관련 엔티티: `DM-E-AiRequest`. 식별자: `DM-V-RequestId`.

### 2.12 Routing Decision

요청 1건에 대한 라우팅 결과: 생성된 후보(`RoutingCandidate`)·단계별 필터 통과/탈락·공정성
점수·최종 선택 provider·fallback 후보·선택 사유. 라우팅 19단계(SOURCE_BRIEF §7) 중
8~14단계의 산출물이다. 관련 엔티티: `DM-E-RoutingCandidate`, `DM-E-RoutingDecision`.

### 2.13 Usage Log / Contribution Log

요청 처리 결과의 영속 기록을 **두 관점**으로 분리한다(README §P1 모델링 결정 #10, 로드맵
항목 323/324):
- **`DM-E-UsageLog`(요청자 관점, SOURCE_BRIEF §14 `usage_log`)**: **누가 얼마나** 사용했는가.
  유저 일일 한도 판정·`/my-usage` 의 데이터 소스.
- **`DM-E-ContributionLog`(프로바이더 관점)**: **어떤 provider 가 얼마나** 처리했는가(기여량).
  공정성 점수의 "최근 처리량"·관리자 공정성 리포트·`/providers` 기여량 표시의 데이터 소스.

둘 다 민감 프롬프트 내용을 포함하지 않으며(`DM-R-10`) 로드맵 차수 29 를 뒷받침한다.

### 2.14 Privacy Notice

질문 내용이 프로바이더 PC 로 전송될 수 있다는 사실에 대한 고지와 처리 주체 표시 방식
(SOURCE_BRIEF §10, ADR 0002 프라이버시). 서버 정책으로 모드 A(익명)/B(부분 공개)/
C(관리자만 공개, 기본 추천)를 선택한다. README 보안·프라이버시 불변식: **프라이버시 고지
필수, 기본 모드 C.** 별도 엔티티가 아닌 `GuildPolicy.privacy_mode`(값 객체
`DM-V-PrivacyMode`, §5.13)와 고지 문구로 모델링한다.

---

## 3. 도메인 관계도

표기: `1`=하나, `*`=다수, `0..1`=선택. 화살표는 소유/참조 방향.

```
                          ┌──────────────────────────────────────────────┐
                          │                  Guild                         │
                          │  (DM-E-Guild, PK guild_id)                     │
                          └───┬───────────┬───────────┬───────────┬───────┘
                              │1          │1          │1          │1
                       owns   │1          │*          │*          │*
                              ▼           ▼           ▼           ▼
                       GuildPolicy   AllowedChannel  RolePolicy   Provider(=Pool)
                       (DM-E-…)      (DM-E-…)        (DM-E-…)     (DM-E-Provider)
                                                                  │1
                                                          ┌───────┼────────────┐
                                                          │1      │*           │0..1
                                                          ▼       ▼            ▼
                                                 ProviderApproval  Provider   ProviderSession
                                                 (DM-E-…)          Capability (DM-E-…)
                                                                   (DM-E-…)   │1
                                                                   │1         ▼
                                                                   ▼      ProviderHealth
                                                          ProviderContribution (DM-E-…)
                                                          Policy (DM-E-…)
```

```
   User ──places──▶ AiRequest ──produces──▶ RoutingDecision ──selects──▶ Provider
   (DM-V-UserId)    (DM-E-AiRequest)         (DM-E-RoutingDecision)        (DM-E-Provider)
                          │1                        │1
                          ▼                         ▼* RoutingCandidate (DM-E-…)
                    RequestExecution                  └─ candidate.provider_id → Provider
                    (DM-E-…)
                          │1 produces (on completion)
                          ▼
              UsageLog (DM-E-UsageLog) ── requester_id → User (요청자 사용량)
              + ContributionLog (DM-E-ContributionLog) ── provider_id → Provider (기여량)
```

### 3.1 Guild–Pool

`Guild (1) ──owns──▶ Provider (*)`. Provider Pool 은 `guild_id` 기준으로 모인 Provider
집합의 논리적 뷰(§2.8). 한 Provider 는 정확히 하나의 Guild 에 속한다(`provider.guild_id`
필수 FK). Guild 는 또한 `GuildPolicy (1)`·`AllowedChannel (*)`·`RolePolicy (*)` 를 소유한다.

### 3.2 Provider–Agent

`Provider (1) ──has──▶ ProviderSession (0..1)`. Provider Agent(프로바이더 PC 프로그램)가
인증된 outbound 연결을 맺으면 정확히 하나의 활성 `ProviderSession` 이 생성된다. 미연결 시
세션은 없다(`0..1`). 동일 owner 재연결 시 이전 세션은 graceful close 후 교체된다(로드맵
항목 69, 118). Agent 의 연결 자체는 `DM-S-AgentConnState` 로, 세션은
`DM-S-ProviderSessionState` 로 추적한다.

### 3.3 Provider–Capability

`Provider (1) ──provides──▶ ProviderCapability (*)`. 한 프로바이더는 여러 모델을 제공할 수
있고(예: `llama3:8b`, `mistral:7b`), 각 Capability 는 하나의 `ModelProfile` 을 참조한다
(`ModelProfile (1) ◀──refers── ProviderCapability (*)`). 각 Capability 는 모델별
`ProviderContributionPolicy (1)` 와 1:1 대응한다(모델별 한도/허용 범위).

### 3.4 User–Role

`User (*) ──has──▶ Role (*)` (Discord 다대다). 한 멤버의 **최대 허용 부담 수준**과 **일일
한도**는 그가 가진 모든 Role 의 `RolePolicy` 를 **합집합(최대치)** 으로 해석해 산출한다
(로드맵 항목 402). Role 미지정 멤버는 `GuildPolicy.default_role_policy` 를 따른다.

### 3.5 Request–RoutingDecision

`AiRequest (1) ──produces──▶ RoutingDecision (1)`. 라우팅이 시작된 요청은 정확히 하나의
RoutingDecision 을 갖는다. RoutingDecision 은 `RoutingCandidate (*)`(후보 목록, 단계별
필터 결과 포함)를 보유하고, 그중 점수 1위를 `selected_provider_id` 로, 차순위를
`fallback_provider_id (0..1)` 로 가리킨다(§8.9, §8.10). 권한/혼잡으로 후보가 0명이면
선택 없이 요청은 `rejected` 또는 `failed` 로 귀결된다.

### 3.6 UsageLog–ContributionLog

`RequestExecution (1) ──produces(on completion)──▶ UsageLog (1)` + `ContributionLog (1)`.
완료 시 **요청자 관점(`DM-E-UsageLog`, 누가 얼마나)** 과 **프로바이더 기여 관점
(`DM-E-ContributionLog`, 어떤 provider 가 얼마나)** 의 두 기록을 함께 남긴다(README §P1 모델링
결정 #10). UsageLog 는 유저 일일 한도 카운트의, ContributionLog 는 공정성 점수의 "최근
처리량/실패율"·관리자 공정성 리포트의 데이터 소스가 된다(§8.8, 로드맵 차수 29).

---

## 4. 엔티티 정의

표 형식: 속성명 · 타입(§5 값 객체 또는 기본형/enum 참조) · 필수 · 설명 · 불변식.
식별자(PK)와 관계(FK)를 명시한다. 비-목표(billing/price/seller/payout) 필드는 §1.4-2 가드에
따라 어떤 엔티티에도 존재하지 않는다.

### 4.1 Guild — `DM-E-Guild`

길드(Discord 서버) = Provider Pool 의 최상위 경계.

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `guild_id` | `DM-V-GuildId` | Y | PK. Discord 서버 식별자 | 불변, 전역 유일 |
| `name` | string | N | 표시용 길드 이름(캐시) | — |
| `created_at` | timestamp | Y | 풀 활성화 시각 | 생성 후 불변 |

관계: `1 Guild ── * Provider`, `1 Guild ── 1 GuildPolicy`, `1 Guild ── * AllowedChannel`,
`1 Guild ── * RolePolicy`.

### 4.2 GuildPolicy — `DM-E-GuildPolicy`

서버 단위 LLM 사용 정책(SOURCE_BRIEF §6, §14 `guild_policy`).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `guild_id` | `DM-V-GuildId` | Y | PK·FK → Guild | Guild 와 1:1 |
| `approval_mode` | enum(`auto`,`manual`) | Y | 프로바이더 승인 방식 | 기본 `manual` |
| `default_daily_limit` | `DM-V-LimitPolicy` | Y | 서버 기본 일일 요청 제한 | ≥ 0 |
| `default_role_burden` | `DM-V-ModelBurdenLevel`[] | Y | 역할 미지정 멤버 허용 부담수준 | 기본 `[light]` |
| `privacy_mode` | `DM-V-PrivacyMode` | Y | 처리 주체 표시 방식(A_ANONYMOUS/B_PARTIAL/C_ADMIN_ONLY) | 기본 `C_ADMIN_ONLY` (`DM-R-09`) |
| `notice_required` | bool | Y | 프라이버시 고지 노출 필수 | 항상 `true`(`DM-R-08`) |
| `updated_at` | timestamp | Y | 정책 변경 시각 | — |

관계: FK `guild_id` → `DM-E-Guild`.

### 4.3 AllowedChannel — `DM-E-AllowedChannel`

LLM 사용이 허용된 채널(SOURCE_BRIEF §6, 로드맵 항목 325, 397).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `guild_id` | `DM-V-GuildId` | Y | PK(복합)·FK → Guild | — |
| `channel_id` | `DM-V-ChannelId` | Y | PK(복합). 허용 채널 | (guild_id, channel_id) 유일 |
| `allowed` | bool | Y | 허용(`true`)/금지(`false`) | deny 가 allow 우선 |
| `added_by` | `DM-V-UserId` | Y | 설정한 관리자 | — |

관계: 복합 PK (`guild_id`,`channel_id`); FK `guild_id` → `DM-E-Guild`.

### 4.4 RolePolicy — `DM-E-RolePolicy`

역할별 허용 부담 수준·일일 한도(SOURCE_BRIEF §6, 로드맵 항목 399~401).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `guild_id` | `DM-V-GuildId` | Y | PK(복합)·FK → Guild | — |
| `role_id` | `DM-V-RoleId` | Y | PK(복합). 대상 역할 | (guild_id, role_id) 유일 |
| `allowed_burdens` | `DM-V-ModelBurdenLevel`[] | Y | 허용 부담 수준 집합 | 부분집합 ⊆ {light,standard,heavy,restricted} |
| `daily_limit` | `DM-V-LimitPolicy` | Y | 역할 일일 요청 한도 | ≥ 0 |

관계: 복합 PK (`guild_id`,`role_id`); FK `guild_id` → `DM-E-Guild`. 다중 역할은 합집합
해석(§3.4, `DM-R-04`). **저장 키는 `DM-V-RoleId`(snowflake)** 이며, `RoleTier`
(`member`/`trusted`/`admin`, `DM-V-RoleTier` §5.14)는 설정된 role_id 집합으로부터 파생되는
표현용 추상값일 뿐 저장 키가 아니다(README §P1 모델링 결정 #8).

### 4.5 Provider — `DM-E-Provider`

커뮤니티에 로컬 LLM 을 기여하는 주체(SOURCE_BRIEF §2, §14 `provider`).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `provider_id` | `DM-V-ProviderId` | Y | PK. 프로바이더 식별자 | 불변 |
| `guild_id` | `DM-V-GuildId` | Y | FK → Guild. 소속 길드 | 불변(이전 불가) |
| `user_id` | `DM-V-UserId` | Y | FK → User. 등록 유저 | (guild_id, user_id) 유일(`DM-R-02`) |
| `state` | `DM-S-ProviderState` | Y | 등록·가용 상태(10상태) | 전이표(§6.1)만 허용 |
| `registered_at` | timestamp | Y | 등록 요청 시각 | — |
| `removed_at` | timestamp | N | 이탈/제거 시각 | state=`removed` 일 때만 설정 |

관계: FK `guild_id` → Guild, `user_id` → User; `1 Provider ── * ProviderCapability`,
`1 Provider ── 0..1 ProviderSession`, `1 Provider ── 1 ProviderApproval`. **billing/price
필드 없음(가드).**

### 4.6 ProviderApproval — `DM-E-ProviderApproval`

등록 요청의 승인 라이프사이클(로드맵 차수 16).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `provider_id` | `DM-V-ProviderId` | Y | PK·FK → Provider | 1:1 |
| `state` | `DM-S-ApprovalState` | Y | `pending`/`approved`/`rejected`/`expired` | 전이표(§6.… 승인은 ProviderState 와 연동) |
| `requested_by` | `DM-V-UserId` | Y | 등록 요청자 | provider.user_id 와 동일 |
| `decided_by` | `DM-V-UserId` | N | 승인/거절한 관리자 | manual 일 때 필수 |
| `consent_ack` | bool | Y | "프롬프트가 내 PC 로 전송됨" 동의 | 승인 전 `true` 필요(`DM-R-08`) |
| `token_issued` | bool | Y | 승인 시 일회용 Agent 토큰 발급 여부 | approved 시에만 |
| `decided_at` | timestamp | N | 결정 시각 | — |

관계: FK `provider_id` → `DM-E-Provider`. 토큰 평문은 저장하지 않는다(해시만, 로드맵 항목
154·178, `DM-R-10`).

### 4.7 ProviderSession — `DM-E-ProviderSession`

인증된 Agent 연결 1건의 런타임 상태(SOURCE_BRIEF §14 `provider_session`).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `session_id` | `DM-V-SessionId` | Y | PK. 세션 식별자 | 불변 |
| `provider_id` | `DM-V-ProviderId` | Y | FK → Provider | 활성 세션은 provider 당 ≤ 1 |
| `conn_state` | `DM-S-AgentConnState` | Y | WS 연결 상태 | 전이표(§6.3) |
| `session_state` | `DM-S-ProviderSessionState` | Y | online_idle/busy/paused/limited/offline | 전이표(§6.2) |
| `agent_version` | string | N | 보고된 에이전트 버전 | — |
| `platform` | string | N | 보고된 OS/플랫폼 | — |
| `last_heartbeat_at` | timestamp | Y | 마지막 ping/pong 시각 | 만료 시 offline(`DM-R-06`) |
| `active_slots` | int | Y | 현재 동시 처리 중 요청 수 | 0 ≤ active_slots ≤ max_concurrency |
| `connected_at` | timestamp | Y | 인증·연결 성립 시각 | — |

관계: FK `provider_id` → `DM-E-Provider`. inbound 포트 없음·outbound 전용(불변식,
보안 §9.5).

### 4.8 ProviderCapability — `DM-E-ProviderCapability`

프로바이더가 제공하는 모델별 능력(SOURCE_BRIEF §14 `provider_capability`).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `provider_id` | `DM-V-ProviderId` | Y | PK(복합)·FK → Provider | — |
| `model_name` | `DM-V-ModelName` | Y | PK(복합)·FK → ModelProfile | (provider_id, model_name) 유일 |
| `burden_level` | `DM-V-ModelBurdenLevel` | Y | 해당 모델 부담 수준 | 프로바이더 오버라이드 가능(항목 344) |
| `max_context` | int | Y | 최대 컨텍스트 토큰 | > 0 |
| `expected_speed` | enum(`slow`,`normal`,`fast`) | N | 예상 처리 속도 | — |

관계: 복합 PK (`provider_id`,`model_name`); FK `provider_id` → Provider,
`model_name` → `DM-E-ModelProfile`; `1 ProviderCapability ── 1 ProviderContributionPolicy`.

### 4.9 ProviderContributionPolicy — `DM-E-ProviderContributionPolicy`

모델별 기여 한계(SOURCE_BRIEF §5, §14, 로드맵 차수 19).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `provider_id` | `DM-V-ProviderId` | Y | PK(복합)·FK → Provider | — |
| `model_name` | `DM-V-ModelName` | Y | PK(복합)·FK → Capability | Capability 와 1:1 |
| `allowed_roles` | `DM-V-RoleId`[] | Y | 허용 역할(빈 집합=전체) | — |
| `allowed_channels` | `DM-V-ChannelId`[] | Y | 허용 채널(빈 집합=전체) | ⊆ 길드 AllowedChannel |
| `requester_scope` | enum(`all`,`trusted`,`admin`) | Y | 요청자 허용 범위 | 기본 보수적(항목 426) |
| `daily_limit` | `DM-V-LimitPolicy` | Y | 모델별 하루 한도 | ≥ 0 |
| `concurrency_limit` | `DM-V-LimitPolicy` | Y | 동시 요청 한도 | ≥ 1 |
| `max_request_seconds` | `DM-V-TimeoutPolicy` | Y | 요청당 최대 처리 시간 | > 0 |
| `allow_long_prompt` | bool | Y | 긴 프롬프트 허용 | — |
| `max_prompt_len` | int | Y | 프롬프트 길이 상한 | > 0 |

관계: 복합 PK (`provider_id`,`model_name`); FK → `DM-E-ProviderCapability`.

### 4.10 ModelProfile — `DM-E-ModelProfile`

알려진 모델명 → 기본 부담 수준 등 모델 메타(로드맵 항목 327·343).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `model_name` | `DM-V-ModelName` | Y | PK. 정규화된 모델명 | 유일 |
| `default_burden` | `DM-V-ModelBurdenLevel` | Y | 기본 부담 수준(휴리스틱) | 미상 시 `standard`(`DM-R-05`) |
| `default_timeout` | `DM-V-TimeoutPolicy` | Y | 부담수준별 기본 타임아웃 | > 0 |
| `label` | string | N | 표시 라벨/이모지 | — |

관계: `1 ModelProfile ── * ProviderCapability`.

### 4.11 AiRequest — `DM-E-AiRequest`

일반 유저의 AI 요청(SOURCE_BRIEF §14 `request`).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `request_id` | `DM-V-RequestId` | Y | PK. 요청 식별자 | 불변·전역 유일(항목 41·492) |
| `requester_id` | `DM-V-UserId` | Y | FK → User. 요청자 | — |
| `guild_id` | `DM-V-GuildId` | Y | FK → Guild | — |
| `channel_id` | `DM-V-ChannelId` | Y | FK → AllowedChannel | 허용 채널이어야 함(`DM-R-03`) |
| `command` | string | Y | 명령 종류(`/ask` 등) | — |
| `prompt_len` | int | Y | 프롬프트 길이(내용 미저장) | ≥ 0; 내용 미기록(`DM-R-10`) |
| `has_attachment` | bool | Y | 첨부 존재 여부 | — |
| `weight` | `DM-V-RequestWeight` | Y | 판정된 요청 무게 | 6단계 후 산출 |
| `required_burden` | `DM-V-ModelBurdenLevel` | Y | 필요 모델 부담 수준 | 7단계 후 산출 |
| `state` | `DM-S-RequestState` | Y | 처리 상태(10상태) | 전이표(§6.4)만 허용 |
| `selected_provider_id` | `DM-V-ProviderId` | N | 선택된 provider | routing 이후 설정 |
| `failure_reason` | enum(`ERR-*`) | N | 실패 사유 코드 | state∈{failed,rejected}일 때 |
| `created_at` | timestamp | Y | 수신 시각 | — |

관계: FK `requester_id`→User, `guild_id`→Guild, `channel_id`→AllowedChannel,
`selected_provider_id`→Provider; `1 AiRequest ── 1 RoutingDecision`,
`1 AiRequest ── 1 RequestExecution`.

### 4.12 RoutingCandidate — `DM-E-RoutingCandidate`

라우팅 파이프라인의 후보 1건과 단계별 필터/점수 결과(로드맵 차수 21~22).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `request_id` | `DM-V-RequestId` | Y | PK(복합)·FK → AiRequest | — |
| `provider_id` | `DM-V-ProviderId` | Y | PK(복합)·FK → Provider | (request_id, provider_id) 유일 |
| `passed_filters` | bool | Y | §8.2~8.7 전부 통과 여부 | — |
| `rejected_stage` | enum(filter 단계) | N | 탈락 단계(디버그/로그) | passed=false 일 때 |
| `fairness_score` | float(실수) | N | §8.8 공정성 점수 | passed=true 일 때만 산출 |

관계: 복합 PK (`request_id`,`provider_id`); FK → AiRequest, Provider.

### 4.13 RoutingDecision — `DM-E-RoutingDecision`

요청 1건의 최종 라우팅 결과(§8.9~8.10).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `request_id` | `DM-V-RequestId` | Y | PK·FK → AiRequest | 1:1 |
| `selected_provider_id` | `DM-V-ProviderId` | N | 점수 1위 provider | 후보 0명이면 null |
| `fallback_provider_id` | `DM-V-ProviderId` | N | fallback 후보(차순위) | 원 provider 와 상이(§8.10) |
| `outcome` | enum(`selected`,`no_provider_available`,`permission_denied`) | Y | 라우팅 귀결 | — |
| `candidate_count` | int | Y | 생성된 후보 수 | ≥ 0 |
| `reason` | string | N | 선택/거절 사유 요약 | — |
| `decided_at` | timestamp | Y | 결정 시각 | — |

관계: FK → AiRequest, `selected_provider_id`/`fallback_provider_id` → Provider;
`1 RoutingDecision ── * RoutingCandidate`.

### 4.14 RequestExecution — `DM-E-RequestExecution`

선택된 provider 에서의 실제 실행 추적(로드맵 차수 23).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `request_id` | `DM-V-RequestId` | Y | PK·FK → AiRequest | 1:1 |
| `provider_id` | `DM-V-ProviderId` | Y | FK → Provider. 실행 주체 | selected 또는 fallback |
| `is_fallback` | bool | Y | fallback 실행 여부 | fallback 1회 한정(`DM-R-07b`/§8.10) |
| `started_at` | timestamp | N | running 진입 시각 | sent 이후 |
| `finished_at` | timestamp | N | 완료/실패 시각 | — |
| `timeout_seconds` | `DM-V-TimeoutPolicy` | Y | 적용 타임아웃 | min(정책, 부담수준 기본) |
| `outcome` | enum(`completed`,`failed`,`timeout`,`busy`) | N | 실행 결과 | finished 시 설정 |
| `usage_tokens` | int | N | 보고된 토큰 사용량 | ≥ 0; 없으면 null |

관계: FK → AiRequest, Provider; 완료 시 `1 RequestExecution ── 1 UsageLog`(§4.15) +
`1 ContributionLog`(§4.17) 생성.

### 4.15 UsageLog — `DM-E-UsageLog` (요청자 관점)

요청자 사용량 기록(SOURCE_BRIEF §14 `usage_log`, 로드맵 차수 29). **누가 얼마나 사용했는가**를
남긴다(유저 일일 한도·`/my-usage` 소스). 프로바이더 기여량은 별도 `DM-E-ContributionLog`(§4.17).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `log_id` | bigint | Y | PK. 일련 식별자 | 불변 |
| `request_id` | `DM-V-RequestId` | Y | FK → AiRequest | 요청당 ≥ 1 |
| `guild_id` | `DM-V-GuildId` | Y | FK → Guild | 집계 파티션 키 |
| `requester_id` | `DM-V-UserId` | Y | 누가 사용했는가 | 일일 한도 카운트 소스 |
| `burden_level` | `DM-V-ModelBurdenLevel` | Y | 처리 부담 수준 | — |
| `outcome` | enum(`completed`,`failed`,`rejected`) | Y | 결과 | — |
| `logged_at` | timestamp | Y | 기록 시각 | retention 대상(항목 610) |

관계: FK → AiRequest, Guild. **프롬프트/응답 내용 미포함(`DM-R-10`, 항목 612).
billing/price/payout 필드 없음(가드).**

### 4.16 ProviderHealth — `DM-E-ProviderHealth`

프로바이더 보호/건강 상태(SOURCE_BRIEF §9, 로드맵 차수 24).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `provider_id` | `DM-V-ProviderId` | Y | PK·FK → Provider | 1:1 |
| `health_state` | `DM-S-ProviderHealthState` | Y | `healthy`/`degraded`/`temporarily_unavailable`/`unhealthy` | 전이표(§6.6) |
| `cpu_load` | enum(`low`,`high`) | N | 보고된 CPU 부하 | high → 수신 중단 |
| `gpu_load` | enum(`low`,`high`) | N | 보고된 GPU 부하 | — |
| `battery` | enum(`charging`,`battery`) | N | 전원 상태 | `battery` → 자동 pause(`DM-R-06`) |
| `memory_ok` | bool | N | 메모리 여유 | false → 요청 거절 |
| `consecutive_failures` | int | Y | 연속 실패 횟수 | 임계 초과 → unhealthy(`DM-R-06`) |
| `remaining_daily` | int | N | 일일 잔여 요청(보고값) | ≥ 0 |
| `updated_at` | timestamp | Y | 마지막 보고 시각 | — |

관계: FK `provider_id` → `DM-E-Provider`. Agent 의 주기 보고(SOURCE_BRIEF §9 예시 프레임)로
갱신.

### 4.17 ContributionLog — `DM-E-ContributionLog` (프로바이더 관점)

프로바이더 기여량 기록(README §P1 모델링 결정 #10, 로드맵 항목 323/324, 차수 29). **어떤
provider 가 얼마나 처리했는가**를 남겨 공정성 점수·기여량 리포트·`/providers` 기여량 표시의
소스로 쓴다. 요청자 사용량은 별도 `DM-E-UsageLog`(§4.15).

| 속성 | 타입 | 필수 | 설명 | 불변식 |
|---|---|---|---|---|
| `log_id` | bigint | Y | PK. 일련 식별자 | 불변 |
| `request_id` | `DM-V-RequestId` | Y | FK → AiRequest | 요청당 ≤ 1(완료 시) |
| `guild_id` | `DM-V-GuildId` | Y | FK → Guild | 집계 파티션 키 |
| `provider_id` | `DM-V-ProviderId` | Y | 어떤 provider 가 처리했는가 | 기여량 집계 소스 |
| `model_name` | `DM-V-ModelName` | N | 처리에 쓰인 모델 | — |
| `burden_level` | `DM-V-ModelBurdenLevel` | Y | 처리 부담 수준 | — |
| `is_fallback` | bool | Y | fallback 실행 여부 | §4.14 와 정합 |
| `outcome` | enum(`completed`,`failed`) | Y | 처리 결과 | — |
| `logged_at` | timestamp | Y | 기록 시각 | retention 대상(항목 610) |

관계: FK → AiRequest, Guild, Provider. 완료 시 `1 RequestExecution ── 1 ContributionLog` 생성
(`UsageLog` 와 쌍). **프롬프트/응답 내용 미포함(`DM-R-10`). billing/price/payout 필드 없음(가드).**

---

## 5. 값 객체

값 객체는 식별 동등성이 아니라 **값 동등성**을 가지며 불변(immutable)이다. `DM-V-<이름>`.

### 5.1 GuildId — `DM-V-GuildId`
Discord 길드 식별자. 부호 없는 64비트 정수(snowflake)를 문자열로 표현. 불변·전역 유일.
`DM-E-Guild` 의 PK, 다수 엔티티의 FK.

### 5.2 ChannelId — `DM-V-ChannelId`
Discord 채널 식별자(snowflake). `AllowedChannel`·`AiRequest`·기여 정책의 허용 채널 집합에서
사용. 불변.

### 5.3 UserId — `DM-V-UserId`
Discord 사용자 식별자(snowflake). 일반 유저·관리자·프로바이더·요청자를 식별. 한 UserId 가
동시에 요청자이자 프로바이더일 수 있다.

### 5.4 RoleId — `DM-V-RoleId`
Discord 역할 식별자(snowflake). `RolePolicy`·기여 정책의 허용 역할 집합에서 사용. 멤버는
다중 RoleId 보유 가능(합집합 해석, §3.4).

### 5.5 ProviderId — `DM-V-ProviderId`
프로바이더 내부 식별자(UUID 등). `(guild_id, user_id)` 조합당 하나 발급(`DM-R-02`). 불변.

### 5.6 SessionId — `DM-V-SessionId`
프로바이더 세션 내부 식별자(UUID 등). 매 인증 연결마다 새로 발급. 불변. 토큰 값과 무관
(토큰은 해시 저장, `DM-R-10`).

### 5.7 RequestId — `DM-V-RequestId`
요청 추적 식별자. 봇·에이전트 양쪽에서 일관 사용(ADR 0002 프로토콜, 로드맵 항목 41).
요청-응답 상관관계·타임아웃·fallback 추적의 키. 전역 유일·불변.

### 5.8 ModelName — `DM-V-ModelName`
Ollama 모델 식별 문자열(예: `llama3:8b`, `qwen32b`). 정규화 후 `ModelProfile` 의 PK 및
`ProviderCapability` 의 일부 PK. 값 동등성.

### 5.9 ModelBurdenLevel — `DM-V-ModelBurdenLevel`
모델 처리 **부담도**(가격 등급 아님, `DM-R-01`). 정식 4값:
`light` · `standard` · `heavy` · `restricted`.

| 값 | 의미(SOURCE_BRIEF §4) | 예시 용도 |
|---|---|---|
| `light` | 작은 모델, 부담 낮음, 많은 프로바이더 감당 | 짧은 질문·간단 요약·가벼운 Q&A |
| `standard` | 일반 로컬 모델, 중간 부담 | 코딩 질문·일반 문서 요약 |
| `heavy` | 큰 모델, GPU/메모리 부담 큼 | 긴 코드 분석·복잡 설계 리뷰 |
| `restricted` | 프로바이더 특별 제한 | 특정 역할·채널·관리자 요청만 |

순서 관계: `light < standard < heavy`(부담 오름차순). `restricted` 는 부담 축이 아니라
**접근 제한 표식**이다(역할/채널/관리자 게이트).

### 5.10 RequestWeight — `DM-V-RequestWeight`
요청 무게. 프롬프트 길이·복잡도·첨부로 산출한 척도(로드맵 차수 20). 무게 → 필요
부담수준(`required_burden`) 매핑의 입력. 공정성 점수(§8.8)의 일부 가중 입력으로도 쓰인다.

정식 3값(enum, 확정): `light` · `medium` · `heavy`. 필요 모델 부담수준
(`DM-V-ModelBurdenLevel`) 매핑은 다음과 같다(README §P1 모델링 결정 #11, 로드맵 항목 446
경계값 확정):

| RequestWeight | → required_burden(`DM-V-ModelBurdenLevel`) |
|---|---|
| `light` | `light` |
| `medium` | `standard` |
| `heavy` | `heavy` |

경계값: 빈/초단문 프롬프트는 `light`, 초장문·첨부 동반·복잡 코드 분석은 `heavy`, 그 사이는
`medium`(미상 시 보수적으로 `medium`→`standard`, `DM-R-05` 와 정합).

### 5.11 LimitPolicy — `DM-V-LimitPolicy`
한도 정책 값: `{ kind: daily|concurrency, value: int }`. 일일 요청 한도·동시 처리 한도를
표현(SOURCE_BRIEF §5, §6). `value ≥ 0`(일일), `≥ 1`(동시). 잔여량은 `UsageLog` 집계로 판정.

### 5.12 TimeoutPolicy — `DM-V-TimeoutPolicy`
타임아웃 정책 값: 초 단위 양의 실수. 요청당 최대 처리 시간(SOURCE_BRIEF §5)과 부담수준별
기본 타임아웃(`ModelProfile.default_timeout`)의 최소값으로 실행 타임아웃을 결정(§4.14).

### 5.13 PrivacyMode — `DM-V-PrivacyMode`
처리 주체 표시 방식(SOURCE_BRIEF §10, README §보안·프라이버시 불변식·§P1 모델링 결정 #9).
정식 3값(enum, 확정): `A_ANONYMOUS`(익명) · `B_PARTIAL`(부분 공개) · `C_ADMIN_ONLY`(관리자만
공개). **기본값 `C_ADMIN_ONLY`**(`DM-R-09`). `GuildPolicy.privacy_mode` 가 이 값을 보유하며,
화면(`SCR-405`/`SCR-409`/`SCR-510`)·API(§5.4 `API-CMD-PRIVACY`·§6.8
`API-REST-GUILD-PRIVACY-SET`)가 이 값 객체를 인용한다.

| 값 | 의미 | 일반 유저 노출 |
|---|---|---|
| `A_ANONYMOUS` | 익명 — 처리 주체 미표시 | "커뮤니티 로컬 AI 풀에서 처리됨" |
| `B_PARTIAL` | 부분 공개 — 처리 위치만 | "커뮤니티 프로바이더가 처리 / community local provider" |
| `C_ADMIN_ONLY` | 관리자만 provider 식별(기본·추천) | 일반 유저엔 "풀 처리됨", 관리자만 provider 노출 |

### 5.14 RoleTier — `DM-V-RoleTier`(파생 표현값)
역할 등급의 **표현용 추상값**: `member`(일반) · `trusted`(신뢰 멤버) · `admin`(관리자)
(SOURCE_BRIEF §6). 저장 키가 아니라 설정된 `DM-V-RoleId`(snowflake) 집합으로부터 파생되는
라벨이다(README §P1 모델링 결정 #8). `RolePolicy` 의 저장 키는 `DM-V-RoleId` 이며, RoleTier 는
응답·화면에 덧붙이는 표현값일 뿐 PK 가 아니다(§4.4 참조).

---

## 6. 상태 모델

전이표 형식: **현재 상태 → 이벤트/조건 → 다음 상태**. 표에 없는 전이는 거부한다(가드,
로드맵 항목 390·512). 상태명은 README §정식 어휘 그대로다.

### 6.1 Provider 등록 상태 — `DM-S-ProviderState` (10상태)

상태 목록(README): `unregistered` · `pending` · `approved` · `online_idle` ·
`online_busy` · `paused` · `limited` · `offline` · `unhealthy` · `removed`.

| 현재 상태 | 이벤트 / 조건 | 다음 상태 | 비고 |
|---|---|---|---|
| `unregistered` | `/provider-join` 등록 요청 (`DM-EV-ProviderRegistered`) | `pending` | approval_mode=manual |
| `unregistered` | 등록 요청 + approval_mode=auto | `approved` | 자동 승인 |
| `pending` | 관리자 승인 (`DM-EV-ProviderApproved`) | `approved` | 일회용 토큰 발급 |
| `pending` | 거절/만료 | `unregistered` | — |
| `approved` | Agent 연결·인증 (`DM-EV-ProviderAgentConnected`) | `online_idle` | 세션 생성 |
| `online_idle` | 요청 배정·처리 시작 | `online_busy` | active_slots > 0 |
| `online_busy` | 모든 슬롯 처리 완료 | `online_idle` | active_slots = 0 |
| `online_idle`/`online_busy` | `/provider-pause` 또는 배터리 모드 (`DM-EV-ProviderPaused`) | `paused` | 라우팅 후보 제외 |
| `paused` | `/provider-resume` (`DM-EV-ProviderResumed`) | `online_idle` | — |
| `online_idle`/`online_busy` | 한도 소진/부하 임계 | `limited` | 일시적 후보 제외 |
| `limited` | 한도 회복(일일 리셋)/부하 정상 | `online_idle` | — |
| 임의 online_* | 연결 끊김/heartbeat 만료 (`DM-EV-ProviderAgentDisconnected`) | `offline` | 세션 종료 |
| `offline` | 재연결·인증 | `online_idle` | — |
| 임의 상태 | 반복 실패 임계 초과 (`DM-EV-ProviderMarkedUnhealthy`) | `unhealthy` | 자동 비활성화 |
| `unhealthy` | 복구·관리자 재활성 | `offline`/`approved` | — |
| 임의 상태 | `/provider-leave` 또는 `/provider-remove` | `removed` | 풀 이탈(종단) |

### 6.2 Provider Session 상태 — `DM-S-ProviderSessionState`

세션 런타임 상태(Provider 의 online_* 하위 표현).

| 현재 상태 | 이벤트 / 조건 | 다음 상태 |
|---|---|---|
| (생성) | 인증·capability 바인딩 | `online_idle` |
| `online_idle` | 요청 수신·슬롯 점유 | `online_busy` |
| `online_busy` | 슬롯 해제(완료/실패) | `online_idle` |
| `online_idle`/`online_busy` | pause 신호 | `paused` |
| `paused` | resume 신호 | `online_idle` |
| `online_*` | 한도/부하 | `limited` |
| `limited` | 한도/부하 정상화 | `online_idle` |
| 임의 | heartbeat 만료/연결 종료 | `offline`(종단, 세션 폐기) |

### 6.3 Agent 연결 상태 — `DM-S-AgentConnState`

WebSocket 연결 자체의 상태(ADR 0002 프로토콜).

| 현재 상태 | 이벤트 / 조건 | 다음 상태 |
|---|---|---|
| `disconnected` | outbound WS 연결 시작 | `connecting` |
| `connecting` | TLS/핸드셰이크 성공, `auth` 프레임 송신 | `authenticating` |
| `authenticating` | `AuthOk` 수신 | `connected` |
| `authenticating` | `AuthErr`/토큰 무효 | `disconnected` |
| `connected` | ping/pong 정상 | `connected` |
| `connected` | 네트워크 끊김/heartbeat 만료 | `reconnecting` |
| `reconnecting` | 백오프 후 재연결 성공 | `authenticating` |
| `reconnecting` | 종료 시그널 | `disconnected`(종단) |

### 6.4 Request 처리 상태 — `DM-S-RequestState` (10상태)

상태 목록(README): `received` · `policy_checked` · `routing` · `queued` ·
`sent_to_provider` · `running` · `completed` · `failed` · `fallback_running` · `rejected`.

| 현재 상태 | 이벤트 / 조건 | 다음 상태 | 비고 |
|---|---|---|---|
| (시작) | `/ask` 수신 (`DM-EV-AiRequestReceived`) | `received` | request_id 생성 |
| `received` | 길드/채널/유저/역할/길이 검증 통과(1~5단계) | `policy_checked` | — |
| `received` | 정책 위반(채널 불가/권한 부족) | `rejected` | `DM-R-03`/`DM-R-04` |
| `policy_checked` | 무게·필요수준 판단(6~7단계) 후 라우팅 시작 | `routing` | — |
| `routing` | provider 선택 (`DM-EV-ProviderSelected`) | `queued` | 후보 ≥ 1 |
| `routing` | 후보 0명(no_provider_available) | `failed` | 안내 메시지 |
| `routing` | 권한 부족·다운그레이드 불가 | `rejected` | — |
| `queued` | 동시 슬롯 확보·전송 (`DM-EV-RequestSentToProvider`) | `sent_to_provider` | 큐 상한 초과 시 `rejected` |
| `sent_to_provider` | Agent 처리 시작 | `running` | active_slots++ |
| `running` | 결과 수신 (`DM-EV-ProviderResponseReceived` → `DM-EV-RequestCompleted`) | `completed` | UsageLog 기록 |
| `running` | 타임아웃/오류 (`DM-EV-RequestFailed`) | `fallback_running` | 다른 provider 1회 (`DM-EV-FallbackStarted`) |
| `running` | 타임아웃/오류 + fallback 후보 없음 | `failed` | — |
| `fallback_running` | fallback 결과 수신 | `completed` | UsageLog 기록 |
| `fallback_running` | fallback 실패 | `failed` | 안내 메시지 |

종단 상태: `completed` · `failed` · `rejected`.

### 6.5 Routing 상태 — `DM-S-RequestState`(routing 부분 흐름)

라우팅은 Request 상태머신의 `routing` 구간 내부 흐름이다(별도 영속 상태 머신이 아니라
`RoutingDecision.outcome` 으로 귀결).

| 단계 | 산출/전이 |
|---|---|
| 후보 생성(§8.1) | RoutingCandidate[] 생성 |
| 필터(§8.2~8.7) | 각 후보 `passed_filters` 판정·`rejected_stage` 기록 |
| 점수(§8.8) | 통과 후보에 `fairness_score` 부여 |
| 선택(§8.9) | outcome=`selected` → Request `queued` |
| 후보 0명 | outcome=`no_provider_available` → Request `failed` |
| 권한 부족 | outcome=`permission_denied` → Request `rejected` |

### 6.6 Provider Health 상태 — `DM-S-ProviderHealthState`

보호/건강 상태(SOURCE_BRIEF §9).

| 현재 상태 | 이벤트 / 조건 | 다음 상태 |
|---|---|---|
| `healthy` | CPU/GPU 임계 초과·메모리 부족 보고 | `degraded` |
| `degraded` | 부하 정상화 | `healthy` |
| `healthy`/`degraded` | 네트워크 불안정 보고 | `temporarily_unavailable` |
| `temporarily_unavailable` | 안정화·재연결 | `healthy` |
| 임의 | 연속 실패 임계 초과 | `unhealthy`(자동 비활성, ProviderState 연동) |
| `unhealthy` | 관리자 재활성/복구 | `healthy` |

---

## 7. 도메인 규칙

README/SOURCE_BRIEF 의 규칙을 그대로 코드화한다. `DM-R-01`~`DM-R-10`.

- **`DM-R-01` 부담 수준은 가격이 아니다.** `ModelBurdenLevel`(`light`/`standard`/`heavy`/
  `restricted`)은 처리 부담도이며, billing/price/seller/payout 어떤 금전 개념과도 연결되지
  않는다(SOURCE_BRIEF §4·§14, §1.4-2 가드).
- **`DM-R-02` 길드당 유저 1 프로바이더.** `(guild_id, user_id)` 조합은 활성 Provider 를
  최대 1개만 갖는다. 중복 등록 금지(로드맵 항목 363). `removed` 후 재등록은 허용.
- **`DM-R-03` 채널 허용 필수.** 요청은 `AllowedChannel.allowed=true` 인 채널에서만 처리되며,
  deny 설정은 allow 보다 우선한다(SOURCE_BRIEF §6, 로드맵 항목 396).
- **`DM-R-04` 역할 권한 = 합집합.** 멤버의 허용 부담수준·일일 한도는 보유한 모든 역할
  `RolePolicy` 의 합집합(최대치). 역할 미지정 시 `GuildPolicy.default_*`(SOURCE_BRIEF §6,
  로드맵 항목 402~403).
- **`DM-R-05` 미상 모델 = 보수적 기본값.** 부담 수준이 미상인 모델은 `standard` 로 간주한다
  (로드맵 항목 347).
- **`DM-R-06` 프로바이더 보호 우선.** 배터리 모드 → 자동 pause, 메모리 부족 → 요청 거절,
  CPU/GPU 임계 초과 → 수신 중단, heartbeat 만료 → offline, 반복 실패 → 자동 unhealthy.
  보호는 라우팅·요청 처리보다 우선한다(SOURCE_BRIEF §9, 로드맵 차수 24).
- **`DM-R-07` 부담수준 우선 배정.** `light` 요청은 `light` 프로바이더에, `standard` 는
  `standard` 에 우선 배정한다. `heavy` 프로바이더는 `light` 요청에 기본적으로 배정하지
  않는다(낭비 방지). `heavy` 후보가 light 요청에 쓰이는 경우는 다른 적합 후보가 전혀 없을
  때의 예외뿐이다(SOURCE_BRIEF §8·§15, README 예시 `DM-R-07`). **fallback 은 동일 조건으로
  다른 provider 에게 1회만**(`DM-R-07b`, SOURCE_BRIEF §11).
- **`DM-R-08` 동의·고지 필수.** 프로바이더는 승인 전 "프롬프트가 내 PC 로 전송됨"에 동의
  (`ProviderApproval.consent_ack=true`)해야 하고, 길드는 프라이버시 고지를 반드시
  노출한다(`GuildPolicy.notice_required=true`, SOURCE_BRIEF §10, README 불변식).
- **`DM-R-09` 기본 프라이버시 모드 C.** 처리 주체 표시 기본값은 모드 C(관리자만 provider
  식별). 일반 유저에게는 "풀 처리됨"만, 관리자에게만 provider 식별을 노출(SOURCE_BRIEF §10,
  로드맵 항목 590).
- **`DM-R-10` 단일 경로·비밀 비저장.** 외부 유저는 프로바이더 PC 에 직접 접근 불가(경로는
  `봇 → 중앙 서버 → 인증 WS → Agent → localhost Ollama` 뿐). 토큰 평문·프롬프트 내용은
  저장/로그하지 않는다(토큰은 해시, 상수시간 비교)(README 불변식, ADR 0002 §16, 로드맵
  항목 154·178·612·619).

---

## 8. 라우팅 규칙

SOURCE_BRIEF §7 19단계 중 8~14단계와 §8·§15(선택 규칙·공정성)를 단계 목록으로 구체화한다.
입력: `AiRequest`(필요 부담수준·요청자 역할·채널·무게)·`Provider Pool`(guild_id 기준).
출력: `RoutingDecision`.

### 8.1 후보 생성
`guild_id` 로 Provider Pool 을 조회해 후보 목록을 만든다. 각 후보는 `RoutingCandidate` 로
표현하며, 후보의 `ProviderCapability`·`ProviderContributionPolicy`·`ProviderSession`·
`ProviderHealth` 를 점수 입력 컨텍스트로 묶는다(로드맵 항목 470). (19단계 중 8단계)

### 8.2 온라인 필터
`ProviderSession.session_state ∈ {online_idle, online_busy}` 인 후보만 통과.
`offline`·`paused`·`removed`·`unhealthy` 는 탈락(9단계).

### 8.3 부담수준 필터
후보가 `required_burden` 을 감당할 수 있는 모델(`ProviderCapability.burden_level`)을
제공해야 통과. `light < standard < heavy` 순서에서 후보 부담수준 ≥ 필요수준이어야 하며,
`DM-R-07` 의 우선 배정 규칙을 적용한다. `restricted` 모델은 §8.4 권한 게이트를 함께 만족해야
한다(1단계 "모델 수준 감당 가능").

### 8.4 권한 필터
요청자의 역할 합집합(`DM-R-04`)이 후보의 `ProviderContributionPolicy.allowed_roles` 및
`requester_scope`(all/trusted/admin)를 만족해야 통과. 길드 `RolePolicy.allowed_burdens`
가 `required_burden` 을 허용해야 한다(4단계 "요청자 허용", 10단계 "권한 필터").

### 8.5 채널 필터
요청 채널이 길드 `AllowedChannel`(`DM-R-03`)이며, 후보 정책의
`allowed_channels`(빈 집합=전체)에 포함되어야 통과(5단계 "채널 허용").

### 8.6 한도 필터
후보의 모델별 `daily_limit` 잔여량(UsageLog 집계)이 남아 있어야 통과(6단계 "하루 한도
잔여"). 요청 크기(`prompt_len`)가 후보 `max_prompt_len` 이하여야 한다(9단계 "요청 크기 ≤
제한"). 잔여 0 → `limited` 사유로 탈락.

### 8.7 부하 필터
후보 동시 슬롯이 `concurrency_limit` 미만이어야 통과(7단계 "동시 한도 미초과", 12단계 "바쁘지
않은 필터"). `ProviderHealth.consecutive_failures` 임계 초과(높은 실패율)·CPU/GPU high·
메모리 부족 후보는 탈락(10단계 "응답 실패율 낮음", `DM-R-06`).

### 8.8 공정성 점수
§8.2~8.7 을 모두 통과한 후보에 대해 점수를 산출한다(13단계 "쏠림 방지 점수 계산").
SOURCE_BRIEF §15 공식:

```
provider_score =
      w_fit  · 모델_적합도(required_burden 와 capability 일치도)
    + w_on   · 온라인_가산(online_idle=1, online_busy=0.5)
    + w_idle · idle_가산(active_slots == 0 → 1)
    + w_rem  · 남은_한도(daily_limit 잔여 비율)
    + w_fair · 최근_처리량_역가산(최근 처리 적을수록 ↑, UsageLog 집계 기반)
    − w_fail · 최근_실패율(ProviderHealth.consecutive_failures 기반)
    − w_load · 현재_부하(active_slots / concurrency_limit)
    − w_waste· heavy_낭비_패널티(요청=light 인데 후보=heavy 인 경우)
```

원칙(`DM-R-07`): `light→light` 우선, `standard→standard` 우선, `heavy→heavy` 만 후보,
`heavy` 는 `light` 요청에 기본 미사용(없을 때만 예외). 가중치 `w_*` 는 설정/튜닝 포인트
(로드맵 항목 487).

### 8.9 최종 선택
공정성 점수 1위 후보를 `RoutingDecision.selected_provider_id` 로 확정하고
`outcome=selected`, Request 상태를 `queued` 로 전이(14단계 "최종 선택").
동점 시 라운드로빈/시드 랜덤으로 분산(쏠림 방지, 로드맵 항목 485). 선택 사유를 `reason` 에
기록한다.

### 8.10 fallback 후보
선택 provider 실행이 타임아웃/오류로 실패하면(`DM-EV-RequestFailed`), **원 provider 를
제외**하고 동일 조건으로 후보를 재필터(§8.2~8.7)하여 차순위 1인을
`fallback_provider_id` 로 잡아 **1회만** 재시도한다(`fallback_running`, SOURCE_BRIEF §11,
`DM-R-07b`). fallback 도 실패하면 `failed` 로 종료하고 사용자에게 "처리 가능한 커뮤니티 AI
없음"을 안내한다. 실패한 provider 는 `temporarily_unavailable` 로 표시한다(§6.6).

---

## 9. 불변 조건

`DM-R-*` 규칙과 별개로, 모든 시점에 성립해야 하는 데이터 불변식이다.

- **`9.1` 단일 접근 경로.** 어떤 요청도 `봇 → 중앙 서버 → 인증된 WS → Provider Agent →
  localhost Ollama` 외의 경로로 프로바이더 PC 에 도달하지 않는다. 봇은 임의 URL 로 나가지
  않는다(SSRF 불가, README 불변식·ADR 0002).
- **`9.2` 활성 세션 유일성.** 한 `Provider` 는 활성 `ProviderSession` 을 최대 1개만 갖는다
  (`provider 당 active session ≤ 1`). 동일 owner 재연결 시 이전 세션은 폐기 후 교체.
- **`9.3` 비밀 비저장.** 페어링/Agent 토큰 평문은 영속 저장·로그에 남기지 않는다(해시 저장·
  상수시간 비교). 프롬프트/응답 내용은 `AiRequest`·`UsageLog` 에 기록하지 않는다(`prompt_len`
  같은 메타만)(`DM-R-10`, 로드맵 항목 154·612).
- **`9.4` 비-목표 필드 부재.** 어떤 엔티티·값 객체에도 `billing`·`price`·`seller`·`payout`
  (및 가격 등급·수수료·정산·수익) 필드가 존재하지 않는다(§1.4-2 설계 가드, 로드맵 항목 335).
- **`9.5` outbound 전용.** Provider Agent 는 inbound 포트를 열지 않으며, 중앙 서버의 인증된
  요청 외에는 어떤 것도 처리하지 않는다(임의 shell/파일/URL 금지)(ADR 0002 §16).
- **`9.6` 상태 전이 폐쇄성.** 모든 상태 머신(`DM-S-*`)은 §6 전이표에 정의된 전이만 허용하며,
  미정의 전이 요청은 거부·로깅한다(로드맵 항목 390·512). 종단 상태(`removed`,
  Request 의 `completed`/`failed`/`rejected`)에서는 추가 전이가 없다.

---

## 10. 도메인 이벤트

README §도메인 이벤트 목록 14종을 그대로 사용한다. `DM-EV-<이름>`. 각 이벤트는 발행 시점·
주요 페이로드·연관 상태 전이를 명시한다.

| ID | 이벤트 | 발행 시점 | 주요 페이로드 | 연관 전이 |
|---|---|---|---|---|
| `DM-EV-ProviderRegistered` | ProviderRegistered | `/provider-join` 등록 요청 | provider_id, guild_id, user_id | unregistered → pending/approved |
| `DM-EV-ProviderApproved` | ProviderApproved | 관리자 승인(or 자동) | provider_id, decided_by | pending → approved (토큰 발급) |
| `DM-EV-ProviderAgentConnected` | ProviderAgentConnected | Agent 인증·연결 성립 | provider_id, session_id, capability | approved → online_idle |
| `DM-EV-ProviderAgentDisconnected` | ProviderAgentDisconnected | 연결 끊김/heartbeat 만료 | provider_id, session_id | online_* → offline |
| `DM-EV-ProviderPaused` | ProviderPaused | `/provider-pause`/배터리 자동 | provider_id, cause | online_* → paused |
| `DM-EV-ProviderResumed` | ProviderResumed | `/provider-resume` | provider_id | paused → online_idle |
| `DM-EV-AiRequestReceived` | AiRequestReceived | `/ask` 등 요청 수신 | request_id, requester_id, channel_id | (시작) → received |
| `DM-EV-ProviderSelected` | ProviderSelected | 라우팅 최종 선택 | request_id, provider_id, score | routing → queued |
| `DM-EV-RequestSentToProvider` | RequestSentToProvider | 슬롯 확보·WS 전송 | request_id, provider_id | queued → sent_to_provider |
| `DM-EV-ProviderResponseReceived` | ProviderResponseReceived | Agent 결과 프레임 수신 | request_id, usage_tokens | running 유지 |
| `DM-EV-RequestCompleted` | RequestCompleted | 응답 확정·출력 | request_id, provider_id, burden | running/fallback_running → completed (UsageLog) |
| `DM-EV-RequestFailed` | RequestFailed | 타임아웃/오류 | request_id, failure_reason | running → fallback_running/failed |
| `DM-EV-FallbackStarted` | FallbackStarted | fallback 후보 재시도 시작 | request_id, fallback_provider_id | → fallback_running |
| `DM-EV-ProviderMarkedUnhealthy` | ProviderMarkedUnhealthy | 반복 실패 임계 초과 | provider_id, consecutive_failures | 임의 → unhealthy |

---

## 11. 용어 사전

README 정식 어휘를 글자 그대로 사용한다. 동의어 금지.

### 11.1 사용자 용어

| 용어 | 정의 | 출처 |
|---|---|---|
| 일반 유저(User) | `/ask` 로 질문하는 사람. 프로바이더 PC 직접 접근 불가 | README 사용자 유형 |
| 서버 관리자(Admin) | 서버 LLM 정책·채널·역할·프로바이더 승인 관리자 | README 사용자 유형 |
| 프로바이더(Provider) | 자기 PC 로컬 LLM 자원을 커뮤니티에 기여하는 사람 | README 사용자 유형 |
| 시스템 관리자(Operator) | 중앙 봇/서버 운영자 | README 사용자 유형 |
| 신뢰 멤버(trusted) | light+standard 등 확장 허용 등급의 멤버 | SOURCE_BRIEF §6 |

### 11.2 시스템 용어

| 용어 | 정의 | 출처 |
|---|---|---|
| Provider Pool | 한 길드에 연결된 프로바이더 집합(`guild_id → provider_pool[]`) | SOURCE_BRIEF §2 |
| Provider Agent | 프로바이더 PC 의 경량 outbound 프로그램 | SOURCE_BRIEF §2, ADR 0002 |
| 중앙 봇/중앙 서버 | 요청 수신·정책 확인·라우팅·기록 주체 | SOURCE_BRIEF §2 |
| Provider Session | 인증된 Agent 연결 1건의 런타임 객체 | SOURCE_BRIEF §14 |
| 라우팅 키 | personal=user_id / shared=guild_id (Phase A 모드) | ADR 0002 |
| heartbeat | 세션 유효성 유지용 주기 ping/pong | SOURCE_BRIEF §16, ADR 0002 |

### 11.3 상태 용어

| 용어 | 정의 |
|---|---|
| `light`/`standard`/`heavy`/`restricted` | 모델 부담 수준(가격 아님) — `DM-V-ModelBurdenLevel` |
| Provider 10상태 | unregistered·pending·approved·online_idle·online_busy·paused·limited·offline·unhealthy·removed |
| Request 10상태 | received·policy_checked·routing·queued·sent_to_provider·running·completed·failed·fallback_running·rejected |
| `temporarily_unavailable` | 네트워크 불안정/실패 직후 일시 비가용(ProviderHealth) |
| `no_provider_available` | 후보 0명 라우팅 귀결 |

### 11.4 정책 용어

| 용어 | 정의 | 출처 |
|---|---|---|
| 기여(contribution) | 프로바이더가 감당 가능한 범위에서 자원을 제공함 | README 중심 개념 |
| 동의(consent) | 프롬프트가 PC 로 전송됨에 대한 프로바이더/유저 인지·승인 | README, §10 |
| 수용량(capacity) | 프로바이더가 감당 가능한 처리량(한도·동시·시간) | README, §5 |
| 가용성(availability) | 온라인/idle/한도잔여 등 현재 처리 가능 정도 | README, §8 |
| 공정성(fairness) | 특정 provider 쏠림 방지·최근 과다처리자 우선순위 ↓ | README, §15 |
| 승인 방식(approval_mode) | 프로바이더 등록 승인 방식(auto/manual) | SOURCE_BRIEF §6 |
| 요청자 허용 범위(requester_scope) | all/trusted/admin 중 기여 대상 범위 | SOURCE_BRIEF §5 |
| 프라이버시 모드 A/B/C | 처리 주체 표시 방식(C=기본 추천) | SOURCE_BRIEF §10 |
