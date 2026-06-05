package com.discordassistant.central.persistence

import com.discordassistant.central.domain.CandidateStatus
import com.discordassistant.central.domain.FeedbackStatus
import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.domain.MultiResponseRunStatus
import com.discordassistant.central.domain.OverloadRisk
import com.discordassistant.central.domain.PresetImportStatus
import com.discordassistant.central.domain.PresetReportStatus
import com.discordassistant.central.domain.PresetStatus
import com.discordassistant.central.domain.ProviderAvailability
import com.discordassistant.central.domain.PublishedPresetStatus
import com.discordassistant.central.domain.SynthesisStatus
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
@Table(name = "multi_response_policy")
class MultiResponsePolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var channelAiId: Long? = null,
    var mode: String = "single",
    var maxCandidates: Int = 1,
    var requireDistinctModels: Boolean = false,
    var providerDailyLimit: Int = 0,
    var timeoutSeconds: Int = 120,
    var synthesisEnabled: Boolean = false,
    var disabledReason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "multi_response_run")
class MultiResponseRunEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var requestId: String = "",
    var policyId: Long? = null,
    @Convert(converter = MultiResponseRunStatusConverter::class)
    var status: MultiResponseRunStatus = MultiResponseRunStatus.CREATED,
    var candidateCount: Int = 0,
    var selectedCandidateId: Long? = null,
    var ragContextStatus: String? = null,
    var ragContextSourceIds: String? = null,
    var ragContextChars: Int = 0,
    var startedAt: Instant = Instant.EPOCH,
    var finishedAt: Instant? = null,
    var failureReason: String? = null,
) {
    /** 도메인 전이 가드: 허용되지 않은 status 전이는 거부([MultiResponseRunStatus] ALLOWED 맵 기준). */
    fun transitionTo(next: MultiResponseRunStatus) {
        require(status.canTransitionTo(next)) { "illegal multi-response run status transition: ${status.wire} -> ${next.wire}" }
        status = next
    }
}

@Entity
@Table(name = "candidate_answer")
class CandidateAnswerEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var runId: Long = 0,
    var providerUserId: Long? = null,
    var modelName: String? = null,
    var answerRef: String? = null,
    @Convert(converter = CandidateStatusConverter::class)
    var status: CandidateStatus = CandidateStatus.PENDING,
    var latencyMs: Int? = null,
    var safetyFlags: String? = null,
    var qualityScore: Int? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "synthesis_result")
class SynthesisResultEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var runId: Long = 0,
    var answerRef: String? = null,
    @Convert(converter = SynthesisStatusConverter::class)
    var status: SynthesisStatus = SynthesisStatus.PENDING,
    var selectedCandidateIds: String? = null,
    var strategy: String = "best_by_heuristic",
    var qualitySummary: String? = null,
    var safetySummary: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "ai_preset")
class AiPresetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var ownerUserId: Long? = null,
    var name: String = "",
    var summary: String? = null,
    var category: String = "general",
    var visibility: String = "guild_private",
    @Convert(converter = PresetStatusConverter::class)
    var status: PresetStatus = PresetStatus.DRAFT,
    var currentRevisionId: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_revision")
class PresetRevisionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var presetId: Long = 0,
    var revision: Int = 1,
    var name: String = "",
    var purpose: String = "",
    var tone: String = "",
    var answerLength: String = "balanced",
    var constitution: String? = null,
    var safetyLevel: String = "standard",
    var responseMode: String = "balanced",
    var preferredModel: String? = null,
    var minQualityTier: String = "standard",
    var maxCandidates: Int = 1,
    var providerTagFilter: String? = null,
    var tags: String? = null,
    var costGuard: String = "provider_safe",
    var knowledgeSlotNames: String? = null,
    var knowledgeGuide: String? = null,
    var exampleQuestions: String? = null,
    var changeSummary: String? = null,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "published_preset")
class PublishedPresetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var presetId: Long = 0,
    var revisionId: Long = 0,
    var publisherGuildId: Long = 0,
    var publisherUserId: Long? = null,
    var slug: String = "",
    var title: String = "",
    var description: String? = null,
    @Convert(converter = PublishedPresetStatusConverter::class)
    var status: PublishedPresetStatus = PublishedPresetStatus.PUBLISHED,
    var likeCount: Int = 0,
    var importCount: Int = 0,
    var reportCount: Int = 0,
    var publishedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_import")
class PresetImportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var publishedPresetId: Long = 0,
    var sourceRevisionId: Long? = null,
    var targetGuildId: Long = 0,
    var targetChannelId: Long? = null,
    var importedBy: Long? = null,
    var importedPresetId: Long? = null,
    var createdChannelAiId: Long? = null,
    var createdBehaviorVersionId: Long? = null,
    @Convert(converter = PresetImportStatusConverter::class)
    var status: PresetImportStatus = PresetImportStatus.IMPORTED,
    var importedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_reaction")
class PresetReactionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var publishedPresetId: Long = 0,
    var userId: Long = 0,
    var reaction: String = "like",
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "preset_report")
class PresetReportEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var publishedPresetId: Long = 0,
    var reporterUserId: Long? = null,
    var reason: String = "",
    var reasonCode: String = "other",
    var details: String? = null,
    @Convert(converter = PresetReportStatusConverter::class)
    var status: PresetReportStatus = PresetReportStatus.OPEN,
    var createdAt: Instant = Instant.EPOCH,
    var reviewedBy: Long? = null,
    var reviewedAt: Instant? = null,
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
