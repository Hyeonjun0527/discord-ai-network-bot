# 데이터 계보 레코드 계약

- 작업: NEXA-P02-T013 (documentation, `human_gate: false`) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [deletion-propagation.md](./deletion-propagation.md), [domain-events.md](../../../docs/nexa/architecture/domain-events.md),
  [data-categories.md](./data-categories.md)

## 목적

모든 파생 레코드(기억·feature·학습 row)가 **어느 source event에서, 어떤 변환 버전으로** 만들어졌는지
추적할 수 있게 한다. 이로써 한 이벤트가 삭제될 때 영향받는 파생 데이터를 역추적([deletion-propagation.md](./deletion-propagation.md))할 수 있다.

## 계보 필드 (모든 파생 레코드 공통)

| 필드 | 의미 |
| --- | --- |
| `sourceEventId` | 이 레코드를 만든 원본 정규화 이벤트 ID(여럿이면 목록) |
| `transformationVersion` | 변환 로직 버전(임베딩 모델/요약 프롬프트/feature 추출 버전) |
| `derivedAt` | 파생 생성 시각 |

- 적용 대상: socialmemory 기억(일화/관계), participation feature, 학습 dataset row, 임베딩.
- `sourceEventId`는 [domain-events.md](../../../docs/nexa/architecture/domain-events.md)의 이벤트 ID
  체계(dedupEventId/correlationId)와 일관된다.

## acceptance 충족

- **한 이벤트 삭제 시 영향받는 파생 레코드를 역추적할 수 있다**: `sourceEventId` 역인덱스로 해당
  이벤트에서 파생된 모든 레코드(기억·feature·학습·임베딩)를 찾아 삭제 전파 대상으로 수집한다.
- `transformationVersion`으로 특정 변환 버전의 산출물만 선택적 재처리·무효화할 수 있다.

## 불변식

1. 모든 파생 레코드는 `sourceEventId`와 `transformationVersion`을 가진다(계보 없는 파생 금지).
2. source event 삭제는 `sourceEventId` 역인덱스로 파생 전체를 역추적해 전파된다.
3. 계보 자체는 원문을 포함하지 않는다(ID·버전·시각만, [data-categories.md](./data-categories.md) 준수).
