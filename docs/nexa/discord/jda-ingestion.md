# 현재 JDA 이벤트 수집 구조 (조사 — NEXA-P03-T001)

- 작업: NEXA-P03-T001 · 상위 경계 계약: [discord-adapter-boundary.md](../architecture/discord-adapter-boundary.md)
- 조사 대상: `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/`
- 코드 변경 없음. 아래 `file:line` 은 모두 실제 코드 인용(2026-06-21 기준, JDA 5.2.1).

## 요약

- JDA 리스너는 **단일 클래스 `DiscordBot.Listener`** (`ListenerAdapter` 상속)에 전부 모여 있다.
  `DiscordBot.kt:281` 선언, `DiscordBot.kt:299` 가 `ListenerAdapter()` 상속.
- 등록 지점은 **하나**: `DiscordBot.launchJda()` 의 `builder.addEventListeners(listener).build()`
  (`DiscordBot.kt:230`). 리스너 인스턴스는 같은 메서드의 `DiscordBot.kt:210-229` 에서 생성된다.
- 부트스트랩은 `JDABuilder.createLight(token, intents)` (`DiscordBot.kt:209`) — **샤딩 미사용**
  (`ShardManager`/`DefaultShardManagerBuilder`/`useSharding` 호출이 저장소에 전혀 없음).
- 등록·구독하는 게이트웨이 인텐트는 `GatewayIntentPolicy.intents()`
  (`GatewayIntentPolicy.kt:6-11`): `GUILD_MESSAGES`, `GUILD_MESSAGE_REACTIONS`,
  그리고 `messageContentIntentEnabled` 일 때만 `MESSAGE_CONTENT`.

## 작업 요구 이벤트별 현황 (file:line)

### MessageReceived — 구현됨

- 등록: `DiscordBot.Listener` 가 `ListenerAdapter` 를 상속하므로 override 만으로 구독.
  인텐트 `GUILD_MESSAGES`(+선택적 `MESSAGE_CONTENT`)로 들어온다(`GatewayIntentPolicy.kt:8,10`).
- 처리: `override fun onMessageReceived(event: MessageReceivedEvent)` — `DiscordBot.kt:872`.
  - 게이트: `!mentionAskEnabled || !event.isFromGuild || event.author.isBot` 이면 즉시 return
    (`DiscordBot.kt:873`). 즉 **DM·봇 메시지·콘텐츠 인텐트 비활성 시 미수집**.
  - 봇 멘션 → `handleMentionAsk` (`DiscordBot.kt:878-880,886`),
    아니면 자동응답 채널 판정 → `handleAutoRespond` (`DiscordBot.kt:882,910`).
  - 자동응답 채널 판정은 인메모리 캐시 `AutoRespondChannelRegistry.isAutoRespond` O(1)
    (`DiscordBot.kt:913`), 채널 단위 분당 비용 캡은 `rateLimiter.tryAcquire` (`DiscordBot.kt:916`).

### Reaction Add — 구현됨 (메트릭만)

- 처리: `override fun onMessageReactionAdd(event: MessageReactionAddEvent)` — `DiscordBot.kt:963`.
  - 봇 리액션 무시(`DiscordBot.kt:964`), 👍/👎 만 `metrics.record("reaction:up"/"down")`
    (`DiscordBot.kt:965-968`). 내용·작성자·대상 메시지는 수집/영속하지 않는다.
- 인텐트 `GUILD_MESSAGE_REACTIONS` 로 들어온다(`GatewayIntentPolicy.kt:9`).

### Reaction Remove — **미구현**

- `onMessageReactionRemove` override 가 코드에 **없음**(저장소 전역 grep 0건).
  리액션 제거 이벤트는 현재 전혀 수집되지 않는다.

### Typing — **미구현**

- `onUserTyping` override 가 코드에 **없음**(grep 0건). 봇은 타이핑을 **전송만** 한다
  (`event.channel.sendTyping()` — `DiscordBot.kt:938`). `GUILD_MESSAGE_TYPING` 인텐트도
  구독하지 않는다(`GatewayIntentPolicy.kt` 에 부재).

### Member Update (닉네임/표시명) — **미구현**

