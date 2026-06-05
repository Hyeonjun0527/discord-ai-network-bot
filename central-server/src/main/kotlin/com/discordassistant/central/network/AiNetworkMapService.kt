package com.discordassistant.central.network

import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.domain.ProviderAvailability
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service

/**
 * Discord/web 공용 AI 네트워크 지도 read model.
 * Provider 개인 식별자는 노출하지 않고, 서버의 AI 구성·모델 다양성·채널 AI 연결 상태만 요약한다.
 */
@Service
class AiNetworkMapService(
    private val foundation: AiNetworkFoundationService,
    private val providers: ProviderCapabilityProfileRepository,
    private val channelAis: ChannelAiRepository,
    private val knowledgeSpaces: KnowledgeSpaceRepository,
    private val aiLevel: AiLevelService,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun map(guildId: Long): AiNetworkMap {
        featureGate.requireDashboardEnabled()
        val overview = foundation.refreshOverview(guildId)
        val levelView = aiLevel.levelView(guildId)
        val providerList = providers.findByGuildId(guildId)
        val channels = channelAis.findByGuildId(guildId)
        val spaces = knowledgeSpaces.findByGuildId(guildId)
        val models =
            providerList
                .flatMap { provider -> splitCsv(provider.modelNames).map { it to provider } }
                .groupBy({ it.first }, { it.second })
                .map { (modelName, providersForModel) ->
                    AiNetworkModelNode(
                        modelName = modelName,
                        providerCount = providersForModel.size,
                        onlineProviderCount = providersForModel.count { it.providerState == ProviderAvailability.ONLINE },
                        qualityTiers = providersForModel.map { it.qualityTier.wire }.distinct().sorted(),
                        maxBurdens = providersForModel.map { it.maxBurden.name }.distinct().sorted(),
                        tags = providersForModel.flatMap { splitCsv(it.capabilityTags) }.distinct().sorted(),
                    )
                }.sortedWith(compareByDescending<AiNetworkModelNode> { it.onlineProviderCount }.thenBy { it.modelName })
        val channelNodes =
            channels
                .sortedBy { it.channelId }
                .map { channel ->
                    val linkedSpaces = spaces.filter { it.channelId == channel.channelId || it.channelAiId == channel.id }
                    AiNetworkChannelNode(
                        channelId = channel.channelId,
                        name = channel.displayName,
                        hasBehavior = channel.activeBehaviorVersionId != null,
                        knowledgeSpaceCount = linkedSpaces.size,
                        source = channel.source,
                    )
                }
        val capabilityTags = providerList.flatMap { splitCsv(it.capabilityTags) }.distinct().sorted()
        val nextActions =
            buildList {
                if (overview.onlineProviderCount == 0) add("Provider 참여 안내로 최소 1대의 PC를 연결하세요.")
                if (models.isEmpty()) add("Provider Agent가 모델을 보고하도록 연결 상태를 확인하세요.")
                if (channelNodes.isEmpty()) add("/채널프로필 패널에서 첫 채널 AI를 만드세요.")
                if (spaces.isEmpty()) add("README·운영규칙·FAQ를 채널 지식공간에 추가하세요.")
                if (isEmpty()) add("AI 네트워크 구성이 준비됐습니다. 품질 피드백과 모델 정책을 점검하세요.")
            }
        return AiNetworkMap(
            guildId = guildId,
            networkLevel = overview.networkLevel,
            aiLevel = levelView.aiLevel,
            totalXp = levelView.totalXp,
            xpToNext = levelView.xpToNext,
            healthStatus = overview.healthStatus,
            onlineProviderCount = overview.onlineProviderCount,
            approvedProviderCount = overview.approvedProviderCount,
            modelCount = models.size,
            channelAiCount = channelNodes.size,
            knowledgeSpaceCount = spaces.size,
            overloadAlertCount = overview.overloadAlertCount,
            capabilityTags = capabilityTags,
            models = models,
            channels = channelNodes,
            nextActions = nextActions,
        )
    }

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
}

data class AiNetworkMap(
    val guildId: Long,
    val networkLevel: Int,
    val aiLevel: Int,
    val totalXp: Long,
    val xpToNext: Long,
    val healthStatus: String,
    val onlineProviderCount: Int,
    val approvedProviderCount: Int,
    val modelCount: Int,
    val channelAiCount: Int,
    val knowledgeSpaceCount: Int,
    val overloadAlertCount: Int,
    val capabilityTags: List<String>,
    val models: List<AiNetworkModelNode>,
    val channels: List<AiNetworkChannelNode>,
    val nextActions: List<String>,
)

data class AiNetworkModelNode(
    val modelName: String,
    val providerCount: Int,
    val onlineProviderCount: Int,
    val qualityTiers: List<String>,
    val maxBurdens: List<String>,
    val tags: List<String>,
)

data class AiNetworkChannelNode(
    val channelId: Long,
    val name: String,
    val hasBehavior: Boolean,
    val knowledgeSpaceCount: Int,
    val source: String,
)
