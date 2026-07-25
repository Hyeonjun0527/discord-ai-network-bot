package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.domain.model.action.SocialActionKind

class NiaParticipationJudge(
    private val promptAssembler: NiaJudgePromptAssembler,
    private val llmPort: NiaJudgeLlmPort,
    private val outputParser: NiaJudgeOutputParser,
) : SingleParticipationJudgePort {
    override fun decide(request: SingleJudgeDecisionRequest): SingleJudgeDecision {
        val firstPrompt = request.preparedLlmRequest ?: promptAssembler.assemble(request)
        val result = attempt(firstPrompt)
        if (result is NiaJudgeOutputParseResult.Accepted) {
            return SingleJudgeDecisionGuard.apply(request, result.parsed.decision).finalDecision
        }
        return degradedDecision(request, result as NiaJudgeOutputParseResult.Rejected)
    }

    private fun attempt(prompt: NiaJudgeLlmRequest): NiaJudgeOutputParseResult =
        runCatching { llmPort.complete(prompt) }
            .fold(
                onSuccess = outputParser::parse,
                onFailure = { error ->
                    NiaJudgeOutputParseResult.Rejected(
                        code = JUDGE_LLM_ERROR_CODE,
                        message = error.message ?: error::class.simpleName.orEmpty(),
                    )
                },
            )

    private fun degradedDecision(
        request: SingleJudgeDecisionRequest,
        rejection: NiaJudgeOutputParseResult.Rejected,
    ): SingleJudgeDecision {
        directAddressFallback(request, rejection)?.let { return it }
        contextualFollowUpFallback(request, rejection)?.let { return it }
        val fallback =
            listOf(SocialActionKind.WAIT, SocialActionKind.IGNORE)
                .firstOrNull { it in request.constraints.lowConfidenceFallbackActions && it in request.constraints.allowedActions }
                ?: SocialActionKind.IGNORE
        val decision =
            when (fallback) {
                SocialActionKind.WAIT ->
                    SingleJudgeDecision(
                        action = SocialActionKind.WAIT,
                        confidence = 0.0,
                        delay =
                            JudgeDecisionDelay(
                                request.constraints.maxDelayMillis.coerceAtMost(DEFAULT_DEGRADED_DELAY_MILLIS),
                                "parse_failed",
                            ),
                        reactionCandidate = null,
                        speechIntent = null,
                        toneAxes = JudgeToneAxes.NEUTRAL,
                        reasonCode = JudgeReasonCode("judge_output.invalid.${rejection.code}"),
                    )
                else ->
                    SingleJudgeDecision(
                        action = SocialActionKind.IGNORE,
                        confidence = 0.0,
                        delay = JudgeDecisionDelay.IMMEDIATE,
                        reactionCandidate = null,
                        speechIntent = null,
                        toneAxes = JudgeToneAxes.NEUTRAL,
                        reasonCode = JudgeReasonCode("judge_output.invalid.${rejection.code}"),
                    )
            }
        return SingleJudgeDecisionGuard.apply(request, decision).finalDecision
    }

    private fun directAddressFallback(
        request: SingleJudgeDecisionRequest,
        rejection: NiaJudgeOutputParseResult.Rejected,
    ): SingleJudgeDecision? {
        val scene = request.sceneSnapshot
        val constraints = request.constraints
        val conversationMovedElsewhere =
            scene.textSignals.replyTargetKind == "human" ||
                scene.textSignals.otherAddresseeLikely ||
                scene.conversationState.humansTalkingToEachOtherLikely
        if (scene.textSignals.stopRequested || scene.conversationState.resolvedLikely || conversationMovedElsewhere) return null
        if (!scene.directAddressed || !constraints.speechAllowed) return null
        if (SocialActionKind.SPEAK !in constraints.allowedActions) return null
        return SingleJudgeDecision(
            action = SocialActionKind.SPEAK,
            confidence = SingleJudgeDecisionGuard.LOW_CONFIDENCE_THRESHOLD,
            delay = JudgeDecisionDelay.IMMEDIATE,
            reactionCandidate = null,
            speechIntent =
                JudgeSpeechIntent(
                    intentSummary = "acknowledge the current direct address",
                    sceneDirection = "one short natural reply; if asked to stop or yield, acknowledge and yield",
                    actHint = "acknowledge",
                    responseTargetRef =
                        request.rawContextWindow.messages
                            .lastOrNull()
                            ?.ref,
                    responseObligation = JudgeResponseObligation.REQUIRED,
                ),
            toneAxes = JudgeToneAxes.NEUTRAL,
            reasonCode = JudgeReasonCode("judge_output.degraded.direct_address.${rejection.code}"),
        )
    }

    private fun contextualFollowUpFallback(
        request: SingleJudgeDecisionRequest,
        rejection: NiaJudgeOutputParseResult.Rejected,
    ): SingleJudgeDecision? {
        val scene = request.sceneSnapshot
        val constraints = request.constraints
        val continuation = scene.conversationState.niaTurnContinuationLikely
        val responseExpected = scene.textSignals.isQuestion || scene.textSignals.callPressure > 0.0
        val conversationMovedElsewhere =
            scene.textSignals.replyTargetKind == "human" ||
                scene.textSignals.otherAddresseeLikely ||
                scene.conversationState.humansTalkingToEachOtherLikely
        val unsafeToSpeak =
            scene.textSignals.stopRequested ||
                scene.conversationState.resolvedLikely ||
                scene.runtimeGuardState.rateLimitPressure >= MAX_CONTEXTUAL_FALLBACK_PRESSURE ||
                scene.runtimeGuardState.antiSpamPressure >= MAX_CONTEXTUAL_FALLBACK_PRESSURE
        if (!continuation || !responseExpected || conversationMovedElsewhere || unsafeToSpeak) return null
        if (!constraints.speechAllowed || SocialActionKind.SPEAK !in constraints.allowedActions) return null
        return SingleJudgeDecision(
            action = SocialActionKind.SPEAK,
            confidence = SingleJudgeDecisionGuard.LOW_CONFIDENCE_THRESHOLD,
            delay = JudgeDecisionDelay.IMMEDIATE,
            reactionCandidate = null,
            speechIntent =
                JudgeSpeechIntent(
                    intentSummary = "answer the current conversational follow-up",
                    sceneDirection = "one short natural reply that continues NIA's immediately preceding turn",
                    actHint = "reply",
                    responseTargetRef =
                        request.rawContextWindow.messages
                            .lastOrNull()
                            ?.ref,
                    responseObligation = JudgeResponseObligation.REQUIRED,
                ),
            toneAxes = JudgeToneAxes.NEUTRAL,
            reasonCode = JudgeReasonCode("judge_output.degraded.contextual_follow_up.${rejection.code}"),
        )
    }

    companion object {
        const val DEFAULT_DEGRADED_DELAY_MILLIS: Long = 1_000
        private const val JUDGE_LLM_ERROR_CODE: String = "judge_llm_error"
        private const val MAX_CONTEXTUAL_FALLBACK_PRESSURE: Double = 0.8
    }
}
