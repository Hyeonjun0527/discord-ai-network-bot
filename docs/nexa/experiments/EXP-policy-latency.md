# EXP — 추론 latency benchmark (NEXA-P12-T017)

- 작업: NEXA-P12-T017 (`kind: experiment`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- 코드: [`inference_latency.py`](../../../ml/social-policy/src/nexa_policy/benchmarks/inference_latency.py)
- 검증 테스트: [`test_survival.py`](../../../ml/social-policy/tests/test_survival.py)
- 후속 결정: [ADR 0013 정책 서빙 경계](../../adr/0013-policy-serving-boundary.md)

## 범위·금지

- **운영 데이터 금지**: seed 결정론 합성 feature. 이 실험은 정책 forward 의 latency **차수(order)** 와 GLM 호출
  대비 budget gate 로직을 검증한다 — 운영 SLA 측정이 아니다(절댓값은 환경 의존).
- torch 비의존. 실제 ONNX runtime/gRPC 서버 기동 금지 — numpy forward 로 in-process 추론 비용을 근사한다.

## 방법

정책 판단은 GLM 텍스트 생성 호출보다 **먼저** 끝나야 한다(participation 이 "말할지" 를 GLM 호출 전에 결정).
따라서 목표를 GLM 호출 예산에서 정책이 가져도 되는 몫으로 정한다:

- `policy_p95_target = GLM_p95_budget × policy_fraction = 800ms × 0.1 = 80ms`([LatencyBudget]).

[benchmark]/[benchmark_sweep] 가 forward 콜러블을 warmup 후 N 회 호출해 p50/p95/p99(nearest-rank)를 낸다.
측정 시계는 주입 가능해 테스트는 결정론 가짜 시계로 percentile·budget gate 로직을 검증한다(실측은 환경 의존).

## 측정 결과 (DiscreteHazardModel forward, in_dim=16·4 bin, 200 runs)

| batch | p50 | p95 | p99 | budget(80ms) |
| ---: | ---: | ---: | ---: | --- |
| 1 | 0.0026ms | 0.0027ms | 0.0029ms | ✅ |
| 8 | 0.0031ms | 0.0032ms | 0.0033ms | ✅ |
| 32 | 0.0051ms | 0.0052ms | 0.0053ms | ✅ |

(실측 절댓값은 환경 의존 — 핵심은 **차수**다.)

## 해석 (acceptance: 정책 판단이 GLM 호출보다 먼저 충분히 빠르게 완료되는 목표가 정해진다)

- **목표 정의**: 정책 p95 ≤ 80ms(GLM p95 예산 800ms 의 10%). 이 안에서 끝나면 정책 결정이 GLM 호출을 지연시키지
  않는다.
- **in-process forward 는 목표 대비 4~5 자릿수 여유**: 경량 numpy/ONNX forward 의 p95 가 마이크로초대(≈0.003ms)
  로 80ms 목표를 압도적으로 만족한다. batch 가 커져도(1→32) 선형 미만 증가로 여전히 목표 안.
- **서빙 경계 함의**: 추가 네트워크 hop(gRPC) 없이 in-process 가 목표를 만족하므로, hop·직렬화 비용을 들일
  성능 근거가 없다 — 이 수치가 [ADR 0013](../../adr/0013-policy-serving-boundary.md)의 "JVM in-process ONNX
  유지" 결정의 성능 근거다.
- **budget gate 로직 검증**: [LatencyStats.within_budget] 가 측정 p95 를 목표와 비교해 합·불합을 판정하는 로직은
  결정론 가짜 시계로 test 에서 증명된다([test_survival.py] T017).
- **결론**: 정책 추론 latency 목표(p95 ≤ 80ms, GLM 호출 선행)가 정해지고 in-process forward 가 이를 만족한다
  (acceptance 충족). GLM 호출이 latency 지배 항이며 정책은 그 앞에서 무시할 만한 시간에 끝난다.
