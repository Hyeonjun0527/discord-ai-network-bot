# central-server 도메인-우선 헥사고날 마이그레이션 계획

## 진행 현황 (live)

| Phase | 상태 | 커밋 | 비고 |
|---|---|---|---|
| 0 기반/ArchUnit 점진모드 | ✅ Phase1 에 흡수 | — | ADD-only ArchUnit 전략 채택 |
| 1 provider 파일럿 | ✅ **완료** | `provider 도메인 헥사고날 재배치(파일럿)` | 17파일 재배치·god-file 절개·빌드 그린·Kover90 |
| 2 routing + 6 포트 | ✅ **완료** | `routing 도메인 헥사고날 재배치 + 6 포트 추출` | RoutingPorts 추출·계산기 domain/service·Orchestrator 515→414 |
| 3 quota + requestlog | ✅ **완료** | `quota·requestlog 도메인 헥사고날 재배치` | usage 패키지 소멸·Entities.kt 42→33 @Entity·CQRS 유지 |
| 4 guild + channel-ai | ⏳ 대기 | — | high(동시성 보존) |
| 5~7 network 분해 | ⏳ 대기 | — | high(god-package, DTO 허브) |
| 8 discord-platform + global | ⏳ 대기 | — | high(DiscordBot/CommandService 분해) |

**확정된 패키지 컨벤션**: `<domain>/{domain/(model·service)·application/(port)·adapter/(inbound·outbound)}`.
`in`/`out` 은 Kotlin 하드키워드 ↔ ktlint `package-name` 충돌이라 **`inbound`/`outbound`** 채택.
실측 비용: import 파급이 비용의 대부분이나 스크립트로 기계화됨, 실용 절충으로 Kover 90% 유지(보일러플레이트 폭증 없음).

---

> 대상: `central-server/` (Kotlin/Spring Boot, `com.discordassistant.central.*`, 175 소스 파일 / 101 테스트 파일)
> 검증 게이트: `./gradlew build` = test + ktlint(1.4.1) + Kover(minBound 90%) + ArchUnit + Cucumber BDD/추적성 + springdoc OpenAPI 계약
> CI: `central-server-ci.yml`/`central-server-deploy.yml` 이 `central-server/**` push 에 묶임 → **모듈 디렉토리·루트 패키지 불변 유지**

## 0. 핵심 결론 (TL;DR)

**도메인-우선 패키징은 채택, 풀 헥사고날은 비채택.** 이미 god class 분해·ArchUnit 6규칙·CQRS·핵심 포트가 성숙한 시스템이다. 모든 도메인에 4레이어+repo port+entity 매퍼를 강제하면 파일이 175→300+로 불고 Kover 90% 가 보일러플레이트로 희석되어 1인 졸업프로젝트 ROI 를 초과한다.

절충: ①패키징만 도메인-우선 전면 재편 ②port 는 이미 있거나 외부 I/O 경계인 곳만 ③엔티티 anemic 유지(매퍼 회피) ④빌드 그린 유지 점진 이동 ⑤모듈/패키지/CI 경로 불변 ⑥provider+routing 파일럿 후 ROI 측정.

## 1. 실제 도메인 목록 (코드 표면 기반)

핵심 비즈니스 도메인: **provider**(+provider-policy, provider-schedule 서브), **routing**, **quota**, **requestlog**, **guild**(+channel-ai 서브), **onboarding**, **knowledge**, **multiresponse**, **preset**, **ai-network**(+quality-feedback, growth, level 서브슬라이스).

횡단 레이어(비즈니스 도메인 아님): **relay**(WS 플랫폼·와이어 프로토콜 SSOT), **discord-platform**(JDA 봇 인바운드 — 모든 도메인의 adapter/in/discord 집합), **global**(config/security/cors/filter/i18n/audit/observability + dev 테스트 하네스).

> 사용자 예시(provider/routing/quota/guild/preset/requestlog)는 출발점일 뿐. `network` 패키지(28파일)가 실제로는 knowledge/onboarding/multiresponse/preset/ai-network/channel-ai 6도메인을 평면으로 품고 있어 이를 빠뜨리면 안 된다.

## 2. 횡단 관심사 배치

- **relay**: 독립 도메인으로 유지. `ProviderSession`/`ConnectionRegistry` 는 순수 인메모리 도메인, `RelayWebSocketHandler` 는 adapter/in. `protocol/*` 는 외부 생성기(`scripts/gen_wire_contract.py` → `protocol/wire-contract.json`)가 출력 — **물리 이동 시 생성기 출력 경로 동시 변경 필수**(드리프트 가드).
- **discord-platform**: 단일 도메인 아님. 모든 도메인의 인바운드 어댑터를 모아두는 횡단 플랫폼 레이어. `CommandService`(facade god class)를 도메인별 app-service 로 점진 분해하면 `discord→network` 22 import 결합이 분산.
- **global**: 비즈니스 없음. 보안(`SecurityConfig`/`AiNetworkApiSecurityFilter`/필터들), `health`(`PoolMetrics`/`PoolHealthIndicator`), `I18n`/`Messages`, `AuditLog`(provider→global 승격), 공유 어휘(`ContentSafety`/`SupportedLanguage`). `dev`(DevController)는 dev 격리.

## 3. god-file 분해 매핑

