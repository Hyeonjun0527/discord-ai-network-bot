# 연속시간 정책 모델 연구 보고서 (NEXA-P12-T023)

- 작업: NEXA-P12-T023 (`kind: documentation`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- 시간 원점 계약: [time-origin.md](../policy/time-origin.md)
- 서빙 경계 결정: [ADR 0013](../../adr/0013-policy-serving-boundary.md)

> **정직성 게이트(acceptance T023)**: 이 보고서는 **PoC 결과를 production capability 로 과장하지 않는다**. 아래
> 모든 수치는 seed 결정론 **합성 데이터**에서 나왔고(운영 데이터 금지), 모델 다수는 학습되지 않은 init 가중치다.
> 따라서 metric 절댓값은 **상대 비교·수학적 유효성 검증용**이지 운영 품질 보증이 아니다.

## 1. 목적·범위

P12 는 NEXA 의 **타이밍**(언제·어떻게 말할지)을 연속시간 관점에서 모델링하는 후보들을 PoC 로 비교했다. 이
보고서는 실험 결과, 실패, 채택/보류 모델, 그리고 각 모델이 따르는 **수학적 계약**을 정리한다.

- **포함**: 이산 hazard·생존 baseline·neural survival·marked point process(MTPP)·time calibration·burst
  간격·예약 취소 head·multiplier fairness·tempo slice·누출 감사·latency.
- **제외(운영)**: shadow A/B 실행(T022)·사람 평가(T024)·게이트(T025)는 운영이라 이 연구 범위 밖이다.

## 2. 후보 모델·실험 결과 요약

| 모델 / 실험 | 코드 | 실험 문서 | 핵심 결과(합성) | 상태 |
| --- | --- | --- | --- | --- |
| discrete-time hazard | [discrete_hazard.py](../../../ml/social-policy/src/nexa_policy/models/discrete_hazard.py) | [EXP-discrete-hazard](../experiments/EXP-discrete-hazard.md) | survival 단조 비증가, `sum f_k + S_last = 1`(±1e-15) | **채택(기반)** |
| 생존 baseline(Cox/Exp) | [survival_baselines.py](../../../ml/social-policy/src/nexa_policy/models/survival_baselines.py) | [EXP-survival-baselines](../experiments/EXP-survival-baselines.md) | 검열 지원 NLL/C-index 병행 보고 | 채택(대조군) |
| neural survival | [neural_survival.py](../../../ml/social-policy/src/nexa_policy/models/neural_survival.py) | [EXP-neural-survival](../experiments/EXP-neural-survival.md) | action-time joint, 시퀀스 인코더 | 보류(PoC) |
| marked point process | [marked_point_process.py](../../../ml/social-policy/src/nexa_policy/models/marked_point_process.py) | [EXP-mtpp-poc](../experiments/EXP-mtpp-poc.md) | base intensity·mark Brier 산출 | **보류(연구)** |
| time calibration | [calibration/time.py](../../../ml/social-policy/src/nexa_policy/calibration/time.py) | — | 악화 시 미적용(integrated Brier 게이트) | 채택 |
| burst 간격 | [burst_timing.py](../../../ml/social-policy/src/nexa_policy/models/burst_timing.py) | [EXP-burst-baseline](../experiments/EXP-burst-baseline.md) | scheduler 계획(랜덤 sleep 아님), 간격 cap | 채택 |
| 예약 취소 head | [heads.py](../../../ml/social-policy/src/nexa_policy/models/heads.py) | — | 하드 contextVersion invalidation 우선 | 채택(보조) |

## 3. 가로지르는 분석(P12 후반)

- **multiplier saturation·fairness**([EXP-hazard-multiplier](../experiments/EXP-hazard-multiplier.md)): 1.5x 의
  빠른 대화 worst-case 누적 발화확률은 base 대비 **+3pp**(0.95→0.98)로, 과도 끼어들기로 변하지 않는다. odds
  가산의 포화 때문이며, 민감 구간은 빠른 채널이 아니라 **중간 tempo**(Δ +0.127)다.
- **tempo slice 평가**([EXP-timing-by-tempo](../experiments/EXP-timing-by-tempo.md)): slice별 metric 과 은닉
  플래그로 "평균이 빠른 채널 오류를 숨기는지" 를 드러낸다 — 평가는 slice별로 봐야 한다.
- **시간 feature 누출 감사**([EXP-time-leakage-audit](../experiments/EXP-time-leakage-audit.md)): reply 후 계산된
  tempo·finalize reason 이 예측 시점 feature 에 들어가면 row별 cutoff timestamp 로 fail-closed 탐지.
- **latency**([EXP-policy-latency](../experiments/EXP-policy-latency.md)): in-process forward p95 가 정책 목표
  (80ms = GLM 예산 10%)를 4~5 자릿수 여유로 만족 — gRPC hop 없이 충분.

## 4. 채택/보류 결정과 근거

- **채택(기반)**: discrete-time hazard 가 P12 타이밍의 **수학적으로 유효한 이산 근사**(단조 생존·확률 합)로
  채택됐다. 검열(right-censoring)을 never 로 강제하지 않는 NLL 이 안전하다.
- **채택(운영 가드)**: time calibration(악화 시 미적용)·burst scheduler 계획(랜덤 sleep 금지)·예약 취소의 하드
  invalidation 우선 — 모두 "불확실하면 보수적으로" 라는 NEXA fail-safe 원칙과 일관.
- **보류(연구)**: neural survival·MTPP 는 PoC 로 **거동만** 확인했다(학습 X). production 으로 보기엔 학습·검증·
  운영 latency·ONNX export 가능성이 미확정이다. 무거운 모델이 필요해지면 [ADR 0013] 의 gRPC 옵션을 재평가한다.

## 5. 수학적 계약(요약)

- **hazard/생존**: `h_k ∈ [0,1]`, `S_k = prod_{j<=k}(1-h_j)`(단조 비증가), `f_k = h_k·S_{k-1}`,
  `sum_k f_k + S_last = 1`.
- **multiplier**: logit 가산 `logit' = logit(h) + ln(m)`, `h' = sigmoid(logit')`, `h' ≤ 0.999`(cap).
  메시지 수 곱 아님(P08-T017 경계).
- **burst 계획**: 상대 offset 비감소·`interval ∈ [MIN, MAX]`(랜덤 sleep 아님).
- **typing**: `mustEndBy = startOffset + maxDuration`, `maxDuration ≤ 30s`(무한 typing 방지, T014).
- **누출**: 각 row 의 모든 feature `computed_at < feature_cutoff`(미래 누출 금지, T016).

## 6. 실패·한계(정직)

- metric 절댓값은 **합성·미학습**이라 운영 품질을 말하지 못한다(C-index≈0.5 무정보 구간 존재).
- MTPP/neural survival 의 운영 이득은 **미검증** — shadow A/B(T022)·사람 평가(T024)로만 확인 가능(이 연구 범위
  밖, 운영).
- 연속시간 모델의 ONNX export 가능성이 불확실하다 — 불가하면 in-process(ADR 0013) 전제가 흔들려 gRPC 옵션
  활성화가 필요해질 수 있다(미해결).

## 7. 결론

P12 의 연속시간 타이밍은 **discrete hazard 기반 + 보수적 운영 가드(calibration·scheduler·하드 invalidation)**
로 정리된다. neural survival·MTPP 는 **연구 보류**(PoC 거동 확인까지)이며, 운영 승격은 shadow/사람 평가/ONNX
export 검증을 거쳐야 한다 — 이 보고서는 PoC 를 production capability 로 과장하지 않는다(acceptance 충족).
