# 정기 재학습 파이프라인 설계 (NEXA-P19-T019)

- 작업: NEXA-P19-T019 (`kind: implementation`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T018
- 코드: [`pipeline/retrain.py`](../../../ml/social-policy/src/nexa_policy/pipeline/retrain.py)
- 테스트: [`test_retrain_pipeline.py`](../../../ml/social-policy/tests/test_retrain_pipeline.py)
- 상위: [ADR 0014 online adaptation boundary](../../adr/0014-online-adaptation-boundary.md),
  [canary-plan](./canary-plan.md), [model card](../experiments/EXP-policy-mlp.md)

## 목적·범위

정기 재학습을 **dataset 승인→train→eval→model card→signature→shadow 등록** 한 흐름으로 자동화한다. 핵심 안전
불변식: **평가에 실패한 모델은 registry ACTIVE 로 승격되지 않는다**(acceptance T019). 운영 학습 배포는 금지 —
이 설계·오케스트레이션 코드는 stage 순서·게이트·결과 status 를 강제하는 결정론 코어다(합성·CI 친화).

## stage 흐름과 게이트

| # | stage | 게이트 | 실패 시 |
| --- | --- | --- | --- |
| 1 | `DATASET_APPROVAL` | dataset 이 사람/거버넌스 승인됨 | 시작 안 함(REJECTED) |
| 2 | `TRAIN` | 합성 fixture·결정론 학습 | — |
| 3 | `EVAL` | balanced acc·FIR/MIR·calibration·일반화 floor 통과 | **등록 차단(REJECTED)** |
| 4 | `MODEL_CARD` | manifest 자동 생성(수치 드리프트 금지) | — |
| 5 | `SIGNATURE` | artifact 서명(무결성 봉인) | — |
| 6 | `SHADOW_REGISTER` | **SHADOW 까지만** 등록(ACTIVE 아님) | — |

ACTIVE 로 가는 stage 는 파이프라인에 **존재하지 않는다**. ACTIVE 승격은:

1. **human approval UI**(P19-T020) — 사람이 지표·제한·artifact hash 를 보고 이중 확인 후 SHADOW→APPROVED.
2. **독립 아키텍처/코드 감사**(P19-T023).
3. **운영 rollout**(canary→live, 길드별 승인, [canary-plan](./canary-plan.md)).

을 거친다. 어떤 production feedback 도 모델 weight 를 자동 fine-tune 하지 않는다(ADR 0014 계층 B).

## acceptance — 평가 실패 모델이 registry ACTIVE 로 승격되지 않는다

- `run_retrain_pipeline` 은 `EvalGate.passed=False` 면 SHADOW 등록조차 하지 않고 REJECTED 로 끝낸다.
- 통과해도 종료 status 는 최대 `REGISTERED_SHADOW` 다. `PipelineOutcome.promoted_to_active` 는 **항상 False**.
- `PipelineStatus` enum 에 ACTIVE 값이 없다(자동 승격 stage 부재 — 구조적 가드).

## CI 연동(설계)

`.github/workflows/` 의 재학습 잡은 위 stage 를 호출하되, eval 실패 시 비0 종료로 잡을 실패시키고 등록을
생략한다(이미 코드 게이트가 강제). 등록은 SHADOW 까지이며, ACTIVE 승격 단계는 사람 승인(P19-T020) 화면에서만
일어난다 — 워크플로가 ACTIVE 를 자동으로 만들지 않는다.
