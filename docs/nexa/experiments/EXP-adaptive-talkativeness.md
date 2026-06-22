# EXP — adaptive talkativeness 보정 (NEXA-P19-T005)

- 작업: NEXA-P19-T005 (`kind: experiment`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T004
- 코드: [`adaptation/talkativeness.py`](../../../ml/social-policy/src/nexa_policy/adaptation/talkativeness.py)
- 테스트: [`test_adaptation_talkativeness.py`](../../../ml/social-policy/tests/test_adaptation_talkativeness.py)
- 도메인 SSOT: central [`TalkativenessMultiplier.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/config/TalkativenessMultiplier.kt),
  ml [`inference/talkativeness.py`](../../../ml/social-policy/src/nexa_policy/inference/talkativeness.py)
- 경계: [ADR 0014 online adaptation boundary](../../adr/0014-online-adaptation-boundary.md)

## 범위·금지

- **calibration 만**: 운영자가 정한 multiplier **주변에서** 천천히 calibration 을 조정한다. 모델 weight/identity
  학습 아님(ADR 0014 — 실시간 가능한 것은 bounded calibration 뿐).
- **범위 초과 금지**: 보정 결과는 항상 사용자 설정 [lower, upper] 안이다(`adjust` 의 clamp 보장).
- **engagement 조작 금지**: 직접 호출 강제 응답률을 올리는 방향이 아니다(T006 경계와 일관).

## 방법

1. 운영자 baseline·허용 범위·step 한도를 `TalkativenessCalibrationConfig` 로 둔다.
2. 관찰 신호(끼어듦 불만률 vs 발화 기회 놓침률)로 보정 방향을 정한다(`ParticipationSignals.direction`).
3. `adjust` 가 한 step `±max_step` 만 움직이고 [lower, upper] 로 clamp 한다. 각 조정은
   `TalkativenessAdjustment`(이전·이후·이유·clamp 여부)로 설명 가능하게 남는다.
4. `rollback` 이 baseline 으로 즉시 복귀한다.

## acceptance — 사용자 설정 범위를 넘지 않고 자동 변경 내역을 설명·rollback 할 수 있다

- 어떤 신호 조합에서도 `applied ∈ [lower, upper]`(범위 초과 불가).
- 한 step 변화량 ≤ `max_step`(천천히, 폭주 없음).
- 모든 조정이 reason·전후값으로 설명되고, `rollback`(=baseline)으로 되돌릴 수 있다.

## 결과 해석

- 불만↑ → multiplier 감소(말 줄임), 놓침↑ → 소폭 증가. 둘 다 0 이면 변화 없음.
- 이 보정은 SPEAK 확률의 logit 가산 의미(central 과 동일)를 따르며, 메시지 수 곱이 아니다.
- 합성 추정이며 실 적용은 인간 승인 게이트 이후다.
