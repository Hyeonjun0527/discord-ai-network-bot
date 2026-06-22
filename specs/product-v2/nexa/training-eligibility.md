# 학습 데이터 적격성 규칙

- 작업: NEXA-P02-T007 (`human_gate: true`, security) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [data-categories.md](./data-categories.md), [user-opt-out.md](./user-opt-out.md),
  [consent-model.md](./consent-model.md)

## 결정

학습 데이터셋에 포함되려면 **모든 적격 조건을 동시에 만족**해야 한다. **단 하나라도 불충족이면
제외**한다(AND 게이트, fail-closed).

### 적격 조건 (전부 충족 필요)

| 조건 | 요구 |
| --- | --- |
| 명시적 옵트인 | 길드 동의 + 학습 옵트인이 켜져 있음([consent-model.md](./consent-model.md)) |
| 개인 옵트아웃 아님 | 작성자가 옵트아웃하지 않음([user-opt-out.md](./user-opt-out.md)) |
| 연령/법적 제한 | 법적 처리 제한 대상이 아님 |
| 삭제 상태 | 삭제 요청·전파 대상이 아님([deletion-propagation.md](./deletion-propagation.md)) |
| 채널 범위 | 관찰 허용 채널([channel-scope.md](./channel-scope.md)) |
| 라이선스 | 데이터 사용 라이선스 충족 |

## acceptance 충족

- **단일 불충족 조건만 있어도 데이터셋 export에서 제외되는 규칙이 테스트 가능하다**: 각 조건은
  boolean 평가이며 AND로 결합되어, 한 조건이라도 false면 제외된다. 조건별 테스트 케이스로 검증 가능.

## 불변식

1. 적격성은 모든 조건의 AND다(fail-closed — 모호하면 제외).
2. 옵트아웃·삭제 상태는 export 시점에 재평가된다(과거 적격이 영구 적격이 아니다).
3. 옵트인 없는 데이터는 어떤 경우에도 학습에 쓰이지 않는다.
