package com.discordassistant.central.ainetwork.adapter.outbound.persistence

import com.discordassistant.central.ainetwork.domain.model.FeedbackStatus
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.ModelQualityTier
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** ai-network 도메인 JPA(adapter/out): 네트워크 프로필/능력/개요/피드백/이벤트/채널라우팅정책/유저 호감도. */

@Entity
@Table(name = "ai_network_profile")
class AiNetworkProfileEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var displayName: String = "NEXA 네트워크",
    var tagline: String = "함께 만드는 AI 네트워크",
    var description: String? = null,
    var defaultSafetyNotice: String? = null,
    var networkLevel: Int = 1,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

/**
 * 유저-니아 호감도(P16, V47). 유저당 1행(user_id UNIQUE) — 사용할수록 score 가 단조 증가하고 stage 가 오른다.
 * 길드 무관(니아는 한 정체성) — 점수/단계는 user_id 로만 식별한다. 순위/비교 없는 개인 진척도.
 */
@Entity
@Table(name = "user_affinity")
class UserAffinityEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(unique = true) var userId: Long = 0,
    var score: Long = 0,
    var stage: String = "STRANGER",
    var stageOrdinal: Int = 0,
    var lastInteractionAt: Instant? = null,
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
