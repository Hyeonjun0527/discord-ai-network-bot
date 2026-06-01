package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiNetworkEventEntity
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.NetworkOverviewProjectionEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AiNetworkGrowthService(
    private val foundation: AiNetworkFoundationService,
    private val events: AiNetworkEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun recordProviderJoined(
        guildId: Long,
        providerUserId: Long,
        modelNames: List<String>,
        capabilityTags: List<String>,
        maxBurden: String,
        maxConcurrency: Int,
        dailyLimit: Int,
    ): ProviderGrowthResult {
        val before = foundation.refreshOverview(guildId)
        val capability =
            foundation.upsertProviderCapability(
                guildId = guildId,
                providerUserId = providerUserId,
                providerState = "ONLINE",
                modelNames = modelNames,
                capabilityTags = capabilityTags,
                maxBurden = maxBurden,
                maxConcurrency = maxConcurrency,
                dailyLimit = dailyLimit,
                overloadRisk = "normal",
            )
        val overview = foundation.refreshOverview(guildId)
        val title = "Provider가 AI 네트워크에 참여했어요"
        val summary =
            "이 서버는 ${modelNames.joinToString(",").ifBlank { "로컬 AI" }} 모델과 " +
                "${capabilityTags.joinToString(",").ifBlank { "일반" }} 능력을 사용할 수 있게 됐어요."
        val impact =
            providerImpact(
                levelBefore = before.networkLevel,
                levelAfter = overview.networkLevel,
                modelNames = modelNames,
                capabilityTags = capabilityTags,
                maxConcurrency = maxConcurrency,
                dailyLimit = dailyLimit,
            )
        val event =
            events.save(
                AiNetworkEventEntity(
                    guildId = guildId,
                    eventType = "provider_joined",
                    providerUserId = providerUserId,
                    title = title,
                    summary = summary,
                    metadata =
                        listOf(
                            "models=${modelNames.joinToString(",")}",
                            "tags=${capabilityTags.joinToString(",")}",
                            "maxBurden=$maxBurden",
                            "maxConcurrency=$maxConcurrency",
                            "dailyLimit=$dailyLimit",
                            "levelBefore=${before.networkLevel}",
                            "levelAfter=${overview.networkLevel}",
                            "impact=${impact.joinToString("|")}",
                        ).joinToString(";"),
                    createdAt = Instant.now(clock),
                ),
            )
        maybeRecordLevelUp(guildId, overview)
        return ProviderGrowthResult(capability.id, event.id, overview.networkLevel)
    }

    @Transactional
    fun maybeRecordLevelUp(
        guildId: Long,
        overview: NetworkOverviewProjectionEntity = foundation.refreshOverview(guildId),
    ): AiNetworkEventEntity? {
        val levelEvents = events.findByGuildIdAndEventType(guildId, "network_level")
        val lastLevel =
            levelEvents
                .mapNotNull { it.metadata?.substringAfter("level=", "")?.toIntOrNull() }
                .maxOrNull() ?: 0
        if (overview.networkLevel <= lastLevel) return null
        return events.save(
            AiNetworkEventEntity(
                guildId = guildId,
                eventType = "network_level",
                title = "AI 네트워크 레벨 ${overview.networkLevel} 달성",
                summary = levelDescription(overview.networkLevel),
                metadata = "level=${overview.networkLevel}",
                createdAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun levelStatus(guildId: Long): AiNetworkLevelStatus {
        val overview = foundation.refreshOverview(guildId)
        val milestones = levelMilestones(overview)
        return AiNetworkLevelStatus(
            guildId = guildId,
            currentLevel = overview.networkLevel,
            currentTitle = levelTitle(overview.networkLevel),
            currentDescription = levelDescription(overview.networkLevel),
            nextMilestone = milestones.firstOrNull { !it.achieved },
            milestones = milestones,
        )
    }

    fun timeline(guildId: Long): List<AiNetworkEventEntity> = events.findTop20ByGuildIdOrderByCreatedAtDesc(guildId)

    fun timelineCards(guildId: Long): List<NetworkGrowthEventCard> =
        timeline(guildId).map { event ->
            val metadata = parseMetadata(event.metadata)
            NetworkGrowthEventCard(
                id = event.id,
                eventType = event.eventType,
                providerUserId = event.providerUserId,
                channelId = event.channelId,
                title = event.title,
                summary = event.summary,
                impactBullets = metadata["impact"]?.split("|")?.filter { it.isNotBlank() }.orEmpty(),
                levelBefore = metadata["levelBefore"]?.toIntOrNull(),
                levelAfter = metadata["levelAfter"]?.toIntOrNull(),
                createdAt = event.createdAt.toString(),
            )
        }

    private fun levelMilestones(overview: NetworkOverviewProjectionEntity): List<AiNetworkLevelMilestone> =
        listOf(
            milestone(
                level = 1,
                title = levelTitle(1),
                description = levelDescription(1),
                achieved = true,
                gaps = emptyList(),
            ),
            milestone(
                level = 2,
                title = levelTitle(2),
                description = levelDescription(2),
                achieved = overview.onlineProviderCount >= 1,
                gaps = gap(overview.onlineProviderCount >= 1, "온라인 Provider 1명 이상 연결"),
            ),
            milestone(
                level = 3,
                title = levelTitle(3),
                description = levelDescription(3),
                achieved = overview.onlineProviderCount >= 2 && overview.channelAiCount >= 1,
                gaps =
                    gap(overview.onlineProviderCount >= 2, "온라인 Provider 2명 이상") +
                        gap(overview.channelAiCount >= 1, "채널 AI 프로필 1개 이상"),
            ),
            milestone(
                level = 4,
                title = levelTitle(4),
                description = levelDescription(4),
                achieved =
                    overview.knowledgeSpaceCount >= 1 &&
                        overview.channelAiCount >= 2 &&
                        overview.modelCount >= 2,
                gaps =
                    gap(overview.knowledgeSpaceCount >= 1, "지식공간 1개 이상") +
                        gap(overview.channelAiCount >= 2, "채널 AI 프로필 2개 이상") +
                        gap(overview.modelCount >= 2, "서로 다른 모델 2개 이상"),
            ),
            milestone(
                level = 5,
                title = levelTitle(5),
                description = levelDescription(5),
                achieved =
                    overview.feedbackCount >= 5 &&
                        overview.overloadAlertCount == 0 &&
                        overview.knowledgeSpaceCount >= 1 &&
                        overview.channelAiCount >= 2 &&
                        overview.modelCount >= 2,
                gaps =
                    gap(overview.feedbackCount >= 5, "품질 피드백 5개 이상") +
                        gap(overview.overloadAlertCount == 0, "Provider 과부하 알림 0개") +
                        gap(overview.knowledgeSpaceCount >= 1, "지식공간 1개 이상") +
                        gap(overview.channelAiCount >= 2, "채널 AI 프로필 2개 이상") +
                        gap(overview.modelCount >= 2, "서로 다른 모델 2개 이상"),
            ),
        )

    private fun milestone(
        level: Int,
        title: String,
        description: String,
        achieved: Boolean,
        gaps: List<String>,
    ): AiNetworkLevelMilestone =
        AiNetworkLevelMilestone(
            level = level,
            title = title,
            description = description,
            achieved = achieved,
            gaps = gaps,
        )

    private fun gap(
        satisfied: Boolean,
        label: String,
    ): List<String> = if (satisfied) emptyList() else listOf(label)

    private fun levelTitle(level: Int): String =
        when (level) {
            1 -> "기본 AI 네트워크"
            2 -> "Provider 연결"
            3 -> "채널 AI 시작"
            4 -> "지식 기반 네트워크"
            5 -> "품질 라우팅 네트워크"
            else -> "확장 AI 네트워크"
        }

    private fun providerImpact(
        levelBefore: Int,
        levelAfter: Int,
        modelNames: List<String>,
        capabilityTags: List<String>,
        maxConcurrency: Int,
        dailyLimit: Int,
    ): List<String> =
        buildList {
            val models = modelNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val tags = capabilityTags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (models.isNotEmpty()) add("사용 가능한 모델 ${models.joinToString(", ")} 추가")
            if (tags.isNotEmpty()) add("특화 능력 ${tags.joinToString(", ")} 추가")
            add("동시 처리 용량 ${maxConcurrency.coerceAtLeast(1)}개 확보")
            if (dailyLimit > 0) add("하루 최대 $dailyLimit 회 Provider 보호 한도 적용")
            if (levelAfter > levelBefore) add("AI 네트워크 레벨 $levelBefore → $levelAfter 성장")
        }

    private fun parseMetadata(metadata: String?): Map<String, String> =
        metadata
            .orEmpty()
            .split(";")
            .mapNotNull { item ->
                val key = item.substringBefore("=", "").trim()
                val value = item.substringAfter("=", "").trim()
                if (key.isBlank()) null else key to value
            }.toMap()

    private fun levelDescription(level: Int): String =
        when (level) {
            1 -> "기본 질문이 가능한 네트워크가 준비됐어요."
            2 -> "온라인 Provider가 연결되어 즉시 질문을 처리할 수 있어요."
            3 -> "여러 Provider와 채널별 AI 프로필을 함께 사용할 수 있어요."
            4 -> "지식 베이스와 채널별 AI를 함께 활용할 수 있어요."
            5 -> "피드백과 보호 신호를 바탕으로 고품질 라우팅을 실험할 수 있어요."
            else -> "더 강한 AI 네트워크 기능을 사용할 수 있어요."
        }
}

data class ProviderGrowthResult(
    val providerCapabilityId: Long,
    val eventId: Long,
    val networkLevel: Int,
)

data class NetworkGrowthEventCard(
    val id: Long,
    val eventType: String,
    val providerUserId: Long?,
    val channelId: Long?,
    val title: String,
    val summary: String?,
    val impactBullets: List<String>,
    val levelBefore: Int?,
    val levelAfter: Int?,
    val createdAt: String,
)

data class AiNetworkLevelStatus(
    val guildId: Long,
    val currentLevel: Int,
    val currentTitle: String,
    val currentDescription: String,
    val nextMilestone: AiNetworkLevelMilestone?,
    val milestones: List<AiNetworkLevelMilestone>,
)

data class AiNetworkLevelMilestone(
    val level: Int,
    val title: String,
    val description: String,
    val achieved: Boolean,
    val gaps: List<String>,
)
