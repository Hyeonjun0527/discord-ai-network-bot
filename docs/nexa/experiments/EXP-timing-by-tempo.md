# EXP — 채널 tempo 조건부 timing 평가 (NEXA-P12-T015)

- 작업: NEXA-P12-T015 (`kind: experiment`, `human_gate: false`) · 상위:
  [participation-context](../architecture/participation-context.md)
- 코드: [`tempo_slices.py`](../../../ml/social-policy/src/nexa_policy/eval/tempo_slices.py)
- 검증 테스트: [`test_survival.py`](../../../ml/social-policy/tests/test_survival.py)
- 관련: multiplier fairness [EXP-hazard-multiplier](./EXP-hazard-multiplier.md)

## 범위·금지

- **운영 데이터 금지**: seed 결정론 합성 hazard·slice 라벨. 이 실험은 **slice별 집계와 "평균이 worst slice 를
  숨기는가" 판정 로직**을 검증한다 — 운영 timing 품질 보증이 아니다.
- slice 분류 규칙(tempo feature → quiet/normal/fast)은 호출자 책임이다. 이 모듈은 slice별 metric 집계·은닉
  판정만 한다(KISS).

## 방법

timing 오차를 채널 tempo slice(조용함/보통/빠름)별로 쪼개 본다. 평균 한 줄로 보면 빠른 채널의 나쁜 timing 이
다수의 느린 채널 성능에 가려질 수 있다([evaluate_by_tempo] 가 slice별 survival metric 을 따로 계산).

은닉 판정([TempoSliceReport.hides_fast_channel_error]): 전체 integrated Brier 가 합격선([overall_pass_brier])
이하인데 worst slice 의 Brier 가 전체보다 [hide_gap] 이상 더 나쁘면 True — "평균이 빠른 채널 오류를 숨긴다".

합성 시나리오(각 40 표본): quiet slice 는 모델이 사건 bin(0)을 정확히 예측, fast slice 는 사건이 늦은
bin(3)인데 모델이 이른 bin 예측(나쁜 timing).

## 측정 결과 (seed 결정론, quiet 40 + fast 40)

| slice | integrated Brier | delay accuracy |
| --- | ---: | ---: |
| quiet | 0.0008 | 1.00 |
| fast | 0.7071 | 0.00 |
| **overall(평균)** | 0.3540 | 0.50 |

`hides_fast_channel_error` = **True** (overall 합격선 1.0 안이지만 worst slice 가 gap 0.05 이상 나쁨).

## 해석 (acceptance: 평균 성능이 빠른 채널 오류를 숨기지 않는다)

- **평균은 worst slice 를 가린다**: overall Brier 0.354 만 보면 fast slice 의 0.707(완전 오답 수준)이 quiet 의
  0.0008 에 희석돼 안 보인다. delay accuracy 도 평균 0.50 이 fast 의 0.00 을 숨긴다.
- **slice별 보고가 이를 드러낸다**: per-slice metric 과 `hides_fast_channel_error` 플래그가 "fast 채널 timing 이
  실제로 나쁘다" 를 명시적으로 노출한다 — 평균이 빠른 채널 오류를 숨기지 못하게 한다(acceptance 충족).
- **운영 함의**: timing 모델 평가·게이트는 **slice별로** 봐야 한다. 특히 fast slice 의 Brier/delay 오차를
  별도 임계로 감시해야 빠른 대화에서의 끼어들기·지각 발화가 평균 뒤에 숨지 않는다. 이는 multiplier fairness
  분석([EXP-hazard-multiplier])이 "중간 tempo 가 민감" 이라 한 것과 함께 tempo 축 모니터링의 근거다.
- **결론**: tempo slice별 timing error 비교와 은닉 판정 로직이 구현·검증된다. 평균이 빠른 채널 오류를 숨기지
  않도록 slice별 metric 과 명시 플래그를 함께 보고한다(acceptance 충족).

## 미해결 질문

- 실제 tempo slice 경계(분당 메시지 임계)와 운영 fast slice Brier 허용 상한(운영 정책).
