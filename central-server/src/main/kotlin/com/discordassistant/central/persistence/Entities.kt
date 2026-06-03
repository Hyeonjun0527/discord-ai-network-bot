package com.discordassistant.central.persistence

import com.discordassistant.central.domain.ProposalStatus
import com.discordassistant.central.domain.ProviderState
import jakarta.persistence.Column
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
@Table(name = "ai_admin_role")
class AiAdminRoleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var roleId: Long = 0,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
)

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
    @Convert(converter = ProposalStatusConverter::class)
    var status: ProposalStatus = ProposalStatus.APPROVED,
    var requestedBy: Long? = null,
    var reviewedBy: Long? = null,
    var reason: String? = null,
    @Column(name = "payload_hash") var payloadHash: String? = null,
    @Column(name = "routing_snapshot", length = 2000) var routingSnapshot: String? = null,
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
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "provider_capability_profile")
class ProviderCapabilityProfileEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var providerUserId: Long = 0,
    var providerState: String = "UNKNOWN",
    var modelCount: Int = 0,
    var modelNames: String? = null,
    var capabilityTags: String? = null,
    var qualityTier: String = "unknown",
    var maxBurden: String = "LIGHT",
    var maxConcurrency: Int = 1,
    var dailyLimit: Int = 0,
    var availableFromHour: Int? = null,
    var availableToHour: Int? = null,
    var overloadRisk: String = "normal",
    var lastSeenAt: Instant? = null,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "knowledge_space")
class KnowledgeSpaceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var channelAiId: Long? = null,
    var displayName: String = "",
    var status: String = "draft",
    var sourceCount: Int = 0,
    var chunkCount: Int = 0,
    var embeddingModel: String? = null,
    var indexName: String? = null,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "knowledge_source")
class KnowledgeSourceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var guildId: Long = 0,
    var sourceType: String = "",
    var sourceUri: String? = null,
    var title: String = "",
    var status: String = "pending",
    var contentHash: String? = null,
    var riskLevel: String = "normal",
    var addedBy: Long? = null,
    var addedAt: Instant = Instant.EPOCH,
    var indexedAt: Instant? = null,
)

@Entity
@Table(name = "knowledge_document")
class KnowledgeDocumentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var knowledgeSourceId: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var title: String = "",
    var documentType: String = "markdown",
    var contentHash: String = "",
    var tokenEstimate: Int = 0,
    var status: String = "parsed",
    var parsedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "knowledge_chunk")
class KnowledgeChunkEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var knowledgeDocumentId: Long = 0,
    var knowledgeSourceId: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var chunkIndex: Int = 0,
    var title: String = "",
    var contentPreview: String = "",
    var embeddingTextHash: String = "",
    var tokenEstimate: Int = 0,
    var qdrantPointId: String? = null,
    var status: String = "ready",
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "embedding_index_job")
class EmbeddingIndexJobEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var knowledgeSpaceId: Long = 0,
    var triggeredBy: Long? = null,
    var jobType: String = "rebuild",
    var status: String = "queued",
    var collectionName: String = "discord_ai_network",
    var embeddingModel: String = "text-embedding-3-large",
    var sourceCount: Int = 0,
    var chunkCount: Int = 0,
    var failureReason: String? = null,
    var queuedAt: Instant = Instant.EPOCH,
    var startedAt: Instant? = null,
    var finishedAt: Instant? = null,
)

@Entity
@Table(name = "retrieval_policy")
class RetrievalPolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long? = null,
    var knowledgeSpaceId: Long? = null,
    @Column(name = "top_k") var topK: Int = 6,
    var tokenBudget: Int = 1800,
    var rerankEnabled: Boolean = true,
    var sourcePriority: String? = null,
    var status: String = "active",
    var createdAt: Instant = Instant.EPOCH,
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
    var status: String = "open",
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
    var status: String = "created",
    var candidateCount: Int = 0,
    var selectedCandidateId: Long? = null,
    var ragContextStatus: String? = null,
    var ragContextSourceIds: String? = null,
    var ragContextChars: Int = 0,
    var startedAt: Instant = Instant.EPOCH,
    var finishedAt: Instant? = null,
    var failureReason: String? = null,
)

@Entity
@Table(name = "candidate_answer")
class CandidateAnswerEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var runId: Long = 0,
    var providerUserId: Long? = null,
    var modelName: String? = null,
    var answerRef: String? = null,
    var status: String = "pending",
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
    var status: String = "pending",
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
    var status: String = "draft",
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
    var status: String = "published",
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
    var status: String = "imported",
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
    var status: String = "open",
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

@Entity
@Table(name = "provider_durable_revocation")
class ProviderDurableRevocationEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var providerId: Long = 0,
    var guildId: Long = 0,
    var revokedAtEpoch: Long = 0,
)

@Entity
@Table(name = "blocklist")
class BlocklistEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var userId: Long = 0,
    var blockedBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
)
