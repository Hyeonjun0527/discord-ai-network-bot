# ADR 0014: 온라인 학습 허용 범위 — bounded calibration 만 실시간, 모델 weight/identity 는 오프라인 승인

- 상태(Status): 제안됨 (Proposed) — **인간 승인 게이트 대기** (NEXA-P19-T009, `human_gate: true`, `risk: high`)
- 날짜(Date): 2026-06-22
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0007 NEXA social member context](./0007-nexa-social-member-context.md),
  [ADR 0013 정책 서빙 경계](./0013-policy-serving-boundary.md)
- 코드 경계:
  관계 온라인 update [`RelationshipOnlineUpdate.kt`](../../central-server/src/main/kotlin/com/discordassistant/central/socialmemory/domain/service/relationship/RelationshipOnlineUpdate.kt),
  talkativeness 적응 [`adaptation/talkativeness.py`](../../ml/social-policy/src/nexa_policy/adaptation/talkativeness.py),
  delay 적응 [`adaptation/delay.py`](../../ml/social-policy/src/nexa_policy/adaptation/delay.py),
  action mix 적응 [`adaptation/action_mix.py`](../../ml/social-policy/src/nexa_policy/adaptation/action_mix.py)
- 상위: [cohort-design](../nexa/longitudinal/cohort-design.md), [reward-contract](../nexa/research/reward-contract.md)

## 맥락 (Context)

P19 에서 NEXA 는 서버 문화·사용자에 **적응**한다(talkativeness·delay·action mix·관계 상태). "production 에서
실시간으로 무엇을 어디까지 갱신해도 되는가" 라는 경계 질문이 생긴다. 잘못 그으면 (a) production feedback 으로
모델 전체가 자동 fine-tune 되어 통제 불능 drift·reward hacking 에 노출되거나, (b) 한 번의 반응이 관계/정체성을
뒤집어 사람다움이 무너진다. 이 ADR 은 그 경계를 **두 계층**으로 정한다.

### 두 계층 구분

| 축 | A. 실시간 가능 — bounded calibration | B. 오프라인 승인 필요 — 모델 weight / identity |
| --- | --- | --- |
| **대상** | talkativeness multiplier 주변 보정, delay time scale, action mix shift, 관계 상태(rapport) EMA | 정책 모델 weight(behavior cloning/RL), 정체성 kernel(가치·말투·금지), reward 함수 정의 |
| **변경 폭** | bounded·범위 clamp·step cap(단조·작게) | 모델 재학습 = 큰 변화(분포 이동 가능) |
| **상태성** | 운영자 설정/관찰 통계 위의 얇은 보정 레이어 | 학습된 파라미터 자체 |
| **rollback** | 즉시(baseline 복귀) | model registry(V62) 버전 전환·shadow 재평가 |
| **승인** | 운영자 설정 범위 안에서 자동(설명·감사·rollback 가능) | **명시적 인간 승인 게이트**(P19-T020 approval UI) |

## 결정 (Decision)

**실시간 온라인 업데이트는 bounded calibration 으로만 허용한다(계층 A). 모델 weight·정체성·reward 정의의 변경은
오프라인 + 명시적 인간 승인을 거친다(계층 B). production feedback 으로 모델 전체를 자동 fine-tune 하는 경로는
기본 금지한다.**

### 계층 A — 실시간 가능(bounded calibration)

다음만 실시간으로 갱신할 수 있고, 모두 **bounded·설명 가능·rollback 가능**이어야 한다:

1. **talkativeness 보정**: 운영자 multiplier 주변에서 [lower, upper] clamp·`max_step` 한도 안에서만
   (`adaptation/talkativeness.py`). 범위 초과·폭주 불가, baseline 으로 rollback.
2. **delay 타이밍 보정**: 응답 여부(SPEAK 확률)는 불변, 시간 배율만 `[0.5, 2.0]` clamp(`adaptation/delay.py`).
   직접 호출 강제 응답률을 올리지 않는다.
3. **action mix shift**: SPEAK↔REACT 질량을 `MAX_SHIFT_FRACTION` 안에서만 옮기고 IGNORE 불변
   (`adaptation/action_mix.py`). FIR/MIR 동반 확인.
4. **관계 상태 online update**: 새 outcome 당 변화량을 `maxStep` 으로 cap, 최소 표본 gating
   (`RelationshipOnlineUpdate.kt`). 한 번의 반응이 장기 관계를 뒤집지 못한다.

공통 불변식: 변경 폭 상한(step cap·clamp), 적은 표본 보호(min sample), 즉시 rollback, 모든 자동 변경의 설명·감사.

### 계층 B — 오프라인 승인 필요(모델 weight / identity)

다음은 **production feedback 으로 자동 변경하지 않는다**. 오프라인 dataset 승인→train→eval→model card→
signature→shadow 등록(P19-T019 파이프라인)을 거치고, 평가 실패 모델은 ACTIVE 로 승격되지 않으며, 최종 승격은
인간 승인 UI(P19-T020, 이중 확인·audit·rollback target)를 통과해야 한다:

1. 정책 모델 weight(behavior cloning / offline RL 결과).
2. 정체성 kernel(가치·취향·말투·금지사항) — 사람 승인 없이 자동 변형 금지.
3. reward 함수 정의(reward-contract.md) — reward hacking 경계(P19-T016) 위반 시 후보 폐기.

### 비-목표 / 금지

- **production feedback 자동 fine-tune 금지(acceptance T009)**: 사용량·멘션·대화량 신호로 모델 weight 를
  실시간 갱신하는 경로를 두지 않는다. 그런 루프는 engagement 조작·reward hacking 으로 직결된다.
- **engagement 극대화 목표 금지**: 어떤 계층 A 보정도 "더 자주/길게 붙잡기" 를 목표로 하지 않는다(ADR 0007 정신).
- **기존 central 무변경**: 이 P19 작업군은 신규 순수 도메인 코드·ml 모듈·문서만 추가한다(기존 마이그레이션·경로 무변경).

## 결과 (Consequences)

**장점**: 실시간 적응의 이점(서버/사람 리듬에 맞춤)을 얻으면서, 통제 불능 drift·정체성 붕괴·reward hacking 을
구조적으로 차단한다. 모든 실시간 변경이 bounded·설명·rollback 가능하다.
**단점**: 모델 수준 개선은 느리다(오프라인 승인 주기). 그러나 안전·일관성이 속도보다 우선이다(P19 종료 게이트).

## 인간 승인 상태 (Approval)

- `NEXA-P19-T009`, `human_gate: true`, `risk` 높음.
- acceptance("production feedback 로 모델 전체를 자동 fine-tune 하는 경로를 기본 금지한다") 충족 — 계층 B 가
  오프라인+인간 승인을 강제하고, 자동 fine-tune 경로를 비-목표로 명시.

## 미해결 질문

- 계층 A 보정 누적이 장기적으로 계층 B 분포를 흉내내며 우회하지 않는지의 모니터링(P19-T017 drift 탐지로 추적).
- 관계 online update 의 step cap·min sample 기본값의 실 코호트 튜닝(현재 합성 fixture 기준 보수값).
