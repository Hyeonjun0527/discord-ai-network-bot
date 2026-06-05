package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.ainetwork.application.ProviderSafetyService
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyEntity
import com.discordassistant.central.multiresponse.adapter.outbound.persistence.MultiResponsePolicyRepository
import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * read-only 팬아웃 추천 협력자: [MultiResponseService] 에서 분리한 `recommendFanout`.
 * 정책을 조회만 하고(write 없음) @Transactional 이 없어 호출자 TX 에 합류한다. 비활성 판정은
 * [MultiResponsePolicyResolver], 후보 선정은 [ProviderFanoutSelector] 를 공유해 동작이 동일하다.
 */
@Service
class MultiResponseFanoutPlanner(
    private val policies: MultiResponsePolicyRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
    private val safety: ProviderSafetyService? = null,
    private val connectionRegistry: ConnectionRegistry? = null,
    private val policyResolver: MultiResponsePolicyResolver = MultiResponsePolicyResolver(featureGate),
    private val providerSelector: ProviderFanoutSelector =
        ProviderFanoutSelector(
            providerCapabilities = providerCapabilities,
            featureGate = featureGate,
            connectionRegistry = connectionRegistry,
        ),
) {
    fun recommendFanout(
        guildId: Long,
        channelId: Long? = null,
        responseMode: String = "balanced",
        requestedCandidates: Int = 1,
    ): MultiResponseFanoutRecommendation {
        featureGate.requireMultiResponseDashboardEnabled()
        val guildPolicy = policies.findByGuildIdAndChannelIdIsNull(guildId)
        val channelPolicy = channelId?.let { policies.findByGuildIdAndChannelId(guildId, it) }
        val disabledPolicy = with(policyResolver) { disabledPolicy(guildPolicy, channelPolicy) }
        val policySource =
            when {
                channelPolicy != null -> "channel"
                guildPolicy != null -> "guild"
                else -> "default"
            }
        val policy =
            channelPolicy
                ?: guildPolicy
                ?: MultiResponsePolicyEntity(
                    guildId = guildId,
                    channelId = channelId,
                    mode =
                        com.discordassistant.central.multiresponse.domain.model.MultiResponseMode
                            .fromWire(responseMode)
                            .wire,
                    maxCandidates = requestedCandidates.coerceIn(1, featureGate.multiResponseMaxFanout()),
                    createdAt = Instant.now(clock),
                    updatedAt = Instant.now(clock),
                )
        if (disabledPolicy != null) {
            return MultiResponseFanoutRecommendation.disabled(
                guildId = guildId,
                channelId = channelId,
                policySource = policySource,
                policyMode = disabledPolicy.mode,
                reason = with(policyResolver) { disabledPolicy.disabledMessage() },
            )
        }
        val executionPlan = safety?.executionPlan(guildId, policy.mode, policy.maxCandidates)
        val maxSafeCandidates = executionPlan?.maxSafeCandidates ?: policy.maxCandidates
        if (maxSafeCandidates <= 0) {
            return MultiResponseFanoutRecommendation(
                guildId = guildId,
                channelId = channelId,
                policySource = policySource,
                policyMode = policy.mode,
                requestedCandidates = policy.maxCandidates,
                maxSafeCandidates = 0,
                recommendedCandidateCount = 0,
                fanoutAllowed = false,
                status = "blocked_provider_safety",
                reasons = executionPlan?.reasons?.takeIf { it.isNotEmpty() } ?: listOf("provider_safety_blocked"),
                providers = emptyList(),
            )
        }
        val selectedProviders =
            with(providerSelector) {
                selectProviders(
                    guildId = guildId,
                    policy = policy,
                    maxCandidates = maxSafeCandidates,
                    fanoutAllowed = executionPlan?.fanoutAllowed ?: true,
                )
            }
        val status =
            when {
                selectedProviders.isEmpty() -> "no_provider"
                selectedProviders.size > 1 -> "fanout_recommended"
                else -> "single_recommended"
            }
        return MultiResponseFanoutRecommendation(
            guildId = guildId,
            channelId = channelId,
            policySource = policySource,
            policyMode = policy.mode,
            requestedCandidates = policy.maxCandidates,
            maxSafeCandidates = maxSafeCandidates,
            recommendedCandidateCount = selectedProviders.size,
            fanoutAllowed = (executionPlan?.fanoutAllowed ?: true) && selectedProviders.size > 1,
            status = status,
            reasons = executionPlan?.reasons.orEmpty().ifEmpty { listOf(status) },
            providers =
                selectedProviders.map {
                    MultiResponseRecommendedProvider(
                        providerUserId = it.providerUserId,
                        modelName = with(providerSelector) { it.firstModel() },
                        qualityTier = it.qualityTier.wire,
                        overloadRisk = it.overloadRisk.wire,
                    )
                },
        )
    }
}
