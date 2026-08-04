package com.discordassistant.central.channelai

import com.discordassistant.central.channelai.adapter.inbound.web.dto.ApproveChannelAiProposalResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiHistoryResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiWizardDraftResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.ChannelAiWizardResultResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.PendingProposalResponse
import com.discordassistant.central.channelai.adapter.inbound.web.dto.RejectChannelAiProposalResponse
import com.discordassistant.central.channelai.application.AiChangeProposalReview
import com.discordassistant.central.channelai.application.ChannelAiAuditView
import com.discordassistant.central.channelai.application.ChannelAiBehaviorVersionView
import com.discordassistant.central.channelai.application.ChannelAiHistory
import com.discordassistant.central.channelai.application.ChannelAiProposalView
import com.discordassistant.central.channelai.application.ChannelAiWizardDraft
import com.discordassistant.central.channelai.application.ChannelAiWizardResult
import com.discordassistant.central.channelai.application.PendingProposalView
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.application.port.out.NexaParticipationConsentPort
import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T021 — 기존 channelai API 호환(golden) 테스트.
 *
 * NEXA 도입 후에도 기존 channelai 관리 API 의 JSON 계약(응답 키 집합·순서)이 동일한지, 그리고 feature flag OFF
 * (= NEXA 미설정)에서 기존 자동응답 경로만 살아 있는지(NEXA 비활성)를 고정한다.
 *
 * **acceptance(T021) — NEXA 필드 추가가 기존 JSON 계약을 깨지 않는다**:
 *  - 각 channelai 응답 DTO 의 JSON 키 집합이 기존 golden 과 정확히 일치한다(NEXA 필드가 새지 않음).
 *  - 미설정 길드/채널에서 [NexaParticipationFlagService] 가 OFF/비활성을 답한다 — 기존 클라이언트·대시보드는
 *    NEXA 가 없던 때와 동일한 동작을 받는다(feature flag OFF = 회귀 0).
 */
class ChannelAiCompatibilityTest {
    private val objectMapper = jacksonObjectMapper()

    // ── golden: 기존 channelai 응답 키 집합(NEXA 필드 유입 금지) ──────────────────────

    @Test
    fun `wizard draft 응답 키가 golden 과 동일하다`() {
        val draft = ChannelAiWizardDraft("니아", "잡담", "friendly", "balanced", "헌법", "미리보기")
        assertThat(fieldNames(ChannelAiWizardDraftResponse.from(draft)))
            .containsExactly("name", "job", "tone", "answerLength", "constitution", "preview")
    }

    @Test
    fun `wizard result 응답 키가 golden 과 동일하다`() {
        val result = ChannelAiWizardResult(1L, 2L, 3, 4L, "PENDING", null)
        assertThat(fieldNames(ChannelAiWizardResultResponse.from(result)))
            .containsExactly("channelAiId", "behaviorVersionId", "version", "proposalId", "status", "approvalReason")
    }

    @Test
    fun `approve·reject 응답 키가 golden 과 동일하다`() {
        val review = AiChangeProposalReview(1L, "APPROVED", 7L, "ok")
        assertThat(fieldNames(ApproveChannelAiProposalResponse.from(review)))
            .containsExactly("id", "status", "reviewedBy", "reason")
        assertThat(fieldNames(RejectChannelAiProposalResponse.from(review)))
            .containsExactly("id", "status", "reason")
    }

    @Test
    fun `pending 응답 키가 golden 과 동일하다`() {
        val view = PendingProposalView(1L, 100L, 2L, 3L, 4L, "2026-06-22")
        assertThat(fieldNames(PendingProposalResponse.from(view)))
            .containsExactly("id", "channelId", "channelAiId", "proposedBehaviorId", "requestedBy", "createdAt")
    }

    @Test
    fun `history 응답 키가 golden 과 동일하다(상위·중첩 모두)`() {
        val history =
            ChannelAiHistory(
                channelAi = null,
                versions =
                    listOf(
                        ChannelAiBehaviorVersionView(
                            id = 1L,
                            version = 2,
                            purpose = "잡담",
                            tone = "friendly",
                            answerLength = "balanced",
                            createdAt = "2026-06-22",
                        ),
                    ),
                proposals =
                    listOf(
                        ChannelAiProposalView(
                            id = 3L,
                            status = "PENDING",
                            proposedBehaviorId = 4L,
                            requestedBy = 5L,
                            reviewedBy = null,
                        ),
                    ),
                audits =
                    listOf(
                        ChannelAiAuditView(action = "publish", targetType = "ai_behavior_version", targetId = null),
                    ),
            )
        val response = ChannelAiHistoryResponse.from(history)
        assertThat(fieldNames(response))
            .containsExactly("channelAiId", "activeBehaviorVersionId", "versions", "proposals", "audits")
        assertThat(fieldNames(response.versions.single()))
            .containsExactly("id", "version", "purpose", "tone", "answerLength", "createdAt")
        assertThat(fieldNames(response.proposals.single()))
            .containsExactly("id", "status", "proposedBehaviorId", "requestedBy", "reviewedBy")
        assertThat(response.audits).containsExactly("publish:ai_behavior_version:-")
    }

    // ── feature flag OFF = 기존 동작 보존(NEXA 비활성) ──────────────────────────────

    @Test
    fun `미설정 길드 채널은 NEXA 비활성이라 기존 channelai 경로만 동작한다`() {
        val service =
            NexaParticipationFlagService(
                EmptyModeStore(),
                EmptyFlagPort(),
                NexaParticipationConsentPort.Noop,
                "OFF",
                catchUpStateLifecycle = com.discordassistant.central.participation.application.catchup.NiaCatchUpStateLifecycle.Noop,
            )

        assertThat(service.effectiveMode(guildId = 123L, channelId = 456L)).isEqualTo(ShadowMode.OFF)
        assertThat(service.isNexaActive(guildId = 123L, channelId = 456L)).isFalse()
        assertThat(service.allowsRealSend(guildId = 123L, channelId = 456L)).isFalse()
    }

    private class EmptyModeStore : ShadowModeStorePort {
        override fun currentMode(guildPseudonym: String): ShadowMode = ShadowMode.OFF

        override fun applyTransition(audit: ShadowModeAudit) = Unit

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class EmptyFlagPort : NexaParticipationFlagPort {
        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = null

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = emptySet()

        override fun clearGuild(guildPseudonym: String) = Unit

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) = Unit

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) = Unit
    }

    private fun fieldNames(value: Any): List<String> =
        objectMapper
            .valueToTree<ObjectNode>(value)
            .fieldNames()
            .asSequence()
            .toList()
}
