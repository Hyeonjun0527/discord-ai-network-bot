# Masked Member 샘플 단위 (NEXA-P10-T004)

- 작업: NEXA-P10-T004 (`kind: documentation`, `human_gate: true`, 개인정보) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork·socialmemory 경계](../../adr/0010-ainetwork-socialmemory-boundary.md)
- 근거: [event-sequence.md](event-sequence.md),
  [data-categories.md](../../../specs/product-v2/nexa/data-categories.md),
  [observable-state-policy.md](../social-state/observable-state-policy.md)
- 코드: [`ml/social-policy/src/nexa_policy/data/masking.py`](../../../ml/social-policy/src/nexa_policy/data/masking.py)

## 목적

participation 정책 학습의 **샘플 단위**를 정의한다. 특정 인간(actor)의 **다음 행동을 가리고**, 그 시점
이전 이벤트만 입력으로 제공하는 학습 예제다. NEXA 는 "이 장면에서 사람이라면 무엇을 할까"를 그 사람의
미래를 모르는 채로 예측하도록 학습한다.

## 샘플 정의

- **masked_actor**: 다음 행동을 가린 대상 인간(guild-scope 가명).
- **cut_time_ms**: 기준 시점. 이 시각까지를 입력으로 보고, 이후 행동이 라벨이 된다.
- **prior_events**: cut 이전 이벤트만(입력 컨텍스트). cut 이후(미래) 이벤트는 **절대 포함하지 않는다**
  (코드가 생성/생성자에서 강제 — `MaskedMemberExample.__post_init__`).
- **target**: 정답.

## 정답은 문장 하나가 아니라 분포다 (acceptance)

정답(`LabelTargets`)은 단일 텍스트가 아니라 **여러 라벨의 묶음(분포)** 이다:

| 라벨 | 의미 | 생성기 |
| --- | --- | --- |
| action | IGNORE/WAIT/REACT/SPEAK (불확실하면 UNKNOWN mask) | [action.py](../../../ml/social-policy/src/nexa_policy/data/labels/action.py) (T005) |
| target | message/member/thread target(복수·none) | [target.py](../../../ml/social-policy/src/nexa_policy/data/labels/target.py) (T006) |
| delay | 행동까지 지연 + right-censoring | [delay.py](../../../ml/social-policy/src/nexa_policy/data/labels/delay.py) (T007) |
| burst | 응답 burst shape(수·길이·간격·reaction) | [burst.py](../../../ml/social-policy/src/nexa_policy/data/labels/burst.py) (T008) |
| social_act | 약지도 social act(confidence·model version) | [social_act.py](../../../ml/social-policy/src/nexa_policy/data/labels/social_act.py) (T009) |

즉 모델은 "무슨 문장을 말할지"가 아니라 **"행동할지/누구에게/언제/어떤 모양으로/어떤 사회적 행위로"** 의
분포를 맞춘다. 문장 생성은 participation 의 책임이 아니다(speech 가 만든다 — ADR 0007).

## 개인정보 (human_gate 게이트)

- 원문(raw content) 미포함 — 신호·카운트·시각만([event-sequence.md](event-sequence.md) 의
  `contains_raw_content: false`).
- 실제 user id/snowflake 미포함 — guild-scope 가명(`masked_actor` 등, ADR 0010 cross-guild 식별 금지).
- 학습은 옵트인 전용 — export 보안 경계가 `training_eligible`·`consent_opt_in` 행만 통과
  ([data-categories.md](../../../specs/product-v2/nexa/data-categories.md) 의 training artifact = 옵트인).
- 관찰 불확실 샘플은 강제로 한 클래스에 넣지 않고 UNKNOWN mask 를 둔다(T005) — 침묵 ≠ 관찰됨.

## acceptance 충족

- 마스킹 단위(masked_actor·cut·prior_events·target)와 미래 leakage 금지 불변식을 정의·코드로 강제한다.
- 정답이 문장 하나가 아니라 action/target/time/burst 분포임을 위 표로 명시한다.
