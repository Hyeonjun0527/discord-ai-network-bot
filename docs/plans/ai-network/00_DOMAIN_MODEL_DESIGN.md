# AI Network 도메인 모델 설계 초안

> 상태: Draft  
> 작성일: 2026-06-01  
> 목적: “함께 만드는 AI 네트워크” 기능군을 구현하기 전에, DB·도메인 모델·Aggregate 경계를 먼저 확정하고 설계 감사를 수행하기 위한 기준 문서.

## 0. 왜 먼저 설계해야 하나

선택된 기능들은 전부 같은 뿌리를 공유한다.

- 채널별 AI 프로필
- 채널별 AI 헌법
- 채널 AI 카드
- AI 네트워크 지도
- AI 성장 레벨
- Provider 기여 가시화
- 채널별 온보딩
- 응답 속도/품질 모드
- 채널별 지식 업로드/RAG
- 프리셋 공유
- AI 행동 버전 관리
- AI 설정 변경 승인
- “이 채널 AI 만들기” 마법사
- AI 네트워크 대시보드
- 원하는 모델 선택 질문

따라서 화면부터 만들면 안 된다. 먼저 다음 질문에 답해야 한다.

1. “채널 AI”라는 것은 DB 에서 무엇인가?
2. 말투, 헌법, 프롬프트, 모델 정책, 지식, 승인, 버전은 어디에 붙는가?
3. 어떤 설정은 즉시 변경 가능하고, 어떤 설정은 승인/버전/롤백이 필요한가?
4. 대시보드는 원본 테이블을 직접 읽을 것인가, 읽기 모델/projection 을 볼 것인가?
5. RAG 지식, Provider capability, 모델 선택, 품질 피드백이 라우팅과 어떻게 연결되는가?
6. Provider 보호 정책과 민감정보 정책을 어떤 Aggregate invariant 로 강제할 것인가?

## 1. 쉬운 용어 정리

| 용어 | 쉽게 말하면 | 설계상 의미 |
| --- | --- | --- |
| 정보 구조 | 화면과 메뉴를 어떤 묶음으로 나눌지 | Dashboard IA, API resource 경계 |
| 권한 | 누가 무엇을 볼/바꿀 수 있는지 | Role/permission policy, approval gate |
| DB 모델 | 어떤 정보를 어떤 테이블에 저장할지 | Write model, migration, FK/unique/index |
| Read model | 화면이 빠르게 읽기 좋은 요약 데이터 | Projection/DTO/cache, 원본의 파생값 |
| 네트워크 상태 | 이 서버 AI 가 지금 잘 돌아가는지 | Provider/session/request aggregate summary |
| Provider 상태 | 각 참여 PC 가 지금 받을 수 있는지 | Provider session + contribution policy + health |
| 모델 지도 | 어떤 모델/능력이 서버에 있는지 | Model capability projection |
| 채널 AI 목록 | 채널마다 어떤 AI 가 설정됐는지 | ChannelAi aggregate list/card |
| 품질 피드백 | 답변이 좋았는지/문제였는지 | Feedback aggregate, quality signal |
| 과부하 알림 | Provider/서버가 위험하게 바쁜지 | Alert rule/event aggregate |
| 커스터마이징 관리 | 말투/규칙/지식/모델 정책을 바꾸는 것 | Versioned config + approval + audit |

## 2. 설계 원칙

1. **Channel AI 가 중심 Aggregate**다. 대시보드나 마법사는 Channel AI 를 만들고 보여주는 수단이다.
2. **현재 적용 중인 설정과 편집 중인 설정을 분리**한다. Draft 와 Published 를 섞지 않는다.
3. **행동 설정은 버전 불변(immutable)** 으로 남긴다. 문제 생기면 이전 버전으로 rollback 한다.
4. **변경 승인과 변경 이력은 별도 Aggregate** 로 둔다. 설정 테이블에 승인 상태를 덕지덕지 붙이지 않는다.
5. **RAG 지식은 Channel AI 에 직접 박지 않는다.** Knowledge Space 를 따로 두고 Channel AI 가 참조한다.
6. **대시보드는 원본 write model 을 직접 조합하지 않는다.** 화면용 read model/projection 을 둔다.
7. **Provider 보호 정책은 라우팅보다 우선**한다. 커스터마이징은 Provider 한도·일시정지·부하보호를 절대 우회하지 못한다.
8. **프롬프트 원문과 민감정보는 최소 저장**한다. 피드백/대시보드에는 원문을 저장하거나 노출하지 않는다.
9. **Guild scope 를 모든 도메인의 최상위 경계로 둔다.** 다른 서버와 설정/Provider/지식이 섞이면 안 된다.
10. **Discord 패널과 웹 대시보드는 같은 도메인 서비스를 사용**한다. UI 만 다르고 규칙은 하나여야 한다.

