# EXP — talkativeness multiplier offline simulation (NEXA-P09-T020)

- 작업: NEXA-P09-T020 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 스크립트: [`scripts/simulate-talkativeness.py`](../../../scripts/simulate-talkativeness.py)
- 도메인 규칙 SSOT: central [`TalkativenessMultiplier.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/config/TalkativenessMultiplier.kt),
  [`PolicyCalibration.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/service/PolicyCalibration.kt)

## 범위·금지

- **실제 발화 없음**: 운영 DB·게이트웨이·Discord 에 연결하지 않는다. multiplier 가 SPEAK 확률을 어떻게 바꾸는지
  central 의 보정 규칙으로 **오프라인 재생**만 한다(추정이지 운영 품질 보증 아님).
- **메시지 개수 곱 금지**: multiplier 는 "최근 메시지 수 × multiplier" 같은 카운트 곱이 **아니다**. SPEAK 의
  logit(로그-오즈)에 `ln(multiplier)` 를 **가산** 보정한 뒤 softmax 재정규화한다(T017 경계, `applyToSpeakLogit`).

## 방법

각 기본 SPEAK 확률(base)에 대해 multiplier 0.5/1.0/1.5/2.0 을 적용한 SPEAK 확률을 계산한다:

```
speak_logit' = logit(base_speak) + ln(multiplier)        # 0 이면 -10 으로 클램프(강한 침묵 편향)
adjusted_speak = softmax({speak_logit', rest_logit})[SPEAK]
```

SPEAK 만 보정하므로 SPEAK vs 나머지의 2항 오즈로 압축해도 SPEAK 확률은 다항 softmax 와 동일하다. 결정론(랜덤 없음 —
같은 입력=같은 표).

실행:

```bash
python3 scripts/simulate-talkativeness.py            # 표 출력
python3 scripts/simulate-talkativeness.py --json     # 기계 판독(JSON)
```

## 측정 결과 (multiplier 0.5/1.0/1.5/2.0, logit 가산 보정)

| base SPEAK | x0.5 | x1.0 | x1.5 | x2.0 | abs gain @2x |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0.02 | 0.010 | 0.020 | 0.030 | 0.039 | 0.019 |
| 0.05 | 0.026 | 0.050 | 0.073 | 0.095 | 0.045 |
| 0.10 | 0.053 | 0.100 | 0.143 | 0.182 | 0.082 |
| 0.25 | 0.143 | 0.250 | 0.333 | 0.400 | 0.150 |
| 0.50 | 0.333 | 0.500 | 0.600 | 0.667 | 0.167 |
| 0.75 | 0.600 | 0.750 | 0.818 | 0.857 | 0.107 |
| 0.90 | 0.818 | 0.900 | 0.931 | 0.947 | 0.047 |

- `x1.0` 열은 보정 없음(base 그대로) — sanity 체크.
- `abs gain @2x` = (2x 적용 확률 − base) 절대 증가량.

## 해석 (acceptance: saturation curve — 낮은 기본 확률을 무리하게 높이지 않는가)

- **양 끝 saturation**: base 가 매우 낮을 때(0.02)는 2x 배율조차 절대 확률을 +0.019 만 올린다. base 가 매우 높을 때
  (0.90)도 +0.047 로 작다. **logit 가산은 오즈 배율** 이라 확률 0/1 근처에서 절대 변화가 눌린다 — 즉 **낮은 기본
  확률을 무리하게 높은 확률로 끌어올리지 않는다**(saturation). multiplier 는 "조심스러운 정책을 갑자기 수다스럽게"
  만들지 못한다.
- **중간에서 최대 민감도**: abs gain 은 base=0.5 에서 +0.167 로 최대다. multiplier 는 **이미 발화/침묵이 팽팽한
  장면** 에서 가장 크게 작동하고, 한쪽으로 확신이 선 장면에선 거의 움직이지 않는다 — 운영자가 원하는 "미세 조정"
  성질이다.
- **대칭 감쇠**: 0.5 배율은 같은 logit 폭만큼 SPEAK 를 낮춘다(0.5 base → 0.333). 키우는 쪽과 줄이는 쪽이 오즈 공간에서
  대칭이라 예측 가능하다.
- **결론**: talkativeness 를 logit 가산으로 두면 발화 빈도를 **부드럽게** 조절하되 극단 확률을 강제로 뒤집지 않는다.
  이 saturation 이 "multiplier 로 침묵 정책을 무리하게 발화시키지 않는다"는 안전 성질을 수치로 보인다. 기본값 후보
  1.5 의 최종 채택은 여전히 human gate(T017) 다.
