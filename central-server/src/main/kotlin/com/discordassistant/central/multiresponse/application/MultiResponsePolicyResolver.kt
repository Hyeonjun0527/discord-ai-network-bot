package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyEntity
import org.springframework.stereotype.Service

/**
 * 순수 정책 해석 협력자: [MultiResponseService] 에서 분리한 비활성 정책 판정·메시지·모드 정규화·
 * synthesis 허용 판정. 부수효과 없는 순수 함수이며 @Transactional 이 없어 호출자 TX 와 무관하다.
 * (`synthesisAllowed` 는 feature gate snapshot 만 읽는다.)
 */
@Service
class MultiResponsePolicyResolver(
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun disabledPolicy(
        guildPolicy: MultiResponsePolicyEntity?,
        channelPolicy: MultiResponsePolicyEntity?,
    ): MultiResponsePolicyEntity? =
        when {
            guildPolicy?.isDisabled() == true -> guildPolicy
            channelPolicy?.isDisabled() == true -> channelPolicy
            else -> null
        }

    fun MultiResponsePolicyEntity.isDisabled(): Boolean =
        mode.trim().lowercase() in DISABLED_POLICY_MODES || !disabledReason.isNullOrBlank()

    fun MultiResponsePolicyEntity.disabledMessage(): String {
        val scope = if (channelId == null) "guild" else "channel"
        val reason = disabledReason?.trim()?.takeIf { it.isNotBlank() } ?: "policy_disabled"
        return "multi-response disabled by $scope policy: $reason".take(500)
    }

    fun disabledReasonForMode(mode: String): String? =
        mode.takeIf { it.trim().lowercase() in DISABLED_POLICY_MODES }?.let { "policy_disabled" }

    fun sanitizeDisabledReason(reason: String?): String? = reason?.trim()?.take(500)

    fun runtimeObservationMode(
        responseMode: String,
        maxCandidates: Int,
    ): String =
        when {
            maxCandidates > 1 -> "compare"
            responseMode.equals("deep", ignoreCase = true) -> "deep"
            responseMode.equals("saving", ignoreCase = true) -> "saving"
            responseMode.equals("fast", ignoreCase = true) -> "fast"
            else -> "single"
        }

    fun synthesisAllowed(
        strategy: String,
        selectedCandidateIds: List<Long>,
    ): Boolean =
        featureGate.snapshot().multiResponseSynthesis ||
            selectedCandidateIds.size <= 1 &&
            strategy.trim().lowercase() in SYNTHESIS_FLAG_SAFE_SELECTION_STRATEGIES

    companion object {
        val DISABLED_POLICY_MODES = setOf("disabled", "off", "kill_switch", "kill-switch")
        val SYNTHESIS_FLAG_SAFE_SELECTION_STRATEGIES =
            setOf("single_route_runtime", "best_successful_candidate", "best_by_heuristic")
    }
}
