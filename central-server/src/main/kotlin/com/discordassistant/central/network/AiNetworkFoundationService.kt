package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiFeedbackRepository
import com.discordassistant.central.persistence.AiNetworkProfileEntity
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.persistence.NetworkOverviewProjectionRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * AI Network 장기 기능들의 얇은 foundation 서비스.
 *
 * 지금 단계의 목적은 "채널 AI / Provider 능력 / 지식공간 / 대시보드 projection" 이 같은 guild
 * scope와 안전한 메타데이터 계약 위에 올라가도록 만드는 것이다. RAG 본문/응답 원문은 여기 저장하지
 * 않는다.
 */
@Service
class AiNetworkFoundationService(
    private val networkProfiles: AiNetworkProfileRepository,
    private val providerCapabilities: ProviderCapabilityProfileRepository,
    private val knowledgeSpaces: KnowledgeSpaceRepository,
    private val overviewProjections: NetworkOverviewProjectionRepository,
    private val channelAis: ChannelAiRepository,
    private val feedbacks: AiFeedbackRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun ensureNetworkProfile(guildId: Long): AiNetworkProfileEntity {
        val now = Instant.now(clock)
        return networkProfiles.findByGuildId(guildId)
            ?: networkProfiles.save(
                AiNetworkProfileEntity(
                    guildId = guildId,
                    displayName = "냥시스턴트 네트워크",
                    tagline = "함께 만드는 AI 네트워크",
                    description = "여러 사용자의 로컬 AI를 안전하게 연결해 디스코드에서 바로 질문하고 답변받는 네트워크입니다.",
                    defaultSafetyNotice = "민감정보(비밀번호·API 키·개인정보)는 입력하지 마세요.",
                    networkLevel = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
    }

    @Transactional
    fun upsertProviderCapability(
        guildId: Long,
        providerUserId: Long,
        providerState: String,
        modelNames: List<String>,
        capabilityTags: List<String>,
        maxBurden: String,
        maxConcurrency: Int,
        dailyLimit: Int,
        overloadRisk: String,
    ): ProviderCapabilityProfileEntity {
        val now = Instant.now(clock)
        val entity =
            providerCapabilities.findByGuildIdAndProviderUserId(guildId, providerUserId)
                ?: ProviderCapabilityProfileEntity(guildId = guildId, providerUserId = providerUserId)
        entity.providerState = providerState
        entity.modelCount = modelNames.size
        entity.modelNames = modelNames.joinToString(",").ifBlank { null }
        entity.capabilityTags = capabilityTags.joinToString(",").ifBlank { null }
        entity.qualityTier = inferQualityTier(modelNames, capabilityTags)
        entity.maxBurden = maxBurden
        entity.maxConcurrency = maxConcurrency.coerceAtLeast(1)
        entity.dailyLimit = dailyLimit.coerceAtLeast(0)
        entity.overloadRisk = overloadRisk
        entity.lastSeenAt = now
        entity.updatedAt = now
        return providerCapabilities.save(entity)
    }

    @Transactional
    fun createKnowledgeSpace(
        guildId: Long,
        channelId: Long?,
        channelAiId: Long?,
        displayName: String,
        createdBy: Long?,
    ): KnowledgeSpaceEntity {
        val now = Instant.now(clock)
        return knowledgeSpaces.save(
            KnowledgeSpaceEntity(
                guildId = guildId,
                channelId = channelId,
                channelAiId = channelAiId,
                displayName = displayName.trim().ifBlank { "채널 지식공간" },
                status = "draft",
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun currentOverview(guildId: Long): NetworkOverviewProjectionEntity? = overviewProjections.findByGuildId(guildId)

    fun isOverviewStale(overview: NetworkOverviewProjectionEntity): Boolean = overview.staleAfter?.isAfter(Instant.now(clock)) != true

    fun overviewFreshnessStatus(overview: NetworkOverviewProjectionEntity): String = if (isOverviewStale(overview)) "stale" else "fresh"

    fun overviewDegradedReason(o: NetworkOverviewProjectionEntity): String? = if (isOverviewStale(o)) "projection_stale" else null

    @Transactional
    fun refreshOverview(guildId: Long): NetworkOverviewProjectionEntity {
        val now = Instant.now(clock)
        val capabilities = providerCapabilities.findByGuildId(guildId)
        val onlineProviders = capabilities.count { it.providerState.equals("ONLINE", ignoreCase = true) }
        val overloadAlerts =
            capabilities.count {
                it.overloadRisk.equals("high", ignoreCase = true) ||
                    it.overloadRisk.equals("critical", ignoreCase = true)
            }
        val channelAiCount = channelAis.findByGuildId(guildId).size
        val knowledgeSpaceCount = knowledgeSpaces.findByGuildId(guildId).size
        val feedbackCount = feedbacks.countByGuildId(guildId).toInt()
        val modelCount =
            capabilities
                .flatMap { it.modelNames.orEmpty().split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .size
        val level =
            inferNetworkLevel(
                onlineProviders = onlineProviders,
                channelAiCount = channelAiCount,
                knowledgeSpaceCount = knowledgeSpaceCount,
                modelCount = modelCount,
                feedbackCount = feedbackCount,
                overloadAlerts = overloadAlerts,
            )
        val entity =
            overviewProjections.findByGuildId(guildId)
                ?: NetworkOverviewProjectionEntity(guildId = guildId)
        entity.onlineProviderCount = onlineProviders
        entity.approvedProviderCount = capabilities.count { !it.providerState.equals("PENDING", ignoreCase = true) }
        entity.modelCount = modelCount
        entity.channelAiCount = channelAiCount
        entity.knowledgeSpaceCount = knowledgeSpaceCount
        entity.feedbackCount = feedbackCount
        entity.overloadAlertCount = overloadAlerts
        entity.networkLevel = level
        entity.healthStatus =
            when {
                overloadAlerts > 0 -> "warning"
                onlineProviders > 0 -> "ready"
                else -> "needs_provider"
            }
        entity.staleAfter = now.plusSeconds(PROJECTION_STALE_SECONDS)
        entity.refreshedAt = now
        return overviewProjections.save(entity)
    }

    private fun inferQualityTier(
        modelNames: List<String>,
        capabilityTags: List<String>,
    ): String {
        val joined = (modelNames + capabilityTags).joinToString(" ").lowercase()
        return when {
            "coding" in joined || "code" in joined || "coder" in joined -> "specialized"
            "70b" in joined || "large" in joined || "long-context" in joined -> "high"
            modelNames.isNotEmpty() -> "standard"
            else -> "unknown"
        }
    }

    private fun inferNetworkLevel(
        onlineProviders: Int,
        channelAiCount: Int,
        knowledgeSpaceCount: Int,
        modelCount: Int,
        feedbackCount: Int,
        overloadAlerts: Int,
    ): Int =
        when {
            feedbackCount >= 5 && overloadAlerts == 0 && knowledgeSpaceCount > 0 && channelAiCount >= 2 && modelCount >= 2 -> 5
            knowledgeSpaceCount > 0 && channelAiCount >= 2 && modelCount >= 2 -> 4
            channelAiCount > 0 && onlineProviders >= 2 -> 3
            onlineProviders >= 1 -> 2
            else -> 1
        }

    private companion object {
        const val PROJECTION_STALE_SECONDS = 300L
    }
}
