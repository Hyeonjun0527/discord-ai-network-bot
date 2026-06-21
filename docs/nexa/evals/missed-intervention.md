# Missed Intervention proxy (NEXA-P09-T016)

- 작업: NEXA-P09-T016 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 구현: [`InterventionProxies.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/InterventionProxies.kt)
- 테스트: [`ShadowEvaluationMetricsTest.kt`](../../../central-server/src/test/kotlin/com/discordassistant/central/participation/application/evaluation/ShadowEvaluationMetricsTest.kt)

## 정의

**Missed Intervention(MI)**: NEXA 가 침묵(IGNORE/WAIT 예측)했는데 **직접 질문이 반복되거나 유사한 인간이 답한**
경우. 말해야 했는데 침묵했을 가설이다.

shadow 모드에선 NEXA 가 실제로 침묵하므로 이는 **counterfactual proxy** 다 — "만약 침묵했다면 놓칠 뻔했나"를
관찰로 추정한다.

```
MI(prediction, observation) = (prediction.sampledAction ∈ {IGNORE, WAIT})
                            ∧ (counterfactual 창 안에서 인간 응답이 관찰됨 = !observation.isNever)
```

- 예측 = 그 정책이 이 장면에서 낸 샘플 행동.
- "인간 응답 관찰" = 예측 이후 [counterfactual window](../architecture/participation-context.md) 네 창(3/10/30/120초)
  중 어디서든 인간 reply/react 가 나타남. 각 창은 deadline 이내 행동만 본다(미래 leakage 금지).

## 관찰 신호만 — "도움이 필요했다" 추론 금지 (acceptance)

**acceptance(T016) — 도움이 필요했다는 심리 추론 대신 관찰 신호만 사용한다**:

MI 는 "사람이 도움/개입이 필요했다" 같은 **내심 추론을 하지 않는다**. 오직 **관찰된 행동** 만 본다:

- **직접 질문 반복**: 같은 사람이 짧은 시간 안에 다시 묻는 burst(반복) — 관찰 가능한 행동.
- **유사 인간이 답함**: counterfactual 창 안에서 다른 인간이 reply/react — 관찰 가능한 행동.

둘 다 [observable-state-policy](../social-state/observable-state-policy.md) 가 허용하는 관찰 신호다. "그 사람이 답을
원했는지" 같은 의도/감정 추론은 쓰지 않는다 — 단지 **행동이 일어났다**는 사실만 카운트한다.

## 오탐(false positive) 가능성

이 proxy 도 다음 경우 **틀릴 수 있다**(오탐):

1. **자기 해결**: 인간이 질문을 반복했지만 곧 스스로 답을 찾았을 수 있다 → NEXA 침묵이 실제로 문제 아님.
2. **다른 사람이 충분**: 유사 인간이 답했다면 그것으로 충분했고 NEXA 개입은 불필요했을 수 있다(오히려 침묵이 정답).
3. **무관한 응답**: 창 안의 인간 응답이 그 질문과 무관한 다른 burst 일 수 있다(내용 매칭 안 함).

→ MI 비율도 **상한** 으로 읽는다. 실제 "놓친 개입"은 그 이하다. MI 와 FI 는 **서로 반대 방향의 안전 신호**다(MI↓ 를
위해 발화를 늘리면 FI↑) — 둘을 함께 봐야 한다(T022 비교 템플릿).

## human review sample

| 검토 항목 | 판정 |
| --- | --- |
| 반복 질문이 같은 미해결 요청이었나? | 미해결 / 자기해결(오탐) |
| 창 안 인간 응답이 그 질문을 실제로 다뤘나? | 다룸 / 무관(오탐) |
| 다른 인간 응답으로 충분했나(NEXA 불필요)? | 충분(오탐) / 부족 |
| 종합: 실제로 놓친 개입이었나? | 놓침 / 비놓침(오탐) |

검토 표본·합의 오탐률은 P09-T024 이후 게이트에서 독립 집계한다. 자동 proxy 는 1차 신호, human review 가 2차 보정이다.
