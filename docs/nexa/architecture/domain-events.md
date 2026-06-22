# 도메인 간 이벤트 카탈로그

- 작업: NEXA-P01-T020 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 관련 계약: [conversation-context.md](./conversation-context.md),
  [participation-context.md](./participation-context.md), [actionruntime-context.md](./actionruntime-context.md),
  [socialmemory-context.md](./socialmemory-context.md), [logging-boundary.md](./logging-boundary.md)
- dedup/경계 근거: [ADR 0010 ainetwork·socialmemory 경계](../../adr/0010-ainetwork-socialmemory-boundary.md)

## 목적

NEXA 6개 컨텍스트가 주고받는 도메인 이벤트를 카탈로그화한다. 각 이벤트의 **발행자·소비자·멱등성
키·개인정보(PII) 등급**을 고정해, 이중 처리·원문 누출을 방지한다.

## PII 등급 정의

- **high**: 원문 메시지 내용을 참조/포함 가능(저장 시 redaction·보존정책 필수).
- **medium**: 파생 텍스트(후보 문구·요약·관계 추론) 또는 안정 식별자.
- **low**: 결정·상태·correlation ID만. 원문/파생 텍스트 없음.

## 이벤트 카탈로그

| 이벤트 | 발행자 | 소비자 | 멱등성 키 | PII |
| --- | --- | --- | --- | --- |
| `EventIngested` | platform/discord | conversation | `discordMessageId + eventType` | high |
| `BurstFinalized` | conversation | participation, socialmemory | `burstId` | medium |
| `SceneUpdated` | conversation | participation, speech(읽기) | `channelId + sceneSeq` | medium |
| `InteractionObserved` | conversation | socialmemory, ainetwork(브리지) | **`dedupEventId`**(ADR 0010) | medium |
| `ParticipationDecided` | participation | actionruntime, decision log | `correlationId` | low |
| `SpeechPlanned` | speech | actionruntime | `correlationId` | medium |
| `ActionScheduled` | participation | actionruntime | `actionId` | low |
| `ActionReEvaluated` | actionruntime | participation | `actionId + attempt` | low |
| `ActionSent` | actionruntime | requestlog, 실행 감사 | `actionId` | low |
| `ActionCancelled` | actionruntime | 실행 감사 | `actionId` | low |
| `MemorySuperseded` | socialmemory | (내부 projection) | `memoryId + version` | medium |

## 핵심 규칙 (acceptance)

1. **단일 부작용**: 한 사용자 상호작용은 `dedupEventId` 하나로 묶여, `InteractionObserved`가
   socialmemory 관계 projection과 ainetwork 호감도(브리지)에 **각 1회씩만** 반영된다(ADR 0010의
   이중 부작용 방지).
2. **correlation ID 사슬**: `ParticipationDecided → SpeechPlanned → ActionScheduled → ActionSent`은
   동일 `correlationId`를 공유해 requestlog/decision log를 원문 없이 연결한다([logging-boundary.md](./logging-boundary.md)).
3. **원문 비전파**: `EventIngested`만 high다. 이후 이벤트는 원문 메시지 전체를 실어 나르지 않고
   참조/파생만 전달한다. high 이벤트의 영속·삭제는 P03 이벤트 저장 정책을 따른다.
4. **멱등 소비**: 모든 소비자는 멱등성 키로 중복 수신을 무시한다(at-least-once 전제).

## 불변식

1. 각 이벤트의 발행자는 하나다(소유 컨텍스트만 발행).
2. `dedupEventId`/`correlationId` 없이 socialmemory 쓰기·모델 호출을 연결하지 않는다.
3. PII high 이벤트는 redaction·보존정책 경계 안에서만 영속된다.
