package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.domain.model.action.SocialActionKind

object SingleJudgeDecisionGuard {
    const val LOW_CONFIDENCE_THRESHOLD: Double = 0.55

    fun apply(
        request: SingleJudgeDecisionRequest,
        decision: SingleJudgeDecision,
    ): GuardedSingleJudgeDecision {
        var current = decision
        val adjustments = mutableListOf<JudgeDecisionAdjustment>()

        if (current.action !in request.constraints.allowedActions) {
            val fallback = fallbackAction(request)
            adjustments += adjustment("action_not_allowed", current.action, fallback)
            current = fallbackDecision(current, fallback, "action_not_allowed")
        }

        if (current.action == SocialActionKind.SPEAK && request.sceneSnapshot.textSignals.stopRequested) {
            val fallback = fallbackAction(request)
            adjustments += adjustment("explicit_stop_request", current.action, fallback)
            current = fallbackDecision(current, fallback, "explicit_stop")
        }

        if (current.action == SocialActionKind.SPEAK && current.confidence < LOW_CONFIDENCE_THRESHOLD) {
            val fallback = fallbackAction(request)
            adjustments += adjustment("low_confidence_speak", current.action, fallback)
            current = fallbackDecision(current, fallback, "low_confidence")
        }

        val clipped = clipDelayIfNeeded(request, current)
        if (clipped != current) {
            adjustments += adjustment("delay_clipped", current.action, current.action)
            current = clipped
        }

        return GuardedSingleJudgeDecision(
            finalDecision = current,
            adjustments = adjustments,
        )
    }

    private fun fallbackAction(request: SingleJudgeDecisionRequest): SocialActionKind {
        val constraints = request.constraints
        return FALLBACK_ORDER.firstOrNull { action ->
            action in constraints.allowedActions && action in constraints.lowConfidenceFallbackActions
        } ?: constraints.allowedActions.first()
    }

    private fun fallbackDecision(
        original: SingleJudgeDecision,
        action: SocialActionKind,
        reasonSuffix: String,
    ): SingleJudgeDecision =
        when (action) {
            SocialActionKind.WAIT ->
                original.copy(
                    action = SocialActionKind.WAIT,
                    delay =
                        when {
                            original.delay.millis > 0 -> original.delay
                            else -> JudgeDecisionDelay(millis = 1_000, wakeUpHint = reasonSuffix)
                        },
                    reactionCandidate = null,
                    speechIntent = null,
                    reasonCode = original.reasonCode.withSuffix(reasonSuffix),
                )
            SocialActionKind.IGNORE ->
                original.copy(
                    action = SocialActionKind.IGNORE,
                    delay = JudgeDecisionDelay.IMMEDIATE,
                    reactionCandidate = null,
                    speechIntent = null,
                    reasonCode = original.reasonCode.withSuffix(reasonSuffix),
                )
            else ->
                original.copy(
                    action = action,
                    reasonCode = original.reasonCode.withSuffix(reasonSuffix),
                )
        }

    private fun clipDelayIfNeeded(
        request: SingleJudgeDecisionRequest,
        decision: SingleJudgeDecision,
    ): SingleJudgeDecision {
        val maxDelayMillis = request.constraints.maxDelayMillis
        if (decision.delay.millis <= maxDelayMillis) return decision
        val wakeUpHint =
            when {
                decision.action == SocialActionKind.WAIT -> decision.delay.wakeUpHint ?: "max_delay"
                else -> decision.delay.wakeUpHint
            }
        return decision.copy(
            delay = JudgeDecisionDelay(millis = maxDelayMillis, wakeUpHint = wakeUpHint),
            reasonCode = decision.reasonCode.withSuffix("delay_clipped"),
        )
    }

    private fun adjustment(
        code: String,
        fromAction: SocialActionKind,
        toAction: SocialActionKind,
    ): JudgeDecisionAdjustment =
        JudgeDecisionAdjustment(
            code = code,
            fromAction = fromAction,
            toAction = toAction,
        )

    private fun JudgeReasonCode.withSuffix(suffix: String): JudgeReasonCode = JudgeReasonCode("$code.$suffix")

    private val FALLBACK_ORDER = listOf(SocialActionKind.WAIT, SocialActionKind.IGNORE)
}

data class GuardedSingleJudgeDecision(
    val finalDecision: SingleJudgeDecision,
    val adjustments: List<JudgeDecisionAdjustment>,
)

data class JudgeDecisionAdjustment(
    val code: String,
    val fromAction: SocialActionKind,
    val toAction: SocialActionKind,
) {
    init {
        require(code.matches(CODE_PATTERN)) { "adjustment code 는 소문자 안정 코드여야 한다: $code" }
    }

    companion object {
        private val CODE_PATTERN = Regex("[a-z0-9_.-]+")
    }
}
