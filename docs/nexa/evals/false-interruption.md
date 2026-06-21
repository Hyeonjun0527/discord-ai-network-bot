# False Interruption proxy (NEXA-P09-T015)

- 작업: NEXA-P09-T015 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 구현: [`InterventionProxies.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/InterventionProxies.kt)
- 테스트: [`ShadowEvaluationMetricsTest.kt`](../../../central-server/src/test/kotlin/com/discordassistant/central/participation/application/evaluation/ShadowEvaluationMetricsTest.kt)

## 정의

**False Interruption(FI)**: NEXA 가 끼어들었는데(SPEAK 예측) **짧은 시간 내 인간 대화가 이미 이어진** 경우. NEXA 가
실제로 발화했다면 인간의 자연스러운 흐름을 끊었을 가설이다.

shadow 모드에선 NEXA 가 실제로 말하지 않으므로 이는 **counterfactual proxy** 다 — "만약 발화했다면 끼어들 뻔했나"를
관찰로 추정한다.

```
FI(prediction, observation) = (prediction.sampledAction == SPEAK)
                            ∧ (가장 작은 counterfactual 창[3초]에서 인간이 즉시 응답)
```

- 예측 = 그 정책이 이 장면에서 낸 샘플 행동.
- "즉시 응답" = [counterfactual window](../architecture/participation-context.md) 의 **가장 작은 창**(3초) 안에
  인간 reply/react 가 관찰됨. 그 창 deadline 이내 행동만 본다(미래 leakage 금지 — T013 과 같은 경계).

## 관찰 신호만 — 심리 추론 없음

"사람이 끼어들기 싫어했다" 같은 심리 추론을 하지 않는다. **오직 관찰된 행동(reply/react/silence)과 타이밍** 만 본다
([observable-state-policy](../social-state/observable-state-policy.md)). FI 는 정답이 아니라 **약한 안전 신호** 다.

## 오탐(false positive) 가능성

이 proxy 는 다음 경우 **틀릴 수 있다**(오탐):

1. **병렬 대화 정상**: 빠른 채널에선 여러 사람이 동시에 말해도 자연스럽다. 인간이 3초 안에 응답했다고 해서 NEXA 발화가
   반드시 방해였던 것은 아니다(겹쳐 말하기가 규범인 채널).
2. **다른 주제 응답**: 3초 내 인간 응답이 NEXA 가 답하려던 것과 **다른 메시지**에 대한 반응일 수 있다(같은 채널의
   무관한 burst). proxy 는 내용 매칭을 하지 않으므로 이를 구분하지 못한다.
3. **리액션-only**: 인간이 reply 가 아니라 가벼운 react(👍)만 했다면 "대화가 이어졌다"고 보기 약하다 — 그래도 proxy 는
   행동으로 카운트한다.

→ FI 비율은 **상한(upper bound)** 으로 읽어야 한다. 실제 방해율은 그 이하다.

## human review sample

운영 전환 검토 시 다음 표본을 **사람이 직접 검토**해 proxy 의 오탐률을 보정한다(원문은 옵트인·동의 표본만, 가명 유지):

| 검토 항목 | 판정 |
| --- | --- |
| 3초 내 인간 응답이 NEXA 예측 대상과 같은 맥락이었나? | 같음 / 다름(오탐) |
| 채널 규범상 겹쳐 말하기가 자연스러운가? | 자연 / 방해 |
| react-only 인가 reply 인가? | react / reply |
| 종합: 실제 방해였나? | 방해 / 비방해(오탐) |

검토 표본 크기·합의 오탐률은 P09-T024(7일 관찰)·이후 게이트에서 독립 집계한다. 이 문서의 proxy 는 **자동 1차 신호**이고
human review 가 **2차 보정**이다 — 한 지표만으로 정책 승패를 정하지 않는다(T022 비교 템플릿 참조).
