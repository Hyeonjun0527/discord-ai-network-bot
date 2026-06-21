# NEXA Scenario DSL v1

Status: baseline format for `test-fixtures/nexa/scenarios/*.yaml` from `NEXA-P16-T002` onward.

The scenario DSL describes a **synthetic** Discord event stream and the **correct NEXA behavior** that the
event-replay simulator must observe. It is the systematization of the human-likeness gate
([`scripts/nexa-human-likeness-eval.py`](../../../scripts/nexa-human-likeness-eval.py)): instead of asking a
model + judge per run, each scenario declares the behavior invariants NEXA must hold, and the deterministic
simulator ([`scripts/nexa-simulate.py`](../../../scripts/nexa-simulate.py)) replays the events and checks them.

Hard constraints (every scenario, no exceptions):

- **Synthetic only.** No operational data, no real users. Scenarios are fabricated.
- **Shadow mode.** The simulator never sends to Discord or calls GLM. `sends` is always `0`.
- **Deterministic.** A virtual `Clock` (`time.baseInstant` + per-event `atOffsetMs`) and a seeded random
  (`seed`) drive replay. Same seed + same events ⇒ same decision trajectory
  (see [determinism convention](../testing/determinism.md)).
- **No central change.** The simulator is a pure reference model. It does not call central production code;
  a drift guard (`NexaSimulatorVocabularyTest.kt`) pins its action/timing vocabulary to the central domain
  enums so the two cannot diverge silently.

Related files:

- [JSON Schema](../../../test-fixtures/nexa/scenarios/schema.json)
- [Simulator CLI](../../../scripts/nexa-simulate.py)
- [DSL + invariant validator](../../../scripts/validate-nexa-scenarios.py) (runs in `nexa-verify.sh docs`)
- [Conversation fixture format](../testing/conversation-fixtures.md) (the event-shape precedent)

## Top-level shape

```yaml
schemaVersion: nexa.scenario.v1
scenarioId: mention-spam
title: 반복 mention spam — 호출 수와 응답 수가 1:1 이 아님
intent: 한 줄 의도(사람이 읽는 요약)
humanLikenessFocus: [over-conservative-ignore, plainness]   # 점검하는 human-likeness 약점 차원
seed: 16005
time:
  baseInstant: "2026-01-03T12:00:00Z"
  zone: Asia/Seoul
  resolutionMs: 500
guild:
  guildId: guild-nexa-fixture
  channelId: channel-general
  channelKind: MEMBER        # MEMBER = 사람처럼 participation, ASSISTANT = 무조건 답변
actors:
  - { actorId: actor-spammer, label: S, displayName: 도배유저, kind: human }
  - { actorId: actor-nexa,    label: NEXA, displayName: 니아, kind: nexa }
events: [ ... ]              # 아래 이벤트 타입
expected:
  narrative: 사람 멤버라면 이렇게 행동한다는 서술
  invariants: [ ... ]        # 시뮬레이터가 검증하는 불변식
  humanLabels: [ ... ]       # (선택) message target 별 사람 기대 행동
```

`channelKind`:

- `MEMBER` — AI 멤버 채널. NEXA 가 사람처럼 participation 한다(호명/맥락 기반, 대부분 침묵).
- `ASSISTANT` — AI 질문 채널. 무조건 답변(기존 auto_respond 계승). 시뮬레이터는 즉시 SPEAK 예약한다.

## Events

각 이벤트는 `seq`(엄격 증가 정수), `atOffsetMs`(비감소 정수, `baseInstant` 기준 가상 오프셋), `type` 을 가진다.

| type | 핵심 필드 | 의미 |
| --- | --- | --- |
| `message_create` | `messageId`, `authorId`, `content`, `mentionsNexa?`, `replyToMessageId?`, `threadId?` | 사람/봇 메시지. `mentionsNexa: true` 면 NEXA 호명. |
| `message_update` | `messageId`, `editorId`, `content` | 기존 메시지 수정 → 최신 revision. |
| `message_delete` | `messageId`, `deletedByActorId?` | 메시지 삭제 → 그 대상으로 발화 금지. |
| `typing_start` | `actorId`, `durationMs?` | 타이핑 신호(관찰만). |
| `reaction_add` / `reaction_remove` | `messageId`, `actorId`, `emoji{kind,value,name?}` | 리액션(관찰만). |
| `nickname_change` | `actorId`, `nickname`, `previousNickname?` | 닉네임 변경(기억 갱신, 발화 트리거 아님). |
| `fault_inject` | `fault`, `detailMs?` | 결함 주입(견고성 경로): `policy_latency`/`rate_limit`/`duplicate_event`/`send_failure`. |

**검증 무결성(acceptance T002):** validator 는 schema 오류와 **존재하지 않는 message target**(존재하지 않는
`messageId`/`actorId` 참조), 중복 `messageId`, 비증가 `seq`, 감소하는 `atOffsetMs` 에서 실패한다.

## Invariants

`expected.invariants[].kind` 가 시뮬레이터 결정 artifact 에 대해 검증된다. 위반이 하나라도 있으면
`validate-nexa-scenarios.py` 가 비-0 종료한다.

