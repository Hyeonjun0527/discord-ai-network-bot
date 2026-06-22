# EXP — 시간 feature 미래 누출 감사 (NEXA-P12-T016)

- 작업: NEXA-P12-T016 (`kind: security`, `risk: high`, `human_gate: true`) · 상위:
  [participation-context](../architecture/participation-context.md)
- 시간 원점 계약: [time-origin.md](../policy/time-origin.md)
- 코드: [`leakage.py`](../../../ml/social-policy/src/nexa_policy/data/leakage.py)
- 검증 테스트: [`test_survival.py`](../../../ml/social-policy/tests/test_survival.py),
  [`test_leakage.py`](../../../ml/social-policy/tests/test_leakage.py)

## 범위·금지

- **운영 데이터 금지**: 합성 row(feature cutoff·계산 시각)로 누출 탐지 로직을 검증한다. 이 감사는 학습
  tensor 의 **시간 인과성**(예측 시점 feature 가 미래 정보를 쓰지 않음)을 fail-closed 로 강제한다.
- P09-T023(누출 자동 검사)·P10(group/temporal leakage)와 일관 — 같은 [LeakageReport]/[assert_no_leakage]
  파이프라인에 시간 feature cutoff 검사를 추가한다(중복 구현 금지).

## 방법

reply 가 온 **뒤** 계산되는 feature 가 예측 시점 입력에 들어가면 미래 누출이다. 대표 위험:

- **tempo**: reply 도착 후의 메시지 간격으로 계산하면, 예측 시점엔 아직 알 수 없는 미래 속도를 본다.
- **finalize reason**: 행동이 어떻게 종료됐는지(취소/발사)는 결정 **이후** 정보다 — feature 로 쓰면 정답 누설.

[check_feature_cutoff_leakage]: 각 tensor row 마다 **feature cutoff timestamp**(결정이 내려진 시각)를 얻고,
각 feature 의 계산 시각([FeatureTimestamp.computed_at_of])이 cutoff **이상(>=)** 이면 위반으로 보고한다 —
acceptance(T016): "각 tensor row 에 feature cutoff timestamp 가 검증된다". 위반은 fail-closed
([assert_no_leakage] 가 [LeakageError] 예외)로 CI 를 실패시킨다.

## 측정 결과 (합성 row, 결정론)

| row | cutoff(ms) | tempo 계산(ms) | relationship 계산(ms) | 판정 |
| ---: | ---: | ---: | ---: | --- |
| 0 | 1000 | 1500 (cutoff 이후) | 800 (cutoff 이전) | **위반(tempo 누출)** |
| 1 | 2000 | 1800 (이전) | 1900 (이전) | 통과 |

- row 0 의 `tempo` 는 cutoff 이후 계산 → `feature_cutoff` 위반으로 탐지, `assert_no_leakage` 가 예외.
- row 0 의 `relationship`(cutoff 전)·row 1 의 모든 feature 는 위반 아님(정상 인과).

## 해석 (acceptance: 각 tensor row 에 feature cutoff timestamp 가 검증된다)

- **row 단위 시간 인과성 강제**: 각 row 의 feature cutoff 를 기준으로, 그 이후 계산된 feature(reply 파생 tempo·
  finalize reason)를 미래 누출로 잡는다. 정상 feature(cutoff 전 relationship)는 통과한다 — 정상 상관을 죽이지
  않고 **시간 위반만** 탐지하는 보수적 규칙이다.
- **fail-closed**: 위반이 있으면 [assert_no_leakage] 가 예외를 던져 CI 가 실패한다(조용한 통과 금지) — 의도적
  leakage fixture 가 테스트에서 실패함을 [test_survival.py] T016 이 증명한다(P09-T023 정신과 일관).
- **기존 누출 검사와 결합**: group(같은 키 split 중복)·temporal(train 이 holdout cutoff 이상)·label_feature
  (feature 가 label 1:1 복사) 위에 feature_cutoff 를 더해, 시간축 누출까지 같은 파이프라인이 막는다.
- **결론**: 시간 feature 가 미래 정보(reply 후 tempo·finalize reason)를 쓰지 않음을 row별 cutoff timestamp 로
  검증하고 위반은 fail-closed 로 막는다(acceptance 충족).

## 미해결 질문

- 운영 feature 추출 파이프라인의 각 feature 실제 계산 시각 계측(이 감사가 받는 입력의 정확성은 추출기 책임).
