# EXP — talkativeness multiplier saturation·fairness (NEXA-P12-T010)

- 작업: NEXA-P12-T010 (`kind: experiment`, `risk: high`, `human_gate: true`) · 상위:
  [participation-context](../architecture/participation-context.md)
- 시간 원점 계약: [time-origin.md](../policy/time-origin.md), hazard scaling 코드
  [`talkativeness.py`](../../../ml/social-policy/src/nexa_policy/inference/talkativeness.py)
- 분석 코드: [`multiplier_analysis.py`](../../../ml/social-policy/src/nexa_policy/inference/multiplier_analysis.py)
- 검증 테스트: [`test_survival.py`](../../../ml/social-policy/tests/test_survival.py)

## 범위·금지

- **운영 데이터 금지**: seed 결정론 합성 hazard 시나리오만(tempo×관계). 이 실험은 multiplier 의 **수학적
  포화 거동**과 빠른 대화 worst-case 를 정량 보고한다 — 운영 발화율 보증이 아니다.
- multiplier 는 메시지 수 곱이 아니라 **hazard logit 가산**(`ln(multiplier)`)이다(P08-T017 경계). 이 실험은
  그 경계를 바꾸지 않고 그 거동만 분석한다. central·기존 마이그레이션 무변경.

## 방법

서버별 talkativeness multiplier 는 speak/react hazard 의 logit(log-odds)에 `ln(multiplier)` 를 가산한다
([scale_hazard]). 따라서 **오즈 배율**로 작용하고, hazard 는 `[0, 0.999]` 로 cap 된다. 관찰 창 내 "최소 1회
발화" 누적확률은 `P = 1 - prod_k (1 - h_k)` 다([per_window_speak_probability]).

tempo×관계 3 시나리오(seed 결정론 합성 base hazard, 4 bin):

| 시나리오 | base hazard(bin별) | 해석 |
| --- | --- | --- |
| quiet/stranger | 0.02·0.03·0.04·0.05 | 조용한 채널·먼 관계 — 거의 발화 안 함 |
| normal/acquaintance | 0.10·0.12·0.14·0.16 | 보통 tempo·중간 관계 |
| fast/close | 0.45·0.50·0.55·0.60 | 빠른 대화·가까운 관계 — 이미 자주 발화(near-saturated) |

multiplier 0.5/1.0/1.5/2.0 를 적용해 base 대비 발화확률 증가폭과 cap 포화율을 측정한다.

## 측정 결과 (seed 결정론, 합성 4 bin)

multiplier = 1.5(질문의 핵심):

| 시나리오 | base 발화확률 | 1.5x 발화확률 | 증가폭 Δ | cap 포화 bin |
| --- | ---: | ---: | ---: | ---: |
| quiet/stranger | 0.1331 | 0.1911 | +0.0581 | 0% |
| normal/acquaintance | 0.4279 | 0.5552 | **+0.1273** | 0% |
| fast/close | 0.9505 | 0.9805 | +0.0300 | 0% |

- **worst-case(절대 발화율 최대)**: `fast/close`, 1.5x 에서 0.9805 (base 0.9505 대비 **+3.0pp**).
- **최대 증가폭(Δ 최대)**: `normal/acquaintance`, **+0.127** — fast/close(+0.030)보다 4배 이상 크다.

2.0x 까지 sweep 해도 fast/close 의 발화확률 증가폭은 +0.040 에 그치는 반면 normal 은 +0.221 이다.

## 해석 (acceptance: 빠른 대화에서 1.5x 가 과도한 끼어들기로 변하지 않는지 worst-case 보고)

- **빠른 대화는 multiplier 에 가장 둔감하다(과도 끼어들기 아님)**: base hazard 가 이미 높은 fast/close 는
  logit 공간에서 오즈 가산이 누적 발화확률을 거의 못 올린다(0.95 → 0.98, +3pp). 즉 **1.5x 가 빠른 대화에서
  발화율을 폭주시키지 않는다** — 사람이 이미 자주 끼어드는 맥락이라 추가 여지가 작기 때문이다. 이는 odds
  가산의 **포화(diminishing returns)** 가 구조적으로 끼어들기 상한을 눌러 주는 결과다.
- **민감한 곳은 중간 tempo·중간 관계**: 발화확률이 0.5 부근(logit≈0)일 때 오즈 가산 효과가 최대라
  normal/acquaintance 의 Δ 가 가장 크다(+0.127). multiplier 정책의 **공정성(fairness) 위험은 빠른 채널이
  아니라 중간 채널의 발화 빈도 변화**에 있다 — 운영 시 이 slice 를 모니터링해야 한다.
- **하드 cap 의 역할**: hazard 가 0.999 로 cap 되어 어떤 multiplier 에서도 사건이 확정(1.0)이 되지 않는다.
  본 시나리오에선 cap 에 닿는 bin 이 0% 였으나, base 가 더 높아져도 cap 이 폭주를 막는다(과도 끼어들기 방지).
- **결론(worst-case)**: 1.5x 의 빠른 대화 worst-case 누적 발화확률은 0.98(base 대비 +3pp)로, multiplier 가
  빠른 대화에서 과도한 끼어들기로 변하지 않는다(acceptance 충족). 운영 fairness 관찰 우선순위는 fast 가 아닌
  **중간 tempo slice** 다 — 이 결정은 T015(tempo slice 평가)·운영 모니터링으로 이어진다.

## 미해결 질문

- 실제 운영 base hazard 분포가 본 합성 시나리오와 얼마나 일치하는지(운영 shadow 로 후속 확인 — 이 PoC 는
  거동 분석만).
- 중간 tempo slice 의 발화 빈도 변화 허용 상한(운영 정책 — fairness 가드 임계).
