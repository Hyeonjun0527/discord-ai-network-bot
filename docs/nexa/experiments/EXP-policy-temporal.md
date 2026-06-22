# EXP — Temporal encoder baseline 설계 (NEXA-P11-T007)

- 작업: NEXA-P11-T007 (`kind: experiment`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 코드: [`ml/social-policy/src/nexa_policy/models/temporal_encoder.py`](../../../ml/social-policy/src/nexa_policy/models/temporal_encoder.py)

## 범위·금지

- 최근 burst sequence·time gap 을 인코딩하는 **작은 encoder 후보 비교**다(GRU vs mean-pool). numpy forward
  결정론 — 무거운 학습 없음. 합성 시퀀스 fixture.

## 방법

- 후보 1: 소형 단일층 **GRU**(순서·gap 의존). 후보 2: **mean-pool**(dense 사상 후 평균, 순서 무시·경량).
- **시간 구조 민감도**: 같은 원소·다른 순서(시퀀스 뒤집기)에서 인코딩이 얼마나 변하는지로 측정한다.
  GRU 는 순서 의존이라 변하고, mean-pool 은 평균이라 불변(≈0).
- **truncation 영향**: 시퀀스를 최근 `keep_last` 개로 자를 때 인코딩 상대 변화 → 앞쪽 정보 손실 대리지표.

## 측정 결과 (합성 시퀀스, seed 결정론)

| 후보 | parameter 수 | 순서/gap 민감도 |
| --- | ---: | ---: |
| GRU | 288 | 0.221 |
| mean-pool | 32 | 0.000 |

| truncation(GRU) | 상대 변화 |
| --- | ---: |
| keep_last=8 (자르지 않음) | 0.000 |
| keep_last=2 | 0.380 |

## 해석 (acceptance: 모델 선택 근거·sequence truncation 영향 보고)

- **선택 근거**: GRU 가 순서/gap 에 민감(0.221 vs 0.0)해 burst 순서·간격 같은 시간 구조를 포착한다 →
  temporal encoder 후보로 GRU 권장. 단 파라미터(288 vs 32)·latency 가 커서 시퀀스가 매우 짧으면
  mean-pool 도 실용적이다(`EncoderComparison.rationale` 가 이 판단을 담는다).
- **truncation 영향**: 시퀀스를 최근 2개로 자르면 인코딩이 0.38 변한다 → 앞쪽 burst 정보 손실이 작지 않다.
  운영에서 sequence 길이를 줄일 때 이 손실을 감안해야 한다(짧은 truncation 은 위험).
