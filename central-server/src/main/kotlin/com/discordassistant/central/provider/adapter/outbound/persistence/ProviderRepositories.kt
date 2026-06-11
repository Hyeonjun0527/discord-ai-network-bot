package com.discordassistant.central.provider.adapter.outbound.persistence

import com.discordassistant.central.provider.domain.model.ProviderState
import org.springframework.data.jpa.repository.JpaRepository

/**
 * provider 도메인 Spring Data JPA 리포지토리(adapter/out). 파생 쿼리만 사용(JPQL @Query 없음 →
 * 엔티티 패키지 이동에 영향 없음). 동시성/원자 갱신이 필요한 흐름은 application 서비스가 트랜잭션으로 감싼다.
 */

interface ProviderRepository : JpaRepository<ProviderEntity, Long> {
    fun findByProviderUserIdAndGuildId(
        providerUserId: Long,
        guildId: Long,
    ): ProviderEntity?

    /** 한 유저의 모든 길드 등록(가입 시각 집계용 — licensing 체험 시계 시작점). */
    fun findByProviderUserId(providerUserId: Long): List<ProviderEntity>

    fun findByGuildIdAndState(
        guildId: Long,
        state: ProviderState,
    ): List<ProviderEntity>

    fun deleteByProviderUserIdAndGuildId(
        providerUserId: Long,
        guildId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}

interface ProviderContributionPolicyRepository : JpaRepository<ProviderContributionPolicyEntity, Long> {
    fun findByProviderId(providerId: Long): List<ProviderContributionPolicyEntity>

    fun findByProviderIdIn(providerIds: Collection<Long>): List<ProviderContributionPolicyEntity>

    fun deleteByProviderIdIn(providerIds: Collection<Long>)
}

interface ProviderHealthRepository : JpaRepository<ProviderHealthEntity, Long> {
    fun findByProviderId(providerId: Long): ProviderHealthEntity?
}

interface ProviderScheduleRepository : JpaRepository<ProviderScheduleEntity, Long> {
    fun findByProviderIdAndGuildId(
        providerId: Long,
        guildId: Long,
    ): ProviderScheduleEntity?

    fun deleteByGuildId(guildId: Long)

    fun deleteByProviderIdAndGuildId(
        providerId: Long,
        guildId: Long,
    )
}

interface ProviderDurableRevocationRepository : JpaRepository<ProviderDurableRevocationEntity, Long> {
    fun findByProviderIdAndGuildId(
        providerId: Long,
        guildId: Long,
    ): ProviderDurableRevocationEntity?
}
