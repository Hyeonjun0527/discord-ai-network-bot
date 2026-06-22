# EXP — behavior cloning v2 (NEXA-P19-T014)

- 작업: NEXA-P19-T014 (`kind: experiment`, `human_gate: false`, `risk: medium`) · 선행: NEXA-P19-T013
- 코드: [`models/policy_v2.py`](../../../ml/social-policy/src/nexa_policy/models/policy_v2.py)
- 테스트: [`test_policy_v2.py`](../../../ml/social-policy/tests/test_policy_v2.py)
- 상위: [policy v1 MLP](./EXP-policy-mlp.md), [temporal encoder](./EXP-policy-temporal.md)

## 범위·금지

- **운영 데이터 미접근**: 합성 fixture(`make_synthetic_dataset`)·결정론. torch 미사용(numpy).
- **장기 state·timing 은 관찰/집계 신호만**: P10 export 경계. 원문·식별자·내면 상태 추론 없음.
- **학습 배포 금지**: 본 실험은 v1 대비 상대 비교·파이프라인 검증용 산출물이다(운영 모델 승격 아님).

## 방법

1. v1 = `models.mlp.MlpActionModel`(현재 시점 feature 만)으로 action 을 supervised behavior cloning.
2. v2 = 같은 BC 이되 `augment_long_state` 로 **장기 state(누적 SPEAK/REACT 비율·활동 밀도)와 timing(직전 행동
   이후 경과 bin)** 채널을 덧붙여 학습한다. 장기 state 는 시간순 EWMA 로 결정론 파생.
3. `compare_v1_v2` 가 같은 길드-holdout split 에서 두 모델을 학습해 **이득과 비용**을 한 표(`BcComparison`)로 낸다.

## acceptance — v1 대비 장기 cohort holdout 이득과 비용을 비교한다

- **이득**: test balanced accuracy·FIR/MIR·Brier(v1 과 같은 지표 어휘). `balanced_accuracy_gain = v2 - v1`.
- **비용**: param 수·추론 latency. v2 는 입력 차원이 커 `param_cost_ratio ≥ 1`(비용을 숨기지 않는다).
- 같은 holdout 에서 둘을 비교하므로 "장기 state·timing 추가가 이득 대비 비용을 정당화하는가" 를 정직하게 본다.

## 결과 해석

- v2 의 이득이 작고 비용이 크면 v1 을 유지한다(YAGNI — 무거운 입력 정당화 실패).
- 절대 수치는 합성 fixture 라 운영 일반화를 보장하지 않는다. 실제 채택은 baseline 비교·일반화·calibration·독립
  리뷰(human gate, P19-T023) 이후다 — 본 실험만으로 배포하지 않는다.
