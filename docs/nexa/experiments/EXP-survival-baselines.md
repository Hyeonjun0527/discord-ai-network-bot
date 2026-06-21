# EXP — Cox/parametric survival baseline 비교 (NEXA-P12-T005)

- 작업: NEXA-P12-T005 (`kind: experiment`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- discrete hazard baseline: [EXP-discrete-hazard.md](EXP-discrete-hazard.md)
- 코드: [`ml/social-policy/src/nexa_policy/models/survival_baselines.py`](../../../ml/social-policy/src/nexa_policy/models/survival_baselines.py)

## 범위·금지

- **운영 데이터 금지**: seed 결정론 합성 표본만. 해석 가능한 단순 survival baseline(parametric exponential ·
  Cox 선형 부분위험)을 discrete hazard 와 비교한다. **복잡한 모델이 단순 baseline 을 못 넘으면 채택하지
  않는다**(acceptance T005) — 이 문서가 그 기준선을 제공한다.

## 방법

baseline 둘:

- **ExponentialSurvival**(parametric): 상수 hazard `λ = 사건수 / 총 노출시간`(MLE). 검열 표본도 노출시간은
  기여(사건수 X). `S(t)=exp(-λt)`. 공변량을 안 보는 **무정보 시간 baseline**.
- **CoxLinearHazard**: Cox 비례위험의 선형 로그-부분위험 `β·x`(Breslow 근사 partial likelihood 경사상승).
  baseline hazard 비모수는 생략하고 **위험 순서**(C-index)만 본다 — 해석 가능한 위험 순위 baseline.

검증 데이터는 공변량 `x` 가 클수록 사건이 일찍 나도록 만든 합성(`duration = (1-x)*10 + 0.1`). 좋은 위험
모델이면 큰 `x` 에 큰 위험을 줘 C-index 가 높아야 한다.

## 측정 결과 (seed 결정론, 합성 60 표본 / 단일 공변량)

| baseline | 학습 파라미터 | C-index | 비고 |
| --- | --- | ---: | --- |
| ExponentialSurvival(상수 hazard) | λ = 0.1967 | 0.500 | 공변량 무시 → 위험 순서 무정보 |
| CoxLinearHazard(선형 부분위험) | β = 3.881 | 1.000 | 공변량 신호를 완전히 잡음 |

## 해석 (acceptance: 복잡 모델이 단순 baseline 을 못 넘으면 미채택)

- **exponential 의 한계가 곧 기준선**: 상수 hazard 는 공변량을 안 보므로 위험 순서가 무정보(C-index=0.5)다.
  시간 분포의 marginal 만 맞춘다 — "언제"의 평균은 잡아도 "누가 먼저"는 못 가린다.
- **Cox 가 신호를 잡는다**: 단일 공변량이 duration 을 결정하는 합성에서 Cox 선형 부분위험이 C-index=1.0 으로
  위험 순서를 완전히 복원한다(β=3.88, 양의 계수 = 큰 x 가 큰 위험). **이 1.0 이 채택 기준선**이다 —
  neural survival(T006)이 같은 데이터에서 Cox 의 C-index 를 유의하게 못 넘으면 추가 복잡도를 채택하지 않는다.
- **결론**: 해석 가능한 Cox 선형 baseline 이 단순 신호를 이미 잘 잡으므로, neural/MTPP 같은 복잡 모델은
  Cox 가 못 잡는 **비선형·순차 시간 구조**에서만 정당화된다. 그 이득은 T006(action/time 불일치)·T007(MTPP
  likelihood)에서 별도로 측정한다.
