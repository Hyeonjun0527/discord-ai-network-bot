# Human annotation 지침 (NEXA-P10-T020)

- 작업: NEXA-P10-T020 (`kind: documentation`, `human_gate: true`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [observable-state-policy.md](../social-state/observable-state-policy.md),
  [data-categories.md](../../../specs/product-v2/nexa/data-categories.md)
- 도구(SSOT): [`ml/social-policy/src/nexa_policy/annotation/packet.py`](../../../ml/social-policy/src/nexa_policy/annotation/packet.py)
- 합의 계산: [`ml/social-policy/src/nexa_policy/eval/agreement.py`](../../../ml/social-policy/src/nexa_policy/eval/agreement.py)

## 목적

마스킹된 멤버 예제(cut 시점 직전 컨텍스트)를 보고, **그 사람이 다음에 무엇을 했어야 하는지**가 아니라
**관찰된 행동을 어떻게 분류할지**를 표시한다. annotator 는 가명화·최소화된 context packet 만 본다 —
실제 사용자 식별자나 전체 서버 로그를 보지 않는다(P10-T021).

## annotator 가 보는 것

- packet-local alias(`A1`=평가 대상, `A2`, `A3`…)로 가린 작성자.
- 각 이벤트의 종류(message/reply/reaction/mention)와 신호(길이 버킷·질문 여부·reaction code).
- cut 기준 상대 시각(`rel_time_ms`). **원문·실명·실제 id·첨부 URL 은 없다.**

## 평가 항목

각 항목은 **단일 정답을 강요하지 않는다.** 모호하면 `ambiguity=true` 로 표시하고, 복수 annotator 의 분포를 허용한다.

1. **말해야 했는지(action)**: `ignore` / `wait` / `react` / `speak` 중 관찰에 가장 부합하는 것.
   - 관찰만으로 단정 못 하면 `wait`(보류 신호) 또는 `ambiguity=true`.
2. **대상(target)**: 발화/리액션이 향한 alias(`A2` 등) 또는 `none`(특정 대상 없음). 복수 타당하면 가장 직접적인 것.
3. **timing(delay_bin)**: `IMMEDIATE` / `SHORT` / `MEDIUM` / `LONG` / `NEVER`.
   - 세션이 끝나 관찰이 잘린 경우를 진짜 `NEVER` 로 적지 마라(우중도절단 — 모호하면 `ambiguity=true`).
4. **ambiguity**: 위 항목 중 하나라도 관찰만으로 확신하기 어려우면 `true`.
5. **social act**: `acknowledge`/`agree`/`disagree`/`tease`/`ask`/`correct`/`self_disclose`/`change_topic`/`unknown`.
   - 신호가 약하면 `unknown`. 자유 텍스트 라벨을 만들지 마라.

## 정답 강요 금지 — 분포·불확실성 허용 (acceptance)

- 한 항목에 여러 annotator 가 서로 다르게 표시하는 것은 **정상**이다. 강제로 한 클래스에 합의시키지 않는다.
- 합의도는 [agreement.py](../../../ml/social-policy/src/nexa_policy/eval/agreement.py) 의
  `item_agreement`·`cohen_kappa` 로 계산한다.
- 낮은 합의 항목은 gold 에서 제외하거나 soft label(분포)로 유지한다(`resolve_gold`, P10-T022).
  즉 **불확실성은 데이터의 일부**이며, 억지 라벨로 덮지 않는다.

## 금지 추론

내면 상태·정체성·민감 속성(정치/종교/건강/성적지향 등)을 추론하지 마라. 관찰 가능한 행동 신호
([observable-state-policy](../social-state/observable-state-policy.md))만으로 분류한다.

## import 검증

annotation 은 [parse_annotation](../../../ml/social-policy/src/nexa_policy/annotation/packet.py) 으로
schema validation(허용 라벨·필수 필드) 후에만 적재된다. 자유 텍스트 메모는 import 하지 않는다(원문 유입 차단).
