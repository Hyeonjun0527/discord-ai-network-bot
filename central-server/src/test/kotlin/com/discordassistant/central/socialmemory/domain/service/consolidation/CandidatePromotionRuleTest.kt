package com.discordassistant.central.socialmemory.domain.service.consolidation

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryEvidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.model.extraction.StatementModality
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P07-T016 검증·승격 규칙. acceptance: 모든 결과에 reason code. consent/modality/sensitive/conflict 게이트.
 * T022 연계: 농담/부정/인용/가정은 단일 사실로 승격하지 않는다(DISCARD NON_ASSERTION).
 */
class CandidatePromotionRuleTest {
    private val scope = VisibilityScope.Guild("g-1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun source(consent: Boolean = true) =
        MemorySource(
            sourceEventIds = setOf("scene-1"),
            extractionVersion = 1,
            consentGranted = consent,
            createdAt = now,
        )

    private fun candidate(
        modality: StatementModality = StatementModality.ASSERTED,
        sensitive: Boolean = false,
        consent: Boolean = true,
        obj: String = "python",
    ) = MemoryCandidate(
        kind = CandidateKind.TEMPORAL_FACT,
        visibility = scope,
        subject = "p-a",
        predicate = "uses_language",
        obj = obj,
        source = source(consent),
        modality = modality,
        sensitive = sensitive,
    )

    private fun activeFact(obj: String) =
        TemporalFact(
            id = "f-$obj",
            visibility = scope,
            subject = "p-a",
            predicate = "uses_language",
            obj = obj,
            validFrom = now,
            validTo = null,
            source = source(),
            confidence = Confidence.forEvidence(MemoryEvidence.EXPLICIT_DISCORD_EVENT),
            status = MemoryStatus.ACTIVE,
        )

    @Test
    fun `검증 통과 후보는 STORE 이고 reason 은 PROVENANCE_OK`() {
        val d = CandidatePromotionRule.decide(candidate())
        assertEquals(PromotionOutcome.STORE, d.outcome)
        assertEquals(PromotionReason.PROVENANCE_OK, d.reason)
    }

    @Test
    fun `동의 없으면 DISCARD NO_CONSENT`() {
        val d = CandidatePromotionRule.decide(candidate(consent = false))
        assertEquals(PromotionOutcome.DISCARD, d.outcome)
        assertEquals(PromotionReason.NO_CONSENT, d.reason)
    }

    @Test
    fun `농담은 단일 사실로 승격하지 않는다 - DISCARD NON_ASSERTION`() {
        val d = CandidatePromotionRule.decide(candidate(modality = StatementModality.JOKE))
        assertEquals(PromotionOutcome.DISCARD, d.outcome)
        assertEquals(PromotionReason.NON_ASSERTION, d.reason)
    }

    @Test
    fun `부정은 긍정 사실로 승격하지 않는다 - DISCARD NON_ASSERTION`() {
        val d = CandidatePromotionRule.decide(candidate(modality = StatementModality.NEGATED))
        assertEquals(PromotionReason.NON_ASSERTION, d.reason)
    }

    @Test
    fun `민감 추론은 DISCARD SENSITIVE_INFERENCE`() {
        val d = CandidatePromotionRule.decide(candidate(sensitive = true))
        assertEquals(PromotionOutcome.DISCARD, d.outcome)
        assertEquals(PromotionReason.SENSITIVE_INFERENCE, d.reason)
    }

    @Test
    fun `기존 사실과 충돌하면 임의 승격 않고 HOLD CONFLICTS_EXISTING`() {
        // 기존 ACTIVE 사실 object=java, 후보 object=python — GLM 약한 근거로 임의 승격 금지.
        val d = CandidatePromotionRule.decide(candidate(obj = "python"), listOf(activeFact("java")))
        assertEquals(PromotionOutcome.HOLD, d.outcome)
        assertEquals(PromotionReason.CONFLICTS_EXISTING, d.reason)
    }

    @Test
    fun `같은 object 재진술은 충돌이 아니라 STORE(저장은 멱등 처리)`() {
        val d = CandidatePromotionRule.decide(candidate(obj = "java"), listOf(activeFact("java")))
        assertEquals(PromotionOutcome.STORE, d.outcome)
    }
}
