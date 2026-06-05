# AI Network 90%급 단계형 마스터 플랜

> 상태: Draft  
> 작성일: 2026-06-01  
> 목적: 지금까지 나온 AI 네트워크 비전을 삭제하지 않고, 0~N차 단계로 정리해 실제 구현 가능한 최상급 제품/기술 기획으로 승격한다.  
> 관련 문서: [도메인 모델](./00_DOMAIN_MODEL_DESIGN.md), [비관적 감사](./01_DESIGN_RISK_AUDIT.md), [RAG 이식](./02_RAG_STACK_MIGRATION.md), [Preset Registry](./03_PRESET_REGISTRY_DESIGN.md), [다중 응답](./04_MULTI_RESPONSE_DESIGN.md)

## 1. 원칙: 기능 삭제가 아니라 단계화

이 문서는 기능을 줄이기 위한 문서가 아니다.

절대 삭제하지 않는 장기 비전:

- 채널별 AI 프로필
- 채널별 AI 헌법
- 채널 AI 카드
- AI 네트워크 지도
- AI 성장 레벨
- Provider 기여 가시화
- 채널별 온보딩
- 채널별 응답 속도/품질 모드
- 채널별 지식 업로드/RAG
- 프리셋 공유/웹 게시/가져오기/추천/신고
- AI 행동 버전 관리
- AI 설정 변경 승인
- “이 채널 AI 만들기” 마법사
- AI 네트워크 대시보드
- 원하는 모델 선택 질문
- 다중 응답/비교/합성

단, 구현 순서는 나눈다.

이유:

1. DB/도메인/권한을 먼저 잘못 잡으면 이후 기능이 전부 재작업된다.
2. Provider 는 타인의 PC 자원이므로 실험 기능보다 보호 정책이 먼저다.
3. RAG/프리셋/다중응답/대시보드는 서로 연결되지만, 동시에 출시하면 장애 원인을 분리하기 어렵다.
4. 사용자는 “큰 비전”보다 “첫 5분 안에 이해되는 경험”으로 제품을 판단한다.

## 2. 90%급 기획의 기준

최정상급 제품/개발 리더가 90점 이상으로 볼 기획은 다음을 만족해야 한다.

| 평가 축 | 90% 기준 |
| --- | --- |
| 비전 | 한 문장으로 제품 정체성이 설명되고, 모든 기능이 그 문장에 연결된다. |
| 단계화 | 장기 기능을 삭제하지 않으면서도 각 단계의 성공 장면·출시 기준·보류 범위가 명확하다. |
| 도메인 모델 | DB/aggregate/projection/event/audit 경계가 후속 기능을 견딘다. |
| UX | 일반 유저, 관리자, Provider 의 첫 5분 경험이 각각 선명하다. |
| 보안 | 민감정보, 서버 간 격리, 권한, 감사, 삭제, 신고, abuse 대응이 설계에 포함된다. |
| Provider 보호 | 과부하, opt-out, rate limit, kill switch, 비용 가중치가 핵심 요구사항이다. |
| 운영 | CI/CD, rollback, observability, degraded mode, migration 순서가 있다. |
| 검증 | 단계별 acceptance criteria 와 테스트/관측 증거가 있다. |
| 확장성 | RAG, Preset Registry, 다중 응답, 대시보드가 같은 foundation 위에 얹힌다. |
| 의사결정 | 왜 지금 이 순서인지, 왜 대안을 버렸는지 ADR 형태로 남는다. |

현재 문서들의 역할:

- `00_DOMAIN_MODEL_DESIGN.md`: 무엇을 저장하고 어떤 Aggregate 로 나눌지
- `01_DESIGN_RISK_AUDIT.md`: 어떤 실패를 미리 막아야 하는지
- `02_RAG_STACK_MIGRATION.md`: RAG 를 Dailyting 스택 기반으로 어떻게 가져올지
- `03_PRESET_REGISTRY_DESIGN.md`: 프리셋 공유를 웹 제품으로 어떻게 키울지
- `04_MULTI_RESPONSE_DESIGN.md`: 다중 응답을 Provider 보호와 함께 어떻게 실험할지
- 이 문서: 위 문서들을 **실행 가능한 단계형 제품 로드맵**으로 묶는다.

## 3. 제품 북극성

제품 정의:

> NEXA는 여러 사용자의 로컬 AI를 안전하게 연결해, 디스코드 서버마다 바로 사용할 수 있는 “함께 만드는 AI 네트워크”를 제공한다.

사용자가 느껴야 하는 핵심 감각:

1. **즉시성**: `/메뉴` → 질문하기로 바로 AI를 쓸 수 있다.
2. **정체성**: 채널마다 역할과 말투가 다른 AI가 있다.
3. **공동 구축**: Provider 가 참여하면 서버 AI 네트워크가 실제로 좋아진다.
4. **통제감**: 내 PC/서버/채널의 설정과 한도를 내가 이해하고 조절할 수 있다.
5. **안전감**: 내 데이터와 PC가 함부로 쓰이지 않는다.

제품 언어:

- 사용자-facing: “함께 만드는 AI 네트워크”, “내 컴퓨터의 AI로 함께 도와주기”, “채널 AI”
- 내부/기술: Provider, Provider Pool, RoutingPolicy, KnowledgeSpace
- 피해야 할 오해: 여러 PC의 CPU/GPU를 합쳐 하나의 거대 AI를 만드는 것이 아니다. 각 Provider PC의 로컬 AI가 요청을 받아 답하고, 중앙 서버가 안전하게 라우팅/전달한다.

## 4. 전체 기능군 지도

| 기능군 | 이름 | 성공 장면 | 핵심 산출물 | 다음 기능군 잠금해제 |
| --- | --- | --- | --- | --- |
| 0 | Foundation | 나중 기능이 얹힐 DB/권한/감사/보호 뼈대가 생김 | Aggregate, Flyway, audit, feature flag, projection shell | 모든 단계 |
| 1 | Channel AI MVP | “이 채널만의 AI”가 Discord 에서 보임 | ChannelAi, BehaviorVersion, AI 헌법, 온보딩 | 대시보드, 프리셋 |
| 2 | Network Dashboard MVP | 서버의 AI 네트워크 상태가 한눈에 보임 | 읽기 전용 dashboard projection, 모델 지도, Provider 상태 | 품질 라우팅, 성장 레벨 |
| 3 | Knowledge/RAG | 채널 AI가 서버 지식을 참고함 | KnowledgeSpace, Dailyting RAG stack, Qdrant, 삭제/재색인 | 프리셋 고도화, 품질 답변 |
| 4 | Preset Registry | 좋은 AI 설정을 저장/게시/가져옴 | 웹 registry, revision, like, report, import | 마법사, 추천 |
| 5 | Customization Wizard & Approval | 관리자가 질문식으로 채널 AI를 만들고 변경 승인함 | Wizard, approval, rollback, preview | 대형 서버 운영 |
| 6 | Quality Routing & Model Choice | 질문에 맞는 모델/Provider를 더 잘 고름 | capability profile, feedback, routing explanation | 다중 응답 |
| 7 | Multi-response | 고난도 질문에서 여러 응답을 비교/합성함 | fan-out policy, candidate answer, synthesis, kill switch | 고품질 모드 |
| 8 | Advanced Network Growth | AI 네트워크가 성장하고 추천/개선됨 | growth level, recommendations, contribution impact | 장기 제품화 |


