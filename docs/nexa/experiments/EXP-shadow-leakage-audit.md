# EXP — shadow feature leakage 감사 (NEXA-P09-T023)

- 작업: NEXA-P09-T023 (`kind: security`, `human_gate: true`) · 상위: [participation-context](../architecture/participation-context.md)
- 구현: [`FeatureLeakageAudit.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/FeatureLeakageAudit.kt)
- 테스트: [`FeatureLeakageAuditTest.kt`](../../../central-server/src/test/kotlin/com/discordassistant/central/participation/application/evaluation/FeatureLeakageAuditTest.kt)
- 근거 정책: [observable-state-policy](../social-state/observable-state-policy.md),
  feature 카탈로그 [`FeatureCatalog.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/feature/FeatureCatalog.kt)

## 위협 모델

shadow 예측은 "예측 시점([predictedAt], cutoff)에 알 수 있던 정보만" 으로 만들어져야 한다. 그래야 나중에 평가할 때
공정하다. 두 종류의 leakage 가 평가를 오염시킨다:

1. **미래 정보 leakage(future observation)**: feature 가 **예측 시점 이후** 발생한 인간 응답/이벤트를 본 경우. 예측을
   평가하는 label(인간 다음 행동)은 예측 **이후** 신호인데, 같은 신호가 feature 에도 들어가면 정책이 미래를 "커닝"한
   꼴이 된다.
2. **label 누설(target leakage)**: counterfactual outcome(인간이 응답했는지)이 feature 로 흘러들어 예측이 사실상
   정답을 입력으로 받는 경우.
3. **금지 추론(forbidden inference)**: [observable-state-policy](../social-state/observable-state-policy.md) 가
   금지한 성격/감정 추론을 feature 로 쓰는 경우.

## 점검 방법 (acceptance: 각 feature 의 cutoff timestamp 와 source watermark 검증)

각 feature 는 **source watermark** 를 가진다 — 그 feature 가 집계에 사용한 **가장 최근 관찰 시각**. 감사는:

```
for each feature in prediction.features:
    assert feature.watermark.observedAt <= prediction.predictedAt   # 미래 관찰 금지
    assert feature.meta.privacyClass in {OBSERVABLE, AGGREGATE}      # 금지 추론 금지
```

- `observedAt <= predictedAt`: watermark 가 cutoff 를 **넘으면** 미래 leakage([LeakageKind.FUTURE_OBSERVATION]).
  cutoff 와 정확히 같은 시각은 허용(이후가 아님).
- privacy class: 카탈로그가 허용한 OBSERVABLE/AGGREGATE 가 아니면 금지 추론([LeakageKind.FORBIDDEN_PRIVACY_CLASS]).

[FeatureLeakageAudit.audit] 가 이 규칙을 순수 함수로 구현하고, [FeatureLeakageAuditTest] 가 acceptance 를 증명한다:

| 테스트 | 검증 |
| --- | --- |
| `cutoff 이내 watermark 만 있으면 clean` | 정상 경로(cutoff 이내·허용 class)는 위반 없음. |
| `cutoff 이후 관찰을 본 feature 는 미래 leakage 위반` | watermark > cutoff 면 FUTURE_OBSERVATION 검출. |
| `허용되지 않은 privacy class 는 금지 추론 위반` | 비허용 class 면 FORBIDDEN_PRIVACY_CLASS 검출. |
| `카탈로그 모든 feature 는 관찰 가능 또는 집계 class 만` | [FeatureCatalog] 전체가 OBSERVABLE/AGGREGATE — 금지 추론 미사용. |

## counterfactual 경계와의 일관성

[counterfactual observation](../architecture/participation-context.md) 빌더(P09-T013)는 이미 각 창 deadline 이내
행동만 보도록 잘라(미래 leakage 금지) 만들어졌다. 이 감사는 **feature 쪽** 에서 같은 경계를 한 번 더 강제한다 —
feature 입력과 outcome label 이 시간상 분리됨을 양쪽에서 보장한다.

## 운영 전환 체크리스트 (human gate)

이 작업은 `human_gate: true` 다. LIVE 승격 전 사람이 다음을 확인한다:

1. 모든 shadow feature 빌더가 cutoff 이후 이벤트를 입력으로 받지 않는지(코드 리뷰 + 이 감사 테스트 통과).
2. counterfactual outcome 이 어떤 feature 에도 흘러들지 않는지(label 누설 0).
3. 카탈로그에 OBSERVABLE/AGGREGATE 외 class 가 추가되지 않았는지(관찰 가능성 정책 위반 0).
4. 새 feature 추가 시 이 문서·테스트를 함께 갱신했는지.