- `onGuildMemberUpdate` / `onGuildMemberUpdateNickname` / `onUserUpdateGlobalName` /
  `onUserUpdateName` override 가 코드에 **없음**(grep 0건).
- 표시명은 **이벤트로 수집하지 않고 요청 시점에 조회**한다:
  `memberName()` 이 `getMemberById(userId)?.effectiveName` 로 즉석 읽기(`DiscordBot.kt:185-188`).
- 관련 멤버 이벤트 중 구현된 것은 **제거 계열뿐**:
  `onGuildMemberRemove` (`DiscordBot.kt:787`, provider 상태 정리),
  `onGuildLeave` (`DiscordBot.kt:782`), `onChannelDelete` (`DiscordBot.kt:792`).
  닉네임/표시명 변경은 추적하지 않는다. (`createLight` 는 `GUILD_MEMBERS` 인텐트 미사용 —
  `DiscordBot.kt:158-159` 주석이 멤버 캐시가 비어 있을 수 있음을 명시.)

## 실행 스레드 모델

- **모든 리스너 콜백은 JDA 의 단일 이벤트 풀에서 실행된다.** `JDABuilder.createLight`
  (`DiscordBot.kt:209`)는 별도 이벤트 풀/샤딩을 설정하지 않으므로 JDA 기본 이벤트 매니저
  (게이트웨이 스레드에서 순차 디스패치)가 콜백을 돌린다. 커스텀 `EventManager`/`setEventPool`
  설정이 코드에 없다.
- **게이트웨이 스레드를 점유하면 안 되는 추론 작업은 전용 풀로 오프로드**한다:
  - `commandExecutor = Executors.newFixedThreadPool(8)` 이름 `discord-cmd`, 데몬
    (`DiscordBot.kt:194-195`).
  - 느린 명령(`SLOW_COMMANDS = {"ask","imagine"}` — `DiscordBot.kt:550`)은
    `slowCommandExecutor.execute(work)`, 나머지 빠른 명령은 리스너 스레드에서 `work.run()`
    (`DiscordBot.kt:431`).
  - 슬래시 명령은 먼저 `event.deferReply(...).queue()` 로 ack(3초 제한 회피) 후 처리
    (`DiscordBot.kt:384`).
- `onMessageReceived` 의 멘션/자동응답 추론(`commands.ask`)은 **별도 풀로 옮기지 않고
  이벤트 스레드에서 동기 호출**된다(`respondInChannel` — `DiscordBot.kt:940`). 즉 메시지
  자동응답은 게이트웨이 이벤트 스레드에서 블로킹 실행된다(슬래시 ask/imagine 와 다른 점).

## 순서 보장

- JDA 단일 이벤트 풀이 게이트웨이로부터 받은 순서대로 콜백을 **순차 디스패치**하므로,
  이벤트 핸들러 진입 순서는 게이트웨이 수신 순서를 따른다(`createLight` 기본 동작).
- 단, 핸들러 내부에서 슬래시 ask/imagine 처리를 `slowCommandExecutor`(8-스레드 풀)로
  넘기는 순간(`DiscordBot.kt:431`) **완료 순서는 보장되지 않는다** — 8개 작업이 병렬 진행.
- 응답 전송은 대부분 비동기 `.queue(...)` 라 전송 완료 순서도 보장되지 않는다.
  멘션/자동응답 경로는 이벤트 스레드 동기 실행이라 같은 채널 내 상대 순서는 유지된다.

## 예외 처리

- 슬래시 처리: `try { ... } catch (e: Exception)` 로 감싸 사용자에겐 일반 안내,
  서버 로그에 명령·채널·스택을 남긴다(`DiscordBot.kt:424-428`).
- 멘션/자동응답: `respondInChannel` 의 `try/catch (Exception)` 동일 패턴
  (`DiscordBot.kt:939,948-959`).
- 전송 호출은 거의 모두 `.queue({}, {})` 또는 `.queue({}, { e -> ... })` 로 **전송 실패를
  삼킨다**(예: `DiscordBot.kt:778, 918, 938, 958`). REST 실패가 핸들러를 깨지 않는다.
- `isGuildAdmin` 의 REST 멤버 조회 실패는 `catch (Exception)` 으로 '관리자 아님' 보수 처리
  (`DiscordBot.kt:171-178`).