## 3. Bounded Context 제안

### 3.1 AI Network Context

서버 전체의 AI 네트워크 정체성과 성장 상태를 관리한다.

- `AiNetworkProfile`
- `AiNetworkLevel`
- `NetworkCapabilitySummary`
- `NetworkGrowthEvent`

### 3.2 Channel AI Customization Context

채널별 AI 정체성, 말투, 헌법, 모델 정책, 온보딩, 카드 표시를 관리한다.

- `ChannelAi`
- `AiBehaviorVersion`
- `AiConstitution`
- `ResponseModePolicy`
- `ModelSelectionPolicy`
- `ChannelOnboarding`

### 3.3 Change Governance Context

AI 설정 변경 승인, 버전 관리, 롤백, 감사 로그를 관리한다.

- `AiChangeProposal`
- `AiConfigVersion`
- `AiPreset`
- `CustomizationAuditLog`

### 3.4 Knowledge/RAG Context

채널/서버별 지식 업로드, 인덱싱, 검색 정책을 관리한다.

- `KnowledgeSpace`
- `KnowledgeSource`
- `KnowledgeDocument`
- `KnowledgeChunk`
- `EmbeddingIndexJob`
- `RetrievalPolicy`

### 3.5 Provider Network Context

기존 Provider Pool 을 확장해 Provider capability, 태그, 모델, 보호 상태를 관리한다.

- 기존 `ProviderEntity`
- 기존 `ProviderContributionPolicyEntity`
- `ProviderCapabilityProfile`
- `ProviderModelCapability`
- `ProviderTag`
- `ProviderProtectionState`

### 3.6 Routing/Execution Context

질문이 들어왔을 때 Channel AI 설정, 모델 선택, Provider 보호 정책을 합쳐 실제 라우팅을 결정한다.

- 기존 `AiRequestEntity`
- `RoutingDecision`
- `RoutingPolicy`
- `ModelChoice`
- `ResponseMode`

### 3.7 Feedback/Quality Context

답변 품질 피드백, 신고, Provider/모델 품질 신호를 관리한다.

- `AiFeedback`
- `QualitySignal`
- `ProviderTrustScore`
- `ModelQualitySummary`

### 3.8 Dashboard/Observability Context

대시보드와 Discord 패널이 보는 읽기 모델을 관리한다.

- `NetworkOverviewProjection`
- `ProviderStatusProjection`
- `ModelMapProjection`
- `ChannelAiCardProjection`
- `AlertEvent`

## 4. Aggregate 설계

### 4.1 AiNetworkProfile Aggregate

서버 전체 AI 네트워크의 제품적 정체성.

핵심 필드:

- `guildId`
- `displayName`
- `tagline` 기본값: `함께 만드는 AI 네트워크`
- `description`
- `defaultSafetyNotice`
- `defaultChannelAiId`
- `networkLevel`
- `createdAt`, `updatedAt`

Invariant:

- guild 당 하나만 존재한다.
- safety notice 는 비워둘 수 없다.
- network level 은 projection 으로 계산하되, 표시용 snapshot 을 둘 수 있다.

### 4.2 ChannelAi Aggregate

가장 중요한 중심 Aggregate. “이 채널의 AI”를 의미한다.

핵심 필드:

- `id`
- `guildId`
- `channelId`
- `name`
- `avatarUrl`
- `shortDescription`
- `purpose` 예: 개발 질문, 번역, 회의록, 공지 작성, 자유 설정
- `publishedBehaviorVersionId`
- `draftBehaviorVersionId`
- `knowledgeSpaceId`
- `routingPolicyId`
- `onboardingId`
- `status` = active/disabled/archived
- `createdBy`, `updatedBy`, `createdAt`, `updatedAt`

