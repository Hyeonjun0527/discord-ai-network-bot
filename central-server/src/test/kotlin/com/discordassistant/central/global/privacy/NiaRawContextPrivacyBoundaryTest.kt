package com.discordassistant.central.global.privacy

import com.discordassistant.central.global.observability.FirMirProxyMetrics
import com.discordassistant.central.global.observability.NexaActionLabel
import com.discordassistant.central.global.observability.PolicyActionMetrics
import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogEntity
import com.discordassistant.central.shared.RequestState
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * TODO 64 privacy boundary: raw context canary 가 request log, decision log, exception, metric label,
 * dataset/user export 표면에 새지 않는지 한 곳에서 고정한다.
 */
class NiaRawContextPrivacyBoundaryTest {
    private val rawCanary = "RAW_CONTEXT_LEAK_CANARY 야 이럴땐 위로해줘야지"

    @Test
    fun `request log entities expose no raw prompt content or response fields`() {
        val fieldNames =
            listOf(AiRequestEntity::class, UsageLogEntity::class, ContributionLogEntity::class)
                .flatMap { type -> type.java.declaredFields.map { it.name } }
                .joinToString("\n")
                .lowercase()

        assertThat(fieldNames).doesNotContain("prompt")
        assertThat(fieldNames).doesNotContain("content")
        assertThat(fieldNames).doesNotContain("message")
        assertThat(fieldNames).doesNotContain("response")
        assertThat(fieldNames).doesNotContain("raw")
    }

    @Test
    fun `raw context canary is absent from non raw logs metrics exceptions and export`() {
        val surfaces =
            listOf(
                requestLogSurface(),
                decisionLogSurface(),
                speechDecisionLogSurface(),
                exceptionSurface(),
                metricSurface(),
                userExportSurface(),
            ).joinToString("\n")

        assertThat(surfaces).doesNotContain(rawCanary)
        assertThat(surfaces).doesNotContain("위로해줘야지")
    }

    @Test
    fun `decision log evidence refs reject raw transcript text`() {
        assertThatThrownBy {
            decisionRecord(evidenceRefs = setOf("raw_context_window:$rawCanary"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("evidenceRefs")
            .hasMessageNotContaining(rawCanary)
    }

    @Test
    fun `cross subject export failure does not echo raw memory claim`() {
        val service =
            UserDataExportService(
                source(memories = listOf(ExportedMemory("user_99", "mem_x", rawCanary, "stated"))),
            )

        assertThatThrownBy { service.export("user_3") }
            .isInstanceOf(CrossSubjectLeakException::class.java)
            .hasMessageNotContaining(rawCanary)
    }

    private fun requestLogSurface(): String {
        val request =
            AiRequestEntity(
                requestId = "req_1",
                guildId = 1,
                channelId = 2,
                userId = 3,
                state = RequestState.FAILED,
                failReason = "CONSENT_BLOCKED",
                createdAt = Instant.parse("2026-06-29T00:00:00Z"),
            )
        return listOf(
            request.requestId,
            request.guildId,
            request.channelId,
            request.userId,
            request.state,
            request.failReason,
            UsageLogEntity(guildId = 1, userId = 3, requestId = "req_1").requestId,
            ContributionLogEntity(guildId = 1, providerId = 4, requestId = "req_1").requestId,
        ).joinToString("|")
    }

    private fun decisionLogSurface(): String =
        decisionRecord(evidenceRefs = setOf("raw_context_window:hash=abc123"))
            .toString()

    private fun decisionRecord(evidenceRefs: Set<String>): DecisionLogRecord =
        DecisionLogRecord(
            correlationId = "corr_1",
            guildPseudonym = "guild-pseudo",
            channelId = "channel-pseudo",
            contextVersion = 1,
            actionKind = SocialActionKind.WAIT,
            featureHash = "feature-hash",
            featureVectorVersion = 1,
            modelVersion = "policy-v1",
            seed = 7L,
            removedKinds = emptySet(),
            reasonCode = "LOW_CONFIDENCE",
            missingInputCodes = setOf("CONTENT_UNAVAILABLE"),
            evidenceRefs = evidenceRefs,
            consumedGenerationQuota = false,
            decidedAt = Instant.parse("2026-06-29T00:00:01Z"),
        )

    private fun speechDecisionLogSurface(): String =
        SpeechDecisionLog(
            focusThreadKey = "thread-pseudo",
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            outcome = SpeechDecisionOutcome.REACTION_ONLY,
            highRiskDowngraded = false,
            consentBlocked = false,
            generatedCandidateCount = 2,
            criticBlockReasons = setOf("SECRET_DISCLOSURE"),
        ).toString()

    private fun exceptionSurface(): String = ConsentRevokedException("user_3", ProcessingStage.JUDGE_CALL).toString()

    private fun metricSurface(): String {
        val registry = SimpleMeterRegistry()
        PolicyActionMetrics(registry).recordDecision(NexaActionLabel.SPEAK, NexaActionLabel.WAIT)
        FirMirProxyMetrics(registry).recordBatch(sampleCount = 10, falseInterruptionCount = 1, missedInterventionCount = 2)
        return registry.meters.joinToString("\n") { meter ->
            val tags = meter.id.tags.joinToString(",") { "${it.key}=${it.value}" }
            "${meter.id.name}{$tags}"
        }
    }

    private fun userExportSurface(): String =
        UserDataExportService(
            source(
                events = listOf(ExportedSourceEvent("user_3", "evt_1", "message", 1_000)),
                memories = listOf(ExportedMemory("user_3", "mem_1", "likes-noodles", "stated")),
                relations = listOf(ExportedRelationState("user_3", "user_9", "friendly")),
                eligibility = ExportedTrainingEligibility("user_3", eligible = true, reasonCode = "opted_in"),
            ),
        ).export("user_3").toString()

    private fun source(
        events: List<ExportedSourceEvent> = emptyList(),
        memories: List<ExportedMemory> = emptyList(),
        relations: List<ExportedRelationState> = emptyList(),
        eligibility: ExportedTrainingEligibility? = null,
    ) = object : UserDataSourcePort {
        override fun sourceEvents(subjectPseudonym: String): List<ExportedSourceEvent> = events

        override fun memories(subjectPseudonym: String): List<ExportedMemory> = memories

        override fun relationStates(subjectPseudonym: String): List<ExportedRelationState> = relations

        override fun trainingEligibility(subjectPseudonym: String): ExportedTrainingEligibility? = eligibility
    }
}
