package com.discordassistant.central.provider

import com.discordassistant.central.domain.ModelBurden
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
) : ProviderProfileProvider {
    override fun profile(providerId: Long): ProviderProfile {
        val rows = policies.findByProviderId(providerId)
        if (rows.isEmpty()) {
            return ProviderProfile(supportedBurdens = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD))
        }
        val declared = rows.map { ModelBurden.valueOf(it.burden) }.toSet()
        val top = declared.filter { it != ModelBurden.RESTRICTED }.maxByOrNull { it.ordinal } ?: ModelBurden.LIGHT
        val supported =
            ModelBurden.entries
                .filter { it != ModelBurden.RESTRICTED && it.ordinal <= top.ordinal }
                .toMutableSet()
        if (ModelBurden.RESTRICTED in declared) supported.add(ModelBurden.RESTRICTED)
        return ProviderProfile(supportedBurdens = supported)
    }
}
