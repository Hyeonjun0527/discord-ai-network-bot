package com.discordassistant.central.routing.adapter.outbound

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyRepository
import com.discordassistant.central.routing.application.port.ProviderProfileProvider
import com.discordassistant.central.routing.domain.model.ProviderProfile
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.ModelQualityTier
import org.springframework.stereotype.Service

/**
 * provider contribution policy 를 routing [ProviderProfile] 로 변환한다.
 *
 * 우선순위: guild channel allow-list 는 [com.discordassistant.central.routing.application.port.RoutingPolicy] 가
 * request admission 에서 먼저 처리한다. provider contribution policy 는 모델/burden/capability 한도만 소유하고
 * channel scope 를 소유하지 않는다(V41 이후 allowed_role 제거, allowed_channel 컬럼 없음). 따라서 이 DB 어댑터는
 * [ProviderProfile.allowedChannelIds]와 [ProviderProfile.allowedRoleIds]를 채우지 않는다. 별도 provider-scoped
 * channel 제한을 도입하려면 Flyway 스키마와 이 매핑 테스트를 함께 바꿔야 한다.
 */
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
                // 정책 행이 없어도 하드웨어 capability 가 선언돼 있으면 그 상한을 존중한다 — LIGHT 박스에 STANDARD 를
                // 광고해 라우터가 약한 provider 로 과부하 요청을 보내는 비대칭을 없앤다(정책 있을 때만 capping 하던 버그).
                rows.isEmpty() ->
                    capabilityMaxBurden?.let { minByRank(DEFAULT_MAX_BURDEN_WITHOUT_POLICY, it) }
                        ?: DEFAULT_MAX_BURDEN_WITHOUT_POLICY
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
