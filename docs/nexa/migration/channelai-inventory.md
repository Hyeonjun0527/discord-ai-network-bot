# channelai 자동응답 코드 상세 인벤토리 (NEXA-P15-T001)

- 작업: [NEXA-P15-T001](../nexa_500_task_graph.yaml) · 상위: P15 통합 단계
- 관련 계약: [discord-adapter-boundary.md](../architecture/discord-adapter-boundary.md),
  [participation-context.md](../architecture/participation-context.md),
  [guild-policy-boundary.md](../architecture/guild-policy-boundary.md)

## 목적

P15 통합(NEXA participation 을 기존 시스템에 연결)에서 **기존 channelai 자동응답을 회귀 0 으로 보존**하려면,
현행 동작을 클래스·메서드 단위로 못 박고 각 코드를 **유지 · adapter · 이관 · 폐기** 중 하나로 분류해야 한다.
이 문서가 그 SSOT 다. T002(feature flag)·T003(adapter 이동)·T004~T013(파이프라인 연결)은 이 분류를 따른다.

## 현행 동작(트리거 경로) — DiscordBot.Listener

기존 자동응답은 전부 `platform/discord/DiscordBot.kt` 의 JDA `Listener` 안에서 일어난다(channelai 패키지가
직접 JDA 를 호출하지는 않는다 — channelai 는 프로필/플래그/영속만 소유).

| 단계 | 위치(verbatim) | 동작 |
| --- | --- | --- |
| 진입 | `Listener.onMessageReceived` | `mentionAskEnabled && isFromGuild && !author.isBot` 게이트 후 멘션이면 `handleMentionAsk`, 아니면 `handleAutoRespond` |
| 멘션 | `Listener.handleMentionAsk` | 봇 멘션 텍스트로 `/ask` 흐름(`respondInChannel`) = SPEAK |
| 자동응답 | `Listener.handleAutoRespond` | `autoRespondChannels.isAutoRespond(g,c)` true 인 채널에서 멘션 없이 응답 |
| 사전필터 | `AutoRespondChannelRegistry.shouldRespond` | `.` 시작·빈 내용 제외(카미봇 컨벤션) |
| 비용 캡 | `rateLimiter.tryAcquire("autorespond:$g:$c")` | 채널 분당 상한 초과 시 ⏳ 리액션만, 조용히 드롭 |
| 발화 | `Listener.respondInChannel` | typing → `commands.ask` → 프로필 있으면 webhook 페르소나, 없으면 답장 스트림 |

**불변(회귀 0 기준)**: feature flag OFF(=legacy)일 때 이 경로는 **한 줄도 바뀌지 않는다**. P15 는 위 경로를
호출하는 새 코드를 추가하지 않고, 새 NEXA 파이프라인은 flag 가 켜진 길드에서만(그리고 shadow 단계에서는 전송
없이) 동작한다.

## 클래스·메서드 단위 분류

### trigger (JDA 측 — platform/discord)

| 코드 | 분류 | 근거 |
| --- | --- | --- |
| `DiscordBot.Listener.onMessageReceived` | 유지 | legacy 진입점. flag OFF 면 그대로. flag ON(MEMBER 채널)일 때만 별도 NEXA ingestion 분기 추가(T004, 기존 분기 무변경) |
| `Listener.handleMentionAsk` | 유지 | ASSISTANT 채널 멘션 = 무조건 답변. NEXA 가 대체하지 않는다 |
| `Listener.handleAutoRespond` | 유지 | ASSISTANT 자동응답. flag OFF 동작 보존 |
| `Listener.respondInChannel` | 유지 | 공통 발화. NEXA 는 actionruntime executor 로 별도 전송(T006) |
| 자동응답 trigger 의 **정책 표현** | adapter | legacy 결정 로직을 `LegacyAutoRespondPolicy`(P09-T006, participation `SocialPolicyPort`) 가 **수정 없이 미러**. T003 는 이 adapter 를 bean 으로 노출만 한다(JDA 호출 미변경) |

### profile (페르소나·프롬프트)

