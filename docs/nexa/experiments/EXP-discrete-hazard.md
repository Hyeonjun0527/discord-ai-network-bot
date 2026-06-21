# EXP — discrete hazard baseline 유효성 (NEXA-P12-T003)

- 작업: NEXA-P12-T003 (`kind: experiment`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- 시간 원점 계약: [time-origin.md](../policy/time-origin.md), right-censoring
  [censoring.py](../../../ml/social-policy/src/nexa_policy/time/censoring.py)
- 코드: [`ml/social-policy/src/nexa_policy/models/discrete_hazard.py`](../../../ml/social-policy/src/nexa_policy/models/discrete_hazard.py)

## 범위·금지

- **운영 데이터 금지**: seed 결정론 합성 hazard·라벨만. discrete-time survival 의 **수학적 유효성**(단조
  생존·확률 합)과 검열 처리를 검증한다 — 운영 품질 보증이 아니다.

## 방법

연속시간 생존을 시간 bin(예: `[0,2)·[2,10)·[10,60)·[60s+)`)으로 이산화하고, bin `k` 별 **조건부 hazard**
`h_k = P(사건이 bin k | bin k 까지 생존)` 을 독립 sigmoid 로 예측한다(softmax 아님). 파생:

- survival `S_k = prod_{j<=k}(1 - h_j)` — `h_k ∈ [0,1]` 이므로 단조 비증가.
- 사건 pmf `f_k = h_k * S_{k-1}` — `sum_k f_k + S_last = 1`.

검열(right-censored) 표본은 NLL 에서 "마지막 관찰 bin 까지 생존" 항만 더하고 사건 항을 빼, 검열을 never
정답으로 강제하지 않는다([discrete_nll], acceptance T002/T003 연계).

## 측정 결과 (seed 결정론, 합성 40 표본 / 4 bin)

| 검사 | 값 | 기대 |
| --- | ---: | --- |
| survival 단조성(인접 bin 차의 최댓값) | -0.0527 | ≤ 0 (단조 비증가) |
| `sum_k f_k + S_last` 범위 | 0.99999999~1.00000001 | = 1 (부동소수 오차 내) |

| metric(검열 혼합) | 값 |
| --- | ---: |
| survival NLL | 1.5257 |
| concordance(C-index) | 0.4962 |
| integrated Brier | 0.1793 |
| time calibration error | 0.0328 |
| delay accuracy(병행) | 0.3077 |

## 해석 (acceptance: survival 단조 감소·합계 수학적 유효)

- **단조 감소 확인**: 모든 인접 bin 차가 ≤ 0(최댓값 -0.0527 < 0) — `S` 가 bin 을 지날수록 비증가다. hazard 가
  확률([0,1])인 한 구조적으로 보장되며 수치로도 확인된다.
- **확률 합 유효**: `sum_k f_k + S_last` 이 부동소수 오차(±1e-15) 내에서 정확히 1 이다 — discrete-time
  survival 의 사건/생존 분해가 수학적으로 닫힌다(acceptance 충족).
- **검열 안전**: 학습되지 않은 무작위 가중치라 metric 절댓값은 무의미(C-index≈0.5 무정보). 핵심은 검열
  표본이 [discrete_nll] 에서 사건 항 없이 들어가 never 로 오학습되지 않는 점이다 — 이는
  [test_survival.py](../../../ml/social-policy/tests/test_survival.py) 의 검열 테스트가 보장한다.
- **결론**: discrete hazard 는 P12 의 연속시간 생존을 수학적으로 유효한 이산 근사로 표현한다. 이후 T005
  (Cox/parametric 비교)·T006(neural)·T011(time calibration)이 이 baseline 위에서 정량 비교된다.
