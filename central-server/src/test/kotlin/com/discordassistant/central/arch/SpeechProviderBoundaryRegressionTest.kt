package com.discordassistant.central.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T017 — provider-agent 경계 회귀 테스트(central 측).
 *
 * NEXA social path(speech 발화 생성)가 **provider-agent GLM/WS 프로토콜을 변경하거나 우회 호출하지 않았는지**
 * 회귀로 고정한다(ADR 0006 anti-corruption). 발화 생성의 외부 호출은 오직 routing 의 provider-neutral
 * [com.discordassistant.central.routing.application.CloudLlm] 포트(central 이 z.ai 를 직접 호출)만 거치고,
 * provider-agent WS relay 프로토콜(`..relay.protocol..`)·JDA·glm/zai SDK 타입에는 의존하지 않는다.
 *
 * wire-contract 생성물 diff 없음은 `scripts/gen_wire_contract.py --check`(`make contract`)가 별도로 보증한다 —
 * 이 테스트는 central 코드 그래프에서 speech↛provider-agent 경계만 고정한다(acceptance: 우회 import 0).
 *
 * 이 회귀 규칙들은 NexaArchitectureTest 의 speech 규칙과 의도가 같으나, T017 의 "WS relay 프로토콜 우회 금지" 를
 * 명시적으로 추가 고정한다(speech 가 relay.protocol 프레임 타입을 직접 참조하면 실패).
 */
class SpeechProviderBoundaryRegressionTest {
    private val central =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.discordassistant.central")

    @Test
    fun `speech 는 provider-agent WS relay 프로토콜 프레임에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAnyPackage("..central.speech..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..central.relay..")
            .allowEmptyShould(true)
            .check(central)
    }

    @Test
    fun `speech 는 JDA·glm·zai SDK 타입에 의존하지 않는다(provider-agent 백엔드 우회 금지)`() {
        noClasses()
            .that()
            .resideInAnyPackage("..central.speech..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.dv8tion..")
            .orShould()
            .dependOnClassesThat()
            .haveNameMatching(".*([Gg]lm|[Zz]ai).*")
            .allowEmptyShould(true)
            .check(central)
    }

    @Test
    fun `speech 의 유일한 외부 LLM 경로는 routing CloudLlm 포트다`() {
        // speech adapter 가 routing.application.CloudLlm 을 쓰는 것은 허용(anti-corruption 포트) — 이 테스트는
        // 그 외 외부 백엔드(provider/relay) 직접 의존이 없음을 위 두 규칙으로 보장함을 문서화하는 스모크다.
        noClasses()
            .that()
            .resideInAnyPackage("..central.speech..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..central.provider.adapter..")
            .allowEmptyShould(true)
            .check(central)
    }
}
