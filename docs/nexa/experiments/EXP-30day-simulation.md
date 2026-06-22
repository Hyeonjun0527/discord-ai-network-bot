# EXP — 30일 가상 장기 시뮬레이션 (NEXA-P16-T022)

- 작업: NEXA-P16-T022 (`kind: experiment`, `human_gate: false`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 스크립트: [`scripts/simulate-30day-member.py`](../../../scripts/simulate-30day-member.py)
- 짧은 단위 시나리오: [scenario-dsl.md](../evals/scenario-dsl.md), [`test-fixtures/nexa/scenarios/`](../../../test-fixtures/nexa/scenarios)

## 범위·금지

- **운영 데이터 미접근·전송 0(shadow)·배포 금지**. 합성 이벤트를 결정론 LCG·virtual Clock 으로 생성·재생한다
  (`determinism.md` 규칙). central 운영 코드를 호출하지 않는다(기존 central 무변경).
- 30일치를 실시간으로 도는 게 아니라 **가상 시간**을 한 번에 재생한다. 측정이 아니라 누적 행동의 **관찰**이 목적이다.

## 동기

단발 시나리오(mention spam·이미 답함·stale cancel 등)는 한 순간의 올바른 행동만 본다. 사람다움의 실패는
대개 **누적**으로 드러난다 — 같은 문구를 반복(AI 말투), 기억이 오래돼 stale, 시간이 지날수록 채널을 점유,
관계가 식었는데도 친한 척. 이 실험은 30일 가상 시간에서 그 drift 를 본다.

## 방법

매 가상 일(day)마다:

1. **채널 tempo drift**: 하루 메시지 수가 초반(활발)→후반(조용)으로 선형 감소한다.
2. **기억 감쇠**: 사람이 새 fact 를 말하면 마지막 갱신 시각을 기록한다. 30일 끝에 `MEMORY_VALIDITY_MS`(14일)
   보다 오래된 fact 는 **stale** 로 센다(말할 때 단정 금지 대상).
3. **관계 변화**: 관계 점수는 매일 감쇠(상호작용 없으면 식음) 후 그날 상호작용으로 회복한다.
4. **nickname drift**: 드물게 닉네임이 바뀌어 history 에 쌓인다.
5. **점유율(dominance) 가드**: NEXA 발화 누적 점유율이 `DOMINANCE_CAP`(0.2)에 닿으면 발화 대신 가벼운 REACT
   로 빠진다(혼자 채널을 점유하지 않음).
6. **반복 문구**: 모델 발화 표면형을 작은 pool 에서 골라, 같은 문구가 `REPEAT_PHRASE_THRESHOLD`(3) 이상이면
   "AI 말투 반복" 약점으로 flag 한다.

실행:

```bash
python3 scripts/simulate-30day-member.py            # 기본(days=30, seed=30001)
python3 scripts/simulate-30day-member.py --json     # 기계 판독(JSON)
python3 scripts/simulate-30day-member.py --days 60 --seed 42
```

## 결과 (기본: days=30, seed=30001)

| 지표 | 값 | 해석 |
| --- | --- | --- |
| 총 메시지 | 900 | tempo drift 반영(초반 활발→후반 조용) |
| NEXA 발화 / 반응 | speak=72 / react=0 | 호명 8% 중 점유율 여유에서만 발화 |
| 점유율(dominance) | 0.080 | cap 0.2 미만 — 혼자 점유 안 함(drift_flag=False) |
| 상태 크기 | 207 bytes (fact=7) | 무한 성장 없음(fact 키가 갱신돼 상한) |
| stale fact | 0 | 14일 내 갱신 — 오래된 단정 위험 없음 |
| nickname 변경 | 3 | drift 추적됨 |
| 관계 점수 | 9.14 | 꾸준한 상호작용으로 유지 |
| 반복 문구 top | 그러게×20, 좋네×20, ㅋㅋ×18 … | **flag=True — AI 말투 반복 약점 노출** |

## 관찰·약점

- **점유율은 안전**: 누적 dominance 0.08 < cap 0.2. 장기에서도 채널을 점유하지 않는다.
- **기억은 신선**: stale fact 0. 갱신 경로가 동작해 오래된 사실을 단정할 위험이 낮다(`--days 60` 등으로 갱신
  없는 fact 를 늘리면 stale 이 양수로 올라가 감쇠 보고가 작동함을 확인할 수 있다).
- **반복 문구가 약점**: 작은 표면형 pool 에서 같은 문구가 20회까지 누적된다 — 이는 human-likeness gate 의
  알려진 약점(AI 말투·담백함)을 장기 축에서 재현한다. T024 적대 리포트가 이 flag 를 집계한다.

## 한계

합성 가정(호명률 8%·tempo 선형 감소·표면형 pool)은 운영 분포가 아니다. 절대 수치가 아니라 **drift 의 방향과
가드 작동 여부**를 본다. 운영 전환 시 실제 분포로 보정한다(전송·배포는 이 작업 범위 밖).
