package com.discordassistant.central.provider

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.persistence.ProviderContributionPolicyEntity
import com.discordassistant.central.persistence.ProviderContributionPolicyRepository
import com.discordassistant.central.routing.ProviderProfile
import com.discordassistant.central.routing.ProviderProfileProvider
import org.springframework.stereotype.Service

@Service
class DbProviderProfileProvider(
    private val policies: ProviderContributionPolicyRepository,
    private val capabilities: ProviderCapabilityProfileRepository,
) : ProviderProfileProvider {
    override fun profile(providerId: Long): ProviderProfile =
        buildProfile(
            rows = policies.findByProviderId(providerId),
            capability = null,
        )

    override fun profile(
        guildId: Long,
        providerId: Long,
    ): ProviderProfile =
        buildProfile(
            rows = policies.findByProviderId(providerId),
            capability = capabilities.findByGuildIdAndProviderUserId(guildId, providerId),
        )

    override fun profilesFor(providerIds: Collection<Long>): Map<Long, ProviderProfile> {
        if (providerIds.isEmpty()) return emptyMap()

        val ids = providerIds.toSet()
        val policiesByProvider =
            policies
                .findByProviderIdIn(ids)
                .groupBy { it.providerId }

        return ids.associateWith { providerId ->
            buildProfile(
                rows = policiesByProvider[providerId].orEmpty(),
                capability = null,
            )
        }
    }

    override fun profilesFor(
        guildId: Long,
        providerIds: Collection<Long>,
    ): Map<Long, ProviderProfile> {
        if (providerIds.isEmpty()) return emptyMap()

        val ids = providerIds.toSet()
        val policiesByProvider =
            policies
                .findByProviderIdIn(ids)
                .groupBy { it.providerId }
        val capabilitiesByProvider =
            capabilities
                .findByGuildIdAndProviderUserIdIn(guildId, ids)
                .associateBy { it.providerUserId }

        return ids.associateWith { providerId ->
            buildProfile(
                rows = policiesByProvider[providerId].orEmpty(),
                capability = capabilitiesByProvider[providerId],
            )
        }
    }

    private fun buildProfile(
        rows: List<ProviderContributionPolicyEntity>,
        capability: ProviderCapabilityProfileEntity? = null,
    ): ProviderProfile {
        val declaredBurdens =
            rows
                .mapNotNull { ModelBurden.fromName(it.burden) }
                .toSet()

        val policyMaxBurden =
            declaredBurdens
                .filter { it.isNormalBurden() }
                .maxByOrNull { it.rank }

        val capabilityMaxBurden =
            capability
                ?.maxBurden
                ?.takeIf { it.isNormalBurden() }

        val effectiveMaxBurden =
            when {
                rows.isEmpty() -> DEFAULT_MAX_BURDEN_WITHOUT_POLICY
                policyMaxBurden == null -> DEFAULT_MAX_BURDEN_WITH_INVALID_POLICY
                capabilityMaxBurden == null -> policyMaxBurden
                else -> minByRank(policyMaxBurden, capabilityMaxBurden)
            }

        val supported =
            supportedBurdensUpTo(effectiveMaxBurden)
                .toMutableSet()

        if (ModelBurden.RESTRICTED in declaredBurdens) {
            supported.add(ModelBurden.RESTRICTED)
        }

        return ProviderProfile(
            supportedBurdens = supported,
            qualityTier = qualityTier(capability),
        )
    }

    private fun supportedBurdensUpTo(maxBurden: ModelBurden): Set<ModelBurden> =
        ModelBurden.entries
            .asSequence()
            .filter { it.isNormalBurden() }
            .filter { it.rank <= maxBurden.rank }
            .toSet()

    private fun qualityTier(capability: ProviderCapabilityProfileEntity?): String {
        val tier =
            capability
                ?.qualityTier
                ?.takeUnless { it == ModelQualityTier.UNKNOWN }
                ?: ModelQualityTier.STANDARD
        return tier.wire
    }

    private fun ModelBurden.isNormalBurden(): Boolean = this != ModelBurden.RESTRICTED

    private fun minByRank(
        a: ModelBurden,
        b: ModelBurden,
    ): ModelBurden = if (a.rank <= b.rank) a else b

    companion object {
        private val DEFAULT_MAX_BURDEN_WITHOUT_POLICY = ModelBurden.STANDARD
        private val DEFAULT_MAX_BURDEN_WITH_INVALID_POLICY = ModelBurden.LIGHT
    }
}
