package com.discordassistant.central.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA 엔티티 (specs §14 데이터 모델). 스키마는 Flyway(`db/migration`)가 소유하고 Hibernate 는
 * 매핑만 한다(ddl-auto=none). 컬럼은 기본 snake_case 매핑.
 *
 * 설계 원칙(ADR 0003): billing/price/seller/payout 필드는 두지 않는다(비-목표).
 */

@Entity
@Table(name = "guild")
class GuildEntity(
    @Id var id: Long = 0, // Discord guild_id
    @Column(name = "privacy_mode") var privacyMode: String = "C_ADMIN_ONLY",
    @Column(name = "auto_approve") var autoApprove: Boolean = false,
    @Column(name = "default_model") var defaultModel: String? = null,
    @Column(name = "language") var language: String = "ko",
    @Column(name = "welcome_message") var welcomeMessage: String? = null,
)

@Entity
@Table(name = "allowed_channel")
class AllowedChannelEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
)

@Entity
@Table(name = "role_policy")
class RolePolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var roleId: Long = 0,
    var maxBurden: String = "LIGHT",
    var dailyLimit: Int = 0,
)

@Entity
@Table(name = "provider")
class ProviderEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerUserId: Long = 0,
    var guildId: Long = 0,
    var state: String = "PENDING",
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
@Table(name = "ai_request")
class AiRequestEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var requestId: String = "",
    var guildId: Long = 0,
    var channelId: Long = 0,
    var userId: Long = 0,
    var weight: String = "LIGHT",
    var requiredBurden: String = "LIGHT",
    var providerId: Long? = null,
    var state: String = "RECEIVED",
    var failReason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "usage_log")
class UsageLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var userId: Long = 0,
    var requestId: String = "",
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "contribution_log")
class ContributionLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var providerId: Long = 0,
    var requestId: String = "",
    var createdAt: Instant = Instant.EPOCH,
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
@Table(name = "channel_ai_profile")
class ChannelAiProfileEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    @Column(name = "display_name") var displayName: String = "냥시스턴트",
    @Column(name = "avatar_url") var avatarUrl: String? = null,
)

@Entity
@Table(name = "channel_ai")
class ChannelAiEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    @Column(name = "display_name") var displayName: String = "냥시스턴트",
    @Column(name = "avatar_url") var avatarUrl: String? = null,
    @Column(name = "active_behavior_version_id") var activeBehaviorVersionId: Long? = null,
    var source: String = "manual",
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "ai_behavior_version")
class AiBehaviorVersionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var channelAiId: Long = 0,
    var version: Int = 1,
    var purpose: String = "general_assistant",
    var tone: String = "friendly",
    var answerLength: String = "balanced",
    var constitution: String? = null,
    var safetyLevel: String = "standard",
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
    var changeSummary: String? = null,
)

@Entity
@Table(name = "ai_change_proposal")
class AiChangeProposalEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var channelAiId: Long? = null,
    var proposedBehaviorId: Long? = null,
    var status: String = "approved",
    var requestedBy: Long? = null,
    var reviewedBy: Long? = null,
    var reason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var reviewedAt: Instant? = null,
)

@Entity
@Table(name = "customization_audit_log")
class CustomizationAuditLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var actorId: Long? = null,
    var action: String = "",
    var targetType: String = "",
    var targetId: Long? = null,
    var summary: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)