- **Entities.kt(42 @Entity)** → guild / provider / routing+requestlog / channel-ai / onboarding / ai-network / knowledge / multiresponse / preset 의 `adapter/out` 으로 도메인별 분리.
- **Repositories.kt(42 repo)** → 동일 경계로 분리. `addXp`/`raiseLevel`(원자 UPDATE), `findByIdForUpdate`(PESSIMISTIC_WRITE), 집계 @Query 는 시그니처 보존.
- **22개 *Converter** → 각자 엔티티가 가는 도메인의 `adapter/out` 으로 동반 이동(@Convert autoApply=false 명시 지정이라 안전).
- **RequestOrchestrator.kt** → 6 포트(RoutingPolicy/ProviderProfileProvider/BlocklistChecker/QuotaChecker/ProviderSafetyChecker/UsageRecorder)를 `routing/application/port/out` 으로 추출.
- **MultiResponseService.kt / PresetRegistryService.kt** → 도메인 DTO 가 여기 정의되어 다른 파일이 import → **DTO 먼저 분리해야 패키지 분할 가능**(순환 블로커).
- **DiscordOAuth.kt** → port(application) / HttpDiscordOAuthClient(adapter/out) / state-store 3분할.
- **RateLimitStore.kt / Notifier.kt / DurableTokenRevocations.kt / WebSearch.kt** → 포트(application) + 구현(adapter/out) 분리.

(도메인별 4레이어 상세 파일 매핑은 domainBreakdown 구조화 출력 참조.)

## 4. 단계별 계획 (빌드 그린 유지 vertical-slice)

| Phase | 제목 | 위험 |
|---|---|---|
| 0 | 신규 패키지 골격 + ArchUnit 점진모드 + 커버리지 정책 결정 (코드 이동 0) | low |
| 1 | **파일럿: provider** vertical slice (Entities/Repositories 첫 절개) | medium |
| 2 | routing + 6 포트 추출 | medium |
| 3 | quota + requestlog (작은 위성, port 최소) | low |
| 4 | guild + channel-ai (정책/커스터마이징, 동시성 보존) | high |
| 5 | network 분해 1: knowledge(+WebSearch 이관) + onboarding | high |
| 6 | network 분해 2: multiresponse + preset (DTO 허브 절개) | high |
| 7 | network 분해 3: ai-network 코어 + alert + 상위 오케스트레이터 (Entities.kt 소멸) | high |
| 8 | discord-platform 분해 + global 정리 + ArchUnit 최종 강제(레거시 규칙 삭제) | high |

각 단계 종료 조건: `./gradlew build` 그린(test+ktlint+kover90+ArchUnit), 해당 도메인 BDD/단위 테스트 통과, kover ≥90%. 위험 high 단계는 추가로 Testcontainers(`-PdockerTests`)로 트랜잭션/매핑 실측.

## 5. ArchUnit 재설계

(코드 스케치는 archUnitRedesign 출력 참조.) 요지:
1. `..domain..` 은 application/adapter/infra/프레임워크 무의존(전역 한 줄).
2. `application` 은 adapter 무의존(단 `application.port.out` 인터페이스는 허용).
3. `layeredArchitecture`: adapter→application→domain 단방향.
4. `slices("..central.(*)..")` 도메인 간 순환 금지 + cross-domain 합법 결합(onboarding→routing, quota→routing port, provider↔relay 토큰, network→routing)은 `.ignoreDependency().because()` 화이트리스트.
5. Controller 는 `adapter.in` 에만 + Entity/Repository 무의존(기존 규칙 계승·강화).
6. @Service 는 `application` 에만. @Entity/JpaRepository 는 `adapter.out` 에만.

**점진 전환**: Phase0~7 은 (구)규칙 유지 + 신규 규칙을 이동 완료 도메인만 매칭(화이트리스트 확장). Phase8 에서 레거시 6규칙 삭제. `allowEmptyShould(false)` 로 빈 패키지 공허 통과 방지.

## 6. 리스크 (severity)

- **(high)** Kover 90% 희석 — 매퍼/포트 보일러플레이트. → 매퍼 제외 필터 + anemic 엔티티 + 정책 추출 시 테스트 동반.
- **(high)** Entities/Repositories god-file 분해가 전 도메인 진앙. → 도메인별 Entity+Converter 동반 이동 + Flyway 통합 테스트.
- **(high)** network god-package — DTO 허브 순환 블로커, FeatureGate 전역 결합. → DTO 먼저 분리, FeatureGate 포트화/정리.
- **(high)** 오버엔지니어링(졸업프로젝트 1인). → 실용 절충(섹션0/7).
- **(high)** 동시성/트랜잭션 회귀(REQUIRES_NEW 별빈·PESSIMISTIC_WRITE·XP 격리). → 불변식 명시·빈 병합 금지·Testcontainers 검증.
- **(medium)** relay/protocol 생성기 경로 드리프트. → 이동 보류 또는 생성기 동시 갱신.
- **(medium)** BDD/추적성/OpenAPI. → URL 경로 불변, feature/requirements.yaml 불변, step 동작보존 수정.
- **(medium)** CI 배포 경로. → 모듈/패키지/디렉토리 불변, 멀티모듈 비권장.

## 7. 사용자 결정 갈림길 (권고)

1. **전체 vs 파일럿** → provider+routing 까지만 먼저, ROI 측정 후 network 분해 재결정.
2. **모듈명/구조** → 단일 Gradle 모듈·루트 패키지·central-server 디렉토리 유지.
3. **network 분해 입도** → 코어 6(knowledge/onboarding/preset/multiresponse/guild/ai-network) + 서브 3(channel-ai→guild, quality/growth/level→ai-network).
4. **port 풀적용 vs 절충** → 절충: 기존 포트·외부 I/O 경계만 port, CRUD 도메인은 직접 JPA + adapter/out 격리.
5. **content-safety SSOT** → global/shared 통합.
6. **엔티티 rich vs anemic** → anemic 유지(전이 가드는 이미 domain enum).

## 8. 권고 (최종)

가장 큰 가치는 **Phase 1~4(provider/routing/quota/guild)**에서 회수된다. Phase 5~8(network god-package + DiscordBot/CommandService 분해)은 비용 최대·가치 한계의 '정리' 작업이다. 시간이 제한적이면 **Phase 4 에서 멈추고 나머지는 패키지 이동 없이 ArchUnit 가드만 추가**하는 것도 합리적이다. 빅뱅 금지, 단계마다 `./gradlew build` 그린, 트랜잭션 불변식 보존이 절대 원칙이다.


---

## 부록 A. ArchUnit 재설계 (코드 스케치)

