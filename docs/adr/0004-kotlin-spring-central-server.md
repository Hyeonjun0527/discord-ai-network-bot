# ADR 0004: 중앙 서버를 Kotlin + Spring Boot 로 (폴리글랏 분할)

- 상태(Status): 채택됨 (Accepted)
- 날짜(Date): 2026-05-30
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0002](./0002-remote-agent-byollm.md), [ADR 0003](./0003-community-provider-pool.md)
  의 "중앙 서버" 구현 스택을 확정한다.

## 맥락 (Context)

ADR 0003 의 커뮤니티 Provider Pool 에서 중앙 서버는 단순 "요청→응답" 서버가 아니라
**상태 기계 묶음**이다: Guild 정책 관리, Provider 등록/승인, Provider Session 관리,
WebSocket 연결 관리, Request Router, Request Queue, Timeout/Fallback, Usage Logger,
권한 검사, Health Monitor.

이런 정책·상태·동시성 중심 백엔드는 규모가 커질수록 정적 타입·도메인 모델링·보안·ORM·
운영 관측이 강한 스택이 유리하다. Kotlin + Spring Boot 의 이점:

- `sealed class`/`enum class` 로 상태(Provider/Request 상태머신)를 컴파일 타임에 제약.
- `data class` 로 요청/응답·도메인 모델을 안전하게 표현.
- Spring Security 로 권한, JPA/QueryDSL 로 DB, Actuator 로 운영 상태.
- 테스트 구조(JUnit5/MockK/Testcontainers)가 안정적.

반면 LLM 호출·Ollama 연동·실험은 Python 이 편하다.

## 결정 (Decision)

**폴리글랏 분할**을 채택한다.

| 영역 | 스택 |
| --- | --- |
| 중앙 백엔드 — Discord 처리(JDA/Kord) · Provider Pool · 라우팅 · 세션 · 정책 · WS 릴레이 · DB | **Kotlin + Spring Boot** |
| Provider Agent (유저/프로바이더 PC, localhost Ollama 호출) | **Python** |
| WS 프로토콜(JSON 프레임) | **언어 중립 계약** (`specs/.../api.md §8`) — 서버는 Kotlin, 에이전트는 Python 이 각자 구현 |

구체 결정:

1. **Discord 도 Kotlin 이 직접 처리**한다(JDA/Kord). 즉 중앙 서버가 Discord 게이트웨이에
   붙는다.
2. 코드는 **같은 레포의 폴리글랏 모듈** `central-server/`(Gradle, Kotlin)로 둔다
   (`dashboard/` 처럼 한 레포에서 관리, CI 잡 분리).
3. 앞서 Python 으로 만든 **중앙측 코드(`src/discord_assistant/remote/` 의 relay/registry/
   client/protocol/errors)는 제거**한다. 설계·명세(ADR 0002/0003, `specs/`)와 WS 프로토콜
   계약은 그대로 유효하며 Kotlin 으로 재구현한다.

### 기존 Python Discord 봇의 처리(마이그레이션)

현행 Python 봇(discord.py, 요약/Q&A 등 v0.3.2 배포 중)은 **즉시 삭제하지 않는다**. Kotlin
중앙 서버를 점진 구축하며, 기능 패리티가 확인된 뒤 Discord 처리를 Kotlin 으로 이관한다.
그때까지 두 경로가 공존할 수 있다(배포된 제품 보호).

## 결과 (Consequences)

**장점**

- 정책·상태·라우팅·동시성·DB 가 정적 타입 + Spring 생태계로 견고해진다.
- Provider Pool 의 복잡한 상태머신·권한·공정성 로직을 안전하게 확장.
- Python 은 잘 맞는 곳(에이전트·Ollama·AI 실험)에 집중.

**단점 / 트레이드오프**

- 폴리글랏 → 빌드/배포/CI 가 둘(파이썬 + JVM)로 늘어난다.
- WS 프로토콜을 양쪽 언어에서 **이중 구현**해야 한다(계약 동기화 필요).
- Discord 봇을 Kotlin(JDA)으로 재작성하는 마이그레이션 비용.
- 앞서 만든 Python relay/registry/client(차수 1~5 중앙측)를 폐기(PoC/참조로만 가치).

## 구현 메모

- 모듈 경로: `central-server/`(Gradle Kotlin DSL). 스프링 부트 3.x, Kotlin 2.x, JVM 21.
- 패키지 구조는 `specs/product-v2` 도메인에 맞춘다: `discord` · `provider` · `pool` ·
  `routing` · `relay`(WebSocket) · `policy` · `usage` · `health`.
- WS 프로토콜은 `specs/.../api.md §8` 을 SSOT 로 Kotlin `data class`/`sealed class` 로 구현.
- Python `remote/` 패키지 제거, 그 지원용 변경(`models.LLMProvider.REMOTE_AGENT`,
  `RoutingMode`, settings relay_* , pyproject agent extra/스크립트, .env.example, ssot-check)
  도 함께 되돌린다.
