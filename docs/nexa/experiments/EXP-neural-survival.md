# EXP — neural survival action/time 불일치 (NEXA-P12-T006)

- 작업: NEXA-P12-T006 (`kind: experiment`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- survival baseline 비교: [EXP-survival-baselines.md](EXP-survival-baselines.md)
- temporal encoder 설계: [EXP-policy-temporal.md](EXP-policy-temporal.md)
- 코드: [`ml/social-policy/src/nexa_policy/models/neural_survival.py`](../../../ml/social-policy/src/nexa_policy/models/neural_survival.py)

## 범위·금지

- **운영 데이터 금지**: seed 결정론 합성 시퀀스만. temporal encoder(GRU) hidden 에서 **action head**(어떤
  행동)와 **time head**(언제, action 별 hazard)를 함께 내고, 둘의 **불일치를 정량화**한다(acceptance T006).
  무거운 학습 없음 — 무작위 init forward 의 구조적 불일치를 측정.

## 방법

GRU encoder 상태 `h` → ① action_head(softmax 전체 분포), ② TIMED_ACTIONS(react/speak)별 time hazard head.
action head 의 행동확률 `p_action` 과 time head 의 누적 사건확률 `sum_k f_k`(관찰 창 내 발화 확률)이 같은
입력에서 얼마나 어긋나는지를:

- **mean_abs_gap**: `mean |p_action - cum_event|` (절대 불일치).
- **correlation**: 두 신호의 상관(높을수록 일관 — action 확률↑ 일 때 빠른 사건 hazard↑).

로 본다. 두 head 가 한 trunk 를 공유하지만 따로 나오므로, 보정 없이는 "SPEAK 확률은 낮은데 빠른 SPEAK
hazard" 같은 모순이 가능하다 — 그 크기를 수치화해 T008 joint sampler 의 필요성을 정당화한다.

## 측정 결과 (seed 결정론, 합성 20 시퀀스)

| timed action | mean_abs_gap | correlation |
| --- | ---: | ---: |
| react | 0.6341 | -0.6666 |
| speak | 0.8027 | 0.6109 |

## 해석 (acceptance: action head 와 time head 불일치가 정량화)

- **불일치가 크다**: 무작위 init 상태에서 action 확률과 time 누적확률의 평균 절대차가 react 0.63·speak 0.80
  으로 크고, react 는 상관이 **음수(-0.67)** 다 — action head 가 react 를 높게 줄 때 time head 는 오히려 늦은
  사건(낮은 누적확률)을 예측하는 모순이 구조적으로 발생함을 보인다.
- **joint 정합의 필요**: 두 head 를 독립으로 두면 이 불일치가 추론에 새어 "react 확률 높음 + react delay 없음"
  같은 모순 출력이 가능하다. 이는 T008 action-time joint sampler 가 **뽑힌 action 의 time head 에서만 delay 를
  뽑게** 해 구조적으로 제거한다([sampler.py](../../../ml/social-policy/src/nexa_policy/inference/sampler.py)).
- **결론**: neural survival 의 action/time 불일치는 측정 가능하고 작지 않다. 따라서 P12 추론은 두 head 를
  독립 사용하지 않고 joint sampling(T008)으로 묶는다. 학습으로 불일치를 줄이는 것은 후속 과제이며, 본 PoC 는
  불일치의 **존재와 크기**를 정량화하는 데 그친다(acceptance 충족).