목표: (구)레이어 규칙을 (신)도메인-격리 + 레이어방향 규칙으로 점진 교체. **핵심은 마이그레이션 동안 신·구 패키지가 공존하므로 규칙을 ADD-only 로 추가하고, 한 도메인이 완전히 이동한 뒤에야 그 도메인의 격리 규칙을 켜는 것.**

레이어 식별은 패키지 컨벤션으로: ..<domain>.domain.. / ..<domain>.application.. / ..<domain>.adapter.. / ..<domain>.infrastructure..

```kotlin
@AnalyzeClasses(packages = ["com.discordassistant.central"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    // (1) 모든 .domain.. 은 바깥 레이어/프레임워크 무의존 — 전역 한 줄로 신규 도메인 자동 커버.
    @ArchTest val domainIsPure: ArchRule =
        noClasses().that().resideInAPackage("..central..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..application..", "..adapter..", "..infrastructure..",
                "org.springframework..", "jakarta.persistence..", "net.dv8tion..",
                "org.springframework.web..", "com.fasterxml.jackson.."
            )
        // 예외: Jackson 직렬화 어노테이션만 쓰는 relay.protocol.Frame 은
        // .allowEmptyShould(true) 대신 .because()+ignoreDependency 로 화이트리스트.

    // (2) application 은 adapter/infrastructure(in/out 어댑터, 프레임워크 세부)에 의존 안 함.
    //     단 application/port/out 인터페이스 정의는 application 소속이라 허용.
    @ArchTest val applicationDoesNotDependOnAdapters: ArchRule =
        noClasses().that().resideInAPackage("..application..")
            .and().resideOutsideOfPackage("..application..port..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter..")

    // (3) adapter→application→domain 방향 + adapter 끼리/도메인끼리 횡단 금지(레이어드 방향).
    //     ArchUnit layeredArchitecture 로 한 번에:
    @ArchTest val layerDirection: ArchRule =
        layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..central..domain..")
            .layer("Application").definedBy("..central..application..")
            .layer("Adapter").definedBy("..central..adapter..")
            .layer("Infrastructure").definedBy("..central..infrastructure..")
            .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter", "Infrastructure")

    // (4) 도메인 간 격리: 한 도메인의 domain 은 다른 도메인의 domain 을 import 하지 않는다.
    //     예외는 명시적 공유: ..central.shared..(또는 global) 만 모두가 의존 가능.
    //     SliceRule 로 각 ..central.<x>.. 슬라이스 순환 금지:
    @ArchTest val domainsAreCycleFree: ArchRule =
        slices().matching("..central.(*)..").should().beFreeOfCycles()
        // cross-domain 허용 결합(onboarding→routing, quota/requestlog→routing port,
        // provider↔relay 토큰포트)은 .ignoreDependency(...) 화이트리스트로 명시.

    // (5) Controller 는 adapter/in 에만 + persistence(엔티티/리포지토리) 무의존(기존 규칙 계승·강화).
    @ArchTest val controllersInAdapterIn: ArchRule =
        classes().that().haveSimpleNameEndingWith("Controller")
            .should().resideInAPackage("..adapter.in..")
    @ArchTest val controllersDoNotTouchPersistenceAdapter: ArchRule =
        noClasses().that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository").orShould()
            .haveSimpleNameEndingWith("Entity")

    // (6) @Service 는 application 에만(기존 servicesNotInWebLayers 의 일반화).
    @ArchTest val servicesInApplication: ArchRule =
        classes().that().areAnnotatedWith(Service::class.java)
            .should().resideInAnyPackage("..application..")

    // (7) @Entity/JpaRepository 는 ..adapter.out.. 에만(영속 격리).
    @ArchTest val persistenceInAdapterOut: ArchRule =
        classes().that().areAnnotatedWith(jakarta.persistence.Entity::class.java)
            .or().areAssignableTo(JpaRepository::class.java)
            .should().resideInAPackage("..adapter.out..")
}
```

점진 전환 전략(빌드 그린 유지의 핵심):
- Phase 0~7 동안 (구)레이어 규칙(domainIsIndependent/persistenceStaysLow/...)을 그대로 두고, 위 신규 규칙은 **이동이 끝난 도메인 패키지만 매칭하도록** .that().resideInAnyPackage("..provider..", ...) 로 화이트리스트를 단계마다 넓힌다. 또는 layeredArchitecture 를 도메인별로 N개 인스턴스화해 완료된 도메인만 등록.
- cross-domain 합법 결합(onboarding→routing LLM, quota→routing port, provider↔relay 토큰, network→routing)은 .ignoreDependency()/.because() 로 명시적 화이트리스트 — '왜 허용되는가'를 코드에 문서화.
- Phase 8 에서 (구)규칙 6종을 삭제하고 신규 규칙만 남긴다. allowEmptyShould 는 빈 슬라이스 단계에서 false-negative 방지를 위해 신중히(빈 패키지가 규칙을 '공허하게 통과'하지 않도록 .allowEmptyShould(false) 유지).

주의: ArchUnit 1.3.0 의 layeredArchitecture + slices 는 한 도메인이 다른 도메인의 application port 를 구현하는 backward 의존(quota→routing.QuotaChecker)을 순환으로 오탐할 수 있다 — port 를 routing/application/port/out 에 두고 구현을 quota/application 에 두면 quota→routing 단방향이라 OK이지만, 슬라이스 순환 검사는 ignoreDependency 화이트리스트 필요.


---

## 부록 B. 도메인별 레이어 파일 매핑


### provider (파일럿)

