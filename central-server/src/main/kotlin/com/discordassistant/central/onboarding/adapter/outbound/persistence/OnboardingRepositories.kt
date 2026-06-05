package com.discordassistant.central.onboarding.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** onboarding 도메인 Spring Data JPA 리포지토리(adapter/out). */

interface GuildOnboardingConsentRepository : JpaRepository<GuildOnboardingConsentEntity, Long> {
    fun findByGuildIdOrderByCreatedAtDesc(guildId: Long): List<GuildOnboardingConsentEntity>

    fun deleteByGuildId(guildId: Long)
}

interface GuildOnboardingRunRepository : JpaRepository<GuildOnboardingRunEntity, Long> {
    fun findByGuildIdOrderByCreatedAtDesc(guildId: Long): List<GuildOnboardingRunEntity>

    fun findByProposalId(proposalId: Long): GuildOnboardingRunEntity?

    fun deleteByGuildId(guildId: Long)
}

interface GuildOnboardingOptOutRepository : JpaRepository<GuildOnboardingOptOutEntity, Long> {
    fun findByGuildId(guildId: Long): List<GuildOnboardingOptOutEntity>

    fun existsByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    ): Boolean

    @org.springframework.transaction.annotation.Transactional
    fun deleteByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}
