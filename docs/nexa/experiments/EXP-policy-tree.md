# EXP — Gradient-boosted tree baseline (NEXA-P11-T005)

- 작업: NEXA-P11-T005 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 코드: [`ml/social-policy/src/nexa_policy/models/tree.py`](../../../ml/social-policy/src/nexa_policy/models/tree.py)
- 비교 baseline: [EXP-policy-logistic](./EXP-policy-logistic.md)

## 범위·금지

- 합성 fixture·seed 결정론. 운영 데이터 없음. 수치는 파이프라인 동작 예시.

## 방법

- 모델: sklearn `HistGradientBoostingClassifier(max_depth=3, max_iter=60, l2_regularization=1.0)`.
  비선형 tabular baseline. 입력은 logistic 과 동일(feature + missing-mask).
- 길드 holdout(test)은 P10 길드 단위 split 이라 **train 에 없는 길드**다 → 일반화/과적합 측정에 적합.
- permutation importance(balanced accuracy 기준)로 feature 기여를 분석한다.

## 측정 결과 (합성 fixture, seed 20260622, 길드 holdout)

| 지표 | 값 |
| --- | ---: |
| train balanced accuracy | 0.97 |
| test(길드 holdout) balanced accuracy | 0.47 |
| overfit gap(train − test) | 0.50 |

- 큰 overfit gap(0.50)은 작은 fixture에서 GBT 가 train 을 거의 외움을 보인다 → 정규화(l2·depth 제한)와
  더 많은 데이터가 필요하다는 **과적합 신호를 정량 보고**한다.

## 해석 (acceptance: 길드 holdout 성능과 과적합 차이 보고)

- `TreeReport` 가 train·test balanced accuracy 와 `overfit_gap` 을 함께 담는다 → 과적합을 한 수치로 본다.
- `feature_importance` 는 어떤 feature 가 예측에 기여하는지 보여, 비선형 모델이 선형(logistic)이 못 잡는
  상호작용을 쓰는지 점검한다. 작은 fixture라 importance 변동이 크므로 holdout 에 SPEAK 가 충분할 때만 계산한다.
