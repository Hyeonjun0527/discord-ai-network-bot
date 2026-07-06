package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyEntity
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.shared.ModelQualityTier
import org.springframework.stereotype.Service

/**
 * read-only Provider 팬아웃 선택 협력자: [MultiResponseService] 에서 분리한 후보 선정/용량 판정/
 * 팬아웃 태그 판정. provider capability·라이브 세션을 조회만 하며(write 없음) @Transactional 이
 * 없어 호출자 TX 에 합류한다. 선정 정렬·필터·모델 distinct 규칙은 원본과 동일하다.
 */
@Service
class ProviderFanoutSelector(
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val connectionRegistry: ConnectionRegistry? = null,
) {
    fun selectProviders(
        guildId: Long,
        policy: MultiResponsePolicyEntity,
        maxCandidates: Int = policy.maxCandidates,
        fanoutAllowed: Boolean = true,
    ): List<ProviderCapabilityProfileEntity> {
        val providers = providerCapabilities.findByGuildId(guildId)
        if (providers.any { it.overloadRisk == OverloadRisk.CRITICAL }) return emptyList()
        val effectiveMaxCandidates = if (fanoutAllowed) maxCandidates.coerceIn(1, featureGate.multiResponseMaxFanout()) else 1
        val advancedFanout = effectiveMaxCandidates > 1 || policy.synthesisEnabled
        val ranked =
            providers
                .filter { it.providerState == ProviderAvailability.ONLINE }
                .filter { it.hasLiveCapacity(guildId) }
                .filter { !it.overloadRisk.isOverload }
                .filter { policy.providerDailyLimit <= 0 || it.dailyLimit <= 0 || it.dailyLimit >= policy.providerDailyLimit }
                .filter { !advancedFanout || !it.hasFanoutExclusion() }
                .filter { !advancedFanout || it.hasFanoutOptIn() }
                .sortedWith(
                    compareByDescending<ProviderCapabilityProfileEntity> { it.qualityTier == ModelQualityTier.SPECIALIZED }
                        .thenByDescending { it.qualityTier == ModelQualityTier.HIGH }
                        .thenByDescending { it.modelCount }
                        .thenBy { it.providerUserId },
                )
        val selected = mutableListOf<ProviderCapabilityProfileEntity>()
        val usedModels = mutableSetOf<String>()
        for (provider in ranked) {
            val model = provider.firstModel()?.lowercase().orEmpty()
            if (policy.requireDistinctModels && model.isNotBlank() && !usedModels.add(model)) continue
            selected += provider
            if (selected.size >= effectiveMaxCandidates) break
        }
        return selected
    }

    fun ProviderCapabilityProfileEntity.hasLiveCapacity(guildId: Long): Boolean {
        if (maxConcurrency <= 0) return false
        val session = connectionRegistry?.byProvider(guildId, providerUserId) ?: return true
        if (session.remainingDailyRequests <= 0) return false
        val liveCap = session.capability.maxConcurrency.coerceAtLeast(1)
        val profileCap = maxConcurrency.coerceAtLeast(1)
        return session.activeRequests < minOf(liveCap, profileCap)
    }

    fun ProviderCapabilityProfileEntity.hasFanoutExclusion(): Boolean {
        val tags =
            capabilityTags
                .orEmpty()
                .split(",", " ") // opt-in 판정과 동일한 구분자 — 공백 구분 제외 태그가 새지 않게 한다.
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
        return tags.any { it in FANOUT_EXCLUSION_TAGS }
    }

    fun ProviderCapabilityProfileEntity.hasFanoutOptIn(): Boolean {
        val tags =
            capabilityTags
                .orEmpty()
                .split(",", " ")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        return tags.any { it in FANOUT_OPT_IN_TAGS }
    }

    fun ProviderCapabilityProfileEntity.firstModel(): String? =
        modelNames
            .orEmpty()
            .split(",")
            .firstOrNull { it.isNotBlank() }
            ?.trim()

    companion object {
        val FANOUT_OPT_IN_TAGS = setOf("multi-response", "multi_response", "fanout", "fanout-opt-in")
        val FANOUT_EXCLUSION_TAGS = setOf("fanout-excluded", "fanout-opt-out", "no-fanout", "multi-response-excluded")
    }
}