## 4.1 기능 보존 인벤토리

모든 장기 기능은 삭제하지 않고 아래 단계 중 하나에 배치한다. “첫 유용 단계”는 최소 가치가 사용자에게 보이는 시점이고, “완성 단계”는 운영 가능한 정식 형태가 되는 시점이다.

| 기능 | 보존 | 첫 유용 단계 | 완성 단계 | 하드 블로커 |
| --- | --- | --- | --- | --- |
| 채널별 AI 프로필 | 예 | Release 1 | Release 1 | `channel_ai_profile` 호환 migration |
| 채널 AI 카드 | 예 | Release 1 | Release 2 | ChannelAi projection |
| 채널별 AI 헌법 | 예 | Release 1 | Release 3 | BehaviorVersion + safety precedence |
| 채널별 온보딩 | 예 | Release 1 | Release 1 | Preview renderer |
| AI 행동 버전 관리 | 예 | Release 1 | Release 1 | immutable version schema + rollback |
| AI 설정 변경 승인 | 예 | Release 1 기본 audit | Release 3 | role policy + proposal workflow |
| “이 채널 AI 만들기” 마법사 | 예 | Release 3 간단 flow | Release 3 | preview + approval + rollback |
| AI 네트워크 대시보드 | 예 | Release 2 읽기 전용 | Release 8+ | auth/masking/projection freshness |
| AI 네트워크 지도 | 예 | Release 2 | Release 11 | ProviderCapabilityProfile |
| Provider 기여 가시화 | 예 | Release 2 | Release 11 | anti-pressure UX |
| AI 성장 레벨 | 예 | Release 11 | Release 11 | vanity metric 방지, capability 기반 계산 |
| 채널별 응답 속도/품질 모드 | 예 | Release 5 | Release 5 | Provider safety gate |
| 원하는 모델 선택 질문 | 예 | Release 5 | Release 5 | model availability/fallback UX |
| 품질 피드백 | 예 | Release 4 간단 수집 | Release 5 | privacy-preserving aggregation |
| 채널별 지식 업로드/RAG | 예 | Release 7 offline | Release 8 runtime opt-in | cross-guild leakage test + sensitive scanner |
| Preset Registry | 예 | Release 3 guild/private | Release 6 public web | moderation/report/revision |
| 다중 응답/비교/합성 | 예 | Release 9 dry-run/dev guild | Release 10 opt-in | fan-out gate + Provider opt-in |

## 4.2 현재 구현 상태와 이행 전략

현재 코드는 이미 일부 기능을 갖고 있다. 90점짜리 기획이 되려면 “새 모델로 가자”가 아니라, 현재 상태에서 어떻게 안전하게 넘어갈지까지 써야 한다.

현재 확인된 상태:

- `central-server/src/main/resources/db/migration/V5__channel_ai_profile.sql` 은 `channel_ai_profile(guild_id, channel_id, display_name, avatar_url)` 만 저장한다.
- `ChannelAiProfileEntity` 도 표시 이름과 아바타 URL 중심이다.
- `SecurityConfig` 는 `central.oauth.enabled=false` 일 때 기본 `permitAll` 이다.
- `DashboardController` 의 최근 요청 응답은 프롬프트 본문은 제외하지만 `providerId` 를 노출한다.

Release 0 의 하드 게이트:

1. dashboard/API 를 어떤 환경에서 공개할지 결정한다. 운영 기본값은 인증/권한 활성 또는 민감 필드 masking 이어야 한다.
2. `providerId` 는 일반/공개 대시보드에서 직접 노출하지 않고 masked provider label 또는 aggregate 로 바꾼다.
3. `channel_ai_profile` 을 삭제하지 않고 새 ChannelAi 모델로 backfill/dual-read/cutover 한다.
4. prompt/system prompt 저장 정책을 Release 1 전에 닫는다. 기본은 원문 최소 저장, private ref 또는 encrypted/ref-only 저장이다.
5. “결정은 나중에”로 남은 항목 중 Release 1 실행을 막는 것은 Release 0 에서 ADR 로 닫는다.

## 4.3 필수 Phase Template

각 단계는 반드시 아래 항목을 채운 뒤 구현 티켓으로 분해한다.

- Goal: 이 단계가 해결하는 문제
- User-visible value: 누가 무엇을 체감하는지
- Explicit non-goals: 이번 단계에서 하지 않는 것
- Owner persona: 일반 유저 / 관리자 / Provider / 운영자
- Tables/entities touched: 변경되는 DB/aggregate
- APIs/commands touched: Discord command, REST, WebSocket, provider-agent contract
- Migration/backfill plan: 기존 데이터 이동/호환/검증
- Feature flags/kill switches: 즉시 끌 수 있는 스위치
- Rollback plan: 코드/DB/설정/인덱스 되돌리기
- Security/privacy gates: 권한, masking, 민감정보, cross-guild 테스트
- Provider-protection gates: opt-out, limit, overload, fan-out 제한
- Observability metrics: metric/event/log/dashboard freshness
- Verification commands: 실제 실행할 테스트/빌드/스모크
- Acceptance demo: 릴리스 전에 보여줄 Discord/web 흐름
- Do-not-ship condition: 이 조건이면 출시 금지
- Exit criteria: 다음 단계로 넘어가는 기준

## 5. Capability Group 0 — Foundation

### 목표

후속 모든 기능이 의존하는 뼈대를 만든다. UI를 예쁘게 만드는 단계가 아니라, 나중에 갈아엎지 않는 구조를 만드는 단계다.

### 포함 범위

- Guild scope 를 최상위 격리 경계로 확정
- ChannelAi, BehaviorVersion, RoutingPolicy, ProviderCapabilityProfile, KnowledgeSpace, DashboardProjection 의 최소 테이블/도메인 정의
- audit log 표준화
- permission policy 표준화
- feature flag / kill switch 표준화
- Provider 보호 정책을 라우팅보다 우선하는 invariant 로 구현
- dashboard projection skeleton
- migration/rollback 규칙

### 핵심 결정

- Channel AI 가 중심 Aggregate 다.
- 설정은 mutable row 를 덮어쓰지 않고 immutable version 을 만든다.
- 대시보드는 write model 을 직접 조인하지 않는다.
- Provider 보호 정책은 어떤 기능도 우회할 수 없다.
- 민감정보 원문은 저장하지 않는 것을 기본값으로 한다.

