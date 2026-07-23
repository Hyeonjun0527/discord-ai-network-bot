package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate

class NiaParticipationJudge(
    private val promptAssembler: NiaJudgePromptAssembler,
    private val llmPort: NiaJudgeLlmPort,
    private val outputParser: NiaJudgeOutputParser,
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) : SingleParticipationJudgePort {
    override fun decide(request: SingleJudgeDecisionRequest): SingleJudgeDecision {
        val firstPrompt = request.preparedLlmRequest ?: promptAssembler.assemble(request)
        val first = attempt(firstPrompt)
        if (first.result is NiaJudgeOutputParseResult.Accepted) {
            return SingleJudgeDecisionGuard.apply(request, first.result.parsed.decision).finalDecision
        }
        val firstRejected = first.result as NiaJudgeOutputParseResult.Rejected

        // 복구 프롬프트는 모델이 반환한 형식 오류만 고칠 수 있다. 공급자 실패를 재시도하면 새 출력 없이 호출 예산만 두 배가 된다.
        if (firstRejected.code == JUDGE_LLM_ERROR_CODE) {
            return degradedDecision(request, firstRejected)
        }

        val repairPrompt = firstPrompt.repairPrompt(request, firstRejected, requireNotNull(first.responseContent))
        val repaired = attempt(repairPrompt)
        if (repaired.result is NiaJudgeOutputParseResult.Accepted) {
            return SingleJudgeDecisionGuard.apply(request, repaired.result.parsed.decision).finalDecision
        }

        return degradedDecision(request, repaired.result as NiaJudgeOutputParseResult.Rejected)
    }

    private fun attempt(prompt: NiaJudgeLlmRequest): JudgeAttempt =
        runCatching { llmPort.complete(prompt) }
            .fold(
                onSuccess = { response -> JudgeAttempt(outputParser.parse(response), response.content) },
                onFailure = { error ->
                    JudgeAttempt(
                        result =
                            NiaJudgeOutputParseResult.Rejected(
                                code = JUDGE_LLM_ERROR_CODE,
                                message = error.message ?: error::class.simpleName.orEmpty(),
                            ),
                        responseContent = null,
                    )
                },
            )

    private fun NiaJudgeLlmRequest.repairPrompt(
        request: SingleJudgeDecisionRequest,
        rejection: NiaJudgeOutputParseResult.Rejected,
        invalidOutput: String,
    ): NiaJudgeLlmRequest {
        val validRefs = request.rawContextWindow.messages.map { it.ref }
        val latestRef = validRefs.lastOrNull().orEmpty()
        val allowedActions = request.constraints.allowedActions.joinToString(",") { it.name.removeSuffix("_PENDING") }
        val configuredRepairInstruction =
            NiaPromptTemplate.render(
                promptSource.text(NiaPromptKey.JUDGE_REPAIR_TEMPLATE),
                mapOf("rejectionCode" to rejection.code, "outputSchema" to outputSchema),
            )
        val compactRepairPrompt =
            """
            $configuredRepairInstruction
            Repair the untrusted judge output below. Do not re-judge the conversation and do not add commentary.
            Return exactly one JSON object matching ${NiaJudgeLlmRequest.OUTPUT_SCHEMA}.
            parse_error=${rejection.code}
            allowed_actions=$allowedActions
            speech_allowed=${request.constraints.speechAllowed}
            reaction_allowed=${request.constraints.reactionAllowed}
            max_delay_ms=${request.constraints.maxDelayMillis}
            valid_evidence_refs=${validRefs.joinToString(",")}
            latest_message_ref=$latestRef

            Required common fields:
            {"schema":"${NiaJudgeLlmRequest.OUTPUT_SCHEMA}","action":"IGNORE|WAIT|REACT|SPEAK|CANCEL","reason":"short reason","confidence":0.0,"evidenceRefs":[]}
            confidence must be 0..1. Non-IGNORE actions require evidenceRefs from valid_evidence_refs.
            WAIT requires positive reevaluateAfterMs not greater than max_delay_ms.
            REACT requires reactionCode.
            SPEAK requires speechIntent with intentSummary, sceneDirection, deliveryMode CHANNEL|REPLY,
            responseTargetRef equal to latest_message_ref, responseObligation REQUIRED|OPTIONAL, and groundingNeed NONE|WEB_VERIFY.
            Optional common fields: reasonCode, riskFlags, reevaluateAfterMs, toneAxes, beliefUpdates.
            Never include final response text. Omit fields that do not apply.

            INVALID_OUTPUT_BEGIN
            ${invalidOutput.take(MAX_REPAIR_OUTPUT_CHARS)}
            INVALID_OUTPUT_END
            """.trimIndent()
        return copy(
            prompt = compactRepairPrompt,
            stablePromptPrefixChars = 0,
            metadata = metadata + (REPAIR_ATTEMPT_METADATA_KEY to "true"),
        )
    }

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
        const val DEFAULT_REPAIR_DELAY_MILLIS: Long = 1_000
        private const val JUDGE_LLM_ERROR_CODE: String = "judge_llm_error"
        const val REPAIR_ATTEMPT_METADATA_KEY: String = "repair_attempt"
        private const val MAX_CONTEXTUAL_FALLBACK_PRESSURE: Double = 0.8
        private const val MAX_REPAIR_OUTPUT_CHARS: Int = 16_000
    }

    private data class JudgeAttempt(
        val result: NiaJudgeOutputParseResult,
        val responseContent: String?,
    )
}