- **domain**: ProviderState.kt(domain→provider/domain), ProviderAvailability.kt, ProviderModelScope.kt, ModelBurden.kt(routing 과 공유 — global/shared 또는 routing 에 둠), ModelQualityTier.kt, OverloadRisk.kt, RestHint.kt(discord→provider/domain, 한글 하드코딩 분리), ModelClassifier.kt(network→provider/domain), AvailabilityWindow.kt(provider→domain), DurableTokenService.kt(시크릿 외부화 후 domain-service), ProviderRecord(ProviderRegistrationService 에서 추출한 aggregate), JoinResult/AgentJoinToken 값객체
- **application**: ProviderRegistrationService.kt(app-service, ProviderRecord/repo port 분리 후), ProviderProtectionService.kt, ProviderSelfServiceCommands.kt(discord→application), ProviderPoolReconciliationService.kt(discord→application, cross-domain reconciliation), port out: ProviderRepository(persistence 추상화), TokenStore/TokenIssuer(TokenService 에서 추출)
- **adapter/in**: ProviderAgentSyncController.kt(web→adapter/in/web)
- **adapter/out**: TokenService.kt(인메모리 토큰 store 구현), ProviderEntity/ProviderHealthEntity/ProviderCapabilityProfileEntity/ProviderDurableRevocationEntity(Entities.kt 에서 분리), ProviderRepository 구현(Repositories.kt 분리), ProviderStateConverter/ProviderAvailabilityConverter/ModelQualityTierConverter/ModelBurdenConverter/OverloadRiskConverter, JpaDurableTokenRevocations(DurableTokenRevocations.kt split)
- **infrastructure**: central.token.* / central.durable.* @Value 를 받는 ProviderTokenProperties(시크릿 외부화)
- _주의_: 파일럿. ProviderSchedule(provider-schedule)·ContributionPolicy(provider-policy)는 provider/<sub> 하위 서브패키지로. relay.OwnerBinding/TokenVerifier 포트 소유권은 relay 에 남기고 provider 가 구현(현 구조 유지). @Scheduled enforce 는 inbound 스케줄러 어댑터로 분리 권장하나 파일럿에선 보류.

### relay

- **domain**: protocol/WireContractGenerated.kt(생성 코드 — 생성기 출력 경로 동시 변경), protocol/Frame.kt, protocol/FrameCodec→ProtocolException 부분, RemoteExceptions.kt, ProviderSession.kt(핵심 도메인 서비스), ConnectionRegistry.kt(인메모리 레지스트리, @Component 유지), OwnerBinding 값객체(TokenVerifier.kt 에서 추출), AgentConnection.kt(out port — domain 인접 application)
- **application**: TokenVerifier.kt(out port), AgentConnection.kt(out port), 인증/세션수립 유스케이스(RelayWebSocketHandler 에서 추출 권장), port out: ProviderLifecycleListener(growth 콜백 역전), DurableTokenIssuer(현 provider→relay 포트로 이동 검토)
- **adapter/in**: RelayWebSocketHandler.kt(adapter/in/web — WS)
- **adapter/out**: protocol/FrameCodec.kt(message mapper), WsAgentConnection.kt(AgentConnection 구현)
- **infrastructure**: RelayWebSocketConfig.kt
- _주의_: protocol 하위는 scripts/gen_wire_contract.py 가 출력하는 경로(central-server 밖 생성기)와 동기화 — 패키지 물리 이동 시 생성기 OUTPUT_PATH 와 DO NOT EDIT 헤더 경로를 함께 갱신해야 빌드/드리프트 가드가 깨지지 않음. RelayWebSocketHandler 의 network.AiNetworkGrowthService nullable 결합은 ProviderLifecycleListener 포트로 역전.

### routing

- **domain**: RoutingDomain.kt(값객체/enum 집합), ProviderFilterPipeline.kt(필터 정책 — Candidate/RequestContext 값객체는 domain-model 로 분리 권장), ProviderRouter.kt(HALO-GF 스코어링 domain-service), RequestWeigher.kt(domain-policy), ProviderRoutingStats.kt(인메모리 domain-service), RoutingDualVariableManager.kt, RoutingReservationManager.kt, RoutingAttemptLifecycleManager.kt, RoutingAuditLogger.kt, RoutingHedgingPolicy.kt, IdempotencyGuard.kt, RequestState.kt/RequestWeight.kt(domain→routing), ChannelAiRoutingSnapshot.kt(엔티티 의존 매퍼로 밀어낸 후 domain-model)
- **application**: RequestOrchestrator.kt(app-service, 흐름 조율만 남김), ChannelAiRoutingPolicyService.kt(network→routing/application, 정책계산은 domain-policy 추출), port out(RequestOrchestrator.kt 에서 ports/out 패키지로 추출): RoutingPolicy,ProviderProfileProvider,BlocklistChecker,QuotaChecker,ProviderSafetyChecker,UsageRecorder + no-op 기본구현, port out: InferenceTransport/ProviderSessionGateway(relay.ConnectionRegistry 추상화)
- **adapter/in**: ChannelAiRoutingPolicyController.kt(dashboard→routing/adapter/in)
- **adapter/out**: DbProviderProfileProvider.kt(provider→routing/adapter/out, ProviderProfileProvider 구현), AiRequestEntity/ChannelAiRoutingPolicyEntity(Entities.kt 분리), RequestStateConverter.kt
- _주의_: 6개 포트가 RequestOrchestrator.kt 인라인 정의 → routing/application/port/out 으로 추출(구현체는 quota/requestlog/guild/provider 에 분산). relay 직접 참조(ConnectionRegistry.sendInfer)는 InferenceTransport 포트로 역전. WebSearch/WebContentFetcher 는 routing→knowledge 로 물리 이관.

### quota

- **domain**: 쿼터 비교 규칙(QuotaService 에서 추출 가능한 domain-policy, dailyLimit=0=unlimited, UTC midnight reset)
- **application**: RateLimiter.kt(discord→quota/application), QuotaService.kt(usage→quota, routing.QuotaChecker 구현), BlocklistService.kt(provider→quota, routing.BlocklistChecker 구현), port out: RateLimitStore(인터페이스)
- **adapter/out**: RateLimitStore.kt split → InMemoryRateLimitStore/RedisRateLimitStore, BlocklistEntity/Repository(persistence 분리)
- **infrastructure**: central.ratelimit.* 프로퍼티
- _주의_: 작은 도메인. RateLimitStore 포트만 의미 있고 나머지는 port 생략 가능(실용적 절충). routing 으로의 backward 의존(QuotaChecker/BlocklistChecker port 가 routing 소유)은 유지.

