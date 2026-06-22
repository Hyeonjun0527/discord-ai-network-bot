# EXP — delay personalization (NEXA-P19-T006)

- 작업: NEXA-P19-T006 (`kind: experiment`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T005
- 코드: [`adaptation/delay.py`](../../../ml/social-policy/src/nexa_policy/adaptation/delay.py)
- 테스트: [`test_adaptation_delay.py`](../../../ml/social-policy/tests/test_adaptation_delay.py)
- 관련: time calibration [`calibration/time.py`](../../../ml/social-policy/src/nexa_policy/calibration/time.py)
- 경계: [ADR 0014 online adaptation boundary](../../adr/0014-online-adaptation-boundary.md)

## 범위·금지

- **타이밍만**: 길드 tempo·사용자 관찰 지연으로 "응답한다면 얼마나 빨리/느리게"의 time scale 만 조정한다.
  응답 여부(SPEAK 확률)는 건드리지 않는다.
- **강제 응답률 금지(acceptance T006)**: 직접 호출(멘션)에 대한 강제 응답률을 올리는 방식이 아니다.
  `DelayCalibration` 에 응답률·강제응답 필드가 없고 출력은 시간 배율(`combined_scale`)뿐이다.
- **bounded**: 배율은 `[DELAY_SCALE_MIN, DELAY_SCALE_MAX]` 로 clamp(폭주 금지).

## 방법

1. 관찰 통계(`DelayObservation`): 길드 중앙 간격/기준, 사용자 관찰 지연/기준(모두 양수 초).
2. `adjust_delay_scale` 가 guild_scale=guild_gap/reference, user_scale=user_delay/reference 를 구하고
   log 공간 가중 기하평균으로 결합 후 clamp 한다(`user_weight` 로 서버 vs 개인 비중 조절).
3. 결과 배율은 calibration/time.py 의 time temperature 와 같은 정신으로 hazard 시간 분포를 이동/평탄화하는 데
   쓰인다(모델 weight 학습 아님).

## acceptance — 직접 호출에 대한 강제 응답률을 올리는 방식이 아니다

- 출력이 시간 배율 한 개뿐이라 응답 확률을 바꿀 수 없다(구조적). 빠른 서버/사용자→scale<1, 느린→scale>1.
- 중립 입력(guild=reference, user=reference)에서 combined_scale=1.0(보정 없음).

## 결과 해석

- 개인화는 "더 자주 답하게" 가 아니라 "그 서버/사람의 리듬에 맞는 타이밍" 이다. engagement 조작 아님.
- 합성 추정이며 실 적용은 인간 승인 게이트 이후다.
