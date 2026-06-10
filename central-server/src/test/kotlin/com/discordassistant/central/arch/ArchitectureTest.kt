package com.discordassistant.central.arch

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * 아키텍처 규칙 보호(차수 18). Cucumber 가 "기능"을 지키면 ArchUnit 은 "코드 구조"를 지킨다.
 * 레이어 의존 방향을 깨는 변경(도메인이 웹/DB 를 참조 등)을 컴파일은 통과해도 테스트로 차단한다.
 */
@AnalyzeClasses(
    packages = ["com.discordassistant.central"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureTest {
    // 공유 커널(shared: ModelBurden/ModelQualityTier/RequestState/ContentSafety/SupportedLanguage/
    // ResponseMode)은 순수 Kotlin 값/열거다 — 어떤 도메인·어댑터·애플리케이션·프레임워크에도 의존하지 않는다.
    @ArchTest
    val sharedKernelIsPure: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..central.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..central.application..",
                "..central.adapter..",
                "..central.platform..",
                "org.springframework..",
                "jakarta.persistence..",
                "net.dv8tion..",
            )

    // (구) persistenceStaysLow 규칙은 제거됨 — persistence 패키지가 도메인별 adapter.outbound.persistence
    // 로 전부 이관되어 비었다(Phase 1~7). 영속 위치는 migratedPersistenceInAdapterOutbound 가 강제한다.

    // 컨트롤러는 도메인-우선 헥사고날 인바운드 웹 어댑터(..adapter.inbound.web..) 또는 dev 에만 존재한다.
    // (모든 도메인 컨트롤러가 <domain>.adapter.inbound.web 으로 이관 완료. dev 는 테스트 하네스.)
    @ArchTest
    val controllersLiveInWebLayers: ArchRule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAnyPackage(
                "..adapter.inbound.web..",
                "..central.dev..",
            )

    // 컨트롤러(인바운드 웹 어댑터)는 영속 어댑터(엔티티/리포지토리)에 **전혀** 의존하지 않는다 —
    // 서비스(application)가 엔티티 대신 DTO/view 를 반환한다(감사 2026-06-03 C).
    @ArchTest
    val controllersDoNotTouchPersistence: ArchRule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.outbound.persistence..")

    @ArchTest
    val controllersDoNotInjectRepositories: ArchRule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")

    // 서비스(@Service)는 웹 계층(web/dashboard/dev) 패키지에 두지 않는다(도메인/집계 로직이 웹에 새지 않게).
    @ArchTest
    val servicesNotInWebLayers: ArchRule =
        noClasses()
            .that()
            .areAnnotatedWith(org.springframework.stereotype.Service::class.java)
            .should()
            .resideInAnyPackage(
                "..central.web..",
                "..central.dashboard..",
                "..central.dev..",
            )

    // ── 도메인-우선 헥사고날 점진 전환(ADD-only) ─────────────────────────────────
    // 이동이 끝난 도메인의 domain 레이어는 순수 Kotlin 규칙만 가진다 — application/adapter/infra 와
    // Spring/JPA/JDA 같은 프레임워크에 의존하지 않는다. 도메인이 이동할 때마다 이 화이트리스트를 넓힌다.
    // (provider 파일럿: ..central.provider.domain.. = ProviderState/AvailabilityWindow)
    @ArchTest
    val migratedDomainsArePure: ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage(
                "..central.provider.domain..",
                "..central.guild.domain..",
                "..central.channelai.domain..",
                "..central.knowledge.domain..",
                "..central.onboarding.domain..",
                "..central.multiresponse.domain..",
                "..central.preset.domain..",
                "..central.ainetwork.domain..",
                "..central.licensing.domain..",
            ).should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..application..",
                "..adapter..",
                "..infrastructure..",
                "org.springframework..",
                "jakarta.persistence..",
                "net.dv8tion..",
            )

    // 영속 어댑터: 이동 완료 도메인(provider/quota/requestlog)의 @Entity 는 adapter.outbound.persistence 에만 둔다.
    @ArchTest
    val migratedPersistenceInAdapterOutbound: ArchRule =
        classes()
            .that()
            .resideInAnyPackage(
                "..central.provider..",
                "..central.quota..",
                "..central.requestlog..",
                "..central.guild..",
                "..central.channelai..",
                "..central.knowledge..",
                "..central.onboarding..",
                "..central.multiresponse..",
                "..central.preset..",
                "..central.ainetwork..",
                "..central.licensing..",
            ).and()
            .areAnnotatedWith(jakarta.persistence.Entity::class.java)
            .should()
            .resideInAPackage("..adapter.outbound.persistence..")

    // routing 도메인(모델 + 계산기 도메인서비스)은 인프라(영속/웹/메시지/Discord)와 application/adapter 에
    // 의존하지 않는다. 실용 절충: 도메인서비스는 @Component(DI)만 허용하고 인프라 결합은 금지한다
    // (계산기 10종은 인프라 import 0 — 순수 로직 + DI). RequestOrchestrator(application)가 포트로 위임한다.
    @ArchTest
    val routingDomainHasNoInfrastructure: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..central.routing.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..central.routing.application..",
                "..central.routing.adapter..",
                "jakarta.persistence..",
                "org.springframework.web..",
                "org.springframework.data..",
                "net.dv8tion..",
            )

    // routing application 은 adapter(아웃바운드 구현체)에 의존하지 않는다 — 포트(application.port)로만.
    @ArchTest
    val routingApplicationDoesNotDependOnAdapter: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..central.routing.application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..central.routing.adapter..")
}
