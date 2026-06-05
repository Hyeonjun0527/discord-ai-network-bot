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

/**
 * DB(contribution policy) 기반 프로바이더 프로필 (K-차수 11). 상위 부담 지원 시 하위도 지원으로 본다.
 * 정책 미설정 프로바이더는 보수적 기본값(LIGHT/STANDARD).
 */
@Service
class DbProviderProfileProvider(
    private val policies: ProviderContributionPolicyRepository,
    private val capabilities: ProviderCapabilityProfileRepository,
) : ProviderProfileProvider {
    override fun profile(providerId: Long): ProviderProfile = buildProfile(policies.findByProviderId(providerId))

    override fun profile(
        guildId: Long,
        providerId: Long,
    ): ProviderProfile =
        buildProfile(
            rows = policies.findByProviderId(providerId),
            capability = capabilities.findByGuildIdAndProviderUserId(guildId, providerId),
        )

    /** 후보 프로바이더 프로필을 IN 조회 1회로 일괄 산출(라우팅 핫패스의 후보당 쿼리 N+1 제거). */
    override fun profilesFor(providerIds: Collection<Long>): Map<Long, ProviderProfile> {
        if (providerIds.isEmpty()) return emptyMap()
        val byProvider = policies.findByProviderIdIn(providerIds.toSet()).groupBy { it.providerId }
        return providerIds.associateWith { buildProfile(byProvider[it].orEmpty()) }
    }

    override fun profilesFor(
        guildId: Long,
        providerIds: Collection<Long>,
    ): Map<Long, ProviderProfile> {
        if (providerIds.isEmpty()) return emptyMap()
        val ids = providerIds.toSet()
        val byProvider = policies.findByProviderIdIn(ids).groupBy { it.providerId }
        val capabilitiesByProvider =
            capabilities
                .findByGuildIdAndProviderUserIdIn(guildId, ids)
                .associateBy { it.providerUserId }
        return providerIds.associateWith { buildProfile(byProvider[it].orEmpty(), capabilitiesByProvider[it]) }
    }

    private fun buildProfile(
        rows: List<ProviderContributionPolicyEntity>,
        capability: ProviderCapabilityProfileEntity? = null,
    ): ProviderProfile {
        if (rows.isEmpty()) {
            val maxBurden = capability?.maxBurden ?: ModelBurden.STANDARD
            return ProviderProfile(
                supportedBurdens = supportedBurdens(maxBurden),
                qualityTier = qualityTier(capability),
            )
        }
        val declared = rows.map { ModelBurden.valueOf(it.burden) }.toSet()
        val top = declared.filter { it != ModelBurden.RESTRICTED }.maxByOrNull { it.ordinal } ?: ModelBurden.LIGHT
        val supported =
            ModelBurden.entries
                .filter { it != ModelBurden.RESTRICTED && it.ordinal <= top.ordinal }
                .toMutableSet()
        if (ModelBurden.RESTRICTED in declared) supported.add(ModelBurden.RESTRICTED)
        return ProviderProfile(supportedBurdens = supported, qualityTier = qualityTier(capability))
    }

    private fun supportedBurdens(maxBurden: ModelBurden): Set<ModelBurden> =
        ModelBurden.entries
            .filter { it != ModelBurden.RESTRICTED && it.rank <= maxBurden.rank }
            .toSet()

    private fun qualityTier(capability: ProviderCapabilityProfileEntity?): String =
        (capability?.qualityTier ?: ModelQualityTier.STANDARD).wire
}
