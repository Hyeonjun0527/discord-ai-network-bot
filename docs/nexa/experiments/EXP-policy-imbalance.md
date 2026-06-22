# EXP — class imbalance 처리 (NEXA-P11-T014)

- 작업: NEXA-P11-T014 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 코드: [`ml/social-policy/src/nexa_policy/training/imbalance.py`](../../../ml/social-policy/src/nexa_policy/training/imbalance.py)

## 범위·금지

- 다수 클래스(IGNORE) 쏠림을 막는 전략 비교다. 합성 fixture·seed 결정론. 운영 데이터 없음.

## 방법

- 전략: (1) none(그대로) (2) class weight(inverse-frequency) (3) focal loss(γ=2) (4) under-sampling(다수 클래스를
  소수 클래스 수에 맞춰 결정론 샘플).
- 각 전략으로 action MLP(numpy)를 학습하고 **balanced accuracy·SPEAK recall·퇴화 여부**를 보고한다.
- **퇴화 판정**: 소수 클래스(SPEAK) recall 이 바닥(기본 0.05) 미만이면 "모두 IGNORE" 퇴화로 본다 →
  높은 raw accuracy 라도 **탈락**(`disqualified=True`).

## 측정 결과 (합성 fixture; 두 split seed 예시)

| split seed | 자격 통과 전략 | 비고 |
| --- | --- | --- |
| 7 | undersample(SPEAK recall 0.33) | none/class_weight/focal 은 퇴화로 탈락 |
| 20260622 | 없음(전부 탈락) | 작은 holdout·강한 불균형에서 단순 전략이 전부 퇴화 |

- 결과는 fixture·split 에 민감하다. 핵심은 **수치(accuracy)가 높아도 SPEAK 를 못 잡는 모델을 자동 탈락**시키는
  게이트가 작동한다는 점이다.

## 해석 (acceptance: accuracy 만 높고 모두 IGNORE 하는 모델을 탈락시킨다)

- `degenerate_all_ignore` 가 소수 클래스 recall 로 퇴화를 판정하고, `compare_imbalance_strategies` 가 각 전략을
  `disqualified` 로 표시한다. `best_qualified()` 는 **퇴화가 아닌** 전략 중 최선만 고른다 →
  "balanced accuracy 만 보고 모두 IGNORE 하는 모델을 채택"하는 실수를 구조적으로 막는다.
- under-sampling 이 소수 클래스 신호를 가장 잘 보존하는 경향(작은 fixture)이나, 데이터가 충분하면 class weight·
  focal 이 정보 손실 없이 유리할 수 있다(운영 데이터 확보 후 재실험 대상).
