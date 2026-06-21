# EXP — Logistic SPEAK/SILENT baseline (NEXA-P11-T004)

- 작업: NEXA-P11-T004 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 모델 코드: [`ml/social-policy/src/nexa_policy/models/logistic.py`](../../../ml/social-policy/src/nexa_policy/models/logistic.py)
- feature SSOT: [features.md](../policy/features.md) · baseline 재현: [EXP-policy-baselines.md](./EXP-policy-baselines.md)

## 범위·금지

- **운영 데이터 없음**: 합성 fixture(`make_synthetic_dataset`, seed 결정론)만 사용한다. 외부 모델 학습 금지.
- 수치는 초소형 fixture(샘플 수백) 기준의 **파이프라인 동작 예시**다 — 운영 품질 보증이 아니다.

## 방법

- 입력: feature 값 + missing-mask 결합 설계행렬(0 과 "모름" 구분, features.md 불변식 2).
- 모델: sklearn `LogisticRegression(class_weight="balanced", solver="lbfgs")`. SPEAK vs SILENT(=not SPEAK)
  이진. class weight 로 다수 SILENT 쏠림을 보정(소수 SPEAK recall 반영).
- 라벨: P10 action 라벨. **UNKNOWN(mask=0)은 학습·평가에서 제외**(강제 IGNORE 금지).
- 결정론: 같은 dataset/seed → 같은 계수·지표.

## 측정 결과 (합성 fixture, seed 20260622, 길드 holdout split)

| split | balanced accuracy | FIR(SPEAK→SILENT) | Brier |
| --- | ---: | ---: | ---: |
| validation | 0.72 | — | — |
| test | 0.51 | 0.75 | 0.40 |

- baseline(always-silent)은 FIR=1.0(모든 SPEAK 놓침). logistic 은 FIR 0.75 로 일부 SPEAK 를 잡아
  **바닥선을 넘는다**(class weight 효과). 작은 fixture라 test 변동이 크다.

## 해석 (acceptance: val/test balanced accuracy·FIR·MIR·Brier 저장)

- `LogisticReport.to_dict()` 가 val/test 의 balanced accuracy·FIR·MIR·Brier 를 모두 직렬화한다 →
  모델 간(tree/mlp/baseline) 동일 지표로 비교 가능.
- `coefficients` 는 feature 별 해석 가능 — 어떤 feature(예: `burst.has_mention`)가 SPEAK logit 을
  끌어올리는지 본다. 해석 가능성이 logistic baseline 의 목적이다.
