# central-server (Kotlin + Spring Boot)

커뮤니티 로컬 AI Provider Pool 의 **중앙 서버**. 설계는 [ADR 0003](../docs/adr/0003-community-provider-pool.md)
및 스택 결정 [ADR 0004](../docs/adr/0004-kotlin-spring-central-server.md), 명세는
[`specs/product-v2`](../specs/product-v2) 를 따른다.

## 책임
Discord 처리(JDA) · Provider Pool · 라우팅 · Provider Session · 정책 · WebSocket 릴레이(에이전트
연결) · 사용량/기여 기록 · 헬스 모니터.

Provider Agent(유저/프로바이더 PC, localhost Ollama 호출)는 **Python** 으로 유지되며, 이 서버와
`specs/.../api.md §8` 의 JSON WS 프로토콜로 통신한다.

## 스택
- Kotlin 2.1 / JVM 21 (Gradle toolchain)
- Spring Boot 3.4 (web · websocket · actuator · validation)
- JDA 5 (Discord)

## 빌드 / 실행
```bash
cd central-server
./gradlew build          # 컴파일 + 테스트
./gradlew bootRun        # 로컬 실행(8080). DISCORD_BOT_TOKEN 등 환경변수 필요
```

## 패키지 구조(점진 구축)
```
com.discordassistant.central
  ├─ CentralServerApplication.kt
  ├─ domain/        ModelBurden · RequestWeight · ProviderState · RequestState · PrivacyMode (구현됨)
  ├─ discord/       JDA 핸들러(슬래시 명령)            (예정)
  ├─ provider/      등록·승인·세션·capability·정책      (예정)
  ├─ pool/          Provider Pool 조회·상태             (예정)
  ├─ routing/       무게 판단·필터 파이프라인·공정성 점수 (예정)
  ├─ relay/         WebSocket 릴레이(에이전트 연결)      (예정)
  ├─ policy/        Guild/Role/Channel 정책            (예정)
  ├─ usage/         사용량·기여 기록                    (예정)
  └─ health/        Provider/세션 헬스                  (예정)
```

> 주의: Discord 봇 토큰·시크릿은 절대 커밋하지 않는다. 환경변수로 주입한다.
