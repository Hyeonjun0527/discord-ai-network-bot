# EXP — reward proxy validation (NEXA-P19-T012)

- 작업: NEXA-P19-T012 (`kind: experiment`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T011
- 코드: [`rl/reward_validation.py`](../../../ml/social-policy/src/nexa_policy/rl/reward_validation.py)
- 테스트: [`test_reward_validation.py`](../../../ml/social-policy/tests/test_reward_validation.py)
- 상위: [reward-contract](../research/reward-contract.md), [ADR 0014](../../adr/0014-online-adaptation-boundary.md)

## 범위·금지

- **운영 데이터 미접근**: 합성 fixture·결정론. 인간 평가는 블라인드 합성 점수로 모사.
- **블라인드 인간 평가**: 평가자는 정책 정체를 모른다. 사용자 심리를 정답으로 강요하지 않는다(체감 입력일 뿐).
- **torch 미사용**: numpy 상관/불일치 계산.

## 방법

1. reward-contract.md 의 각 proxy 축(continuation·reciprocity·... )에 대해 segment 별 proxy 점수와
   블라인드 인간 점수를 모은다.
2. `validate_proxy` 가 Spearman·Pearson 상관과 disagreement_rate(중앙값 기준 부호 불일치)를 계산한다.
3. `usable = (Spearman ≥ min_correlation) AND (disagreement ≤ max_disagreement)`.

## acceptance — 상관이 낮은 proxy 는 RL 에 사용하지 않는다

- 인간 평가와 상관이 높은 proxy 만 `usable=True` → RL(P19-T013~T015)의 reward 에 포함.
- 상관이 낮거나(`Spearman < min_correlation`) 불일치가 큰 proxy 는 `usable=False` → 폐기. reward hacking 의
  대표 경로(인간이 싫어하는데 proxy 만 오르는 축)를 사전 차단한다.

## 결과 해석

- 높은 상관 = proxy 가 "사람이 느끼는 좋음" 을 대리한다는 증거(추정). 낮은 상관 = 해킹 위험 → 사용 금지.
- 이 검증은 합성 추정이며, 실 proxy 채택은 인간 승인 게이트(reward 정의는 ADR 0014 계층 B) 이후다.