### requestlog

- **domain**: burdenWeight(AnalyticsService 의 ModelBurden 가중 — ModelBurden domain-policy 로 추출), RequestState.kt(routing 과 공유)
- **application**: UsageService.kt(usage→requestlog, UsageRecorder 구현, write; XP 적립은 ai-network 위임 유지·runCatching 격리 보존), AnalyticsService.kt(read query, 내부 DTO 반환 경계 보존), port out: UsageRepository
- **adapter/out**: UsageLogEntity/ContributionLogEntity/AiRequestEntity 집계 @Query(Entities/Repositories 분리), projection 인터페이스(ChannelUsageSummary 등), RequestStateConverter(routing 과 공유 — 한쪽 소유 결정 필요)
- _주의_: UsageService 가 4 도메인(usage_log/contribution_log/ai_request/provider_health)+XP 를 가로지름 → requestlog 가 write 허브. XP 는 ai-network.AiLevelService 위임 유지(분리 금지: runCatching 격리가 rollback 방지).

### guild

- **domain**: PrivacyMode.kt(domain→guild), ModelBurden 정책(isBurdenAllowed/maxAllowedBurden 합집합 규칙을 PolicyService 에서 domain-policy 로 추출), RoleSnapshot/GuildSettings 값객체(PolicyService 내부 → 도메인 값객체 승격 후보)
- **application**: PolicyService.kt(policy→guild/application; AutoApprovePolicy port 분리), PrivacyService.kt(discord→guild), GuildRemovalCleanupService.kt(discord→guild, cross-domain 정리), port out: AutoApprovePolicy(routing 이 소비), GuildRepository/RolePolicyRepository/AllowedChannelRepository 추상화
- **adapter/in**: DashboardWriteController.kt(dashboard→guild/adapter/in)
- **adapter/out**: GuildEntity/AllowedChannelEntity/RolePolicyEntity/AiAdminRoleEntity(Entities 분리), 해당 Repository(Repositories 분리)
- _주의_: PolicyService 가 RoutingPolicy(routing 정의 port) 구현 + AutoApprovePolicy port 정의 동거 → AutoApprovePolicy 를 guild/port 로, RoutingPolicy 구현은 guild/adapter 성격. AuditLog 의존은 global.audit 로 승격.

### channel-ai (guild 산하 또는 독립)

- **domain**: ProposalStatus.kt(domain→channel-ai), approvalDecision/payloadHash 도메인 정책(ChannelAiCustomizationService 에서 추출), ChannelAiProfile 값객체 + DEFAULT_* 상수(discord 에서 이전 — SSOT 위치 교정)
- **application**: ChannelAiCustomizationService.kt(network→channel-ai, 1200줄 → 생성/승인/권한/프롬프트조립 분해 후보), ChannelAiProfileService.kt(discord→channel-ai, network 와 책임 중복 통합), port out: 4개 repo 추상화
- **adapter/in**: ChannelAiCustomizationController.kt(dashboard→channel-ai/adapter/in, DashboardActor 보안패턴 유지)
- **adapter/out**: ChannelAiEntity/AiBehaviorVersionEntity/AiChangeProposalEntity/CustomizationAuditLogEntity(Entities 분리), Repository(findByIdForUpdate PESSIMISTIC_WRITE 보존), ProposalStatusConverter.kt
- _주의_: discord.DEFAULT_CHANNEL_AI_* 상수가 channel-ai 도메인 SSOT 로 이동(현재 위치 어긋남). PESSIMISTIC_WRITE/낙관재시도 동시성은 어댑터 메서드로 보존.

### onboarding

- **domain**: InstallGuide.kt(domain→onboarding, OsGuide 동반), GuildHistoryBackfillService.kt(discord→onboarding/domain, 순수 정제 로직), OnboardingAnalyzer.kt(network→onboarding, ObjectMapper 만 — Spring 비의존 domain-service), GuildBrief 값객체(web.DiscordOAuth 에서 추출)
- **application**: GuildOnboardingService.kt(network→onboarding, LLM 트랜잭션 밖 호출 보존), OnboardingBackfillIndexer.kt(REQUIRES_NEW 별빈 유지 필수 — 합치면 프록시 우회로 무효화), ProviderConnectService(web.ProviderConnectController 흐름조율 추출 권장), port out: OnboardingLlm, DiscordOAuthClient
- **adapter/in**: ProviderConnectController.kt(web→onboarding/adapter/in), InstallPageController.kt(web→onboarding/adapter/in)
- **adapter/out**: RequestOrchestratorOnboardingLlm.kt(network→onboarding/adapter/out, OnboardingLlm 구현), HttpDiscordOAuthClient(web.DiscordOAuth split), ConnectStateStore/ProviderSelectionStore(인메모리 상태저장), GuildOnboardingConsent/Run/OptOut Entity(Entities 분리)
- **infrastructure**: central.connect.discord-client-id/secret @Value
- _주의_: DiscordOAuth.kt 3분할(port/client/state-store). RequestOrchestratorOnboardingLlm 은 onboarding→routing 의존(현 ArchUnit 허용, port/adapter 교과서 사례).

### knowledge

