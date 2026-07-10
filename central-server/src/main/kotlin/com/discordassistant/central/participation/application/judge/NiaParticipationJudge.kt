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

        // A repair prompt can only correct malformed model output. Retrying a timeout/provider failure doubles the
        // bounded call budget while offering no new output to repair, so degrade fail-closed immediately instead.
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

    companion object {
        const val DEFAULT_REPAIR_DELAY_MILLIS: Long = 1_000
        private const val JUDGE_LLM_ERROR_CODE: String = "judge_llm_error"
    }
}
