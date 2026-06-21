# central-server/AGENTS.md — Kotlin/Spring 중앙 서버 규칙

이 파일은 `central-server/**` 하위 변경에 적용되는 전용 규칙이다. 루트 [../AGENTS.md](../AGENTS.md)를
먼저 따르고, 여기서는 Kotlin/Spring central-server의 계층·테스트·마이그레이션 규칙만 추가한다.

## 1. 책임과 경계

`central-server`는 Provider Pool 중앙 서버와 Discord 봇을 소유한다. Discord 수신/실행, provider
라우팅, guild/channel 정책, dashboard/API, 관측성, Flyway 스키마가 여기 책임이다. Provider Agent의
로컬 추론·데스크톱 UI 구현은 [../provider-agent/](../provider-agent/)와 [../prototypes/desktop/](../prototypes/desktop/)가
소유하므로 central-server에서 몰래 우회 구현하지 않는다.

## 2. 헥사고날 패키지 규칙

현재 전환된 도메인은 대체로 아래 방향을 따른다.

```text
<domain>/domain        순수 Kotlin 값·규칙·도메인 서비스
<domain>/application   use case, port, transaction boundary
<domain>/adapter       inbound web/discord, outbound persistence/external API
platform/discord       JDA 수신 정규화·Discord 실행 adapter
global                 cross-cutting config/security/i18n/observability
relay/protocol         provider-agent wire frame 계약
```

금지:

- `domain` 레이어에서 `org.springframework.*`, `jakarta.persistence.*`, `net.dv8tion.*`를 import하지 않는다.
- `domain` 레이어에서 `application`/`adapter`에 의존하지 않는다.
- Controller가 `adapter.outbound.persistence`의 Entity/Repository를 직접 만지지 않는다.
- `application` 레이어가 outbound adapter 구현체에 직접 의존하지 않는다. port/interface로 넘긴다.
- 새 NEXA 도메인(`conversation`, `participation`, `socialmemory`, `actionruntime`, `speech`)도 같은 규칙으로 시작한다.

허용되는 예외는 기존 ArchUnit 기준선에 이미 명시된 경우만이다. 새 예외가 필요하면 먼저 ADR 또는 NEXA
ExecPlan에 이유·범위·회수 조건을 남기고 ArchUnit을 함께 갱신한다.

## 3. ArchUnit과 구조 테스트

구조 규칙은 [src/test/kotlin/com/discordassistant/central/arch/ArchitectureTest.kt](src/test/kotlin/com/discordassistant/central/arch/ArchitectureTest.kt)가
소유한다. 새 패키지·도메인 전환을 추가하면 다음을 함께 확인한다.

- 이동 완료 `domain` 패키지는 `migratedDomainsArePure` 또는 해당 도메인 순수성 규칙에 포함한다.
- 새 `@Entity`는 `<domain>/adapter/outbound/persistence` 아래에 둔다.
- 새 Controller는 `<domain>/adapter/inbound/web` 또는 명시된 dev harness에만 둔다.
- 구조를 느슨하게 만들기보다 테스트 fixture 또는 adapter seam을 만든다.

## 4. Flyway·영속화

- 스키마는 `src/main/resources/db/migration/`의 Flyway migration이 소유한다.
- `ddl-auto`로 스키마를 생성·수정하지 않는다.
- 이미 적용된 migration은 수정하지 말고 새 `V__` migration을 추가한다.
- JPA Entity는 persistence adapter에만 둔다. 도메인 모델과 Entity를 같은 타입으로 쓰지 않는다.
- DB 변경은 repository/service 테스트 또는 `-PdockerTests` 통합 테스트로 검증 범위를 맞춘다.

## 5. JDA 격리

- JDA 타입(`net.dv8tion.*`)은 `platform/discord` 또는 Discord inbound adapter 경계에 가둔다.
- application/domain 서비스에는 JDA 객체를 넘기지 말고, 필요한 값만 자체 DTO/context로 정규화한다.
- Discord 렌더링·interaction glue는 얇게 유지하고, 정책·라우팅·권한 판단은 테스트 가능한 서비스로 둔다.
- 실제 Discord `LIVE` 발화·운영 토큰 사용은 인간 승인 게이트다.

## 6. 시간·랜덤·외부 호출

- 시간은 `Instant.now()`를 직접 흩뿌리지 말고 `Clock`을 주입한다. 테스트는 `Clock.fixed(...)`를 쓴다.
- 랜덤·샘플링·라우팅 선택은 seed 또는 전략 객체를 주입해 재현 가능하게 만든다.
- 외부 모델/HTTP/Discord 호출은 port/adapter 뒤에 두고, 단위 테스트는 fake/stub으로 검증한다.
- NEXA social policy 경로는 같은 scene/state/model/seed에서 재생 가능한 결정을 목표로 한다.

## 7. 테스트 기준

기본 검증:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
central-server/gradlew -p central-server build --no-daemon --console=plain
```

Docker 필요 BDD/Testcontainers 검증:

```bash
central-server/gradlew -p central-server test -PdockerTests --no-daemon --console=plain
```

테스트 선택 기준:

- 순수 도메인 규칙 → plain unit test.
- JPA repository/service → `@DataJpaTest` 또는 필요한 범위의 Spring test.
- transaction/proxy/운영 wiring 의미 → `@SpringBootTest`.
- Provider Pool 핵심 흐름 → Cucumber BDD + Testcontainers(`-PdockerTests`).
- wire protocol 변경 → 루트에서 `make contract`.
- OpenAPI/API surface 변경 → `/v3/api-docs` 관련 테스트와 문서 drift 확인.

Kover/ktlint/ArchUnit은 `build` 게이트에 포함된다. 실패를 숨기거나 coverage 제외를 넓히지 않는다.