| kind | value | 검증 |
| --- | --- | --- |
| `max_speak_count` | int | SPEAK 횟수 ≤ value(과반응 방지). |
| `min_speak_count` | int | SPEAK 횟수 ≥ value(over-conservative IGNORE 점검). |
| `max_react_count` | int | REACT 횟수 ≤ value. |
| `speak_to_mention_ratio_below` | float | SPEAK/mention 비율 < value(mention spam 1:1 아님). |
| `no_speak_after_human_answer` | — | 사람이 먼저 답하면 예약을 취소/재평가, 같은 답 중복 발화 안 함. |
| `no_stale_send` | — | `sends == 0`(shadow). 예약이 stale 하게 전송되지 않음. |
| `cancel_on_context_change` | — | 맥락 변화(사람 응답/삭제) 시 `cancel_pending` 결정이 있음. |
| `no_unprompted_speak` | — | 모든 SPEAK 가 target 메시지를 가짐(호명/맥락 없는 자발 발화 금지). |
| `no_self_consecutive_speak` | — | 사람 메시지 사이 NEXA 가 연속 두 번 SPEAK 안 함. |
| `share_cap_below` | float | 전체 메시지 중 NEXA 발화 점유율 < value(채널 점유 방지). |
| `decision_count_equals` | int | 결정 개수 == value. |
| `speak_uses_latest_revision` | — | 발사한 SPEAK 가 최신 revision 기준. |
| `no_speak_on_deleted_target` | (messageId) | 삭제된 메시지를 대상으로 SPEAK 안 함. |
| `uses_current_fact_not_stale` | — | 현재 사실을 쓰고 stale 사실을 단정하지 않음(닉/기억). |
| `no_conflict_as_fact` | — | 상충하는 사실을 확정 사실로 쓰지 않음. |
| `speak_target_message` | (messageId) | 어떤 SPEAK 이 그 메시지를 대상으로 함. |

`humanLabels[]` 는 특정 `messageId` 에 사람이 기대하는 행동(`SPEAK`/`REACT`/`IGNORE`/`WAIT`)을 단다.
`SPEAK` 라벨인데 시뮬레이터가 한 번도 SPEAK 하지 않으면 **over-conservative IGNORE** 후보로 실패한다 —
human-likeness gate 의 약점(과보수 IGNORE)을 직접 점검한다.

## 행동·타이밍 어휘 (central 동기화)

행동 코드와 타이밍 버킷은 central participation 도메인 enum 의 안정 코드를 그대로 쓴다:

- 행동: `ignore`, `wait`, `react`, `speak`, `cancel_pending`
  ([`SocialActionKind`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/action/SocialAction.kt)).
  SPEAK 만 generation quota 를 소모한다(quota-boundary.md).
- 타이밍: `IMMEDIATE`, `SHORT`, `MEDIUM`, `LONG`, `NEVER`
  ([`DelayBucket`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/decision/DelayDistribution.kt)).

`NexaSimulatorVocabularyTest.kt` 가 시뮬레이터의 Python 상수를 이 enum 들과 대조해 drift 를 잡는다.

## 시나리오 11종 (NEXA-P16-T003 ~ T013)

각 시나리오는 시뮬레이터로 재생되어 기대 행동이 검증된다.

| 파일 | task | 기대 행동(핵심) |
| --- | --- | --- |
| `fragmented-nickname.yaml` | T003 | 짧은 조각마다 판단·응답하지 않고 침묵(`max_speak=0`). |
| `rapid-dyad.yaml` | T004 | 호명돼도 맥락 변화로 예약 취소, stale 전송 0. |
| `mention-spam.yaml` | T005 | **호출 6 vs SPEAK 0~1 — 1:1 아님, 과반응 안함**. |
| `serious-direct-question.yaml` | T006 | 직접 질문 + pause → 생성 경로 정확히 한 번(`min_speak=1`). |
| `already-answered.yaml` | T007 | **사람이 먼저 답하면 침묵(취소/재평가)**. |
| `edit-delete.yaml` | T008 | 최신 revision 으로 답, 삭제된 대상엔 발화 금지. |
| `nickname-over-time.yaml` | T009 | 닉 변경만으론 안 나섬, 현재 닉 사용. |
| `bot-dominance.yaml` | T010 | 혼자 연속 발화 안 함, share cap 작동. |
| `silent-server.yaml` | T011 | **30일 조용해도 먼저 안 나섬, 자동 주제 방송 금지**. |
| `high-traffic.yaml` | T012 | 호명된 thread 만 반응, cross-thread 발화 안 함. |
| `conflicting-memory.yaml` | T013 | 상충 사실을 확정으로 쓰지 않음. |

## 실행

```bash
# 단일 시나리오 재생 + 결정 artifact(JSON)
python3 scripts/nexa-simulate.py test-fixtures/nexa/scenarios/mention-spam.yaml --json

# 전부 재생 + 기대 행동 검증
python3 scripts/nexa-simulate.py --all

# CI 게이트(schema + 재생 + invariant). nexa-verify.sh docs 에 묶여 있다.
python3 scripts/validate-nexa-scenarios.py
```
