# ADR 0011: conversation 채널 파티션 키 — 스레드와 부모 채널의 순서 스트림 분리

- 상태(Status): 승인됨 (Accepted) — 기술 결정 2026-06-21 (NEXA-P03-T013, `human_gate: false`)
- 날짜(Date): 2026-06-21
- 결정자(Deciders): conversation projection 차수(P03) 실행 규칙
- 관련: [ADR 0007 사회적 행위자 모델](./0007-nexa-social-member-context.md)
- 계약: [conversation-context.md](../nexa/architecture/conversation-context.md),
  [domain-events.md](../nexa/architecture/domain-events.md)

## 맥락 (Context)

conversation projection(P03-T014~)은 "같은 채널의 이벤트가 한 순서 스트림으로 처리"되도록 partition
key가 필요하다(T013 deliverable). Discord에는 일반 채널과, 그 채널에 매달린 **스레드(thread)**가 있다.
스레드는 부모 채널 안에서 열리지만, Discord Gateway에서 **자기 고유 snowflake id**를 가진 별개 채널이며
메시지 이벤트의 `channelId`는 스레드 자신의 id로 온다(부모 id가 아님).

따라서 결정해야 할 점: **스레드 이벤트를 부모 채널 스트림에 합칠 것인가(merge), 별개 스트림으로 분리할
것인가(separate).**

핵심 사실:

- conversation 도메인 이벤트(`NormalizedDiscordEvent`)는 `channelId`만 운반한다 — 부모-자식 관계(스레드
  ↔ 부모)는 도메인 모델에 없다(discord-adapter-boundary.md: 어댑터가 정규화하며 도메인은 JDA 타입을
  보지 않는다).
- `source_sequence`(어댑터 수신 순번)는 **채널 단위**로 단조 증가한다 — 한 채널 안에서만 순서가 의미를
  가진다. 서로 다른 채널(스레드 포함) 사이에는 전역 순서가 없다.
- "지금 무슨 대화가 오가는가"(Scene projection, conversation-context.md)는 한 대화 표면 단위로 읽히는
  것이 자연스럽다 — 스레드는 부모와 분리된 별개 대화 표면이다.

## 결정 (Decision)

**partition key = `channelId`(스레드 분리, separate).** 스레드 이벤트는 부모 채널 스트림에 합치지 않고
스레드 자신의 `channelId`로 독립 순서 스트림을 형성한다.

근거:

1. **순서 결정성**: `source_sequence`가 채널별 단조 증가라, 부모와 스레드를 합치면 서로 다른 두 단조
   수열이 섞여 전순서가 깨진다. 같은 `channelId`로 분리하면 각 스트림이 그 채널의 단조 수열 그대로
   결정론적 순서를 유지한다(T014 projector의 충돌 규칙이 단순해진다).
2. **도메인 순수성**: 부모-자식 합치기는 스레드→부모 매핑을 도메인이 알아야 하는데, 이는
   conversation.domain이 Discord 토폴로지에 의존하게 만든다(discord-adapter-boundary.md 위반). 분리는
   `channelId` 동등성만으로 성립한다(추가 의존 0).
3. **대화 표면 일치**: 스레드는 사용자에게 별개 대화 표면이다 — Scene projection이 스레드를 부모와
   섞으면 "지금 이 채널의 대화"가 부정확해진다.

이 결정은 순수 도메인 값 [`ChannelPartitionKey`](../../central-server/src/main/kotlin/com/discordassistant/central/conversation/application/dispatch/ChannelPartitionKey.kt)로
캡슐화한다 — partition key 산출 규칙을 한곳에 두어 projector/buffer/dedup이 같은 규칙을 공유한다(DRY).

## 비-목표

- 스레드↔부모 **관계 추적**(스레드가 어느 부모에서 열렸는가)은 별개 관심사다 — 필요하면 후속 task에서
  계보(source_event_id) 또는 메타 이벤트로 다룬다. 본 ADR은 **순서 파티셔닝**만 결정한다.
- 길드 전역 순서 — 존재하지 않으며 만들지 않는다(채널 단위 순서만 의미를 가진다).

## 결과 (Consequences)

**장점**: partition key가 `channelId` 하나로 단순하고, 각 스트림의 단조 순서가 보존된다. 도메인이 Discord
스레드 토폴로지에 의존하지 않는다.

**단점**: 스레드와 부모를 "하나의 대화"로 보고 싶은 소비자는 두 스트림을 스스로 조합해야 한다(이는
projection 소비자의 관심사이며, 순서 계층의 책임이 아니다).

## 되돌림 가능성

merge 전략이 필요해지면 partition key 산출만 [`ChannelPartitionKey`] 한곳에서 바꾸면 된다(소비자 무변경).
스레드→부모 매핑을 어댑터가 정규화 이벤트에 실어주는 것이 선행 조건이다.