- 안전 재기동(intent fallback)은 `runCatching { ... }.onFailure { ... }`
  (`DiscordBot.kt:252-259`).

## 재연결 · 세션 경계

- **Reconnect/Resume/SessionInvalidate 전용 핸들러는 없다.** `onReconnected` /
  `onSessionDisconnect` / `onSessionRecreate` / `onSessionResume` / `onSessionInvalidate`
  override 가 코드에 **전혀 없음**(grep 0건). 즉 재연결·세션 재개는 JDA 내부 자동 재연결에
  맡기고 애플리케이션 코드는 관여하지 않는다.
- 구현된 세션 라이프사이클 콜백은 두 개뿐:
  - `onReady(event: ReadyEvent)` — `DiscordBot.kt:585`. `gatewayStatus.markReady(...)` +
    가용/비가용 길드 수 로깅(`DiscordBot.kt:586-592`).
  - `onShutdown(event: ShutdownEvent)` — `DiscordBot.kt:595`. close code 해석,
    `gatewayStatus.markShutdown(...)`, code 4014(DISALLOWED_INTENTS)면 `onDisallowedIntents()`
    콜백 호출(`DiscordBot.kt:596-603`).
- 4014 처리(`handleDisallowedIntents` — `DiscordBot.kt:243-260`)는 **명시적 재기동 경로**다:
  `jda?.shutdownNow()` 후 `launchJda(messageContentIntent=false)` 로 Message Content 없이
  슬래시 명령만 안전 재부팅(`DiscordBot.kt:252-259`). 한 번만 시도(`fallbackAttempted`
  AtomicBoolean — `DiscordBot.kt:190,250`).
- 종료: `@PreDestroy stop()` 이 `jda?.shutdown()` + `commandExecutor.shutdown()`
  (`DiscordBot.kt:262-266`).

## 샤딩 · 길드→샤드 매핑

- **샤딩 미사용.** `JDABuilder.createLight` 단일 게이트웨이 연결(`DiscordBot.kt:209`).
  `ShardManager`/`DefaultShardManagerBuilder`/`useSharding`/`setShardsTotal` 호출이
  저장소 전역에 없다.
- 따라서 **길드→샤드 매핑 개념이 존재하지 않는다.** 모든 길드가 하나의 게이트웨이 연결을
  공유하며, 길드 조회는 단일 JDA 인스턴스 캐시(`jda?.guilds`, `getGuildById`)로 한다
  (`DiscordBot.kt:137-153`).
- 명령 등록 스코프는 글로벌(+옵션 `central.discord.guild-id` 즉시 등록)이며 샤드와 무관
  (`DiscordBot.kt:232-236, 268-278`).

## NEXA 수집(P03)이 이 구조 위에 어떻게 올라가는가

현재 리스너는 JDA 타입(`MessageReceivedEvent` 등)을 그대로 들고 `CommandService`/메트릭을 직접
호출하는 **얇은 글루**다 — MessageReceived·ReactionAdd 만 부분 수집하고 ReactionRemove·Typing·
MemberUpdate 는 미구현이며, 표시명은 이벤트가 아니라 요청 시 즉석 조회한다. P03 수집은 이
`DiscordBot.Listener` 의 override 들을 [discord-adapter-boundary.md](../architecture/discord-adapter-boundary.md)
가 규정한 **수신 포트(`DiscordEventInbound`)** 뒤로 넣어, 각 JDA 이벤트를 `DiscordEventEnvelope`
(채널/길드/작성자/내용 참조·타임스탬프)로 정규화한 뒤 conversation 도메인에 인입하는 방식으로
올라간다. 즉 누락된 ReactionRemove·Typing·MemberUpdate(닉네임/표시명) 리스너를 어댑터 경계
**안쪽**에 추가하고, 추론·전송을 게이트웨이 스레드에서 떼어내 어댑터가 정규화만 하도록 유지하면
(불변식 1·3), 단일 게이트웨이(비샤딩)·단일 이벤트 풀이라는 현 실행 모델 위에서 도메인이 JDA
타입을 보지 않고 이벤트를 수집할 수 있다.