### 산출물

- Flyway migration 초안
- Repository 테스트
- 권한 정책 테스트
- audit event catalog
- feature flag catalog
- projection freshness 계약
- 운영 runbook 초안

### DoD

- [x] 모든 새 테이블에 `guild_id` 또는 명시적 global/parent scope 가 있다. — `AiNetworkFoundationServiceTest` 가 AI Network 테이블별 direct guild scope·parent/catalog scope 컬럼 계약을 검증한다.
- [x] cross-guild 접근을 막는 테스트가 있다. — `AiNetworkFoundationServiceTest`/`KnowledgeIndexingServiceTest` 가 길드 스코프 조회와 cross-guild RAG 업데이트 차단을 검증한다.
- [x] 설정 변경은 audit log 에 남는다. — `ChannelAiCustomizationServiceTest` 가 wizard publish/propose/approve/reject/rollback/AI admin role 변경·거부 audit 을 검증한다.
- [x] feature flag 로 신규 기능을 끌 수 있다.
- [x] Provider overload 상태에서는 어떤 고급 기능도 실행되지 않는다. — `ProviderSafetyServiceTest`/`MultiResponseServiceTest` 가 overload 시 deep response·multi-response·pressure boost 를 차단/다운그레이드하고 critical overload 에서 fanout 실행을 막는지 검증한다.
- [x] projection 장애가 질문 처리 장애로 번지지 않는다.

## 6. Capability Group 1 — Channel AI MVP

### 목표

사용자가 처음으로 “이 채널만의 AI가 생겼다”고 느끼게 한다.

### 포함 범위

- 채널별 AI 이름/설명/말투/답변 길이
- 채널별 AI 헌법
- 채널 온보딩 메시지
- 채널 AI 카드
- AI 행동 버전 관리 v1
- 설정 rollback v1
- Discord 패널에서 보기/기본 수정

### 첫 5분 UX

관리자:

1. `/설정` 또는 대시보드에서 “이 채널 AI 만들기” 진입
2. 목적 선택: 개발 질문/번역/회의록/공지/자유 설정
3. 말투 선택
4. 금지/주의 규칙 선택
5. 미리보기 확인
6. 채널에 온보딩 카드 게시

일반 유저:

1. 채널 입장 또는 `/메뉴`
2. “이 채널의 AI는 무엇을 도와주는지” 확인
3. 바로 질문

### DoD

- [x] 채널마다 다른 AI 이름/역할/말투가 적용된다. — `ChannelAiCustomizationServiceTest`, `CommandServiceTest` 가 채널별 이름·역할·말투를 prompt/runtime 에 반영하는 회귀를 고정한다.
- [x] 변경 전후 버전이 남고 rollback 된다. — `ChannelAiCustomizationServiceTest`, `ChannelAiProfileServiceTest` 가 version history/proposal/audit 과 rollback 을 검증한다.
- [x] 온보딩 문구가 채널 AI 설정에서 파생된다. — `ChannelAiCustomizationServiceTest` 가 active profile 기반 onboarding title/description/examples/safety notice 를 검증한다.
- [x] 민감정보 경고가 AI 헌법보다 우선한다. — `ChannelAiCustomizationServiceTest` 가 sensitive question 에서 RAG 를 제외하고 safety warning 을 최우선으로 렌더링한다.
- [x] 관리자 권한 없는 사용자는 설정을 바꿀 수 없다. — `CommandServiceTest` 와 `AiNetworkApiSecurityFilterTest` 가 Discord 명령/패널 및 웹 API 관리자 토큰 가드를 검증한다.

## 7. Capability Group 2 — Network Dashboard MVP

### 목표

“함께 만드는 AI 네트워크”를 눈으로 보이게 한다.

### 포함 범위

- 네트워크 상태
- Provider 상태
- 모델 지도
- 채널 AI 목록
- 기여/사용량 요약
- 과부하 알림 기초
- 읽기 전용 웹 대시보드 또는 Discord 패널

### 정보 구조

- Overview: 온라인 Provider, 사용 가능 모델, 혼잡도, 최근 성공률
- Providers: 상태, 모델, 가용시간, 보호 상태
- Model Map: 모델명, 수준, 특화 태그, 채널 사용 가능 여부
- Channel AIs: 채널별 AI 카드
- Alerts: 과부하, 실패율, RAG 재색인 실패
- Audit: 최근 설정 변경

### DoD

- [x] 대시보드는 projection 만 읽는다. — `/dashboard` 기본 경로는 기존 overview projection 을 read-only 로 사용하고, 수동 갱신은 `refreshOverview=true` 로 분리한다.
- [x] 일반 유저/Provider/관리자에게 보이는 정보가 다르다. — `AiNetworkDashboardControllerTest` 가 public/provider/admin audience 별 Provider 표시·상태·용량 노출 차이를 검증한다.
- [x] Provider 개인 정보와 민감 상태는 노출하지 않는다. — `AiNetworkDashboardControllerTest` 가 public dashboard/provider/overload/multi-response load 에서 provider id·capacity·민감 risk 를 마스킹한다.
- [x] stale projection 은 freshness 를 표시한다. — `AiNetworkDashboardControllerTest` 가 `refresh=false` stale projection 의 `freshnessStatus=stale`, `degradedReason=projection_stale` 를 검증한다.
- [x] dashboard 장애 시 Discord 질문 기능은 유지된다. — `MultiResponseServiceTest` 와 `AiNetworkFeatureGateTest` 가 dashboard projection gate off 상태에서도 질문 fan-out 경로가 유지됨을 검증한다.

## 8. Capability Group 3 — Knowledge/RAG

### 목표

서버와 채널이 자기 지식을 쌓고, 채널 AI가 그 지식을 안전하게 참고한다.

### 기준 스택

`~/coding_stuffs/dailyting` 에서 검증한 RAG 스택을 가져온다.

- Python 3.12 Docker
- Qdrant
- OpenAI `text-embedding-3-large`
- LlamaIndex
- BM25
- SQLite `meta.db`
- exact + BM25 + vector hybrid
- RRF
- `BAAI/bge-reranker-v2-m3`
- golden set eval
- CI/CD rebuild workflow
- Cloudflare Access 보호 패턴

### 포함 범위

- file/link/text 지식 등록
- KnowledgeSpace
- guild/channel scope filter
- 민감정보/비밀값 스캔
- 삭제/비활성화/재색인
- RAG 검색 실패 시 graceful fallback
- RAG context token budget

### DoD

- [x] guild filter 없는 검색은 실패한다.
- [x] 서버 A 지식이 서버 B 질문에 노출되지 않는다.
- [x] 삭제된 문서는 검색 결과에서 빠진다.
- [x] secret pattern 감지 문서는 색인되지 않는다.
- [x] RAG 실패는 일반 질문 장애로 번지지 않는다.
- [x] golden set 기준 Hit@K/MRR/Recall 을 CI 에서 확인한다.