- **domain**: Knowledge*Status(4종)/EmbeddingJobStatus/RetrievalPolicyStatus.kt(domain→knowledge), KnowledgeSafety.kt(network→knowledge/domain, 단 ContentSafety 와 SSOT 정리 필요), SSRF 가드(KnowledgeIngestionService 의 validateUri/isBlockedAddressLiteral → domain-policy 추출), WebResultReranker/WebSearchPromptBuilder/WebResult/WebAugmentation(routing.WebSearch 에서 추출), UrlSafety/HtmlText(routing.WebContentFetcher 에서 추출, 순수 함수)
- **application**: KnowledgeIngestionService.kt/KnowledgeIndexingService.kt/KnowledgeSearchService.kt(network→knowledge), port out: WebSearchAugmenter(routing.WebSearch split), 7개 repo 추상화
- **adapter/in**: KnowledgeIngestionController.kt(dashboard→knowledge/adapter/in)
- **adapter/out**: SearxngWebSearch(routing→knowledge/adapter/out, java.net.http), WebContentFetcher.kt(routing→knowledge/adapter/out), Knowledge*Entity/EmbeddingIndexJobEntity/RetrievalPolicyEntity(Entities 분리), 6개 Knowledge Converter
- **infrastructure**: SearXNG 엔드포인트 프로퍼티
- _주의_: routing 의 WebSearch/WebContentFetcher 물리 이관이 핵심. KnowledgeSafety(network) vs ContentSafety(domain) 중복 — content-safety SSOT 를 global/shared 또는 knowledge 한 곳으로 통합 결정 필요.

### multiresponse

- **domain**: CandidateStatus/SynthesisStatus/MultiResponseRunStatus/MultiResponseMode/FanoutLoadRisk.kt(domain→multiresponse), selectProviders/fan-out 정책(MultiResponseService 에서 domain-policy 추출 후보)
- **application**: MultiResponseService.kt(network→multiresponse, write, 30+ DTO 정의 허브 → DTO 분리 필수), MultiResponseReportingService.kt(read CQRS), port out: ProviderSafetyChecker 사용, relay 라이브용량 조회 port, repo 추상화
- **adapter/in**: MultiResponseController.kt(dashboard→multiresponse/adapter/in, 교차 DTO 추출)
- **adapter/out**: MultiResponsePolicy/Run/Candidate/Synthesis Entity(Entities 분리), 4개 Converter
- _주의_: MultiResponseService.kt/PresetRegistryService.kt 에 도메인 DTO 가 정의되어 다른 파일이 import → DTO 를 도메인별 모듈로 먼저 분리하지 않으면 패키지 분할이 막힘(블로커). relay.ConnectionRegistry nullable 결합은 LiveCapacityQuery port 로 역전.

### preset

- **domain**: Preset*Status/PublishedPresetStatus/PresetImportStatus/PresetReportStatus.kt(domain→preset), PresetModerationRules.kt(domain-policy), payloadHash 구성(ChannelAiCustomization 과 동일 유지 필수)
- **application**: PresetRegistryService.kt(network→preset, write, 11 repo 광역, 모든 Preset* DTO 정의), PresetCatalogQueryService.kt(read CQRS), port out: repo 추상화
- **adapter/in**: PresetRegistryController.kt(dashboard→preset/adapter/in)
- **adapter/out**: AiPreset/Revision/Published/Import/Reaction/Report Entity(Entities 분리), 4개 Converter
- _주의_: 가장 광역(11 repo). channel/routing/proposal 에 강결합 — preset import 가 channel-ai 제안을 생성. SECRET_PATTERN 이 write/read 서비스에 중복(동기화 주석 존재) → 공유 위치로.

### ai-network

- **domain**: AiLevelFormula.kt(순수 함수 object — 가장 깨끗한 분리), AiNetworkLimits.kt(상수), milestone/growthAction/levelTitle 추천(AiNetworkGrowthService 인라인 → domain-policy 추출), guardFanout/executionPlan(ProviderSafetyService 도메인 정책 추출), Severity(alert.Notifier 의 enum), edge-trigger 상태머신(PoolAlertMonitor 추출)
- **application**: AiLevelService.kt(REQUIRES_NEW XP 적립), AiNetworkFoundation/Growth/Map/LaunchChecklist Service, AiNetworkReadinessService(입력을 도메인모델로 바꾸면 domain-service 승격), AiNetworkDashboardQueryService(CQRS read, web DTO 결합 정리), AiQualityFeedbackService(quality-feedback 서브), ProviderSafetyService(routing.ProviderSafetyChecker 구현), PoolAlertMonitor.kt(alert→ai-network/application), Notifier 포트(alert), port out: Notifier, PoolHealthQuery(relay 역전), repo 추상화
- **adapter/in**: AiNetworkDashboardController/FeatureController/GrowthController(dashboard→ai-network), AiQualityFeedbackController(또는 multiresponse), MetricsApiController(relay 메트릭 read), ProviderSafetyController, DashboardController(개요 — cross-domain admin 어댑터)
- **adapter/out**: DiscordWebhookNotifier.kt/LoggingNotifier(alert→ai-network/adapter/out/message), AiNetworkProfile/Event/Overview Entity,AiFeedbackEntity,ProviderCapabilityProfileEntity(Entities 분리), FeedbackStatusConverter
- **infrastructure**: AiNetworkFeatureGate.kt(@Value 기능플래그 — application 경계 정책 포트로 추상화 검토)
- _주의_: network 의 '코어' + 다수 상위 오케스트레이터. AiNetworkFeatureGate 가 거의 모든 network 서비스의 기본 인자(전역 횡단). dashboard→network 양방향(ReadinessService 가 web DTO 입력) 정리 필요. DashboardController 는 5패키지 결합 admin 개요 → ai-network 또는 별도 admin 슬라이스.

### discord-platform (횡단 인바운드)

- **domain**: RestHint(provider 로 이동), domain enum 은 각 도메인 소유
- **application**: CommandService.kt(facade god class → 도메인별 app-service 로 점진 분해: AskUseCase/KnowledgeCommandService/PresetCommandService/...), Reply/CommandContext 공용 값타입
- **adapter/in**: DiscordBot.kt(god class → DiscordBootstrap/디스패치/설정패널/온보딩/모달/렌더링 분해), SlashCommandCatalog.kt, CommandLoc.kt, EmbedFactory.kt, MenuFactory.kt, Replies.kt, Pagination.kt
- **infrastructure**: DiscordGatewayStatus.kt, GatewayIntentPolicy.kt, CommandMetrics.kt
- _주의_: 단일 비즈니스 도메인 아님 — 모든 도메인의 adapter/in/discord 를 모아두는 횡단 플랫폼. CommandService 를 도메인별로 쪼개면 discord→network 22 import 결합이 분산. BotGuildLister 는 out-port 성격(웹/어드민이 봇 길드 조회) → 별도 추출.

