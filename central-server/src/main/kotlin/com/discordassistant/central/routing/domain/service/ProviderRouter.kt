package com.discordassistant.central.routing.domain.service

import com.discordassistant.central.routing.domain.model.RoutingDecision
import com.discordassistant.central.routing.domain.model.RoutingScoreBreakdown
import org.springframework.stereotype.Component
import java.util.Locale

data class Selection(
    val providerId: Long,
    val score: Double,
    val reason: String,
    val breakdown: RoutingScoreBreakdown? = null,
)

/**
 * 라우팅 결정자/파사드. HALO-GF 스코어 산출은 [HaloGfScoreModel] 협력자에 위임하고,
 * 후보 선택(comparator)·즉시 디스패치/재큐/거절 결정 오케스트레이션만 담당한다.
 * public 시그니처(scoreResult/score/select/decide)는 분해 전후로 불변이다.
 */
@Component
class ProviderRouter(
    // 구체 모델 대신 포트에 의존(DIP). 기본은 HaloGfScoreModel, 테스트/대체 모델 주입 가능.
    private val scoreModel: ScoreModel = HaloGfScoreModel(),
) {
    fun scoreResult(
        c: Candidate,
        ctx: RequestContext,
    ): RoutingScoreBreakdown = scoreModel.scoreResult(c, ctx)

    fun score(
        c: Candidate,
        ctx: RequestContext,
    ): Double = scoreResult(c, ctx).index ?: Double.NEGATIVE_INFINITY

    fun select(
        candidates: List<Candidate>,
        ctx: RequestContext,
    ): Selection? {
        val best =
            chooseBest(
                candidates.map { candidate ->
                    candidate to scoreResult(candidate, ctx)
                },
            ) ?: return null

        val index = best.second.index ?: return null
        if (index < 0.0) return null

        return Selection(
            providerId = best.first.providerId,
            score = index,
            reason = String.format(Locale.US, "index %.6f", index),
            breakdown = best.second,
        )
    }

    fun decide(
        candidates: List<Candidate>,
        dropped: Map<Long, String>,
        ctx: RequestContext,
        nowMillis: Long = System.currentTimeMillis(),
    ): RoutingDecision {
        val droppedBreakdowns =
            dropped.map { (providerId, reason) ->
                RoutingScoreBreakdown(
                    providerId = providerId,
                    feasible = false,
                    infeasibleReasons = listOf(reason),
                )
            }

        val scored =
            candidates.map { candidate ->
                candidate to scoreResult(candidate, ctx)
            }

        val allBreakdowns = droppedBreakdowns + scored.map { it.second }
        val best = chooseBest(scored)

        return when {
            best != null && (best.second.index ?: Double.NEGATIVE_INFINITY) >= 0.0 ->
                RoutingDecision.ImmediateDispatch(
                    providerId = best.first.providerId,
                    score = best.second.index ?: 0.0,
                    breakdowns = allBreakdowns,
                )

            ctx.retryCount < ctx.maxRetryCount ->
                RoutingDecision.Queue(
                    reason = if (candidates.isEmpty()) "NO_FEASIBLE_PROVIDER_AFTER_FILTER" else "NO_NON_NEGATIVE_SCORE",
                    nextEvaluationAtMillis = nowMillis + REQUEUE_DELAY_MILLIS,
                    breakdowns = allBreakdowns,
                )

            else ->
                RoutingDecision.Reject(
                    reason = "NO_FEASIBLE_PROVIDER",
                    breakdowns = allBreakdowns,
                )
        }
    }

    private fun chooseBest(scored: List<Pair<Candidate, RoutingScoreBreakdown>>): Pair<Candidate, RoutingScoreBreakdown>? {
        val comparator =
            compareByDescending<Pair<Candidate, RoutingScoreBreakdown>> {
                it.second.index ?: Double.NEGATIVE_INFINITY
            }.thenBy {
                it.first.recentHandled
            }.thenBy {
                it.first.activeRequests
            }.thenBy {
                it.first.providerId
            }

        val selectable =
            scored.asSequence().filter { pair ->
                val breakdown = pair.second
                breakdown.isSelectable &&
                    (breakdown.index ?: Double.NEGATIVE_INFINITY).isFinite()
            }

        return selectable.sortedWith(comparator).firstOrNull()
    }

    companion object {
        private const val REQUEUE_DELAY_MILLIS = 250L
    }
}
