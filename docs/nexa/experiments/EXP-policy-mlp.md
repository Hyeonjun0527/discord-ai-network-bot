# EXP — 소형 MLP action model (NEXA-P11-T006)

- 작업: NEXA-P11-T006 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 코드: [`ml/social-policy/src/nexa_policy/models/mlp.py`](../../../ml/social-policy/src/nexa_policy/models/mlp.py)
- 비교: [EXP-policy-logistic](./EXP-policy-logistic.md), [EXP-policy-tree](./EXP-policy-tree.md)

## 범위·금지

- **torch 비의존**: numpy 로 직접 구현한 단일 hidden layer MLP(결정론 init·수동 backprop). 초 단위 학습.
- 합성 fixture·seed 결정론. 운영 데이터 없음.

## 방법

- 구조: `Linear(in→hidden) → ReLU → Linear(hidden→5)` softmax. 5-class action(IGNORE/WAIT/REACT/SPEAK/CANCEL).
- inverse-frequency class weight 로 불균형 보정. 입력은 logistic/tree 와 동일(feature + missing-mask).
- baseline 과 **동일 지표**(balanced accuracy·FIR·MIR·Brier) + 파라미터 수 + 추론 latency 를 보고한다.

## 측정 결과 (합성 fixture, seed 20260622)

| 지표 | 값 |
| --- | ---: |
| parameter 수 | 965 |
| test balanced accuracy | 0.12 |
| test FIR | 0.25 |
| 추론 latency | ~3e-7 s/sample |

- 파라미터(965)는 logistic(≈55)보다 크지만 GBT 앙상블보다 작다. 작은 fixture에서 MLP 의 다중클래스
  balanced accuracy 는 낮다(데이터 부족) — 단일 모델 후보 비교의 **정량 근거**다.

## 해석 (acceptance: parameter 수·latency·성능이 baseline 과 비교)

- `MlpActionReport` 가 parameter 수·`inference_seconds_per_sample`·baseline 동일 지표를 함께 담아
  "성능 대 비용(파라미터·latency)" 트레이드오프를 한 레코드로 본다.
- latency 는 wall-clock 이라 재현 비교에선 제외하고, 학습 결과(메트릭·파라미터)만 결정론으로 일치시킨다.
