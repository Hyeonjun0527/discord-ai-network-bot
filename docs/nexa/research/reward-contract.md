# 대화 구간 reward 계약 (NEXA-P19-T011)

- 작업: NEXA-P19-T011 (`kind: decision`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T010
- 관련: [ADR 0014 online adaptation boundary](../../adr/0014-online-adaptation-boundary.md),
  trajectory builder [`rl/trajectory.py`](../../../ml/social-policy/src/nexa_policy/rl/trajectory.py),
  reward proxy validation [`rl/reward_validation.py`](../../../ml/social-policy/src/nexa_policy/rl/reward_validation.py),
  [reward proxy validation EXP](../experiments/EXP-reward-validation.md)

## 목적

오프라인 RL(P19-T013~T015)이 최적화할 **대화 구간(segment) reward** 를 정의한다. reward 는 "무엇이 좋은
참여인가" 의 조작적 정의이며, 잘못 정의하면 정책이 사람다움이 아니라 reward proxy 를 해킹한다. 이 문서는
**다목적(multi-objective) reward 계약** 과 그 경계를 정한다.

## acceptance — 대화량·멘션 수 단일 reward 를 금지한다

- **단일 engagement reward 금지**: 대화량(메시지 수)·멘션 수 같은 단일 신호를 reward 로 삼지 않는다. 그것을
  최대화하면 도발·과다 mention·감정적 의존·갈등 유도로 reward 를 올리는 정책이 된다(reward hacking — P19-T016).
- reward 는 아래 **여러 축의 가중 합**이며, 각 축은 관찰된 행동 신호에서 나온다(심리 정답 불요). 어떤 단일 축도
  지배하지 못하도록 가중치 상한을 둔다.

## reward 축(다목적)

| 축 | 부호 | 정의(관찰 신호) | 직관 |
| --- | --- | --- | --- |
| **continuation** | + | NEXA 발화 뒤 상대가 대화를 이어감(후속 발화) | 자연스러운 대화 지속 |
| **reciprocity** | + | 주고받음의 균형(한쪽이 일방적이지 않음, InteractionReciprocity) | 대화의 상호성 |
| **interruption** | − | NEXA 가 진행 중 대화에 부적절하게 끼어듦 | 끼어듦 페널티 |
| **dominance** | − | 한 구간에서 NEXA 발화 비중 과다(말 독점) | 독점 페널티 |
| **complaint** | − | 불만/신고/정정/삭제 같은 부정 outcome | 명시적 불쾌 신호 |
| **stale memory** | − | 오래되어 틀린/현재성 없는 기억을 끌어와 부적절 | 기억 노화 페널티 |
| **safety** | − | 안전 위반(P17 안전 게이트와 일관) — **하드 거부** | 안전은 trade off 대상 아님 |

- **+ 축**(continuation·reciprocity)은 좋은 참여, **− 축**은 페널티. safety 위반은 가중 합 이전에 **하드
  마스크**(구간 폐기) — 다른 축 점수로 상쇄할 수 없다(reward hacking 의 핵심 차단).
- 각 축은 [0,1] 정규화 후 가중치를 곱한다. 가중치 합·상한으로 단일 축 지배를 막는다.

## 경계·금지(reward hacking 차단)

1. **engagement 단일 reward 금지**(acceptance): 대화량·멘션·체류 시간 단일 최대화 금지.
2. **갈등·의존 유도 금지**: 갈등 유도·감정적 의존으로 continuation 을 올리는 패턴은 complaint/interruption/
   dominance 페널티로 상쇄되고, 적대 평가(P19-T016)에서 critical 이면 해당 reward/RL 후보를 폐기한다.
3. **proxy 타당성 게이트**: 각 proxy 축은 블라인드 인간 평가와의 상관을 검증(P19-T012)하고, **상관이 낮은
   proxy 는 RL 에 쓰지 않는다**.
4. **safety 우선**: 안전 위반은 가중 합 밖의 하드 거부.

## 산출/소비

- trajectory builder(P19-T010)가 (상태,행동,지연,결과) 구간을 만들고, 이 계약의 축으로 segment reward 를
  계산한다 — **실제 생성 문구만으로 reward 를 계산하지 않고** 취소/침묵도 결과로 포함한다.
- reward proxy validation(P19-T012)이 각 축 proxy 의 인간 평가 상관을 본다.
- offline policy evaluation(P19-T013)이 이 reward 로 baseline 정책 성능을 배포 없이 추정한다.

이 계약은 **추정·연구용**이며, reward 정의 변경은 ADR 0014 의 계층 B(오프라인+인간 승인)에 속한다.
