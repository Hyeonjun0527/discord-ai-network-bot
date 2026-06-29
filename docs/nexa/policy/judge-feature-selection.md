# Judge feature selection

- 작업: [니아 사람같은 participation 전환 TODO 68](../roadmap/nia-humanlike-participation-68-todo.md) D-35, D-36, D-39
- 코드:
  [NexaParticipationEmitBridge.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt),
  [SingleJudgeSceneSnapshotBuilder.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleJudgeSceneSnapshotBuilder.kt),
  [FeatureCatalog.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/feature/FeatureCatalog.kt)

## Audit result

이전 Discord bridge hot path는 policy request를 만들 때 `burst.has_mention`과
`agent.recent_burst_count` 두 feature만 직접 구성했다. 그 상태에서는 `FeatureCatalog`에 이미 있는 thread, tempo,
relationship, memory feature가 실제 participation 판단에 들어가지 않는다.

현재 bridge는 수동 2-feature map을 만들지 않는다. `ParticipationMessageSignal`을
`SingleJudgeSceneObservation`으로 투영하고, `SingleJudgeSceneSnapshotBuilder`가 만든 같은 scene snapshot과
feature vector를 `PolicyDecisionRequest`에 넣는다. 따라서 bridge와 단일 judge 계약이 같은 입력 선택을 공유한다.

## Selected judge inputs

원문은 feature가 아니다. 원문은 `rawContextWindow`/quoted scene data로 judge에 들어가며, 아래 feature는 원문을
대체하지 않는 구조화 evidence다.

| feature group | selected ids | reason |
| --- | --- | --- |
| burst | `burst.is_question`, `burst.has_mention`, `burst.is_reply` | 질문/직접대상/reply 여부만 전달한다. 원문 길이·fragment count는 runtime의 원문 window와 중복되므로 이 경로에서는 제외한다. |
| thread | `thread.direct_address_pressure`, `thread.reply_chain_depth`, `thread.previous_ignored_request_count` | 반복 호출과 reply 흐름을 regex enum이 아니라 연속 scene evidence로 전달한다. |
| tempo | `tempo.rate_limit_pressure`, `tempo.anti_spam_pressure` | 최종 전송 guard와 별개로 judge가 기다림/침묵을 자연스럽게 고를 근거다. |
| relationship | `relationship.familiarity`, `relationship.reciprocity`, `relationship.banter_acceptance`, `relationship.sample_confidence` | 관찰 관계를 쓰되, 표본 confidence를 함께 줘 낮은 confidence에서 과한 친밀감을 피한다. |
| memory | `memory.relevant_present`, `memory.relevant_confidence`, `memory.relevant_age_seconds`, `memory.pending_intent_active` | socialmemory 원문이나 subject를 싣지 않고 관련성·confidence·최근성·pending 여부만 준다. |
| agent | `agent.recent_burst_count`, `agent.last_spoke_age_seconds`, `agent.pending_action_count` | 니아가 방금 많이 말했는지와 pending action 포화 상태를 판단 근거로 둔다. |

## Confidence rule

relationship과 memory는 값만 주지 않는다.

- socialmemory source가 아예 없으면 관련 feature는 `missing`으로 둔다.
- source가 있고 관계 표본이 적으면 `relationship.sample_confidence`를 낮은 present 값으로 둔다.
- source가 있고 관련 기억이 없으면 `memory.relevant_present=0`으로 둔다.
- 관련 기억이 있으면 `memory.relevant_confidence`와 `memory.relevant_age_seconds`를 함께 준다.

judge는 `familiarity=0.9` 같은 높은 값만 보고 친한 척하면 안 된다. `sample_confidence`가 낮으면 그 관계값은 약한
evidence로만 취급해야 한다. 이 규칙은 상황 enum을 늘리는 방식이 아니라, 원문과 구조화 confidence를 함께 보는
단일 judge 입력 계약이다.
