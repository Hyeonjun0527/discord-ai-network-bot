package com.discordassistant.central.network

import com.discordassistant.central.domain.ProposalStatus
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import org.springframework.stereotype.Service

/**
 * Discord/web 공용 AI 네트워크 운영 체크리스트.
 * 컨트롤러 DTO 에 의존하지 않는 서비스 계층 SSOT 로 두어 Discord 어댑터가 dashboard 패키지를 참조하지 않게 한다.
 */
@Service
class AiNetworkLaunchChecklistService(
    private val foundation: AiNetworkFoundationService,
    private val providerSafety: ProviderSafetyService,
    private val qualityFeedback: AiQualityFeedbackService,
    private val multiResponse: MultiResponseService,
    private val channelAis: ChannelAiRepository,
    private val providers: ProviderCapabilityProfileRepository,
    private val knowledgeSpaces: KnowledgeSpaceRepository,
    private val knowledgeSources: KnowledgeSourceRepository,
    private val proposals: AiChangeProposalRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun checklist(guildId: Long): NetworkLaunchChecklist {
        featureGate.requireDashboardEnabled()
        val overview = foundation.refreshOverview(guildId)
        val overload = providerSafety.overloadAlerts(guildId)
        val quality = qualityFeedback.guildSummary(guildId)
        val features = featureGate.snapshot()
        val multi =
            if (features.multiResponseDashboard) {
                multiResponse.operationsSummary(guildId)
            } else {
                MultiResponseOperationsSummary.disabled(guildId)
            }
        val channelCount = channelAis.findByGuildId(guildId).size
        val providerList = providers.findByGuildId(guildId)
        val modelCount = providerList.flatMap { splitCsv(it.modelNames) }.distinct().size
        val spaces = knowledgeSpaces.findByGuildId(guildId)
        val sources = spaces.flatMap { knowledgeSources.findByKnowledgeSpaceId(it.id) }
        val pendingChanges =
            proposals.findByGuildIdOrderByCreatedAtDesc(guildId).count {
                it.status == ProposalStatus.PENDING || it.status == ProposalStatus.STALE
            }
        val featureBaseReady =
            features.aiNetwork &&
                features.dashboard &&
                features.channelAi &&
                features.presets &&
                features.rag &&
                !features.killSwitch
        val advancedLimited =
            !features.multiResponse ||
                !features.multiResponseDashboard ||
                !features.multiResponseSynthesis ||
                !features.multiResponseRag ||
                features.multiResponseMaxFanout <= 1
        val items =
            listOf(
                item(
                    "feature_flags",
                    "기능 플래그/kill switch",
                    featureBaseReady && !advancedLimited,
                    featureBaseReady && advancedLimited,
                    listOf(
                        "aiNetwork=${features.aiNetwork}",
                        "dashboard=${features.dashboard}",
                        "channelAi=${features.channelAi}",
                        "presets=${features.presets}",
                        "rag=${features.rag}",
                        "multi=${features.multiResponse}",
                        "multiDashboard=${features.multiResponseDashboard}",
                        "synthesis=${features.multiResponseSynthesis}",
                        "multiRag=${features.multiResponseRag}",
                        "maxFanout=${features.multiResponseMaxFanout}",
                        "killSwitch=${features.killSwitch}",
                    ),
                    "ENV_FILE 의 AI_NETWORK_* 플래그와 maxFanout 가 의도한 운영값인지 확인하세요.",
                ),
                item(
                    "providers",
                    "Provider 상태",
                    overview.onlineProviderCount > 0,
                    false,
                    listOf("online=${overview.onlineProviderCount}"),
                    "Provider 참여 안내로 최소 1대의 PC를 연결하세요.",
                ),
                item(
                    "models",
                    "모델 지도",
                    modelCount > 0,
                    modelCount == 1,
                    listOf("models=$modelCount"),
                    "다른 모델을 가진 Provider를 늘리거나 선호 모델 정책을 지정하세요.",
                ),
                item("channel_ai", "채널 AI 프로필", channelCount > 0, false, listOf("channels=$channelCount"), "채널프로필 패널에서 이 채널 AI를 먼저 만드세요."),
                item(
                    "knowledge",
                    "RAG 지식",
                    spaces.isNotEmpty() &&
                        sources.any {
                            it.status == "indexed"
                        },
                    spaces.isNotEmpty(),
                    listOf("spaces=${spaces.size}", "sources=${sources.size}"),
                    "README·운영규칙·FAQ를 지식공간에 추가하고 색인하세요.",
                ),
                item(
                    "quality",
                    "품질 피드백",
                    quality.openReports == 0,
                    quality.feedbackCount == 0,
                    listOf("feedback=${quality.feedbackCount}", "openReports=${quality.openReports}"),
                    "열린 품질 신고를 검토하고 답변 피드백을 모으세요.",
                ),
                item(
                    "change_approval",
                    "AI 설정 변경 승인",
                    pendingChanges == 0,
                    pendingChanges > 0,
                    listOf("pendingOrStale=$pendingChanges"),
                    "승인/거절되지 않은 AI 설정 변경을 처리하세요.",
                ),
                item(
                    "provider_safety",
                    "Provider 과부하 보호",
                    overload.highRiskCount == 0,
                    overload.highRiskCount > 0,
                    listOf("highRisk=${overload.highRiskCount}"),
                    "후보 수/깊은 답변/다중응답을 낮추고 과부하 Provider를 쉬게 하세요.",
                ),
                item(
                    "multi_response",
                    "다중응답 안전 게이트",
                    multi.safeToEnableAdvanced,
                    !multi.safeToEnableAdvanced,
                    listOf("status=${multi.status}", "riskCodes=${multi.riskCodes.joinToString(",")}"),
                    multi.nextActions.firstOrNull() ?: "다중응답 운영 상태를 점검하세요.",
                ),
            )
        val blocked = items.count { it.status == "blocked" }
        val warnings = items.count { it.status == "warning" }
        return NetworkLaunchChecklist(
            guildId = guildId,
            status =
                when {
                    blocked > 0 -> "blocked"
                    warnings > 0 -> "warning"
                    else -> "ready"
                },
            score = ((items.count { it.status == "ready" }.toDouble() / items.size.coerceAtLeast(1)) * 100).toInt(),
            readyCount = items.count { it.status == "ready" },
            warningCount = warnings,
            blockedCount = blocked,
            releaseGate = if (blocked == 0) "pass" else "fail",
            items = items,
            nextActions = items.filter { it.status != "ready" }.map { it.nextAction },
        )
    }

    private fun item(
        key: String,
        title: String,
        passed: Boolean,
        warning: Boolean,
        evidence: List<String>,
        nextAction: String,
    ): NetworkLaunchChecklistItem =
        NetworkLaunchChecklistItem(
            key = key,
            title = title,
            status =
                when {
                    passed -> "ready"
                    warning -> "warning"
                    else -> "blocked"
                },
            evidence = evidence,
            nextAction = if (passed) "준비됐습니다." else nextAction,
            blocking = !passed && !warning,
        )

    private fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
}

data class NetworkLaunchChecklist(
    val guildId: Long,
    val status: String,
    val score: Int,
    val readyCount: Int,
    val warningCount: Int,
    val blockedCount: Int,
    val releaseGate: String,
    val items: List<NetworkLaunchChecklistItem>,
    val nextActions: List<String>,
)

data class NetworkLaunchChecklistItem(
    val key: String,
    val title: String,
    val status: String,
    val evidence: List<String>,
    val nextAction: String,
    val blocking: Boolean,
)
