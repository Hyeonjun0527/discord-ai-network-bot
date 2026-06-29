# Speech boundary and burst profile

- 작업: [니아 사람같은 participation 전환 TODO 68](../roadmap/nia-humanlike-participation-68-todo.md) D-55, D-56, D-57
- 코드:
  [BurstShapeCritic.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/domain/service/critic/BurstShapeCritic.kt),
  [ConversationalBoundaryCritic.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/domain/service/critic/ConversationalBoundaryCritic.kt),
  [NexaSpeechPipelineService.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt)
- 테스트:
  [BurstShapeCriticTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/critic/BurstShapeCriticTest.kt),
  [ConversationalBoundaryCriticTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/critic/ConversationalBoundaryCriticTest.kt),
  [NexaSpeechPipelineServiceTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt)

## 결정

speech는 말할지, 기다릴지, 반응만 할지를 새로 판단하지 않는다. 단일 judge가 `SpeechScenePacket`에 넘긴
`speechIntent`, 원문 scene data, `SpeechBurstShape`를 기준으로 후보를 생성하고, 전송 전 critic이 후보를 거른다.

`EMOTIONAL_SUPPORT` 같은 상황 enum은 추가하지 않는다. 직접 반응 요구, 위로 요구, 대화 공백 같은 장면은 raw context와
natural-language intent에 남기고, critic은 결과 텍스트가 멤버 채팅 경계를 벗어났는지만 본다.

## Conversational boundary

[ConversationalBoundaryCritic.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/domain/service/critic/ConversationalBoundaryCritic.kt)는
아래 후보를 `CONVERSATIONAL_BOUNDARY` 하나로 차단한다.

- 직접 반응/위로 요구에서 길게 늘어지는 위로문
- 낮은 위험 채팅에서 `첫째`, `둘째`, `정리하자면` 같은 설명식 답변
- `항상 네 편`, `우리 절친`, `내가 너를 다 알아` 같은 과한 친밀감
- `너 지금 외로운 거구나`처럼 사용자 감정을 대신 단정하는 문장

이 reason은 상황 분류 enum이 아니라 output critic reason이다. 새 사회적 상황을 만들지 않고, 후보가 너무 길거나
부자연스럽게 친한 척하거나 사용자를 대신 해석하는지만 막는다.

## Burst profile

[BurstShapeCritic.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/domain/service/critic/BurstShapeCritic.kt)는
judge가 정한 `SpeechBurstShape`를 전송 전 후보에 적용한다.

- 한 문장 profile이면 한 bubble 후보만 통과한다.
- 짧은 두 문장 profile이면 두 bubble 후보만 통과한다.
- `reactionOnly=true`이면 텍스트 후보는 탈락하고 fallback policy가 reaction-only로 하강한다.
- bubble별 최대 길이를 넘는 후보는 탈락한다.

silence는 speech 후보의 모양이 아니라 upstream action의 결과다. `SPEAK`가 아닌 trigger, stale request, critic 전원 탈락,
고위험 하강, 동의 차단은 speech pipeline에서 텍스트 전송 없이 끝난다.

## Fixed fixture

직접 요구 fixture는 [NexaSpeechPipelineServiceTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt)의
`direct support fixture keeps the short natural candidate`에 고정한다.

입력 원문:

```text
user_1: «야 이럴땐 위로해줘야지 / 위로하라고»
```

기대 동작:

- 장문 위로, 감정 단정, 설명식 후보는 `CONVERSATIONAL_BOUNDARY`로 탈락한다.
- 짧은 후보인 `아 미안, 지금 봤어.`가 선택된다.
- decision log에는 원문 없이 critic reason만 남는다.
