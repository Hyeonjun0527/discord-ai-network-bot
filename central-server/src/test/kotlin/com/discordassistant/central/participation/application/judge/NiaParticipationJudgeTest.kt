package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmResponse
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaParticipationJudgeTest {
    private val mapper = jacksonObjectMapper()
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)
    private val now = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `judge returns guarded decision on first valid output`() {
        val llm = FakeJudgeLlm(response(output("SPEAK")))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest())

        assertThat(decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.speechIntent!!.intentSummary).contains("acknowledge")
        assertThat(llm.requests).hasSize(1)
    }

    @Test
    fun `bridge가 미리 조립한 prompt를 다시 조립하지 않고 그대로 호출한다`() {
        val llm = FakeJudgeLlm(response(output("SPEAK")))
        val prepared =
            NiaJudgeLlmRequest(
                prompt = "PREPARED_SINGLE_PROMPT",
                promptVersion = NiaJudgePromptAssembler.PROMPT_VERSION,
                seed = 42L,
                timeoutMillis = 1_000,
            )

        judge(llm).decide(sampleRequest().copy(preparedLlmRequest = prepared))

        assertThat(llm.requests).containsExactly(prepared)
    }

    @Test
    fun `judge retries malformed output once and uses the valid second result`() {
        val llm = FakeJudgeLlm(response("{bad-json"), response(output("SPEAK")))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest(directAddressed = false))

        assertThat(decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(llm.requests).hasSize(2)
        assertThat(llm.requests.distinct()).hasSize(1)
    }

    @Test
    fun `judge degrades to WAIT or IGNORE after both attempts fail validation`() {
        val llm = FakeJudgeLlm(response("{bad-json"))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest(directAddressed = false))

        assertThat(decision.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(decision.action).isNotEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.confidence).isEqualTo(0.0)
        assertThat(llm.requests).hasSize(2)
    }

    @Test
    fun `judge retries a provider failure once and uses the successful result`() {
        val llm = ThrowOnceJudgeLlm(response(output("SPEAK")))
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest())

        assertThat(decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(llm.calls).isEqualTo(2)
    }

    @Test
    fun `judge falls back to bounded SPEAK for a direct address after provider failure`() {
        val llm = ThrowingJudgeLlm()
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest())

        assertThat(decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.speechIntent!!.sceneDirection).contains("if asked to stop or yield")
        assertThat(decision.reasonCode.code).isEqualTo("judge_output.degraded.direct_address.judge_llm_error")
        assertThat(llm.calls).isEqualTo(2)
    }

    @Test
    fun `judge keeps fail-closed behavior for ambient chat after provider failure`() {
        val llm = ThrowingJudgeLlm()
        val judge = judge(llm)

        val decision = judge.decide(sampleRequest(directAddressed = false))

        assertThat(decision.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(decision.action).isNotEqualTo(SocialActionKind.SPEAK)
        assertThat(llm.calls).isEqualTo(2)
    }

    @Test
    fun `invalid judge output still answers a same-member question that continues nia's turn`() {
        val llm = FakeJudgeLlm(response("{bad-json"))
        val judge = judge(llm)

        val decision =
            judge.decide(
                sampleRequest(
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                    question = true,
                ),
            )

        assertThat(decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.reasonCode.code).isEqualTo("judge_output.degraded.contextual_follow_up.invalid_judge_output")
        assertThat(decision.speechIntent!!.intentSummary).contains("conversational follow-up")
    }

    @Test
    fun `contextual fallback does not mechanically answer a non-question or human handoff`() {
        val nonQuestionJudge = judge(FakeJudgeLlm(response("{bad-json")))
        val humanHandoffJudge = judge(FakeJudgeLlm(response("{bad-json")))
        val namedHandoffJudge = judge(FakeJudgeLlm(response("{bad-json")))

        val nonQuestion =
            nonQuestionJudge.decide(
                sampleRequest(
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                    question = false,
                ),
            )
        val humanHandoff =
            humanHandoffJudge.decide(
                sampleRequest(
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                    question = true,
                    humansTalkingToEachOtherLikely = true,
                ),
            )
        val namedHandoff =
            namedHandoffJudge.decide(
                sampleRequest(
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                    question = true,
                    otherAddresseeLikely = true,
                ),
            )

        assertThat(nonQuestion.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(humanHandoff.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(namedHandoff.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
    }

    @Test
    fun `contextual fallback stays silent for an explicit stop request`() {
        val judge = judge(FakeJudgeLlm(response("{bad-json")))

        val decision =
            judge.decide(
                sampleRequest(
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                    question = true,
                    stopRequested = true,
                ),
            )

        assertThat(decision.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(decision.action).isNotEqualTo(SocialActionKind.SPEAK)
    }

    @Test
    fun `direct-address fallback also stays silent for a stop request or human handoff`() {
        val stopJudge = judge(FakeJudgeLlm(response("{bad-json")))
        val handoffJudge = judge(FakeJudgeLlm(response("{bad-json")))

        val stop =
            stopJudge.decide(
                sampleRequest(
                    directAddressed = true,
                    question = false,
                    stopRequested = true,
                ),
            )
        val handoff =
            handoffJudge.decide(
                sampleRequest(
                    directAddressed = true,
                    question = true,
                    otherAddresseeLikely = true,
                ),
            )

        assertThat(stop.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
        assertThat(handoff.action).isIn(SocialActionKind.WAIT, SocialActionKind.IGNORE)
    }

    private fun judge(llm: NiaJudgeLlmPort): NiaParticipationJudge =
        NiaParticipationJudge(
            promptAssembler = NiaJudgePromptAssembler(),
            llmPort = llm,
            outputParser = NiaJudgeOutputParser(mapper),
        )

    private fun output(action: String): String {
        val base =
            linkedMapOf<String, Any?>(
                "schema" to "nia.participation-judge-output.v1",
                "action" to action,
                "reason" to "synthetic judge reason",
                "reasonCode" to "judge.synthetic",
                "evidenceRefs" to if (action == "IGNORE") emptyList<String>() else listOf("msg_1"),
                "confidence" to 0.82,
                "riskFlags" to emptyList<String>(),
                "reevaluateAfterMs" to if (action == "WAIT") 2_000 else 0,
            )
        if (action == "SPEAK") {
            base["speechIntent"] =
                mapOf(
                    "intentSummary" to "acknowledge direct request",
                    "sceneDirection" to "one short sentence",
                    "deliveryMode" to "CHANNEL",
                    "responseTargetRef" to "msg_1",
                    "responseObligation" to "REQUIRED",
                    "groundingNeed" to "NONE",
                )
        }
        return mapper.writeValueAsString(base)
    }

    private fun response(content: String): NiaJudgeLlmResponse =
        NiaJudgeLlmResponse(content = content, modelVersion = "local-judge-v1", finishReason = "stop")

    private fun sampleRequest(
        directAddressed: Boolean = true,
        niaTurnContinuationLikely: Boolean = false,
        question: Boolean = false,
        humansTalkingToEachOtherLikely: Boolean = false,
        stopRequested: Boolean = false,
        otherAddresseeLikely: Boolean = false,
    ): SingleJudgeDecisionRequest =
        SingleJudgeDecisionRequest(
            rawContextWindow =
                JudgeContextWindowBuilder(maxRawChars = 1_000)
                    .build(
                        RawContextSnapshot(
                            scope = scope,
                            entries =
                                listOf(
                                    RawContextEntry(
                                        scope = scope,
                                        messageId = 10L,
                                        authorPseudonym = "user_a",
                                        occurredAt = now,
                                        replyToMessageId = null,
                                        sourceType = RawContextSourceType.HUMAN,
                                        content = RawContextContent.Available("야 이럴땐 위로해줘"),
                                    ),
                                ),
                        ),
                    ),
            sceneSnapshot =
                SingleJudgeSceneSnapshot(
                    ref = SceneSnapshotRef("guild_a", "channel_a", sceneSeq = 7L, contextVersion = 3L),
                    directAddressed = directAddressed,
                    replyToNia = false,
                    conversationMentionsNia = true,
                    recentAgentBurstCount = 0,
                    silenceMillis = 8_000,
                    textSignals =
                        JudgeSceneTextSignals(
                            contentAvailable = true,
                            isQuestion = question,
                            replyTargetKind = "none",
                            emotionalIntensity = 0.0,
                            callPressure = 0.0,
                            stopRequested = stopRequested,
                            otherAddresseeLikely = otherAddresseeLikely,
                        ),
                    conversationState =
                        JudgeConversationSceneState(
                            humanLikelyAnswering = false,
                            idleGapLikely = false,
                            resolvedLikely = false,
                            humansTalkingToEachOtherLikely = humansTalkingToEachOtherLikely,
                            niaAddressedOrIdleOpportunity = false,
                            niaTurnContinuationLikely = niaTurnContinuationLikely,
                        ),
                ),
            featureVector = FeatureVectorView.empty(version = FeatureCatalog.VERSION),
            memoryRefs = emptyList(),
            constraints =
                JudgeDecisionConstraints(
                    allowedActions = SocialActionKind.entries.toSet(),
                    speechAllowed = true,
                    reactionAllowed = true,
                    maxDelayMillis = 30_000,
                ),
            schemaVersion = SingleJudgeDecisionRequest.CURRENT_SCHEMA_VERSION,
            seed = 42L,
        )

    private class FakeJudgeLlm(
        private vararg val responses: NiaJudgeLlmResponse,
    ) : NiaJudgeLlmPort {
        val requests = mutableListOf<NiaJudgeLlmRequest>()
        private var cursor = 0

        override fun complete(request: NiaJudgeLlmRequest): NiaJudgeLlmResponse {
            requests += request
            return responses.getOrElse(cursor++) { responses.last() }
        }
    }

    private class ThrowOnceJudgeLlm(
        private val response: NiaJudgeLlmResponse,
    ) : NiaJudgeLlmPort {
        var calls: Int = 0

        override fun complete(request: NiaJudgeLlmRequest): NiaJudgeLlmResponse {
            calls++
            if (calls == 1) throw IllegalStateException("provider unavailable")
            return response
        }
    }

    private class ThrowingJudgeLlm : NiaJudgeLlmPort {
        var calls: Int = 0

        override fun complete(request: NiaJudgeLlmRequest): NiaJudgeLlmResponse {
            calls++
            throw IllegalStateException("provider unavailable")
        }
    }
}
