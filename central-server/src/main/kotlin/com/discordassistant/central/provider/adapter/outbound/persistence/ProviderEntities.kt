package com.discordassistant.central.provider.adapter.outbound.persistence

import com.discordassistant.central.provider.domain.model.ProviderState
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * provider 도메인 JPA 엔티티(adapter/out). 스키마는 Flyway(`db/migration`)가 소유하고 Hibernate 는
 * 매핑만 한다(ddl-auto=none). 컬럼은 기본 snake_case 매핑. 엔티티명은 단순명 그대로라 JPQL 영향 없음.
 *
 * 설계 원칙(ADR 0003): billing/price/seller/payout 필드는 두지 않는다(비-목표).
 */

@Entity
@Table(name = "provider")
class ProviderEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerUserId: Long = 0,
    var guildId: Long = 0,
    @Convert(converter = ProviderStateConverter::class)
    var state: ProviderState = ProviderState.PENDING,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "provider_schedule")
class ProviderScheduleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerId: Long = 0, // = Discord providerUserId(세션 providerId 와 동일)
    var guildId: Long = 0,
    @Column(name = "from_hour") var fromHour: Int = 0, // UTC 시 0..23
    @Column(name = "to_hour") var toHour: Int = 0,
)

@Entity
@Table(name = "provider_contribution_policy")
class ProviderContributionPolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerId: Long = 0,
    var model: String = "",
    var burden: String = "STANDARD",
    var allowedRole: String = "all",
    var dailyLimit: Int = 0,
    var maxConcurrency: Int = 1,
    var maxSeconds: Int = 120,
)

@Entity
@Table(name = "provider_health")
class ProviderHealthEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerId: Long = 0,
    var failures: Int = 0,
    var lastFailureAt: Instant? = null,
)

@Entity
@Table(name = "provider_durable_revocation")
class ProviderDurableRevocationEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerId: Long = 0,
    var guildId: Long = 0,
    var revokedAtEpoch: Long = 0,
)
