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
    // 도메인(순수 값/열거)은 어떤 바깥 레이어에도 의존하지 않는다.
    @ArchTest
    val domainIsIndependent: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..central.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..central.web..",
                "..central.dashboard..",
                "..central.discord..",
                "..central.persistence..",
                "..central.relay..",
                "..central.routing..",
                "..central.policy..",
                "..central.usage..",
                "..central.provider..",
                "..central.alert..",
                "..central.health..",
                "..central.dev..",
            )

    // 영속화 계층은 웹/디스코드/대시보드/dev 를 참조하지 않는다(역방향 의존 금지).
    @ArchTest
    val persistenceStaysLow: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..central.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..central.web..",
                "..central.dashboard..",
                "..central.discord..",
                "..central.dev..",
            )

    // 디스코드 어댑터는 웹/대시보드/dev 컨트롤러 계층에 의존하지 않는다(서비스 경유).
    @ArchTest
    val discordDoesNotTouchWeb: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..central.discord..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..central.web..",
                "..central.dashboard..",
                "..central.dev..",
            )

    // 컨트롤러는 웹 계층(web/dashboard/dev)에만 존재한다.
    @ArchTest
    val controllersLiveInWebLayers: ArchRule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAnyPackage(
                "..central.web..",
                "..central.dashboard..",
                "..central.dev..",
            )

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
}