## 9. Capability Group 4 — Preset Registry

### 목표

잘 만든 채널 AI 설정을 저장하고, 웹에서 게시하고, 다른 서버/채널로 가져와 수정할 수 있게 한다.

### 포함 범위

- 프리셋 저장
- 서버 내부 복사
- 웹 게시
- 가져오기
- 수정/삭제/비공개
- revision
- 따봉 추천
- 신고/검수
- import 기록

### UX 원칙

- “마켓”이라는 단어는 쓰지 않는다.
- 판매/결제/수익화 뉘앙스를 제거한다.
- 가져온 프리셋은 자동 업데이트하지 않는다.
- RAG 원문은 공유하지 않고, 지식 슬롯/가이드만 공유한다.

### DoD

- [x] 게시된 revision 은 immutable 이다.
- [x] 삭제는 hard delete 보다 removed/unlisted/suspended 상태를 우선한다.
- [x] 같은 유저는 같은 프리셋에 like 1개만 가능하다.
- [x] 신고된 프리셋은 검토 상태로 전환할 수 있다.
- [x] 가져온 프리셋은 내 서버에서 수정 가능하지만 원본과 분리된다.
- [x] 비공개 prompt/secret/server ID 는 게시 payload 에 포함되지 않는다.

## 10. Capability Group 5 — Customization Wizard & Approval

### 목표

설정을 어렵게 하지 않고, 큰 서버에서도 안전하게 변경한다.

### 포함 범위

- “이 채널 AI 만들기” 마법사
- 설정 변경 미리보기
- AI 관리자 역할
- 변경 승인/거절
- rollback
- 변경 diff
- 알림

### DoD

- [x] 위험 설정은 즉시 적용되지 않고 승인 요청이 된다. — `ChannelAiCustomizationServiceTest` 가 risky wizard direct publish 를 pending approval 로 강제한다.
- [x] 승인/거절자와 사유가 audit log 에 남는다. — `ChannelAiCustomizationServiceTest` 가 approve/reject reviewer·reason·history/audit 을 검증한다.
- [x] 미리보기와 실제 적용 결과가 같은 renderer 를 쓴다. — `CommandServiceTest` 가 Channel AI preview renderer 와 Discord `/ask` 실행 prompt 의 exact match 를 검증한다.
- [x] rollback 은 이전 BehaviorVersion 으로만 수행한다. — `ChannelAiCustomizationServiceTest` 가 target BehaviorVersion 을 새 active rollback version 으로 복사하고 audit 을 남기는 흐름을 검증한다.
- [x] 권한 없는 관리자가 대형 서버 설정을 임의 변경할 수 없다. — `ai_admin_role` protected mode 를 두고, `ChannelAiCustomizationServiceTest`/`CommandServiceTest` 가 AI 관리자 역할 없는 일반 서버 관리자의 채널 AI 변경을 차단한다.

## 11. Capability Group 6 — Quality Routing & Model Choice

### 목표

질문에 맞는 모델/Provider를 더 똑똑하게 선택하되, 사용자가 이해할 수 있게 설명한다.

### 포함 범위

- 원하는 모델 선택 질문
- 빠른/균형/깊은/절약 모드
- Provider capability tag
- 품질 피드백
- routing explanation
- 모델 지도와 라우팅 연결
- Provider trust score

### DoD

- [x] 사용자는 질문 시 모델 또는 모드를 선택할 수 있다. — `/ask` 의 model 자동완성·mode 선택지와
  `CommandServiceTest`/`ChannelAiRoutingPolicyServiceTest` 가 요청 모델·빠른/균형/깊은/절약 모드 반영을 검증한다.
- [x] 선택 모델이 unavailable 일 때 대체 이유를 설명한다. — `ChannelAiRoutingPolicyServiceTest` 와
  `CommandServiceTest` 가 `requested_model_unavailable` fallback 및 유저 안내 문구를 검증한다.
- [x] 품질 피드백은 raw prompt 없이 저장된다. — `AiQualityFeedbackServiceTest` 가 request id/type/reason redaction 과
  raw answer body 비노출 review summary 를 검증한다.
- [x] Provider trust score 는 공개 망신이 아니라 내부 라우팅 신호다. — feedback 기반 shadow quality 는
  admin-protected candidate catalog 에서만 모델 후보 신호로 노출되고 live routing 선택을 즉시 바꾸지 않음을
  `ChannelAiRoutingPolicyServiceTest` 가 검증한다.
- [x] 긴 질문/첨부/깊은 모드는 더 높은 비용 가중치로 계산된다. — `RequestWeigherTest` 와
  `RequestOrchestratorTest` 가 긴 prompt/첨부/deep 모드의 상향 가중치와 light-only Provider 미전송을 검증한다.

## 12. Capability Group 7 — Multi-response / Compare / Synthesize

### 목표

고난도 질문에서 여러 Provider/모델 응답을 제한적으로 비교해 품질을 높인다.

### 포함 범위

- 기본 off
- 최대 fan-out 2부터 시작
- deep/compare mode 에서만 허용
- CandidateAnswer
- SynthesisResult
- pseudo-streaming throttle 과 결합
- RAG context 공유
- kill switch

### DoD

- [x] 기본 질문은 fan-out 하지 않는다.
- [x] 민감 질문에서는 다중 응답이 자동 비활성화된다.
- [x] Provider opt-out 이 즉시 반영된다.
- [x] 후보 답변 원문 전체를 장기 저장하지 않는다.
- [x] 모든 후보 실패 시 단일 실패 메시지로 정리한다.
- [x] Provider 보호 차단 횟수가 dashboard 에 표시된다.

## 13. Capability Group 8 — Advanced Network Growth

### 목표

서버가 AI 네트워크를 함께 키워간다는 감각을 만든다.

### 포함 범위

- AI 성장 레벨
- Provider 기여 효과 표시
- 네트워크 지도 고도화
- 추천 프리셋
- 채널 AI 개선 제안
- 품질 기반 자동 제안
- 운영자 주간 리포트

### DoD

- [x] Provider 참여 시 네트워크가 어떻게 좋아졌는지 보여준다. — `AiNetworkGrowthServiceTest` 와
  dashboard test 가 Provider 참여 이벤트의 모델·태그·동시처리·일일한도·레벨 변화 impact bullet 을 검증한다.
- [x] 성장 레벨은 vanity metric 이 아니라 실제 capability 에서 계산된다. — `AiNetworkFoundationService` 의
  overview projection 이 온라인 Provider·모델 수·채널 AI·지식공간·피드백·과부하 신호로 레벨을 계산하고,
  `AiNetworkGrowthServiceTest` 가 milestone gap 과 capability basis 를 검증한다.