### global (횡단 인프라/공유)

- **domain**: ContentSafety.kt(공유 어휘 — KnowledgeSafety 와 SSOT 통합), SupportedLanguage.kt(전역 i18n enum), AiNetworkLimits(상수 — ai-network 와 공유)
- **application**: AuditLog.kt(provider→global/audit, 횡단 감사), I18n.kt/Messages.kt(런타임 문구 facade — DiscordLocale 부분만 discord 어댑터로)
- **adapter/in**: AiNetworkApiSecurityFilter.kt(web→global/adapter/in 보안 필터), DashboardActor.kt, MeController.kt, DevController.kt(dev 격리 — @ConditionalOnProperty 유지)
- **infrastructure**: SecurityConfig.kt, CorsConfig.kt, SecurityHeadersFilter.kt, RequestIdFilter.kt, PoolMetrics.kt(health), PoolHealthIndicator.kt(health)
- _주의_: 보안 경로 정책 SSOT 가 SecurityConfig + AiNetworkApiSecurityFilter 두 곳에 분산(드리프트 위험) → 일원화 권고. content-safety 가 ContentSafety(domain)+KnowledgeSafety(network) 두 곳 → 통합. Entities.kt/Repositories.kt 의 잔여(분류 안 된 것)는 도메인 분할 완료 시 소멸.


---

## 부록 C. 실제 도메인 목록 (코드 표면 기반)


- **provider** (~30파일): 로컬 LLM 프로바이더의 풀 참여 라이프사이클(등록/승인/거절/제거), 인증 토큰(일회용+durable HMAC) 발급·검증·폐기, 능력 분류, 보호(pause/resume/leave), 자동 동기화. 가장 깨끗하게 매핑되는 파일럿 후보(순수 도메인 정책 AvailabilityWindow·ModelClassifier·RestHint·OverloadRisk + 명확한 상태머신 ProviderState).
  - 출처 패키지: provider, domain(ProviderState,ProviderAvailability,ProviderModelScope,ModelBurden,ModelQualityTier,OverloadRisk,RestHint,InstallGuide), persistence(Provider*Entity/Repository + 6 Converter), discord(ProviderSelfServiceCommands,ProviderPoolReconciliationService,RestHint), network(ModelClassifier), web(ProviderAgentSyncController)

- **provider-policy** (~3파일): 프로바이더 기여 정책(모델별 부담수준/일일한도/허용역할 범위) 서브도메인. provider 와 분리 가능하나 졸업프로젝트 규모상 provider 하위 서브패키지로 두는 것을 권장.
  - 출처 패키지: provider(ContributionPolicyService), domain(ProviderModelScope), persistence(ProviderContributionPolicyEntity/Repository)

- **relay** (~12파일): 에이전트(데스크톱 앱) WebSocket 릴레이 + 와이어 프로토콜 SSOT. ProviderSession/ConnectionRegistry 가 순수 인메모리 도메인, RelayWebSocketHandler 가 인바운드 어댑터. protocol 하위는 외부 생성기(scripts/gen_wire_contract.py)와 동기화되는 계약이라 물리 이동 시 생성기 출력 경로도 함께 갱신해야 함.
  - 출처 패키지: relay, relay/protocol(생성 코드 — 이동 주의)

- **routing** (~20파일): /ask 요청을 풀로 공정·SLO 기반 라우팅하는 핵심 엔진(HALO-GF 스코어링·예약·쌍대변수·통계·감사). 순수 도메인 서비스가 대부분이라 헥사고날 적합도 높음. 6개 아웃바운드 포트가 RequestOrchestrator.kt 안에 인라인 정의됨(분리 대상).
  - 출처 패키지: routing(WebSearch/WebContentFetcher 제외), domain(RequestState,RequestWeight,ModelBurden,ModelQualityTier,ChannelAiRoutingSnapshot), network(ChannelAiRoutingPolicyService,ChannelAiRoutingSnapshot), persistence(AiRequestEntity,ChannelAiRoutingPolicyEntity,RequestStateConverter), provider(DbProviderProfileProvider,BlocklistService→port 구현)

- **quota** (~5파일): 요청 제어/공정성: rate limit(분당), 일일 쿼터 검사, 사용자 차단. routing 의 QuotaChecker/BlocklistChecker 포트 구현. 작은 도메인이라 port 추상화 최소화 권장.
  - 출처 패키지: discord(RateLimiter,RateLimitStore), provider(BlocklistService), usage(QuotaService), persistence(BlocklistEntity/Repository)

- **requestlog** (~6파일): 사용량/기여/요청 로그 기록(write)과 분석 조회(read). UsageRecorder 포트 구현. CQRS 성격(UsageService write / AnalyticsService read).
  - 출처 패키지: usage(UsageService,AnalyticsService), persistence(UsageLogEntity,ContributionLogEntity,AiRequestEntity 집계,projection 인터페이스), domain(RequestState,ModelBurden)

- **guild** (~12파일): 길드(서버)별 LLM 사용 정책(허용채널·역할정책·자동승인·기본모델/언어·환영메시지), 프라이버시 모드, 채널 AI 표시 프로필, 길드 제거 정리. PolicyService 가 핵심.
  - 출처 패키지: policy(PolicyService→split AutoApprovePolicy port), discord(ChannelAiProfileService,PrivacyService,GuildRemovalCleanupService), domain(PrivacyMode,ProposalStatus,ModelBurden,InstallGuide?), persistence(GuildEntity,AllowedChannelEntity,RolePolicyEntity,AiAdminRoleEntity,GuildOnboarding* 일부), dashboard(DashboardWriteController)

