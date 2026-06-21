# 삭제 전파 요구사항

- 작업: NEXA-P02-T009 (`human_gate: true`, security) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [data-categories.md](./data-categories.md), [retention-policy.md](./retention-policy.md),
  [user-opt-out.md](./user-opt-out.md)

## 결정

네 가지 삭제 트리거가 event store·memory·dataset에 미치는 효과를 정의한다. 삭제는 **원본에서
파생물까지 전파**된다.

### 트리거별 효과

| 트리거 | event store | memory(socialmemory) | dataset(학습) |
| --- | --- | --- | --- |
| Discord 메시지 삭제 | 해당 이벤트·원문 제거 | 그 메시지 기반 일화/임베딩 제거 | 해당 행 제외·재export |
| 사용자 삭제 요청 | 그 사용자 이벤트 제거 | 관계/일화 제거 | 제외 |
| 길드 탈퇴/봇 제거 | 길드 이벤트 일괄 제거 | 길드 스코프 기억 제거 | 길드 데이터 제외 |
| 동의 철회 | 신규 수집 중단 + 기존 제거 | 기존 기억 제거 | 산출물 제거 |

### hash/provenance 보존 여부 (acceptance)

- 삭제된 원문의 **hash/provenance(삭제 증적)만 남길지 여부는 법적 검토 상태와 함께 결정**한다.
  현재 기본 입장: 삭제 검증·중복 삭제 방지를 위한 **비가역 hash + 삭제 시각만** 보존 가능,
  원문·식별자는 보존하지 않는다. 이 입장의 법적 적합성 검토는 **OPEN(법무 검토 대기)**로 표시한다.

## acceptance 충족

- **삭제된 원문의 hash/provenance만 남길지 여부가 법적 검토 상태와 함께 명확하다**: 기본 입장(비가역
  hash+시각만, 원문 미보존)과 그 법무 검토 상태(OPEN)를 명시했다.

## 불변식

1. 삭제는 원본→파생(projection·임베딩·기억·dataset)으로 전파된다.
2. 삭제 후 원문·식별자는 보존하지 않는다(필요 시 비가역 hash+시각만, 법무 검토 대기).
3. 동의 철회는 신규 수집 중단과 기존 데이터 제거를 동시에 요구한다.