| 코드 | 분류 | 근거 |
| --- | --- | --- |
| `ChannelAiProfileService` | 유지 | 채널 AI 프로필 SSOT(displayName/purpose/tone/...). NEXA speech 는 identity kernel 로 읽기만(T008) |
| `ChannelAiPromptRenderer` | 유지 | 프로필→프롬프트 렌더. ASSISTANT 경로 그대로 |
| `ChannelAiWizardPresetFactory` | 유지 | 위저드 프리셋. T013 preset 통합은 별도 관리자 명령으로 추가만 |
| `ChannelAiOnboardingPresenter` | 유지 | 온보딩 표현. T014 NEXA 동의 단계는 후속 |

### persistence (영속)

| 코드 | 분류 | 근거 |
| --- | --- | --- |
| `ChannelAiEntity` / `ChannelAiPersistence` | 유지 | `channel_ai` 테이블(V5/V7/V33/V48 `auto_respond`). NEXA flag 는 **별도 테이블**(V65+ additive)로 추가, 기존 컬럼 무변경 |
| `AutoRespondChannelRegistry` | 유지 | 핫패스 O(1) 캐시 + `auto_respond` SSOT. `isAutoRespond`/`shouldRespond` 는 legacy·adapter 양쪽에서 재사용 |
| `BehaviorVersionWriter` / `CustomizationAuditRecorder` | 유지 | behavior version·audit. 무변경 |
| `ProposalStatus` / `ProposalStatusConverter` | 유지 | 커스터마이즈 제안 상태. 무변경 |

### API (웹/서비스)

| 코드 | 분류 | 근거 |
| --- | --- | --- |
| `ChannelAiCustomizationController` (+ DTO) | 유지 | 웹 대시보드 전용 채널 AI 설정 API. 정책은 웹 전용 원칙 유지 |
| `ChannelAiCustomizationService` / `ChannelAiProposalQueryService` | 유지 | 커스터마이즈 유스케이스. 무변경 |
| `ChannelAiAccessControlService` / `ChannelAiApprovalPolicy` | 유지 | 권한·승인 게이트. T013 NEXA 관리자 명령도 이 권한 모델을 재사용 |

### JDA side effect (전송)

| 코드 | 분류 | 근거 |
| --- | --- | --- |
| `DiscordAnswerRenderer.sendAnswerWebhook` / `replyToMessageWithPseudoStream` | 유지 | legacy 전송. NEXA 전송은 actionruntime `DiscordSendPort` 구현(`platform/discord/nexa/*Executor`)으로 분리(T006), shadow 단계에서 hard block |
| `rateLimiter` (`quota.application.RateLimiter`) | 유지 | 채널 비용 캡. legacy·NEXA 모두 quota 경계 준수(T011) |

## 분류 요약

- **유지**: channelai 패키지 전부(프로필/프롬프트/영속/API/접근제어) + legacy trigger 6개 메서드.
  기존 동작의 SSOT 이며 P15 에서 수정하지 않는다.
- **adapter**: legacy 자동응답 **결정 로직**은 `LegacyAutoRespondPolicy`(이미 존재, P09-T006)가 미러한다.
  T003 는 이 adapter 를 participation `SocialPolicyPort` bean 으로 노출만 한다(JDA listener 직접 호출 유지).
- **이관(migrate)**: 없음. P15 는 기존 코드를 옮기지 않고 **새 NEXA 파이프라인을 flag 뒤에 추가**한다
  (회귀 0 의 핵심 — legacy 경로를 건드리지 않는다).
- **폐기(deprecate)**: 없음. NEXA MEMBER 채널이 LIVE 로 가더라도 ASSISTANT 자동응답(legacy)은 계속 유지된다
  (온보딩이 두 채널을 모두 만든다 — 메모리 nexa_onboarding_and_policy_ui).

## P15 후속 연결(이 인벤토리가 보장하는 불변)

1. flag(`ShadowMode`, P09-T007, 기본 OFF) OFF → legacy 경로 100% 동일(golden test, T002/T003).
2. flag ON 이어도 OBSERVE_ONLY/SHADOW_PREDICT 는 `ShadowOutboundDispatcher`/`OutboundGuard` 가 전송 hard block.
3. NEXA participation(MEMBER)·legacy 자동응답(ASSISTANT)은 **동시 직접 호출되지 않는다**(T003 acceptance) —
   JDA listener 는 legacy 만 직접 호출하고, NEXA 는 ingestion→정책→executor 의 별도 경로로 돈다.
4. 기존 `channel_ai` 스키마·마이그레이션은 무변경. NEXA flag 는 V65+ additive 테이블.