- **channel-ai** (~8파일): 채널별 AI 커스터마이징/승인 워크플로(마법사 생성·롤백·자유지침·승인/거절·역할권한·behavior 채번 동시성·프롬프트 프리뷰). ChannelAiCustomizationService(1200줄)가 핵심. guild 산하로 둘지 독립 도메인으로 둘지는 openDecision.
  - 출처 패키지: network(ChannelAiCustomizationService), discord(ChannelAiProfileService 일부), persistence(ChannelAiEntity,AiBehaviorVersionEntity,AiChangeProposalEntity,CustomizationAuditLogEntity,ProposalStatusConverter), dashboard(ChannelAiCustomizationController), domain(ProposalStatus)

- **onboarding** (~12파일): 서버 AI 자동 온보딩(consent→draft→PENDING 제안→RAG 백필 색인→run 추적), LLM 분석(트랜잭션 밖), 웹 OAuth '토큰 받기' 연동, 설치 랜딩. discord/web/network 에 흩어진 온보딩 흐름.
  - 출처 패키지: network(GuildOnboardingService,OnboardingAnalyzer,OnboardingBackfillIndexer,RequestOrchestratorOnboardingLlm), discord(GuildHistoryBackfillService), web(DiscordOAuth,ProviderConnectController,InstallPageController), persistence(GuildOnboardingConsent/Run/OptOut Entity), domain(InstallGuide)

- **knowledge** (~18파일): 채널별 RAG 지식공간/소스/색인 잡/검색/컨텍스트/평가 + SSRF 가드 + 웹검색 증강(routing 에서 이관). 순수 검색 정책(BM25 재랭킹·스코어링) 추출 후보 풍부.
  - 출처 패키지: network(KnowledgeIngestion/Search/IndexingService,KnowledgeSafety), routing(WebSearch,WebContentFetcher → knowledge 로 이관), persistence(Knowledge*Entity,EmbeddingIndexJobEntity,RetrievalPolicyEntity + 6 Converter), dashboard(KnowledgeIngestionController), domain(Knowledge*Status,EmbeddingJobStatus,RetrievalPolicyStatus)

- **multiresponse** (~16파일): 다중응답(팬아웃) 정책/run 라이프사이클/후보·합성/통계/추천/운영요약. MultiResponseService(1300줄, 30+ DTO 정의 허브)가 write, MultiResponseReportingService 가 read(CQRS).
  - 출처 패키지: network(MultiResponseService,MultiResponseReportingService), persistence(MultiResponsePolicy/Run/Candidate/Synthesis Entity + 4 Converter), dashboard(MultiResponseController), domain(CandidateStatus,SynthesisStatus,MultiResponseRunStatus,MultiResponseMode,FanoutLoadRisk,FeedbackStatus)

- **preset** (~16파일): 프리셋 레지스트리 CRUD/카탈로그/발행/가져오기/좋아요·신고·모더레이션. PresetRegistryService(11 repo, 가장 광역) write + PresetCatalogQueryService read.
  - 출처 패키지: network(PresetRegistryService,PresetCatalogQueryService), persistence(AiPreset/Revision/Published/Import/Reaction/Report Entity + 4 Converter), dashboard(PresetRegistryController), domain(Preset*Status,PublishedPresetStatus,PresetImportStatus,PresetReportStatus,PresetModerationRules)

- **ai-network** (~24파일): 서버 AI 레벨/경험치·성장·대시보드 read model·레디니스 체크리스트·네트워크 지도·기능 게이트·프로바이더 능력 foundation·과부하 안전·품질 피드백. network 패키지의 '코어' 슬라이스이자 다수 도메인을 가로지르는 상위 오케스트레이터들의 집합.
  - 출처 패키지: network(AiLevel*,AiNetworkFoundation/Growth/Map/Readiness/LaunchChecklist/DashboardQuery Service,AiNetworkFeatureGate,AiNetworkLimits,ProviderSafetyService,AiQualityFeedbackService), alert(전체 — Pool 헬스 알림), persistence(AiNetworkProfile/Event/Overview Entity,AiFeedbackEntity,ProviderCapabilityProfileEntity,FeedbackStatusConverter), dashboard(AiNetwork*Controller,AiQualityFeedbackController,MetricsApiController,ProviderSafetyController,DashboardController-개요부분), domain(FeedbackStatus)

- **discord-platform(횡단 인바운드 플랫폼)** (~13파일): JDA 봇 어댑터 — 모든 도메인의 인바운드 진입점. DiscordBot(god class)·CommandService(facade god class)·EmbedFactory/MenuFactory/SlashCommandCatalog/CommandLoc 등 표현 자원. 단일 비즈니스 도메인이 아니라 모든 도메인의 adapter/in/discord 를 모아두는 횡단 플랫폼 레이어로 유지하되, CommandService 를 도메인별 app-service 로 점진 분해.
  - 출처 패키지: discord(DiscordBot,CommandService,SlashCommandCatalog,CommandLoc,EmbedFactory,MenuFactory,Replies,Pagination,DiscordGatewayStatus,GatewayIntentPolicy,CommandMetrics)

- **global(횡단 인프라/공유)** (~15파일): config/security/cors/filter/i18n/audit/observability 횡단 관심사. 비즈니스 도메인 없음. web 의 보안 인프라, health/PoolMetrics, discord 의 I18n/Messages, provider 의 AuditLog, domain 의 ContentSafety/SupportedLanguage 등 공유 어휘.
  - 출처 패키지: web(SecurityConfig,CorsConfig,SecurityHeadersFilter,RequestIdFilter,AiNetworkApiSecurityFilter,DashboardActor,MeController), health(PoolMetrics,PoolHealthIndicator), discord(I18n,Messages,CommandMetrics), provider(AuditLog), domain(ContentSafety,SupportedLanguage,AiNetworkLimits), dev(DevController — 테스트 하네스, dev 격리)
