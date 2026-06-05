package com.discordassistant.central.persistence

import com.discordassistant.central.domain.FeedbackStatus
import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.domain.OverloadRisk
import com.discordassistant.central.domain.ProviderAvailability
import jakarta.persistence.Convert
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
@Table(name = "ai_network_profile")
class AiNetworkProfileEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var displayName: String = "냥시스턴트 네트워크",
    var tagline: String = "함께 만드는 AI 네트워크",
    var description: String? = null,
    var defaultSafetyNotice: String? = null,
    var networkLevel: Int = 1,
    // 활동 경험치/레벨(V34, Phase 1). networkLevel(milestone 기반 "구성 단계")과 별개 — 사용량 기반 단조 증가.
    var totalXp: Long = 0,
    var aiLevel: Int = 1,
    var lastXpAt: Instant? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "provider_capability_profile")
class ProviderCapabilityProfileEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var providerUserId: Long = 0,
    @Convert(converter = ProviderAvailabilityConverter::class)
    var providerState: ProviderAvailability = ProviderAvailability.UNKNOWN,
    var modelCount: Int = 0,
    var modelNames: String? = null,
    var capabilityTags: String? = null,
    @Convert(converter = ModelQualityTierConverter::class)
    var qualityTier: ModelQualityTier = ModelQualityTier.UNKNOWN,
    @Convert(converter = ModelBurdenConverter::class)
    var maxBurden: ModelBurden = ModelBurden.LIGHT,
    var maxConcurrency: Int = 1,
    var dailyLimit: Int = 0,
    var availableFromHour: Int? = null,
    var availableToHour: Int? = null,
    @Convert(converter = OverloadRiskConverter::class)
    var overloadRisk: OverloadRisk = OverloadRisk.NORMAL,
    var lastSeenAt: Instant? = null,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "network_overview_projection")
class NetworkOverviewProjectionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var onlineProviderCount: Int = 0,
    var approvedProviderCount: Int = 0,
    var modelCount: Int = 0,
    var channelAiCount: Int = 0,
    var knowledgeSpaceCount: Int = 0,
    var feedbackCount: Int = 0,
    var overloadAlertCount: Int = 0,
    var networkLevel: Int = 1,
    var healthStatus: String = "unknown",
    var staleAfter: Instant? = null,
    var refreshedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "ai_feedback")
class AiFeedbackEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var requestId: String? = null,
    var userId: Long? = null,
    var channelAiId: Long? = null,
    var rating: Int? = null,
    var feedbackType: String = "general",
    var reason: String? = null,
    @Convert(converter = FeedbackStatusConverter::class)
    var status: FeedbackStatus = FeedbackStatus.OPEN,
    var reviewedBy: Long? = null,
    var reviewedAt: Instant? = null,
    var resolutionReason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "ai_network_event")
class AiNetworkEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var eventType: String = "",
    var actorUserId: Long? = null,
    var providerUserId: Long? = null,
    var channelId: Long? = null,
    var title: String = "",
    var summary: String? = null,
    var metadata: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "channel_ai_routing_policy")
class ChannelAiRoutingPolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var channelAiId: Long? = null,
    var responseMode: String = "balanced",
    var preferredModel: String? = null,
    var allowedModels: String? = null,
    var minQualityTier: String = "standard",
    var maxCandidates: Int = 1,
    var providerTagFilter: String? = null,
    var costGuard: String = "provider_safe",
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)