- [x] 추천은 관리자 승인 전 자동 적용되지 않는다. — `AiNetworkGrowthAction.autoApply=false` 와
  `requiresAdminApproval` guard 를 노출하고, high-risk preset import 는 pending proposal 로만 생성됨을
  `AiNetworkGrowthServiceTest`/`PresetRegistryServiceTest` 가 검증한다.
- [x] 사용자는 “함께 구축하고 있다”는 감각을 얻는다. — growth plan `builderMessage` 와 timeline 이
  Provider·모델·채널 AI·지식·피드백이 쌓여 함께 만들어지는 네트워크라는 문맥을 제공함을 테스트로 고정했다.

## 14. 의존성 DAG

```text
Capability Group 0 Foundation
  ├─ Capability Group 1 Channel AI MVP
  │   ├─ Capability Group 2 Dashboard MVP
  │   │   ├─ Capability Group 6 Quality Routing
  │   │   │   └─ Capability Group 7 Multi-response
  │   │   └─ Capability Group 8 Advanced Growth
  │   ├─ Capability Group 3 Knowledge/RAG
  │   │   └─ Capability Group 6 Quality Routing
  │   ├─ Capability Group 4 Preset Registry
  │   │   └─ Capability Group 5 Wizard & Approval
  │   └─ Capability Group 5 Wizard & Approval
  └─ Cross-cutting: security, audit, feature flags, provider protection, CI/CD
```

## 14.1 `channel_ai_profile` → ChannelAi Migration Appendix

현재 운영 데이터는 `channel_ai_profile` 에 이미 존재하므로, 새 모델은 무중단 호환 이행을 전제로 한다.

순서:

1. 새 테이블을 nullable/safe 기본값으로 추가한다: `channel_ai`, `ai_behavior_version`, `ai_change_proposal`, `customization_audit_log`.
2. `(guild_id, channel_id)` 마다 `channel_ai` 1개를 backfill 한다.
3. 기존 `display_name`, `avatar_url` 과 기본 말투/안전 정책으로 초기 `ai_behavior_version` 을 만든다.
4. 기존 command/service 는 compatibility read service 를 통해 새 모델 우선, 없으면 old table fallback 으로 읽는다.
5. 전환 기간에는 dual-read 를 유지하고, 필요한 write path 는 new model 에 먼저 쓰되 old table 동기화 여부를 ADR 로 결정한다.
6. 검증 query 로 count parity, `(guild_id, channel_id)` unique, null/default, orphan version 을 확인한다.
7. 한 릴리스 이상 검증 후 write path 를 새 모델로 완전 전환한다.
8. rollback 이 필요하면 feature flag 로 old table read 를 우선하도록 되돌린다.
9. old table 제거는 별도 릴리스/마이그레이션으로만 수행하고, 운영 검증 전에는 삭제하지 않는다.

검증 query 예시:

```sql
-- old/new count parity
select count(*) from channel_ai_profile;
select count(*) from channel_ai where source = 'channel_ai_profile_backfill';

-- duplicate 방지
select guild_id, channel_id, count(*)
from channel_ai
group by guild_id, channel_id
having count(*) > 1;

-- 현재 적용 버전 누락 방지
select ca.id
from channel_ai ca
left join ai_behavior_version bv on bv.id = ca.active_behavior_version_id
where bv.id is null;
```

## 14.2 Unified Verification Matrix

이 검증 매트릭스는 §20 Release Train 0~11 과 1:1 로 맞춘다. Capability Group 번호가 아니라 실제 실행 Release 번호를 기준으로 gate 를 판단한다.

| Release | 필수 검증 | 명령/증거 |
| --- | --- | --- |
| Release 0 — Current-state hardening | docs-links, dashboard auth/masking ADR, providerId 노출 risk closure, feature flag inventory | `python3 scripts/check_links.py`, Security/Dashboard ADR, masking fixture, risk checklist |
| Release 1 — Channel Ai foundation | Flyway migration, repository, `channel_ai_profile` backfill parity, immutable behavior version, rollback | `central-server/gradlew -p central-server build`, migration parity query, rollback demo |
| Release 2 — Read-only network/card/dashboard | projection freshness, role-based visibility, provider identity masking, dashboard degraded mode | controller tests, projection freshness fixture, masked `/api/dashboard` response, stale projection test |
| Release 3 — Wizard/preview/local presets | preview/publish parity, local preset draft/import, approval proposal, audit/rollback | Cucumber P0 for create/edit/publish/rollback, preview renderer test, audit assertion |
| Release 4 — Feedback/capability/shadow routing | feedback privacy, capability profile update, shadow score does not affect live routing, minimum sample rules | unit/integration tests, routing shadow logs, prompt/provider privacy negative tests |
| Release 5 — Live quality/model selection | opt-in model/mode selection, fallback explanation, cooldown/rate-limit, Provider safety dominance | routing tests, provider overload simulation, kill switch smoke, user-facing fallback fixture |
| Release 6 — Public Preset Registry | publish/import/revision/report moderation, unsafe preset blocking, import creates draft not live mutation | repository/API tests, moderation fixtures, revision immutability test, report flow test |
| Release 7 — RAG infra offline | Qdrant/RAG worker/eval/golden set, cross-guild leakage negative test, deletion propagation, no runtime attachment | RAG CI, golden Hit@K/MRR/Recall, Qdrant filter tests, deletion reindex test |
| Release 8 — RAG runtime opt-in | admin knowledge upload, sensitive scanner, SSRF defense, token budget, graceful fallback without RAG | upload/security tests, RAG context budget tests, runtime fallback e2e, citation fixture |
| Release 9 — Multi-response dry-run | dev guild or shadow-only fan-out recommendation, maxFanout, Provider opt-in validation, no default synthesis | integration tests, dry-run logs, opt-in negative test, fan-out cap assertion |
| Release 10 — Multi-response opt-in | explicit user/admin opt-in, candidate redaction, timeout/fallback, overload alert, kill switch | e2e compare/deep mode, provider disconnect test, redaction assertion, kill switch smoke |
| Release 11 — Network growth | growth level capability basis, contribution impact anti-pressure UX, recommendation approval required | dashboard tests, UX acceptance demo, anti-pressure review, recommendation approval tests |

공통 검증:

- 코드 변경: `central-server/gradlew -p central-server build`
- provider-agent protocol 변경: provider-agent ruff/mypy/pytest + wire contract
- 문서 변경: `python3 scripts/check_links.py`
- 운영 반영 주장: actuator health, relevant CI/deploy run, rollback note

## 14.3 자동 실패 조건

아래 조건 중 하나라도 있으면 90점 기획으로 보지 않는다.

- RAG runtime 이 cross-guild leakage/security gate 전에 켜진다.
- 다중 응답이 기본 on 이다.
- dashboard read/write 가 auth/masking 결정 없이 공개된다.
- `channel_ai_profile` 에서 새 모델로 가는 migration/rollback 전략이 없다.
- quality routing, RAG, dashboard write, fan-out 에 kill switch 가 없다.
- Provider opt-out 보다 품질/랭킹/다중응답이 우선한다.
- prompt 원문/후보 답변 원문 장기 저장이 기본값이다.
- Release 1 실행을 막는 open question 이 ADR 없이 남아 있다.

