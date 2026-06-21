# EXP — marked temporal point process PoC (NEXA-P12-T007)

- 작업: NEXA-P12-T007 (`kind: experiment`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- discrete hazard baseline: [EXP-discrete-hazard.md](EXP-discrete-hazard.md)
- 코드: [`ml/social-policy/src/nexa_policy/models/marked_point_process.py`](../../../ml/social-policy/src/nexa_policy/models/marked_point_process.py)

## 범위·금지

- **PoC 수준 — production 통합 금지**: 운영/central/inference 와 결합하지 않는다. likelihood 와 mark
  calibration(Brier)만 보고한다(acceptance T007). 무겁지 않게 homogeneous Poisson 강도 + mark softmax 만.
- **운영 데이터 금지**: seed 결정론 합성 사건열.

## 방법

대화 타임라인을 marked temporal point process(MTPP)로 본다: **IGNORE 는 사건 없음**(no event), 실제 행동
REACT/SPEAK/CANCEL 은 시각 `t`(원점 기준 초)에서 발생하는 **mark 가 달린 사건**이다.

- 시간 강도: homogeneous Poisson `λ = 사건수 / horizon`(MLE).
- mark 분포: 관찰 mark 의 Laplace-smoothed 분포(react/speak/cancel).
- log-likelihood `LL = sum_i[log λ + log p(mark_i)] - λ·horizon`.
- mark Brier: 예측 mark 분포 vs 실제 one-hot 평균(calibration).

discrete hazard(T003)가 "대상 인간이 언제 행동하나"의 생존이라면, MTPP 는 "NEXA 가 어떤 행동을 언제 하나"를
사건열로 직접 모델링하는 대안 시점이다 — PoC 로 likelihood/calibration 이득만 가늠한다.

## 측정 결과 (seed 결정론, 합성 4 사건 / horizon 10s)

| 지표 | 값 |
| --- | ---: |
| base intensity λ | 0.4 (사건/초) |
| log-likelihood | -11.8653 |
| mark Brier | 0.6327 |

## 해석 (acceptance: PoC 는 production 통합 없이 likelihood·calibration 이득만 평가)

- **likelihood 가 정의된다**: homogeneous Poisson + mark 항으로 사건열의 log-likelihood 가 유한하게
  계산된다(-11.87). 이는 향후 비-homogeneous(시간 의존) 강도·neural mark intensity 와 비교할 **기준 likelihood**다.
- **mark calibration 측정 가능**: mark Brier 0.63 은 4 사건 합성에서 smoothed marginal 의 보정 수준이다.
  사건이 적어 절댓값은 거칠지만, PoC 의 목적은 mark 분포를 사건열에서 추정·평가하는 **경로가 동작함**을 보이는 것.
- **IGNORE = 사건 없음 처리 확인**: IGNORE 는 mark 가 아니라 사건 부재로 들어가, 강도 λ 가 실제 행동(react/
  speak/cancel)만 센다([EVENT_MARKS]). 이는 discrete hazard 의 "never/검열"과 일관된 사건-부재 처리다.
- **결론**: MTPP 는 "행동+시점"을 사건열로 직접 모델링하는 유망한 대안이나, 본 PoC 는 likelihood/Brier 가
  **계산·비교 가능함**만 확인한다. production 통합·neural intensity 학습은 범위 밖이다(acceptance: PoC 한정).
  당장의 P12 추론은 discrete hazard(T003) + joint sampler(T008)를 채택하고, MTPP 는 후속 비교 후보로 남긴다.
