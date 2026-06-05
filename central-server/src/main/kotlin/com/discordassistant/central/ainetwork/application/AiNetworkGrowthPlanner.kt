package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.NetworkOverviewProjectionEntity
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Component

/**
 * 순수/읽기 협력자(god-class 분해): [AiNetworkGrowthService] 에서 레벨 마일스톤 평가·성장 플랜 산출·
 * 모델 능력 정규화·entity→DTO 매핑을 추출한다. **저장소·@Transactional 이 없는 순수 함수만** 모았다 —
 * write/원장 기록/XP 라이프사이클은 파사드([AiNetworkGrowthService])에 그대로 잔존한다.
 *
 * 레벨 공식·임계값·사용자 노출 문구·메타데이터 포맷은 추출 전과 1바이트도 다르지 않다(동작보존).
 */
@Component
class AiNetworkGrowthPlanner {
    fun growthPlanFromOverview(
        guildId: Long,
        overview: NetworkOverviewProjectionEntity,
    ): AiNetworkGrowthPlan {
        val milestones = levelMilestones(overview)
        val next = milestones.firstOrNull { !it.achieved }
        val actions = growthActions(overview, milestones).sortedWith(compareBy<AiNetworkGrowthAction> { it.priority }.thenBy { it.key })
        return AiNetworkGrowthPlan(
            guildId = guildId,
            currentLevel = overview.networkLevel,
            targetLevel = next?.level,
            targetTitle = next?.title,
            healthStatus = overview.healthStatus,
            summary = growthPlanSummary(overview, next, actions),
            builderMessage = builderMessage(overview),
            capabilityBasis = capabilityBasis(overview),
            recommendationPolicy = RECOMMENDATION_POLICY,
            actions = actions,
        )
    }

