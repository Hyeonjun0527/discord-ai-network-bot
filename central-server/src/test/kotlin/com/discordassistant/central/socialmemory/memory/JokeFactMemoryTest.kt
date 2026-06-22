package com.discordassistant.central.socialmemory.memory

import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.model.extraction.StatementModality
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialmemory.domain.service.consolidation.CandidatePromotionRule
import com.discordassistant.central.socialmemory.domain.service.consolidation.PromotionOutcome
import com.discordassistant.central.socialmemory.domain.service.consolidation.PromotionReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant

/**
 * NEXA-P07-T022 농담과 사실 구분. 합성 fixture(test-fixtures/nexa/memory/joke-vs-fact.yaml, 실제 사용자 데이터
 * 아님)를 로드해 규칙(T016)을 case 별로 검증한다 — 실제 외부 API·운영 DB 호출 없음.
 *
 * acceptance: 농담/부정/인용/가정을 단일 사실로 승격하지 않는다("나 일베 아님" 같은 맥락성 발화를 민감 사실로
 * 영구 저장하지 않음). 민감 추론(정치·종교 등)은 단정이라도 폐기한다(observable-state-policy 금지 목록).
 */
class JokeFactMemoryTest {
    private val scope = VisibilityScope.Guild("g-joke-fixture")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private data class Case(
        val id: String,
        val subject: String,
        val predicate: String,
        val obj: String,
        val modality: StatementModality,
        val sensitive: Boolean,
        val expectOutcome: PromotionOutcome,
        val expectReason: PromotionReason,
    )

    @TestFactory
    fun `농담 부정 인용 가정 민감추론은 단일 사실로 승격하지 않는다`(): List<DynamicTest> =
        loadCases().map { case ->
            DynamicTest.dynamicTest(case.id) {
                val candidate =
                    MemoryCandidate(
                        kind = CandidateKind.TEMPORAL_FACT,
                        visibility = scope,
                        subject = case.subject,
                        predicate = case.predicate,
                        obj = case.obj,
                        source = MemorySource(setOf("scene-${case.id}"), 1, true, now),
                        modality = case.modality,
                        sensitive = case.sensitive,
                    )
                val decision = CandidatePromotionRule.decide(candidate)
                assertEquals(case.expectOutcome, decision.outcome, "${case.id} outcome")
                assertEquals(case.expectReason, decision.reason, "${case.id} reason")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun loadCases(): List<Case> {
        val file = File("../test-fixtures/nexa/memory/joke-vs-fact.yaml")
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        check(root["schemaVersion"] == "nexa.memory-fixture.v1") { "예상치 못한 memory fixture 스키마 버전" }
        return (root["cases"] as List<Map<String, Any?>>).map { c ->
            Case(
                id = c["id"].toString(),
                subject = c["subject"].toString(),
                predicate = c["predicate"].toString(),
                obj = c["object"].toString(),
                modality = StatementModality.valueOf(c["modality"].toString()),
                sensitive = c["sensitive"] as Boolean,
                expectOutcome = PromotionOutcome.valueOf(c["expectOutcome"].toString()),
                expectReason = PromotionReason.valueOf(c["expectReason"].toString()),
            )
        }
    }
}