## 15. 공통 비기능 요구사항

### 보안

- guild scope 누락 금지
- 권한 없는 설정 변경 금지
- 민감정보 저장 최소화
- prompt/answer 원문 장기 저장 기본 금지
- 지식 업로드 MIME/크기/링크 allowlist
- SSRF 방어
- 신고/차단/삭제 이력 보존

### Provider 보호

- opt-in/opt-out
- daily limit
- concurrency limit
- availability window
- overload detection
- multi-response cost multiplier
- kill switch
- Provider 상태 비공개 보호

### 운영

- Flyway migration
- rollback plan
- projection rebuild
- CI docs-links/build/test
- RAG rebuild workflow
- health check
- degraded mode
- audit export

### 관측성

- request id
- route decision reason
- provider filtered reason
- RAG search latency
- dashboard projection freshness
- fan-out actual count
- kill switch event
- safety block event

## 16. Release Gate

각 단계는 아래 gate 를 통과해야 다음 단계로 넘어간다.

1. Product Gate: 사용자 성공 장면과 UX copy 가 문서화되었는가?
2. Domain Gate: Aggregate/table/event/audit 경계가 확정되었는가?
3. Security Gate: 권한/민감정보/cross-guild/abuse 테스트가 있는가?
4. Provider Safety Gate: 과부하/opt-out/kill switch 가 적용되는가?
5. Observability Gate: 장애 원인을 추적할 event/metric/log 가 있는가?
6. Rollback Gate: 설정/DB/인덱스/feature flag rollback 이 가능한가?
7. CI Gate: 자동 검증이 실패를 잡는가?
8. UX Gate: 일반 유저·관리자·Provider 가 첫 5분 안에 이해하는가?

## 17. 90점 판정용 자기 평가표

| 항목 | 가중치 | 통과 기준 |
| --- | ---: | --- |
| 비전-기능 정렬 | 10 | 모든 기능이 “함께 만드는 AI 네트워크”에 연결됨 |
| 단계형 로드맵 | 15 | 삭제 없이 0~11 release train 으로 성공 장면/DoD/잠금해제 관계가 있음 |
| 도메인/DB 설계 | 15 | Aggregate, version, audit, projection, scope 원칙이 있음 |
| UX | 10 | 일반 유저/관리자/Provider 첫 경험이 있음 |
| 보안/개인정보 | 10 | cross-guild, secret, 권한, 신고, 삭제 정책이 있음 |
| Provider 보호 | 10 | opt-out, limit, overload, kill switch, fan-out 제한이 있음 |
| RAG 실현성 | 8 | Dailyting 기반 스택/CI/CD/격리/삭제/평가가 있음 |
| 프리셋 제품성 | 7 | 웹 게시/가져오기/수정/삭제/추천/신고/revision 이 있음 |
| 다중 응답 안전성 | 7 | 기본 off, 최대 fan-out, 민감질문 차단, 원문 저장 제한이 있음 |
| 운영/검증 | 8 | release gate, CI, rollback, observability 가 있음 |

목표 점수: 90점 이상.

현재 이 문서가 보강한 부분:

- 기존 기능을 삭제하지 않고 단계화했다.
- 각 단계별 성공 장면, 포함 범위, DoD 를 추가했다.
- RAG/프리셋/다중응답을 Foundation 위의 장기 기능으로 연결했다.
- 90점 판정 기준을 명시했다.

## 18. ADR

### Decision

AI 네트워크 기획은 기능 축소형 MVP 가 아니라, **비전 보존형 단계 로드맵**으로 관리한다.

### Drivers

- 사용자가 요구한 장기 기능을 삭제하면 제품 정체성이 약해진다.
- 동시에 모든 기능을 구현하면 보안/운영/Provider 보호 리스크가 커진다.
- Channel AI, RAG, Preset, Dashboard, Multi-response 는 같은 foundation 을 공유한다.

### Alternatives considered

1. 1차 MVP 로 기능을 크게 삭제한다.
   - 거절 이유: 사용자가 명시적으로 허용하지 않았고, 장기 제품성이 약해진다.
2. 모든 기능을 동시에 구현한다.
   - 거절 이유: 장애 원인 분리, 권한, Provider 보호, RAG 격리 검증이 어렵다.
3. 문서만 넓게 두고 구현 순서를 정하지 않는다.
   - 거절 이유: 실행 가능한 기획이 아니라 아이디어 목록에 머문다.

### Why chosen

단계형 로드맵은 장기 비전을 보존하면서도 각 단계의 성공/검증/보류 범위를 명확히 한다. 이 방식이 제품 기획과 엔지니어링 실행 가능성을 동시에 만족한다.

### Consequences

- 초기 작업은 UI보다 DB/도메인/권한/감사에 집중된다.
- 각 단계 release gate 를 통과하지 못하면 다음 단계로 넘어가지 않는다.
- 문서량은 늘어나지만, 향후 재작업 비용을 줄인다.

### Follow-ups

- Release 0/1 을 실제 Flyway/Repository/API 설계 티켓으로 분해한다.
- 각 단계별 세부 체크리스트를 100개 단위로 확장할 수 있다.
- 대시보드 IA 와 웹 화면 설계를 별도 문서로 구체화한다.
- RAG 이식 시 Dailyting 구현과 차이를 ADR 로 남긴다.

## 19. Canonical Vocabulary & Decision Closure

90점 기획은 용어가 흔들리면 안 된다. 아래를 구현 전 표준 용어로 고정한다.

| 개념 | Canonical name | 기존/혼동 표현 | 결정 |
| --- | --- | --- | --- |
| 채널별 AI 정체성 | `ChannelAi` / `channel_ai` | `channel_ai_profile`, `ai_channel_profile` | `channel_ai_profile` 은 legacy compatibility table 이다. 새 write model 은 `channel_ai` 다. |
| 표시 이름/아이콘 | `ChannelAi.displayName`, `ChannelAi.avatarUrl` | behavior version 내부 값 | 표시 정체성은 ChannelAi 소유. 버전은 행동/정책/말투를 소유한다. 표시 이름 변경도 audit 은 남긴다. |
| 행동 설정 버전 | `AiBehaviorVersion` / `ai_behavior_version` | `ai_persona`, `ai_prompt_template` | persona/template 은 version payload 내부 구성요소다. 독립 write model 로 먼저 만들지 않는다. |
| 변경 승인 | `AiChangeProposal` / `ai_change_proposal` | approval state on config row | 승인 상태는 설정 row 에 붙이지 않는다. proposal 이 draft/published 전환을 관리한다. |
| 라우팅 정책 | `RoutingPolicy` / `routing_policy` | model policy, burden policy | 속도/품질/모델/fan-out 허용을 하나의 정책 aggregate 로 관리한다. |
| 지식 공간 | `KnowledgeSpace` / `knowledge_space` | RAG folder, channel docs | RAG source/chunk/vector 는 KnowledgeSpace 하위다. ChannelAi 는 참조만 한다. |
| 대시보드 데이터 | `DashboardProjection` | raw joins, dashboard DTO | 화면은 projection/read model 만 본다. write model 직접 조합 금지. |
| 프리셋 공유 | `PresetRegistry` / `PublishedPreset` | market, template store | 판매/결제 뉘앙스 금지. 공개 공유/가져오기/신고 중심이다. |

