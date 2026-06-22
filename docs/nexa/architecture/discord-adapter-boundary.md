# 경계 계약: platform/discord 정규화 이벤트 어댑터

- 작업: NEXA-P01-T011 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 계약 대상: `central-server/.../central/platform/discord/**`
- 관련 계약: [conversation-context.md](./conversation-context.md),
  [actionruntime-context.md](./actionruntime-context.md)

## 목적

JDA 이벤트를 내부 `DiscordEventEnvelope`로 정규화하고, 전송도 이 어댑터 뒤에서만 일어나게 하여
**이후 어떤 도메인도 JDA 타입을 보지 않게** 한다.

## 양방향 포트 (acceptance — 수신/전송 분리)

- 수신(inbound): `DiscordEventInbound` — JDA 리스너가 `MessageReceivedEvent` 등을
  `DiscordEventEnvelope`(채널/길드/작성자/내용 참조·타임스탬프, 원문 최소화)로 변환해 conversation에 인입.
- 전송(outbound): `DiscordSendPort` — actionruntime이 전송을 명령하면 어댑터가 JDA 호출로 실현.

두 포트는 분리되어 **테스트 대역(fake)으로 교체 가능**하다(JDA 없이 도메인 테스트).

## 금지 (ArchUnit 강제, ADR 0008)

- `platform/discord` 패키지 밖의 도메인(conversation/participation/socialmemory/speech/
  actionruntime)은 `net.dv8tion.jda` 타입을 import하지 않는다.
- 어댑터는 정규화만 하고 행동 결정·문장 생성을 하지 않는다.

## 불변식

1. JDA 객체는 어댑터 경계를 넘지 않는다 — 도메인은 `DiscordEventEnvelope`/도메인 명령만 본다.
2. 수신과 전송은 별도 포트이며 서로를 직접 호출하지 않는다(루프백 금지).
3. 어댑터는 상태를 결정하지 않는다(관찰=conversation, 전송 타이밍=actionruntime).
