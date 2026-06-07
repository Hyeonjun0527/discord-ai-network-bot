package com.discordassistant.central.provider.application

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyRepository
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프로바이더 기여 정책 (LAUNCH 차수 11). 모델별 부담수준·한도/동시/시간을 설정한다.
 * `/provider-models` `/provider-limit` 의 백엔드.
 * (공개 대상 scope 는 제거됨 — '서버 멤버만'은 길드별 라우팅 격리로 보장되고 세분화는 강제 불가.)
 */
@Service
class ContributionPolicyService(
    private val repo: ProviderContributionPolicyRepository,
    private val audit: AuditLog,
) {
    /** 제공 모델 목록 설정(기존 정책을 모델 단위로 재구성). burden 은 모델별 기본 부담수준. */
    @Transactional
    fun setModels(
        providerId: Long,
        models: List<String>,
        burden: ModelBurden,
    ) {
        val existing = repo.findByProviderId(providerId).associateBy { it.model }
        val keep = models.toSet()
        // 제거된 모델 정책 삭제
        existing.values.filter { it.model !in keep }.forEach { repo.delete(it) }
        // 신규 모델 정책 추가(기존은 유지)
        models.filter { it !in existing }.forEach {
            repo.save(ProviderContributionPolicyEntity(providerId = providerId, model = it, burden = burden.name))
        }
        audit.record("provider_models", "provider:$providerId", "provider:$providerId", models.toString())
    }

    /** 모델별 한도(일일/동시/최대시간) 설정. */
    @Transactional
    fun setLimit(
        providerId: Long,
        model: String,
        dailyLimit: Int,
        maxConcurrency: Int,
        maxSeconds: Int,
    ) {
        val p = policyFor(providerId, model)
        p.dailyLimit = dailyLimit
        p.maxConcurrency = maxConcurrency
        p.maxSeconds = maxSeconds
        repo.save(p)
        audit.record("provider_limit", "provider:$providerId", "model:$model", "$dailyLimit/$maxConcurrency/$maxSeconds")
    }

    fun policies(providerId: Long): List<ProviderContributionPolicyEntity> = repo.findByProviderId(providerId)

    @Transactional
    fun deleteProviders(providerIds: Collection<Long>) {
        if (providerIds.isNotEmpty()) {
            repo.deleteByProviderIdIn(providerIds)
        }
    }

    private fun policyFor(
        providerId: Long,
        model: String,
    ): ProviderContributionPolicyEntity =
        repo.findByProviderId(providerId).firstOrNull { it.model == model }
            ?: ProviderContributionPolicyEntity(providerId = providerId, model = model)
}