Invariant:

- `(guildId, channelId)` 당 active Channel AI 는 최대 1개.
- published version 이 없으면 기본 행동 버전을 fallback 으로 사용한다.
- archived Channel AI 는 라우팅에 사용하지 않는다.
- channel deletion 시 active 상태를 archived 로 전환하거나 정합성 서비스가 정리한다.

현재 코드와의 연결:

- 기존 `channel_ai_profile` 은 `ChannelAi` 의 아주 작은 부분만 담고 있다.
- V5 `channel_ai_profile(display_name, avatar_url)` 은 향후 `channel_ai` + `ai_behavior_version` 으로 확장/마이그레이션한다.

### 4.3 AiBehaviorVersion Aggregate

AI 의 행동 설정 버전. 프롬프트, 헌법, 말투, 답변 길이, 모델 정책의 snapshot 이다.

핵심 필드:

- `id`
- `channelAiId`
- `versionNumber`
- `name`
- `tone`
- `language`
- `answerLength`
- `answerFormat`
- `constitutionText`
- `systemPrompt`
- `responseTemplate`
- `modelSelectionPolicyId`
- `responseModePolicyId`
- `safetyPolicyLevel`
- `changeSummary`
- `createdBy`, `createdAt`
- `publishedAt`, `supersededAt`

Invariant:

- 한 번 published 된 version 은 수정하지 않는다.
- 수정은 새 version 생성으로만 한다.
- rollback 은 이전 version id 를 `publishedBehaviorVersionId` 로 다시 가리키는 방식이다.
- system prompt 는 권한 있는 관리자만 볼 수 있다.

### 4.4 AiConstitution Value Object

채널 AI 가 지켜야 하는 규칙 묶음.

예시 필드:

- `rules[]`
- `forbiddenTopics[]`
- `sensitiveInfoPolicy`
- `uncertaintyPolicy`
- `citationPolicy`
- `toneBoundaries`

설계 결정:

- 별도 테이블로 분리할 수도 있지만, 1차는 `AiBehaviorVersion` 안의 immutable snapshot 으로 둔다.
- 나중에 헌법 프리셋이 필요하면 `AiConstitutionTemplate` 으로 승격한다.

### 4.5 ResponseModePolicy Aggregate

빠른 답변/균형/깊은 답변/절약 모드.

핵심 필드:

- `id`
- `guildId`
- `name`
- `mode` = fast/balanced/deep/economy
- `maxBurden`
- `preferredLatencyMs`
- `allowStreaming`
- `allowFallback`
- `allowFanOut`
- `maxFanOut`
- `maxPromptChars`
- `maxAnswerChars`

Invariant:

- economy/fast 는 heavy/restricted 를 기본 사용하지 않는다.
- fan-out 은 기본 false.
- Provider 한도보다 강한 요청을 만들 수 없다.

### 4.6 ModelSelectionPolicy Aggregate

사용자가 원하는 모델로 질문하거나, 채널이 모델 선택 규칙을 갖는 기능.

핵심 필드:

- `id`
- `guildId`
- `scopeType` = guild/channel/user/request
- `scopeId`
- `selectionMode` = auto/user_selected/fixed/prefer_fast/prefer_quality/prefer_economy
- `allowedModels[]`
- `blockedModels[]`
- `allowedBurdenLevels[]`
- `providerTagRequirements[]`
- `fallbackPolicy`

Invariant:

- request-level 선택은 channel/guild policy 를 넘어설 수 없다.
- 사용자가 모델을 골라도 해당 모델 Provider 가 온라인이 아니면 명확한 fallback/실패 안내를 한다.
- restricted 모델은 권한 정책을 통과해야만 선택 가능하다.

### 4.7 KnowledgeSpace Aggregate

서버/채널의 RAG 지식 공간.

핵심 필드:

- `id`
- `guildId`
- `scopeType` = guild/channel
- `scopeId`
- `name`
- `retrievalPolicyId`
- `status`
- `createdBy`, `createdAt`

