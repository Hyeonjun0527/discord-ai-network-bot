# EXP — 보수적 offline RL PoC (NEXA-P19-T015)

- 작업: NEXA-P19-T015 (`kind: experiment`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T014
- 코드: [`rl/train_conservative.py`](../../../ml/social-policy/src/nexa_policy/rl/train_conservative.py)
- 테스트: [`test_train_conservative.py`](../../../ml/social-policy/tests/test_train_conservative.py)
- 상위: [trajectory dataset](./EXP-policy-baselines.md), [reward-contract](../research/reward-contract.md),
  [ADR 0014 online adaptation boundary](../../adr/0014-online-adaptation-boundary.md)

## 범위·금지

- **운영 데이터 미접근**: 합성 오프라인 배치(`make_synthetic_offline_batch`)·결정론. torch 미사용(numpy tabular).
- **production 통합 금지**: PoC 다. 메모리 안 tabular Q 만 갱신하고 registry/디스크/네트워크에 닿지 않는다.
- **학습/배포 금지**: 평가는 OPE·simulation·human review 뿐(acceptance). 정책을 운영에 적용하지 않는다.

## 문제 — OOD action 과대평가

오프라인 RL 의 핵심 위험은 **데이터 support 밖(OOD) action 을 과대평가**하는 것이다: behavior policy 가 거의
안 한 action 의 Q 가 부풀어, 위험하거나 사람답지 않은 정책이 나온다. CQL 정신의 보수적 penalty 로 OOD action 의
Q 를 끌어내려 데이터 안쪽 action 을 선호하게 한다.

## 방법

1. trajectory(T010)의 (state, action, reward)를 이산화한 `OfflineBatch`.
2. `train_conservative_q`: `target = mean_reward(s,a) - penalty/(1+support(s,a))`. support 가 낮을수록(OOD) penalty
   가 커져 Q 가 낮아진다. `conservative_penalty=0` 이면 비보수적(대비군).
3. 평가 셋(production 미적용):
   - `ope_value`: IPS offline policy evaluation(ope.py 재사용) — 점추정 + CI + support 진단.
   - `simulate_action_support`: 그리디 정책이 support 안쪽 action 만 고르는지(OOD 회피) 시뮬레이션.
   - `conservative_review_summary`: human review 요약(`production_applied=False` 명시).

## acceptance — production 통합 없이 OPE·simulation·human review 만 수행한다

- 학습은 메모리 tabular Q 뿐 — 레지스트리 ACTIVE 승격·배포 경로 없음.
- 보수적(penalty>0)이면 `in_support_fraction == 1.0` → 그리디가 **데이터에 없던 action 을 고르지 않는다**(OOD 회피).
  비보수적(penalty=0)은 OOD 를 선택할 수 있어 대비된다(테스트가 증명).
- OPE 는 CI·ESS 를 함께 보고해 support 부족을 숨기지 않는다(점추정만 보고 금지).

## 결과 해석

- 보수성으로 OOD 를 회피해도, 실 채택은 reward hacking 적대 평가(T016)·독립 리뷰(human gate, T023) 이후다.
- 합성 PoC 라 절대 수치는 운영 일반화를 보장하지 않는다 — 방법(보수적 OOD 억제)의 검증용이다.
