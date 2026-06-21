package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import kotlin.math.exp

/**
 * 버스트 종료·다른 인간 응답·채널 tempo·직접 대상 여부를 **가중합**으로 쓰는 기준선(NEXA-P09-T005).
 *
 * 네 사회 신호를 [BurstAwareWeights] 로 가중합한 점수를 로지스틱으로 SPEAK 확률로 바꾼다(나머지는 IGNORE).
 * baseline 중 가장 풍부하지만 여전히 relationship/memory 를 안 쓰는 휴리스틱이다.
 *
 * **acceptance(T005) — 가중치가 설정 파일에 숨지 않고 versioned config 로 저장된다**:
 * 가중치는 [weights]([BurstAwareWeights], [BurstAwareWeights.version] 보유)로만 주입된다 — 매직넘버/외부
 * properties 에 숨기지 않는다. [modelVersion] 에 weights 버전을 박아 어떤 가중치로 나온 결정인지 추적·재현한다.
 *
 * 신호 추출(원문 비참조 — 정규화 feature 만):
 * - **burst 종료**: [FeatureCatalog.BURST_GAP_SECONDS](간격↑ = burst 끝나 틈 생김).
 * - **다른 인간 응답**: [FeatureCatalog.TEMPO_HUMAN_BURST_RATE](인간 응답률↑ = 이미 받쳐짐).
 * - **채널 tempo**: [FeatureCatalog.TEMPO_MEDIAN_GAP_SECONDS](간격↑ = 느림 = 끼어들 여유).
 * - **직접 대상**: [FeatureCatalog.BURST_HAS_MENTION] 또는 [FeatureCatalog.THREAD_FOCUS_PRESENT].
 */
class BurstAwareHeuristicPolicy(
    private val weights: BurstAwareWeights = BurstAwareWeights.V1,
) : BaselinePolicy() {
    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse {
        val burstEnded = norm(request, FeatureCatalog.BURST_GAP_SECONDS, scale = GAP_SCALE_SECONDS)
        val otherHumanResponded = norm(request, FeatureCatalog.TEMPO_HUMAN_BURST_RATE, scale = HUMAN_RATE_SCALE)
        val channelTempo = norm(request, FeatureCatalog.TEMPO_MEDIAN_GAP_SECONDS, scale = GAP_SCALE_SECONDS)
        val directlyAddressed =
            maxOf(
                bool(request, FeatureCatalog.BURST_HAS_MENTION),
                bool(request, FeatureCatalog.THREAD_FOCUS_PRESENT),
            )

        val score =
            weights.bias +
                weights.burstEnded * burstEnded +
                weights.otherHumanResponded * otherHumanResponded +
                weights.channelTempo * channelTempo +
                weights.directlyAddressed * directlyAddressed
        val rawSpeak = logistic(score)
        // speechAllowed=false 면 발화 게이트가 닫혀 SPEAK 확률 0(계약 안전).
        val speak = if (request.config.speechAllowed) rawSpeak else 0.0
        val weightsMap =
            mapOf(
                SocialActionKind.SPEAK to speak,
                SocialActionKind.IGNORE to (1.0 - speak),
            ).filterValues { it > 0.0 }
        return BaselineDistributions.response(
            actionWeights = weightsMap,
            modelVersion = modelVersion(),
            // 0.5 근처에서 불확실성 최대(신호가 발화/침묵을 또렷이 가르지 못함).
            uncertainty = 1.0 - kotlin.math.abs(speak - 0.5) * 2.0,
        )
    }

    /** weights 버전을 박은 모델 버전 — 어떤 가중치 set 으로 나온 결정인지 추적·재현(acceptance T005). */
    fun modelVersion(): String = "$MODEL_VERSION_PREFIX-w${weights.version}"

    /** [id] 정규화 값을 [scale] 로 0..1 로 누른다(missing/부재면 0). */
    private fun norm(
        request: PolicyDecisionRequest,
        id: FeatureId,
        scale: Double,
    ): Double {
        val v = request.features[id]?.let { if (it.missing) return 0.0 else it.value } ?: return 0.0
        return (v / scale).coerceIn(0.0, 1.0)
    }

    /** [id] 불리언 신호(missing/부재면 0, present 면 >=0.5 → 1.0). */
    private fun bool(
        request: PolicyDecisionRequest,
        id: FeatureId,
    ): Double = request.features[id]?.let { if (!it.missing && it.value >= 0.5) 1.0 else 0.0 } ?: 0.0

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))

    companion object {
        /** 결정 추적·shadow 비교용 안정 모델 버전 prefix(weights 버전이 뒤에 붙는다). */
        const val MODEL_VERSION_PREFIX: String = "baseline-burst-aware-heuristic"

        /** 초 단위 간격을 0..1 로 누르는 스케일(이 값 이상이면 1.0 포화). */
        private const val GAP_SCALE_SECONDS = 30.0

        /** 인간 응답률을 0..1 로 누르는 스케일. */
        private const val HUMAN_RATE_SCALE = 5.0
    }
}