하위 엔티티:

- `KnowledgeSource`: file/link/text
- `KnowledgeDocument`: 추출된 문서 단위
- `KnowledgeChunk`: 검색 chunk
- `EmbeddingIndexJob`: 인덱싱 작업 상태

Invariant:

- 원본 파일/링크의 접근 권한을 확인해야 한다.
- 민감정보 스캔/차단 정책을 통과해야 한다.
- RAG 검색 결과는 Channel AI 의 system prompt 와 합쳐지되, Provider 보호 정책을 우회하지 않는다.
- 삭제된 지식은 검색 결과에 나오면 안 된다.

### 4.8 AiPreset Aggregate

프리셋 공유. 마켓이 아니라 서버 안에서 설정을 저장/복사하는 기능.

핵심 필드:

- `id`
- `guildId`
- `name`
- `description`
- `sourceChannelAiId`
- `behaviorVersionSnapshot`
- `routingPolicySnapshot`
- `knowledgePolicySnapshot` 또는 `knowledgeSpaceLinkPolicy`
- `createdBy`, `createdAt`

Invariant:

- 프리셋은 snapshot 이다. 원본 채널 변경이 자동 반영되지 않는다.
- 다른 채널에 적용하면 새 draft behavior version 을 만든다.
- 적용은 승인 정책을 통과해야 할 수 있다.

### 4.9 AiChangeProposal Aggregate

설정 변경 승인.

핵심 필드:

- `id`
- `guildId`
- `targetType` = channel_ai/behavior_version/routing_policy/knowledge_space/preset
- `targetId`
- `proposalType` = create/update/publish/rollback/delete/apply_preset
- `payloadSnapshot`
- `riskLevel` = low/medium/high
- `status` = pending/approved/rejected/applied/cancelled
- `requestedBy`
- `reviewedBy`
- `requestedAt`, `reviewedAt`, `appliedAt`

Invariant:

- high risk 변경은 승인 없이 적용하지 않는다.
- 승인된 payload 와 적용 payload 가 달라지면 안 된다.
- 적용 후 audit log 를 남긴다.

### 4.10 ProviderCapabilityProfile Aggregate

Provider 가 네트워크에 추가하는 능력.

핵심 필드:

- `providerId`
- `guildId`
- `models[]`
- `tags[]` 예: coding, translation, summary, long_context, fast_response
- `availableHours`
- `qualitySignals`
- `lastSeenAt`
- `protectionState`

Invariant:

- provider_hello 의 런타임 capability 와 DB 의 수동 태그를 구분한다.
- Provider 가 꺼져 있으면 라우팅 후보가 아니다.
- quality score 가 높아도 Provider 보호 상태가 우선한다.

### 4.11 AiFeedback Aggregate

답변 품질 피드백.

핵심 필드:

- `id`
- `guildId`
- `channelId`
- `requestId`
- `providerId`
- `model`
- `feedbackType` = positive/negative/report
- `reasonCode`
- `createdBy`
- `createdAt`

Invariant:

- 프롬프트 원문과 답변 원문은 기본 저장하지 않는다.
- 같은 유저가 같은 request 에 반복 피드백을 남기지 못하게 한다.
- 신고는 관리자 검토 대상으로 projection 된다.

### 4.12 Dashboard Projection

Aggregate 가 아니라 읽기 모델이다.

- `NetworkOverviewProjection`
- `ProviderStatusProjection`
- `ModelMapProjection`
- `ChannelAiCardProjection`
- `QualityFeedbackProjection`
- `OverloadAlertProjection`

Invariant:

- projection 은 재생성 가능해야 한다.
- projection 오류가 라우팅/실행을 막으면 안 된다.
- 민감정보와 내부 prompt 는 projection 에 넣지 않는다.

## 5. 기능별 Aggregate 매핑