### 닫힌 결정

| 결정 항목 | 결정 | 이유 | 재검토 조건 |
| --- | --- | --- | --- |
| ChannelAi 이름/아이콘 소유권 | ChannelAi 가 소유 | 웹훅/채널 표시 정체성은 행동 버전보다 안정적이어야 함 | 버전별 캐릭터 A/B 테스트가 필요할 때 |
| system prompt 저장 | 원문 장기 저장 기본 금지, encrypted/ref-only 우선 | 유출 리스크가 제품 신뢰를 망침 | self-hosted enterprise 모드가 생길 때 |
| approval 정책 | 기본은 서버별 설정, 단 고위험 변경은 항상 proposal | 작은 서버 사용성을 보존하면서 대형 서버 위험 방지 | 관리자 role 모델이 바뀔 때 |
| dashboard auth | 운영은 인증/권한 또는 민감 field masking 을 Release 0 gate 로 강제 | 현재 `permitAll` 기본값과 `providerId` 노출은 장기 위험 | 완전 공개 status page 를 별도 설계할 때 |
| RAG collection | 초기 단일 collection + mandatory guild filter | 운영 단순성 우선, 테스트로 유출 방지 | guild 수/collection cardinality 가 병목일 때 |
| multi-response 기본값 | 항상 off | Provider DDoS 오해와 과부하 방지 | 장기적으로도 기본 on 은 재검토 신중 |

## 20. Release Train 0~11

아래가 구현 티켓으로 분해할 때의 실제 release train 이다. 앞선 Capability Group 0~8 설명은 기능군 이해용이고, 실행은 이 0~11 ledger 를 따른다.

| Release | 이름 | 핵심 목표 | 대표 non-goals | Exit gate |
| --- | --- | --- | --- | --- |
| 0 | Current-state hardening | 현재 코드/문서/보안 상태 reconcile | 새 UX 대량 추가 | open decision closure, dashboard masking/auth ADR |
| 1 | Channel AI foundation | `channel_ai`/version/proposal/audit + legacy backfill | RAG, public preset, fan-out | migration parity, rollback demo |
| 2 | Read-only network/card/dashboard | masked projection 기반 상태판 | write dashboard | freshness, masking, degraded-mode tests |
| 3 | Wizard/preview/local presets | draft→preview→publish, guild preset | public registry | preview parity, audit, rollback |
| 4 | Feedback/capability/shadow routing | 품질·capability 신호 수집, dry-run scoring | live reroute | privacy aggregation, provider safety dominance |
| 5 | Live quality/model selection | opt-in 모델/모드 선택과 안전 라우팅 | fan-out | cooldown/rate-limit/kill switch |
| 6 | Public Preset Registry | 웹 게시/가져오기/추천/신고 | raw RAG copy | moderation, revision immutability |
| 7 | RAG infra offline | Qdrant/RAG worker/eval/golden set | Discord runtime RAG | cross-guild leakage tests, deletion tests |
| 8 | RAG runtime opt-in | admin 지식 업로드와 채널별 RAG | automatic memory | sensitive scan, token budget, fallback |
| 9 | Multi-response dry-run | dev guild/shadow fan-out recommendation | general use, synthesis default | maxFanout, provider opt-in, timeout tests |
| 10 | Multi-response opt-in | 제한적 compare/deep mode | default-on | kill switch, overload alert, redaction |
| 11 | Network growth | 성장 레벨/기여 효과/추천 | competitive ranking pressure | anti-pressure review, capability-based metrics |

### Capability Group ↔ Release Train 매핑

| Capability Group | 기능군 의미 | 실제 실행 Release | 이유 |
| --- | --- | --- | --- |
| Group 0 Foundation | 공통 뼈대 | Release 0~1 | 현재 상태 hardening 과 새 write model 이 분리되어야 함 |
| Group 1 Channel Ai MVP | 채널 AI 정체성 | Release 1~3 | 기본 프로필은 Release 1, wizard/preview 는 Release 3 |
| Group 2 Dashboard MVP | 네트워크 가시화 | Release 2 | write dashboard 전 read-only trust cockpit 먼저 |
| Group 3 Knowledge/RAG | 서버 지식 연결 | Release 7~8 | infra/offline 검증 후 runtime opt-in |
| Group 4 Preset Registry | 설정 공유 | Release 3, 6 | guild/local preset 후 public registry |
| Group 5 Wizard & Approval | 안전한 변경 | Release 3 | preview/publish/rollback 과 같이 출시 |
| Group 6 Quality Routing | 더 좋은 라우팅 | Release 4~5 | shadow scoring 후 live opt-in |
| Group 7 Multi-response | 비교/합성 | Release 9~10 | dry-run/dev guild 후 opt-in |
| Group 8 Advanced Growth | 성장/추천 | Release 11 | capability/feedback 데이터가 쌓인 뒤 가능 |

### Release별 실행 템플릿 요약

