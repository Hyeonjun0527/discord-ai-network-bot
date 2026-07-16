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
        val firstPrompt = promptAssembler.assemble(request)
        val first = attempt(firstPrompt)
        if (first is NiaJudgeOutputParseResult.Accepted) {
            return SingleJudgeDecisionGuard.apply(request, first.parsed.decision).finalDecision
        }
        val firstRejected = first as NiaJudgeOutputParseResult.Rejected

        // 복구 프롬프트는 모델이 반환한 형식 오류만 고칠 수 있다. 공급자 실패를 재시도하면 새 출력 없이 호출 예산만 두 배가 된다.
        if (firstRejected.code == JUDGE_LLM_ERROR_CODE) {
            return degradedDecision(request, firstRejected)
        }

        val repairPrompt = firstPrompt.repairPrompt(firstRejected)
        val repaired = attempt(repairPrompt)
        if (repaired is NiaJudgeOutputParseResult.Accepted) {
            return SingleJudgeDecisionGuard.apply(request, repaired.parsed.decision).finalDecision
        }

        return degradedDecision(request, repaired as NiaJudgeOutputParseResult.Rejected)
    }

    private fun attempt(prompt: NiaJudgeLlmRequest): NiaJudgeOutputParseResult =
        runCatching { llmPort.complete(prompt) }
            .fold(
                onSuccess = { response -> outputParser.parse(response) },
                onFailure = { error ->
                    NiaJudgeOutputParseResult.Rejected(
                        code = JUDGE_LLM_ERROR_CODE,
                        message = error.message ?: error::class.simpleName.orEmpty(),
                    )
                },
            )

    private fun NiaJudgeLlmRequest.repairPrompt(rejection: NiaJudgeOutputParseResult.Rejected): NiaJudgeLlmRequest =
        copy(
            prompt =
                prompt +
                    "\n\nREPAIR_INSTRUCTION:\n" +
                    "The previous judge output was invalid (${rejection.code}). " +
                    "Return only valid JSON matching $outputSchema. Do not include final response text.",
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
                                request.constraints.maxDelayMillis.coerceAtMost(DEFAULT_REPAIR_DELAY_MILLIS),
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
                ),
            toneAxes = JudgeToneAxes.NEUTRAL,
            reasonCode = JudgeReasonCode("judge_output.degraded.contextual_follow_up.${rejection.code}"),
        )
    }

    companion object {
        const val DEFAULT_REPAIR_DELAY_MILLIS: Long = 1_000
        private const val JUDGE_LLM_ERROR_CODE: String = "judge_llm_error"
        private const val MAX_CONTEXTUAL_FALLBACK_PRESSURE: Double = 0.8
    }
}
