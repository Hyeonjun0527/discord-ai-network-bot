# EXP — Always-silent·fixed-probability baseline 재현 (NEXA-P11-T003)

- 작업: NEXA-P11-T003 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 코드: [`ml/social-policy/src/nexa_policy/baselines/policy_baselines.py`](../../../ml/social-policy/src/nexa_policy/baselines/policy_baselines.py)
- 상위 baseline(P09): [EXP-talkativeness](./EXP-talkativeness.md)

## 범위·금지

- 학습 없는 무지 기준선이다 — **정의가 곧 결과**(always-silent=항상 IGNORE, fixed-probability=marginal 분포).
- 합성 fixture·seed 결정론. 운영 데이터·외부 모델 호출 없음.

## 방법

- 같은 dataset split 에서 두 baseline 의 action 확률을 계산하고 P11 공통 지표(balanced accuracy/FIR/MIR/Brier)로
  평가한다. **UNKNOWN(mask=0)은 제외**한다(강제 IGNORE 금지 — P10 라벨 규칙 계승).
- `evaluate_baseline` 은 모델 평가와 **동일한 지표 함수**(metrics.py)를 쓴다 → 모델이 넘어야 할 바닥선 고정.

## 측정 결과 (합성 fixture, seed 20260622)

| baseline | balanced accuracy | FIR | MIR | Brier |
| --- | ---: | ---: | ---: | ---: |
| always_silent | 0.20 | 1.00 | 1.00 | 0.78 |
| fixed_probability(marginal) | 0.20 | 1.00 | 1.00 | 0.56 |

- always-silent 은 모든 SPEAK·상호작용을 놓친다(FIR=MIR=1.0) — 가장 보수적 바닥선.
- fixed-probability 의 argmax 도 다수 클래스(IGNORE)라 동일하게 상호작용을 놓치지만 Brier 는 더 낮다
  (확률 분포가 marginal 을 반영).

## 해석 (acceptance: Kotlin shadow 와 차이가 있으면 원인 문서화)

- 이 baseline 은 학습이 없어 **언어·런타임 독립**이다(정의가 동일하면 Kotlin shadow 와 점수가 같아야 한다).
- 차이가 생긴다면 원인은 (a) split 불일치, (b) UNKNOWN 제외 규칙 불일치, (c) 지표 정의 차이뿐이다 →
  `BaselineReport.notes` 로 차이 원인을 명시하도록 했다(조용한 불일치 금지). 현 fixture에선 shadow 차이 없음.
