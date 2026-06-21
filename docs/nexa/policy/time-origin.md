# 정책 이벤트 시간 원점 계약 (SSOT) — NEXA-P12-T001

- 작업: NEXA-P12-T001 (`kind: documentation`, `human_gate: true`) · 상위 계약:
  [participation-context.md](../architecture/participation-context.md)
- 관련: delay 라벨 [delay.py](../../../ml/social-policy/src/nexa_policy/data/labels/delay.py),
  right-censoring [censoring.py](../../../ml/social-policy/src/nexa_policy/time/censoring.py),
  talkativeness multiplier 계약 [features.md](features.md)
- 코드 미러(시간 원점 상수·단위): `ml/social-policy/src/nexa_policy/time/origin.py`

## 목적

P12 의 "언제 말할지"(타이밍)는 응답 지연을 **연속시간 생존분석**(survival/hazard)으로 모델링한다.
생존분석은 모든 표본이 **같은 시간 원점 `t = 0`** 과 **같은 시간 단위**를 써야 위험률(hazard)·생존확률
(survival)·검열(censoring)이 수학적으로 정의된다. 학습(ml)·Kotlin runtime(participation)·평가(eval)가
서로 다른 원점을 쓰면 같은 모델이 다른 결정을 내린다 — 이 문서가 그 단일 원점을 못박는다(acceptance:
**학습·Kotlin runtime·평가가 같은 원점을 사용한다**).

## 시간 원점 `t = 0` (opportunity)

정책이 "지금부터 얼마 뒤에(또는 끼어들지) 행동할까"를 재는 **기준 시점**을 *opportunity* 라 한다.
`t = 0` 은 다음 중 **가장 최근에 발생한 opportunity 이벤트의 발생 시각**(`event_time_ms`)이다:

1. **burst finalize**: 관찰 중인 버스트가 [FixedGapBurstSegmenter] 기준으로 종료(finalize)된 시각.
   대화 한 덩어리가 끝나 "응답할 자리"가 열린 1차 opportunity.
2. **scene update**: scene(대화 장면) 상태가 갱신된 시각(주제 전환·참여자 변화 등). 새 맥락이 생겨
   재평가가 필요한 opportunity.
3. **직접 호출**(direct address): NEXA 가 멘션/답장 등으로 직접 지목된 이벤트의 시각. 즉시 응답 기대가
   있는 opportunity.

여러 후보가 동시 구간에 있으면 **가장 늦은(최근) 시각**을 `t = 0` 으로 삼는다 — 가장 신선한 맥락이
타이밍 기준이다. opportunity 가 아닌 단순 관찰 이벤트(타인의 일반 메시지)는 원점을 옮기지 않고,
`t > 0` 구간의 사건/공변량으로만 들어간다.

## 시간 축·단위

- 모든 시각은 epoch milliseconds(`event_time_ms`, `Long`/`int64`)로 저장한다. 이는 P10 스키마
  [delay.py](../../../ml/social-policy/src/nexa_policy/data/labels/delay.py) 의 `delay_ms` 와 동일 단위다.
- **모델·metric 의 시간 축 `t` 는 초(seconds, float)** 다. `t = (event_time_ms - origin_ms) / 1000.0`.
  생존분석의 hazard 는 단위시간당 위험률이라 ms 스케일은 수치적으로 너무 작다 — 초로 통일한다(코드 상수
  `MS_PER_SECOND = 1000`).
- `t ≥ 0` 만 유효하다. origin 이전 사건은 공변량이지 생존 시간축의 사건이 아니다.

## right-censoring 정의

생존 시간 `T` 는 "opportunity 이후 대상 인간(또는 NEXA 결정 대상)이 **행동할 때까지의 시간**"이다.
관찰이 끝났는데도 행동이 없으면 그 표본은 **검열(censored)** 이다 — `T > c`(관찰 한계 `c` 이상)만 안다.
검열을 "행동 안 함(never)"으로 잘못 학습하면 모델이 과소 발화한다. 검열 사유는 셋이다(P10 delay 라벨과
일관 — [delay.py](../../../ml/social-policy/src/nexa_policy/data/labels/delay.py)):

1. **세션 종료**(session end): 관찰 창이 세션 경계로 잘림 — "최소 이만큼은 안 했다"일 뿐 진짜 never 아님.
2. **관찰 창 종료**(observation window end): 고정 관찰 창(`c` 초)이 끝났는데 행동 없음 — 우중도절단.
3. **동의 철회**(consent withdrawal): 표본 주체가 옵트아웃 → 그 시점 이후 관찰 불가, 검열 처리.

`observed_full_window = True` 로 끝까지 봤는데도 행동이 없을 때만 **진짜 never**(`is_never`)로 본다.
이 셋의 데이터 처리 구현은 P12-T002 [censoring.py](../../../ml/social-policy/src/nexa_policy/time/censoring.py)
가 담당한다.

## 세 소비자의 원점 정합 (acceptance)

- **학습(ml)**: 합성 fixture 의 각 표본은 `origin_ms` 와 `event_time_ms` 에서 `t` 를 위 공식으로 만든다.
  [origin.py](../../../ml/social-policy/src/nexa_policy/time/origin.py) 의 `to_relative_seconds` 단일 함수만 쓴다.
- **Kotlin runtime(participation)**: 결정 시점의 opportunity(burst finalize / scene update / 직접 호출)
  시각을 `t = 0` 으로 잡고, 모델 hazard 를 같은 초 단위 축에서 해석한다. participation 은 이 문서의 원점
  규칙을 따르되 **본 작업(P12)에서는 코드+테스트까지만**(운영 적용 없음, human gate).
- **평가(eval)**: survival metric(P12-T004
  [survival.py](../../../ml/social-policy/src/nexa_policy/eval/survival.py))은 같은 `t`·검열 플래그를 입력으로
  받는다 — 학습과 동일 원점·단위라야 C-index·integrated Brier 가 의미를 가진다.

세 소비자가 모두 `origin.py` 의 상수·변환 함수를 단일 출처로 참조하므로 원점 드리프트가 코드로 막힌다.