| 기능 | 중심 Aggregate | 보조 Aggregate |
| --- | --- | --- |
| 채널별 AI 프로필 | ChannelAi | AiBehaviorVersion, ModelSelectionPolicy |
| AI 헌법 | AiBehaviorVersion | AiConstitution VO, AiPreset |
| 채널 AI 카드 | Dashboard Projection | ChannelAi, KnowledgeSpace, RoutingPolicy |
| AI 네트워크 지도 | Dashboard Projection | ProviderCapabilityProfile, AiNetworkProfile |
| AI 성장 레벨 | AiNetworkProfile | NetworkGrowthEvent, ProviderCapabilityProfile |
| Provider 기여 가시화 | ProviderCapabilityProfile | NetworkGrowthEvent, ContributionLog |
| 채널별 온보딩 | ChannelAi | ChannelOnboarding, AiBehaviorVersion |
| 응답 속도/품질 모드 | ResponseModePolicy | RoutingPolicy, ProviderProtection |
| 지식 업로드/RAG | KnowledgeSpace | KnowledgeSource, EmbeddingIndexJob |
| 프리셋 공유 | AiPreset | AiBehaviorVersion, AiChangeProposal |
| 행동 버전 관리 | AiBehaviorVersion | AiChangeProposal, AuditLog |
| 설정 변경 승인 | AiChangeProposal | CustomizationAuditLog |
| 채널 AI 만들기 마법사 | ChannelAi | AiPreset, AiChangeProposal |
| AI 네트워크 대시보드 | Dashboard Projection | 모든 주요 Aggregate |
| 원하는 모델 선택 질문 | ModelSelectionPolicy | RoutingPolicy, ProviderCapabilityProfile |

## 6. DB 테이블 초안

1차 설계 테이블:

- `ai_network_profile`
- `channel_ai`
- `ai_behavior_version`
- `response_mode_policy`
- `model_selection_policy`
- `knowledge_space`
- `knowledge_source`
- `knowledge_document`
- `knowledge_chunk`
- `embedding_index_job`
- `ai_preset`
- `ai_change_proposal`
- `customization_audit_log`
- `provider_capability_profile`
- `provider_model_capability`
- `provider_tag`
- `ai_feedback`
- `network_growth_event`
- `dashboard_projection_snapshot` 또는 projection view/materialized table

마이그레이션 원칙:

- 기존 `channel_ai_profile` 은 바로 삭제하지 않는다.
- `channel_ai` 로 확장 마이그레이션 후 compatibility view/service 를 둔다.
- `display_name`, `avatar_url` 은 `channel_ai` 또는 current `AiBehaviorVersion` 중 어디에 둘지 결정해야 한다.
  - 추천: 표시 이름/아이콘은 `ChannelAi`, 말투/프롬프트/규칙은 `AiBehaviorVersion`.

## 7. 라우팅 시 설정 조합 순서

질문 1건이 들어오면 다음 순서로 설정을 합성한다.

1. Guild policy 확인
2. Channel AI 조회
3. Published behavior version 조회
4. User preference 적용 가능 여부 확인
5. Request-level model selection 확인
6. Channel/Guild model policy 와 권한 policy 로 상한 적용
7. Response mode policy 적용
8. KnowledgeSpace retrieval 여부 결정
9. Provider 후보 생성
10. Provider 보호 조건 적용
11. Capability/tag/model matching
12. Fairness/quality score 적용
13. 최종 Provider 선택
14. 응답 생성
15. usage/contribution/feedback 대상 기록

절대 규칙:

- 사용자 선택 모델은 권한/채널 정책/Provider 보호를 넘지 못한다.
- Channel AI system prompt 는 Provider 보호 정책을 우회하지 못한다.
- RAG 지식이 없어도 질문은 기본 AI 로 처리 가능해야 한다.

## 8. 설계 감사 체크리스트

각 기능별 100개 체크리스트를 쓰기 전에, 설계 PR 마다 아래 감사를 통과해야 한다.

### 8.1 Aggregate fit 감사

- [ ] 새 기능이 어느 Aggregate 의 책임인지 명확한가?
- [ ] Aggregate 사이 순환 의존이 없는가?
- [ ] ChannelAi 가 비대해져 God aggregate 가 되지 않는가?
- [ ] Dashboard 는 projection 으로 남고 write model 이 되지 않는가?
- [ ] RAG 지식이 ChannelAi 설정과 과도하게 결합되지 않는가?

### 8.2 권한/승인 감사