    fun timelineCard(event: AiNetworkEventEntity): NetworkGrowthEventCard {
        val metadata = parseMetadata(event.metadata)
        return NetworkGrowthEventCard(
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

    fun levelMilestones(overview: NetworkOverviewProjectionEntity): List<AiNetworkLevelMilestone> =
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

    private fun growthActions(
        overview: NetworkOverviewProjectionEntity,
        milestones: List<AiNetworkLevelMilestone>,
    ): List<AiNetworkGrowthAction> =
        buildList {
            if (overview.onlineProviderCount < 1) {
                add(
                    growthAction(
                        key = "connect_first_provider",
                        priority = 10,
                        severity = "critical",
                        title = "첫 Provider를 연결하세요",
                        description = "온라인 Provider가 없으면 질문을 처리할 로컬 AI가 없습니다.",
                        command = "/프로바이더참여",
                        dashboardPath = "/admin/dashboard/providers",
                        unlocksLevel = 2,
                    ),
                )
            }
            if (overview.onlineProviderCount in 1..1) {
                add(
                    growthAction(
                        key = "add_second_provider",
                        priority = 20,
                        severity = "recommended",
                        title = "두 번째 Provider를 초대하세요",
                        description = "Provider가 2명 이상이면 채널 AI와 함께 레벨 3로 성장할 수 있습니다.",
                        command = "/프로바이더참여",
                        dashboardPath = "/admin/dashboard/providers",
                        unlocksLevel = 3,
                    ),
                )
            }
            if (overview.channelAiCount < 1) {
                add(
                    growthAction(
                        key = "create_first_channel_ai",
                        priority = 30,
                        severity = "recommended",
                        title = "첫 채널 AI를 만드세요",
                        description = "채널별 역할·말투·답변 길이를 설정해야 함께 만드는 AI 네트워크 정체성이 생깁니다.",
                        command = "/채널프로필",
                        dashboardPath = "/admin/dashboard/channels",
                        unlocksLevel = 3,
                        requiresAdminApproval = true,
                    ),
                )
            }
            if (overview.channelAiCount in 1..1) {
                add(
                    growthAction(
                        key = "create_second_channel_ai",
                        priority = 40,
                        severity = "optional",
                        title = "두 번째 채널 AI를 만드세요",
                        description = "채널 AI가 2개 이상이면 지식/RAG와 모델 지도를 채널별로 나눠 운영할 수 있습니다.",
                        command = "/채널프로필",
                        dashboardPath = "/admin/dashboard/channels",
                        unlocksLevel = 4,
                        requiresAdminApproval = true,
                    ),
                )
            }
            if (overview.modelCount < 2 && overview.onlineProviderCount > 0) {
                add(
                    growthAction(
                        key = "increase_model_diversity",
                        priority = 50,
                        severity = "optional",
                        title = "서로 다른 모델을 2개 이상 확보하세요",
                        description = "모델 다양성이 있어야 원하는 모델 선택, 특화 라우팅, 고품질 응답 실험을 할 수 있습니다.",
                        command = null,
                        dashboardPath = "/admin/dashboard/model-map",
                        unlocksLevel = 4,
                    ),
                )
            }
            if (overview.knowledgeSpaceCount < 1) {
                add(
                    growthAction(
                        key = "add_first_knowledge_space",
                        priority = 60,
                        severity = "optional",
                        title = "첫 지식공간을 추가하세요",
                        description = "README·FAQ·운영규칙을 지식공간에 등록하면 채널 AI가 서버 맥락을 참고할 수 있습니다.",
                        command = "/지식추가",
                        dashboardPath = "/admin/dashboard/knowledge",
                        unlocksLevel = 4,
                        requiresAdminApproval = true,
                    ),
                )
            }
            if (overview.feedbackCount < 5) {
                add(
                    growthAction(
                        key = "collect_quality_feedback",
                        priority = 70,
                        severity = "optional",
                        title = "품질 피드백 5개를 모으세요",
                        description = "따봉·신고·사유가 쌓이면 모델 선택과 채널 AI 개선을 근거 있게 할 수 있습니다.",
                        command = null,
                        dashboardPath = "/admin/dashboard/quality",
                        unlocksLevel = 5,
                    ),
                )
            }
            if (overview.overloadAlertCount > 0) {
                add(
                    growthAction(
                        key = "resolve_provider_overload",
                        priority = 5,
                        severity = "critical",
                        title = "Provider 과부하를 먼저 해소하세요",
                        description = "과부하 알림이 있으면 레벨 5와 다중 응답/깊은 답변 실험보다 보호 정책이 우선입니다.",
                        command = "/내상태",
                        dashboardPath = "/admin/dashboard/providers/overload",
                        unlocksLevel = 5,
                    ),
                )
            }
            if (isEmpty() && milestones.all { it.achieved }) {
                add(
                    growthAction(
                        key = "experiment_advanced_features",
                        priority = 100,
                        severity = "info",
                        title = "고급 기능을 실험하세요",
                        description = "프리셋 공유, 다중 응답, RAG 품질 평가를 단계적으로 켜도 되는 상태입니다.",
                        command = null,
                        dashboardPath = "/admin/dashboard/experiments",
                        unlocksLevel = null,
                        requiresAdminApproval = true,
                    ),
                )
            }
        }

    private fun growthAction(
        key: String,
        priority: Int,
        severity: String,
        title: String,
        description: String,
        command: String?,
        dashboardPath: String,
        unlocksLevel: Int?,
        requiresAdminApproval: Boolean = false,
        autoApply: Boolean = false,
    ): AiNetworkGrowthAction =
        AiNetworkGrowthAction(
            key = key,
            priority = priority,
            severity = severity,
            title = title,
            description = description,
            command = command,
            dashboardPath = dashboardPath,
            unlocksLevel = unlocksLevel,
            requiresAdminApproval = requiresAdminApproval,
            autoApply = autoApply,
        )

    private fun builderMessage(overview: NetworkOverviewProjectionEntity): String =
        "이 서버의 AI 네트워크는 Provider ${overview.onlineProviderCount}명, 모델 ${overview.modelCount}개, " +
            "채널 AI ${overview.channelAiCount}개, 지식공간 ${overview.knowledgeSpaceCount}개, " +
            "품질 피드백 ${overview.feedbackCount}개가 쌓이면서 함께 만들어지고 있어요."

    private fun capabilityBasis(overview: NetworkOverviewProjectionEntity): List<String> =
        listOf(
            "onlineProviderCount=${overview.onlineProviderCount}",
            "modelCount=${overview.modelCount}",
            "channelAiCount=${overview.channelAiCount}",
            "knowledgeSpaceCount=${overview.knowledgeSpaceCount}",
            "feedbackCount=${overview.feedbackCount}",
            "overloadAlertCount=${overview.overloadAlertCount}",
        )

    private fun growthPlanSummary(
        overview: NetworkOverviewProjectionEntity,
        next: AiNetworkLevelMilestone?,
        actions: List<AiNetworkGrowthAction>,
    ): String =
        when {
            overview.overloadAlertCount > 0 -> "Provider 보호가 우선입니다. 과부하를 낮춘 뒤 성장 기능을 켜세요."
            next == null -> "모든 성장 레벨을 달성했습니다. 고급 기능을 안전하게 실험할 수 있습니다."
            actions.isEmpty() -> "다음 레벨 ${next.level} 준비 상태를 확인하세요."
            else -> "다음 목표는 레벨 ${next.level} ${next.title}입니다. 우선 액션: ${actions.first().title}"
        }

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

    fun levelTitle(level: Int): String =
        when (level) {
            1 -> "기본 AI 네트워크"
            2 -> "Provider 연결"
            3 -> "채널 AI 시작"
            4 -> "지식 기반 네트워크"
            5 -> "품질 라우팅 네트워크"
            else -> "확장 AI 네트워크"
        }

    fun providerImpact(
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

    fun inferCapabilityTags(modelNames: List<String>): List<String> = ModelClassifier.capabilityTags(modelNames)

    fun inferMaxBurden(modelNames: List<String>): ModelBurden = ModelClassifier.maxBurden(modelNames)

    fun normalizeCsv(value: String?): List<String> = normalizeList(value.orEmpty().split(","))

    fun normalizeList(values: List<String>): List<String> =
        values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun parseMetadata(metadata: String?): Map<String, String> =
        metadata
            .orEmpty()
            .split(";")
            .mapNotNull { item ->
                val key = item.substringBefore("=", "").trim()
                val value = item.substringAfter("=", "").trim()
                if (key.isBlank()) null else key to value
            }.toMap()

    fun levelDescription(level: Int): String =
        when (level) {
            1 -> "기본 질문이 가능한 네트워크가 준비됐어요."
            2 -> "온라인 Provider가 연결되어 즉시 질문을 처리할 수 있어요."
            3 -> "여러 Provider와 채널별 AI 프로필을 함께 사용할 수 있어요."
            4 -> "지식 베이스와 채널별 AI를 함께 활용할 수 있어요."
            5 -> "피드백과 보호 신호를 바탕으로 고품질 라우팅을 실험할 수 있어요."
            else -> "더 강한 AI 네트워크 기능을 사용할 수 있어요."
        }

    internal companion object {
        const val RECOMMENDATION_POLICY =
            "성장 추천은 자동 적용되지 않으며, " +
                "채널 AI·지식·실험 설정은 관리자 검토/승인 후에만 바뀝니다."
    }
}
