package com.discordassistant.central.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T022 — 전체 NEXA 모듈 순환·경계 통합 검증.
 *
 * NEXA 5개 도메인의 **domain 레이어가 서로 순환 의존하지 않는지**(DAG)를 슬라이스 단위로 고정한다. 도메인 값
 * 객체는 단방향(actionruntime/socialmemory → participation)만 허용되고, 역방향(participation → 하류)이 생기면
 * 순환이 되어 실패한다.
 *
 * **acceptance(T022) — adapter 우회 import 가 없고 DAG 문서와 실제 그래프가 일치한다**:
 *  - NEXA domain 슬라이스가 cycle-free(이 테스트).
 *  - adapter 우회 import 금지·하류 미참조는 [NexaArchitectureTest] 의 5개 production 규칙이 강제한다(10/10 유지).
 *  - 상위 모듈 그래프 ↔ DAG 문서(module-dag.md) 일치는 `scripts/central-package-graph.py --check` 와
 *    `scripts/validate-nexa-architecture-ssot.py`(둘 다 `nexa-verify.sh docs`)가 보증한다.
 *
 * [NexaArchitectureTest] 의 10/10 카운트를 건드리지 않으려고 별도 클래스에 둔다(이 테스트는 그 카운트 밖).
 */
class NexaModuleCycleTest {
    private val central =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.discordassistant.central")

    @Test
    fun `NEXA 도메인 레이어 슬라이스는 순환이 없다`() {
        slices()
            .matching("com.discordassistant.central.(*).domain..")
            .should()
            .beFreeOfCycles()
            .check(central)
    }
}
