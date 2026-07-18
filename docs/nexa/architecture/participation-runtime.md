# Participation runtime loop

- 작업: [니아 사람같은 participation 전환 TODO 68](../roadmap/nia-humanlike-participation-68-todo.md) D-27~D-34
- 코드: [ParticipationRuntimeLoop.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/runtime/ParticipationRuntimeLoop.kt),
  [ChannelAttentionGate.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/service/ChannelAttentionGate.kt),
  [SingleJudgeSceneSnapshotBuilder.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleJudgeSceneSnapshotBuilder.kt)
- judge feature 선별: [judge-feature-selection.md](../policy/judge-feature-selection.md)

## 결정

`니아수다` participation은 메시지가 들어온 순간만 평가하지 않는다. runtime loop가 message, typing, idle tick,
pending action wake-up을 받아 단일 judge 평가 시점을 만든다.

가져오는 범위는 `discord-assistant-core`의 event loop, idle polling, gated pipeline, pending wake-up 개념이다.
가져오지 않는 범위는 3표결, ensemble judge, majority vote다. 모든 wake는 downstream 단일 judge 평가 하나로 이어진다.

## 왜 per-message bridge만으로 부족한가

[NexaParticipationEmitBridge.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt)는
Discord message event가 들어올 때만 실행되는 동기 경로다. 이 경로만 있으면 `WAKE_AFTER_IDLE` deadline을 계산할 수는
있지만, 새 메시지가 없는 "대화 공백 후"에는 다시 participation 평가를 시작할 entrypoint가 없다.

따라서 [ChannelAttentionGate.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/service/ChannelAttentionGate.kt)는
deadline 계산과 `idleDue` 판정만 담당하고, [ParticipationRuntimeLoop.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/runtime/ParticipationRuntimeLoop.kt)가
`onIdleTick`에서 deadline을 소비해 단일 judge를 깨운다.

## Runtime inputs

| 입력 | 역할 | 결과 |
| --- | --- | --- |
| `onMessage` | Discord message/bridge 이후 message event | `EvaluateNow`, `ScheduleIdleReevaluation`, `Wait`, `NoWake` |
| `onTyping` | 사람이 작성 중이라는 tempo signal | typing grace 동안 `Wait` |
| `onIdleTick` | active idle polling | idle deadline 도래 시 `EvaluateNow(IDLE_DUE)` |
| `onPendingActionWake` | 예약 발화/반응 실행 직전 최신 scene 확인 | `ReevaluatePending` 또는 `CancelPending` |

runtime은 원문을 보관하지 않는다. 원문 source transcript는 RawContextStore와 `JudgeContextWindow`가 관리하고, judge
request의 `rawContextWindow`로 들어간다. runtime은 judge가 원문과 함께 볼 구조화 신호만 만든다.

메시지마다 사라지지 않아야 하는 공통 기반·경쟁 의도 가설·최근 니아 행동·그 행동에 대한 사람 반응은
`socialpolicy`의 focus-thread별 bounded scene belief projection에 남는다. judge 갱신에는 evidence ref와 불확실성이
필수이며, 다음 관찰이 기존 가설을 supersede/reject할 수 있다.

## Turn-taking signals

runtime과 scene snapshot은 아래 신호를 enum이 아니라 연속 값/불리언 evidence로 넘긴다.

| 신호 | 의미 |
| --- | --- |
| `directAddressPressure` | 같은 thread에서 니아를 직접 부르는 압력. 반복 요구 regex가 아니라 thread state로 누적한다. |
| `replyChainDepth` | 현재 발화가 이어진 reply chain 깊이. |
| `nicknameCall` | 멘션 없이 별명/호명으로 니아를 부른 신호. |
| `previousIgnoredRequestCount` | 이전 직접 요청이 응답 없이 지나간 횟수. |
| `humansTalkingToEachOtherLikely` | 사람끼리 답하고 있어 끼어들지 말아야 할 가능성. |
| `niaAddressedOrIdleOpportunity` | 니아 대상 발화이거나 공백으로 개입 여지가 있는 장면. |
| `rateLimitPressure` / `antiSpamPressure` | judge 입력으로 쓰는 압력. 최종 전송 직전 guard와 별개로 판단 근거에 포함한다. |

이 신호들은 `EMOTIONAL_SUPPORT` 같은 상황 enum을 대체하지 않는다. judge는 원문 context window와 이 scene signal을
함께 보고 `SPEAK / WAIT / REACT / IGNORE / CANCEL_PENDING` 중 하나를 고른다.

## Pending action rule

예약된 발화나 반응은 실행 직전에 최신 scene으로 다시 본다.

- 대상 메시지가 만료되면 `CancelPending(TARGET_EXPIRED)`.
- 이미 해결된 흐름이면 `CancelPending(ALREADY_RESOLVED)`.
- 다른 사람이 충분히 답했으면 `CancelPending(OTHER_HUMAN_ANSWERED)`.
- context version이 바뀌었으면 기존 판단을 그대로 보내지 않고 `ReevaluatePending(CONTEXT_VERSION_CHANGED)`.
- 그 외에는 `ReevaluatePending(PENDING_DUE)`로 최신 raw context window와 scene snapshot을 다시 구성한다.
- `WAIT` due는 기존 결정을 전송하지 않는다. WAIT 완료와 child judge 명령을 transactional outbox에 함께 기록한 뒤
  최신 scene으로 새 판단을 시작한다. 프로세스 재시작에 필요한 routing ID는 outbox에 암호화해 보존한다.

## Guard boundary

rate limit과 anti-spam은 judge 입력과 actionruntime 전송 guard 양쪽에 존재한다.

- runtime/scene 단계: 압력을 `rateLimitPressure`, `antiSpamPressure`로 넘겨 judge가 말하기/기다리기/반응만 하기
  판단에 반영한다.
- actionruntime 단계: 각 실제 SEND bubble/REACT Discord 호출 직전 채널·전역 원자적 execution permit이 최종
  안전장치로 남는다.

이 경계 때문에 "제한이 있으니 무조건 선제 hard drop"이 아니라, 제한에 가까운 상황도 judge가 자연스러운 WAIT/IGNORE로
해석할 수 있다.