| Release | Owner persona | Tables/entities touched | API/command touched | Flags/kill switch | Rollback/degraded path | Do-not-ship condition |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 운영자/개발자 | 기존 dashboard/security/profile | dashboard API, docs | dashboard auth/masking flags | 기존 동작 유지, 민감 필드 숨김 | dashboard 공개 범위/마스킹 ADR 없음 |
| 1 | 관리자 | `channel_ai`, `ai_behavior_version`, `ai_change_proposal`, audit | `/설정`, profile service | new ChannelAi read/write flag | legacy `channel_ai_profile` fallback | backfill parity/rollback 실패 |
| 2 | 관리자/일반 유저/Provider | dashboard projections | `/메뉴`, `/내상태`, dashboard read API | dashboard read flag | stale 표시, 질문 기능 유지 | provider identity/masking 실패 |
| 3 | 관리자 | draft/version/local preset/proposal | wizard, preview, publish, rollback | wizard/local preset flags | 이전 BehaviorVersion 복구 | preview 와 실제 publish 불일치 |
| 4 | 운영자 | feedback, capability profile, shadow score | feedback buttons, internal scoring | shadow routing flag | live routing 영향 없음 | prompt/provider privacy 침해 |
| 5 | 일반 유저/관리자 | routing policy, model choice | `/질문` mode/model options | quality routing kill switch | single-provider/basic routing fallback | Provider safety 보다 품질 점수가 우선 |
| 6 | 관리자/웹 사용자 | preset registry/revision/import/report | web registry, import API | public registry flag | imported draft 삭제/rollback | unsafe preset 이 live config 를 직접 변경 |
| 7 | 운영자 | RAG infra, meta.db, Qdrant collection | RAG rebuild/eval tooling | RAG infra flag | runtime 미연결, index 재빌드 | cross-guild leakage/deletion test 실패 |
| 8 | 관리자/일반 유저 | knowledge source/space/projection | knowledge upload, RAG search | RAG runtime kill switch | RAG context 제외하고 일반 답변 | secret/SSRF/token budget gate 실패 |
| 9 | 운영자/dev guild | multi_response_run dry-run | internal/dev compare route | fan-out dry-run flag | 실제 fan-out 없음 또는 dev guild 한정 | maxFanout/provider opt-in 검증 실패 |
| 10 | 일반 유저/Provider/관리자 | candidate/synthesis summaries | compare/deep mode | multi-response/synthesis kill switches | single response fallback | default-on 또는 Provider opt-out 무시 |
| 11 | 관리자/Provider | growth/capability recommendation projections | network growth dashboard | recommendation flag | 추천 숨김, 자동 적용 없음 | 경쟁/압박 UX 로 Provider 이탈 유발 |

## 21. Projection Freshness & Degraded Mode Contract

대시보드는 신뢰 제품이다. 숫자가 틀리면 없는 것보다 나쁘다.

| Projection | Freshness target | Stale 표시 | Rebuild 방식 | 장애 시 동작 |
| --- | --- | --- | --- | --- |
| `network_overview_projection` | 30초~2분 | “최근 갱신 N분 전” | scheduled rebuild + event refresh | 질문 기능 정상, dashboard degraded |
| `provider_status_projection` | 30초 | “상태 지연 가능” | registry snapshot | Provider 상세 숨김, aggregate 유지 |
| `model_map_projection` | 5분 | “모델 정보 갱신 중” | provider hello/model scan event | 라우팅은 live registry 사용 |
| `channel_ai_projection` | 설정 변경 즉시 | “설정 반영 중” | behavior publish event | old published version 유지 |
| `rag_source_projection` | indexing 완료 후 | “색인 대기/실패” | RAG rebuild workflow | RAG context 제외, 일반 답변 유지 |
| `quality_summary_projection` | 1시간 | “표본 부족/갱신 중” | batch aggregation | 라우팅 반영 금지 |

Projection 원칙:

- projection 누락/지연은 질문 처리 실패가 아니다.
- stale projection 은 숨기지 않고 표시한다.
- dashboard write 는 projection 이 아니라 domain service 를 호출한다.
- projection rebuild command 와 last successful rebuild timestamp 를 운영자가 확인할 수 있어야 한다.

## 22. First 5 Minutes Product Journey

### 일반 유저

1. `/메뉴` 또는 봇 mention 으로 시작한다.
2. 첫 화면에서 “바로 질문하기”와 “이 채널 AI가 하는 일”을 본다.
3. 설정 없이 첫 질문을 보낸다.
4. 답변을 받고 필요하면 👍/👎/🚩 피드백을 남긴다.
5. Provider 내부 ID 나 복잡한 모델 정보는 보지 않는다.

### 서버 관리자 / AI 관리자

1. `/설정` 또는 dashboard 에서 “이 채널 AI 만들기”를 선택한다.
2. 목적/말투/답변 길이/안전 규칙을 선택한다.
3. Discord embed 미리보기와 테스트 질문을 확인한다.
4. publish 또는 approval 요청을 한다.
5. 문제 발생 시 최근 변경 내역에서 rollback 한다.

### Provider

1. “내 컴퓨터의 AI로 함께 도와주기”에서 안전 설명을 먼저 본다.
2. 내 PC에서 무엇이 나가고 무엇은 절대 접근하지 않는지 확인한다.
3. 설치 명령을 실행한다.
4. 상태/가용시간/일일 한도/수신정지를 한 화면에서 조절한다.
5. fan-out/고품질 모드는 별도 opt-in 임을 이해한다.

### Empty/Error state

- Provider 없음: “아직 연결된 로컬 AI가 없어요. 관리자나 멤버가 내 컴퓨터의 AI를 연결하면 질문할 수 있어요.”
- RAG 색인 실패: “등록된 지식을 잠시 참고하지 못하지만, 일반 답변은 계속 가능합니다.”
- dashboard stale: “최근 상태 갱신이 지연되고 있어요. 질문 기능과는 별개입니다.”
- Provider overload: “지금은 참여 PC를 보호하기 위해 요청을 줄이고 있어요.”

## 23. Metrics & North-star

North-star promise:

> Ask immediately, contribute safely, customize with control.

| 영역 | 핵심 지표 | Guardrail |
| --- | --- | --- |
| 즉시 사용 | median time to first answer, first-answer success rate | fallback/error rate |
| 관리자 설정 | channel AI publish success, preview→publish conversion, rollback usage | config incident rate |
| Provider 신뢰 | install completion, pause/resume usage, provider churn | overload alert count |
| 대시보드 | weekly active admins, time-to-diagnosis, projection freshness age | stale dashboard rate |
| RAG | indexing success, retrieval Hit@K/MRR/Recall, citation helpfulness | blocked sensitive upload count |
| 프리셋 | preview→import conversion, rollback after import, report rate | unsafe preset suspension rate |
| 품질 | helpfulness score, feedback rate, repeated issue clusters | provider shame/privacy incident count |
| 다중 응답 | compare helpfulness delta, actual fanout, timeout/fallback rate | fan-out overload blocks |

## 24. 90점 재평가 Rubric

이 문서를 다시 평가할 때는 아래 점수표를 사용한다.

| 항목 | 배점 | 현재 충족 근거 |
| --- | ---: | --- |
| 기능 보존 | 15 | §1, §4.1 이 모든 장기 기능을 보존하고 release train 에 배치 |
| Sequencing/dependency | 20 | §4, §14, §20 이 capability-group map + release train + DAG 제공 |
| Data/model/migration | 15 | §4.2, §14.1, §19 가 current-state, migration, canonical naming 제공 |
| Security/provider safety | 15 | §4.3, §14.3, §15, §16 이 hard gate 와 자동 실패 조건 제공 |
| Product clarity | 10 | §3, §22, §23 이 첫 5분 UX 와 north-star 지표 제공 |
| Verification/observability | 15 | §14.2, §21, §23 이 검증·freshness·metric 제공 |
| Operational realism | 10 | §16, §18, §20 이 rollback, release gate, ADR 제공 |

목표: 외부/멀티 에이전트 비판에서 90점 이상을 받을 때까지 위 항목의 약점을 보강한다.
