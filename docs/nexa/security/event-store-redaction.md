# event store redaction 구현 (NEXA-P03-T022)

- 작업: NEXA-P03-T022 (`human_gate: false`, implementation, security 핵심) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [redaction-contract.md](./redaction-contract.md), [data-categories.md](../../../specs/product-v2/nexa/data-categories.md),
  [deletion-propagation.md](../../../specs/product-v2/nexa/deletion-propagation.md),
  [external-model-data.md](../../../specs/product-v2/nexa/external-model-data.md)

## 목적

삭제/옵트아웃 트리거가 도착하면 event store 의 대상 이벤트에서 **암호화 content payload 를 무효화**하고,
원문/식별자가 평문으로 남지 않게 강제한다. 이벤트의 **존재·순서·계보는 보존**하되 원문 참조만 비가역으로 무효화한다.

## 구현

- 유스케이스: `conversation/application/privacy/RedactConversationEventService` (`redact(eventId, trigger)`).
- 무효화: `EventStorePort.markRedacted(eventId)` 가 `redacted=true` 전이 + `content_cipher=null`(암호화 참조 폐기).
  행을 지우지 않으므로 event sourcing 순서·계보가 깨지지 않는다(deletion-propagation.md 불변식 1).
- provenance/처리 증거: `RedactionReceipt(eventId, trigger, processedAt)` — **원문/snowflake 원문/키/가명/비가역
  hash 를 담는 필드가 구조적으로 없다**. 회귀 테스트(`RedactConversationEventServiceTest`)가 금지 필드 부재를
  reflection 으로 고정한다.
- 멱등: 이미 redaction 됐거나 대상이 없으면 `ALREADY_REDACTED_OR_ABSENT`(중복 side effect 없음).

## redaction-contract.md 금지 필드 (event store / receipt 에 평문 금지)

| 금지 필드 | event store 처리 | receipt 처리 |
| --- | --- | --- |
| 메시지 원문(raw content) | `content_cipher=null`(미저장/폐기) | 필드 없음 |
| 작성자 snowflake 원문 | 저장 안 함(채널/순서 메타만) | 필드 없음 |
| API 키·토큰 | 저장 안 함 | 필드 없음 |
| 비가역 hash | **미보존(법무 OPEN, 아래 참조)** | 필드 없음 |

## replay 일관성 (acceptance)

redaction 후 `EventStorePort.streamByChannel` 의 그 이벤트는 `redacted=true`(content_cipher=null)라 replay 에서
**content unavailable 로 일관되게** 보인다. 이벤트는 사라지지 않고(순서 보존) 상태만 unavailable 이다.

## 비가역 hash 보존 — 법무 검토 OPEN (BLOCKER 추적)

[deletion-propagation.md](../../../specs/product-v2/nexa/deletion-propagation.md) T009 의 "삭제 후 비가역 hash 보존"
의 법적 적합성은 **법무 검토 미확정(OPEN)** 이다(task graph P02 게이트의 미해결 BLOCKER 1건).

- **현재 입장(보수적)**: hash 를 **보존하지 않는다**. `RedactionReceipt` 는 비가역 hash 도, 원문/식별자도 담지 않고
  처리 증거(이벤트 키·트리거 코드·시각)만 남긴다. 코드 KDoc(`RedactConversationEventService`)에도 OPEN 을 명시했다.
- **확정 시**: 법무가 비가역 hash 보존을 허용하면 그때 **비가역 hash + 삭제 시각만**(원문/식별자 미보존)으로
  `RedactionReceipt` 를 확장한다. 그 전까지 hash 필드는 도입하지 않는다(최소 입장).

## acceptance 충족

- redacted 이벤트가 replay 에서 content unavailable 로 일관되게 보임: `RedactConversationEventServiceTest`
  `redacted 이벤트는 replay 에서 content unavailable 로 일관되게 보인다` 가 증명.
- 금지 필드 부재: 같은 테스트의 `RedactionReceipt 는 원문 식별자 키 hash 를 담는 어떤 필드도 갖지 않는다` 가
  reflection 으로 고정(드리프트 가드 포함).
- hash 보존 법무 OPEN: 본 문서 + 유스케이스 KDoc 에 명시.

## 불변식

1. redaction 은 행을 지우지 않고 `content_cipher` 무효화 + 상태 전이만 한다(존재·순서·계보 보존).
2. `RedactionReceipt` 는 원문·식별자·키·비가역 hash 필드를 갖지 않는다(회귀 테스트로 강제).
3. 비가역 hash 보존은 법무 검토(OPEN) 확정 전까지 구현하지 않는다(보수적 최소 입장).
