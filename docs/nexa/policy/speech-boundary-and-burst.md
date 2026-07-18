# Speech boundary and burst profile

- 작업: [니아 사람같은 participation 전환 TODO 68](../roadmap/nia-humanlike-participation-68-todo.md) D-55, D-56, D-57
- 코드:
  [BurstShapeCritic.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/domain/service/critic/BurstShapeCritic.kt),
  [NexaSpeechPipelineService.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt)
- 테스트:
  [BurstShapeCriticTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/critic/BurstShapeCriticTest.kt),
  [NexaSpeechPipelineServiceTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt)

## 결정

speech는 말할지, 기다릴지, 반응만 할지를 새로 판단하지 않는다. 단일 judge가 `SpeechScenePacket`에 넘긴
`speechIntent`, 원문 scene data, `SpeechBurstShape`를 기준으로 후보를 생성한다.

`EMOTIONAL_SUPPORT` 같은 상황 enum은 추가하지 않는다. 직접 반응 요구, 위로 요구, 대화 공백 같은 장면은 raw context와
natural-language intent에 남긴다.

## Conversational boundary

말투와 대화 품질은 문자열·정규식으로 후보를 탈락시키지 않는다. 다음 판단은 최근 원문 장면, 페르소나, few-shot을
받은 생성 모델이 맡는다.

- 직접 반응이나 위로 요구에서 답변 길이와 온도
- 낮은 위험 채팅에서 설명이 필요한지 여부
- 현재 관계에 맞는 친밀감
- 사용자의 감정을 확인할지 되물을지 여부

일부 표현만 보고 차단하면 반어법·장난·관계 맥락을 잃고, 후보가 모두 탈락해 무응답이 될 수 있다. 따라서
`ConversationalBoundaryCritic`은 운영 전송 경로에서 제거하며, 자연스러움은 프롬프트와 문맥 기반 생성으로 조정한다.

## Burst profile

[BurstShapeCritic.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/speech/domain/service/critic/BurstShapeCritic.kt)는
judge가 정한 `SpeechBurstShape`를 전송 전 후보에 적용한다.

- 일상적인 한마디·기능 채널 안내·맥락 수습은 한 bubble 후보만 통과한다.
- 이야기·농담처럼 내용 전개가 필요한 요청은 judge가 `bubbleCount=2..4`를 정하며, speech가 같은 수의 bubble을
  현재 응답 안에서 완결한다. `준비해볼게`처럼 다음 응답을 예고하고 끝내지 않는다.
- `reactionOnly=true`이면 텍스트 후보는 탈락하고 fallback policy가 reaction-only로 하강한다.
- bubble별 최대 길이를 넘는 후보는 탈락한다.

선택된 bubble 배열은 [SpeechBurstContentCodec.kt](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/content/SpeechBurstContentCodec.kt)로
한 content record에 저장한다. scheduler는 이를 다시 배열로 복원하고 각 bubble을 별도 Discord 메시지로 보낸다.
첫 bubble만 원문에 답장하고 뒤 bubble은 같은 응답 묶음의 연속 채팅으로 보낸다.

같은 요청을 반복했는지와 그때 얼마나 짜증 섞인 반응을 할지는 문자열·정규식이 아니라 raw conversation을 보는
judge와 speech가 결정한다. 처음 안내한 기능 채널 이름을 매번 그대로 반복하지 않고, 앞선 답을 가리키거나 반복이
누적되면 짧게 선을 긋는다.

silence는 speech 후보의 모양이 아니라 upstream action의 결과다. `SPEAK`가 아닌 trigger, stale request,
고위험 하강, 동의 차단, 비밀 유출 또는 전송 형식 위반은 speech pipeline에서 텍스트 전송 없이 끝난다.

## Fixed fixture

직접 요구 fixture는 [NexaSpeechPipelineServiceTest.kt](../../../central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt)의
`대화 품질은 정규식이 아니라 생성 문맥이 결정한다`에 고정한다.

입력 원문:

```text
user_1: «야 이럴땐 위로해줘야지 / 위로하라고»
```

기대 동작:

- 장문 위로, 감정 단정, 설명식 표현이라는 이유만으로 후보를 탈락시키지 않는다.
- 생성 모델은 원문 장면을 보고 적절한 표현을 만든다.
- decision log에는 비밀 유출·버블 형식 같은 객관적인 차단 사유만 남는다.