- [ ] 이 변경은 즉시 적용 가능한가, 승인 후 적용해야 하는가?
- [ ] 누가 draft 를 만들고 누가 publish 할 수 있는가?
- [ ] rollback 권한은 누구에게 있는가?
- [ ] Provider 본인 설정과 서버 관리자 설정이 충돌하면 어느 쪽이 우선인가?
- [ ] 변경 이력이 audit log 에 남는가?

### 8.3 버전/롤백 감사

- [ ] published 설정은 불변인가?
- [ ] 새 변경은 새 version 으로 남는가?
- [ ] 이전 version 으로 되돌릴 수 있는가?
- [ ] 프리셋 적용이 원본 설정을 오염시키지 않는가?
- [ ] 마이그레이션 후 기존 채널 프로필이 보존되는가?

### 8.4 Provider 보호 감사

- [ ] 새 설정이 Provider 한도/일시정지/가용시간을 우회하지 않는가?
- [ ] 빠른/깊은/품질 모드가 fan-out 을 무심코 켜지 않는가?
- [ ] 모델 선택이 restricted 모델을 무단 사용하지 않는가?
- [ ] RAG 검색이 긴 프롬프트를 만들어 Provider 과부하를 유발하지 않는가?
- [ ] 대시보드가 특정 Provider 에 요청을 몰아주는 UX 를 만들지 않는가?

### 8.5 개인정보/민감정보 감사

- [ ] 프롬프트 원문 저장이 필요한가? 필요 없다면 저장하지 않는가?
- [ ] feedback 에 답변 원문/민감정보가 저장되지 않는가?
- [ ] 지식 업로드에 민감정보 스캔/삭제 경로가 있는가?
- [ ] 일반 유저에게 Provider 신원이 과도하게 노출되지 않는가?
- [ ] export/delete 요청에 대응할 수 있는가?

### 8.6 기능 간 어울림 감사

- [ ] 마법사로 만든 Channel AI 가 버전 관리/승인/프리셋/RAG 와 자연스럽게 연결되는가?
- [ ] 대시보드에서 보이는 값이 실제 라우팅에 쓰이는 값과 어긋나지 않는가?
- [ ] AI 헌법과 지식 검색 결과가 충돌할 때 우선순위가 있는가?
- [ ] 사용자 모델 선택과 채널 모델 정책이 충돌할 때 안내 문구가 있는가?
- [ ] 성장 레벨이 과도한 Provider 기여 경쟁을 유도하지 않는가?

## 9. 개발 순서 제안

1. **도메인 설계 확정**: 이 문서 감사 후 Aggregate/테이블 이름 확정.
2. **Schema foundation**: ChannelAi, BehaviorVersion, ChangeProposal, AuditLog 최소 스키마.
3. **Read-only Channel AI Card**: 기존 `channel_ai_profile` 을 새 모델로 감싼 뒤 카드 표시.
4. **마법사 MVP**: 질문식으로 Channel AI draft 생성.
5. **버전 publish/rollback**: draft → proposal → publish → rollback.
6. **프리셋 공유**: published version snapshot 을 preset 으로 저장/적용.
7. **응답 모드/모델 선택**: routing policy 와 연결.
8. **Provider capability/model map**: 네트워크 지도와 Provider 기여 가시화.
9. **RAG KnowledgeSpace**: 업로드/인덱싱/검색 연결.
10. **AI Network Dashboard**: projection 기반으로 전체를 보여준다.

## 10. 아직 결정해야 할 질문

- [ ] Channel AI 표시 이름/아이콘은 version 대상인가, 현재 프로필 속성인가?
- [ ] system prompt 를 DB 에 평문 저장할지, 암호화/마스킹 저장할지?
- [ ] RAG 임베딩 저장소를 Postgres 확장(pgvector)로 갈지, 외부 벡터 DB 로 갈지?
- [ ] 프리셋을 guild 내부 공유만 할지, 나중에 공개 공유까지 고려할지?
- [ ] 승인 정책을 모든 서버에 강제할지, 서버별 on/off 로 둘지?
- [ ] 웹 대시보드 인증을 Discord OAuth 로 할지, 관리자 토큰/세션으로 할지?
