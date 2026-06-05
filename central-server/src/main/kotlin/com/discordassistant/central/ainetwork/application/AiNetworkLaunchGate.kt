package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkDashboardResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistItemResponse
import com.discordassistant.central.ainetwork.adapter.inbound.web.dto.AiNetworkLaunchChecklistResponse
import org.springframework.stereotype.Component

/**
 * AI Network 런치 체크리스트(릴리스 게이트) 평가 협력자 — 읽기 전용·순수 함수
 * (@Transactional·write·repo 의존 없음).
 *
 * readiness 영역을 체크리스트 항목으로 매핑하고 기능 플래그·과부하·projection·품질신고·승인대기열·
 * 다중응답 안전 게이트를 평가해 ready/warning/blocked·releaseGate(pass/fail)를 산정한다. 본문은
 * [AiNetworkReadinessService] 에서 1바이트 불변으로 이동했으며 게이트 판정 기준·사용자 노출 문구는
 * 변경하지 않는다.
 */
@Component
class AiNetworkLaunchGate {
    fun launchChecklist(
        dashboard: AiNetworkDashboardResponse,
        featureSnapshot: AiNetworkFeatureSnapshot,
    ): AiNetworkLaunchChecklistResponse {
        val readinessItems =
            dashboard.readiness.areas.map {
                AiNetworkLaunchChecklistItemResponse(
                    key = it.key,
                    title = it.title,
                    status = it.status,
                    evidence = it.evidence,
                    nextAction = it.nextAction,
                    blocking = it.status == "blocked",
                )
            }
        val featureBaseReady =
            featureSnapshot.aiNetwork &&
                featureSnapshot.dashboard &&
                featureSnapshot.channelAi &&
                featureSnapshot.presets &&
                featureSnapshot.rag &&
                !featureSnapshot.killSwitch
        val advancedLimited =
            !featureSnapshot.multiResponse ||
                !featureSnapshot.multiResponseDashboard ||
                !featureSnapshot.multiResponseSynthesis ||
                !featureSnapshot.multiResponseRag ||
                featureSnapshot.multiResponseMaxFanout <= 1
        val safetyItems =
            listOf(
                checklistItem(
                    key = "feature_flags",
                    title = "기능 플래그/kill switch",
                    passed = featureBaseReady && !advancedLimited,
                    warning = featureBaseReady && advancedLimited,
                    evidence =
                        listOf(
                            "aiNetwork=${featureSnapshot.aiNetwork}",
                            "dashboard=${featureSnapshot.dashboard}",
                            "channelAi=${featureSnapshot.channelAi}",
                            "presets=${featureSnapshot.presets}",
                            "rag=${featureSnapshot.rag}",
                            "multi=${featureSnapshot.multiResponse}",
                            "multiDashboard=${featureSnapshot.multiResponseDashboard}",
                            "synthesis=${featureSnapshot.multiResponseSynthesis}",
                            "multiRag=${featureSnapshot.multiResponseRag}",
                            "maxFanout=${featureSnapshot.multiResponseMaxFanout}",
                            "killSwitch=${featureSnapshot.killSwitch}",
                        ),
                    nextAction = "ENV_FILE 의 AI_NETWORK_* 플래그와 maxFanout 가 의도한 운영값인지 확인하세요.",
                ),
                checklistItem(
                    key = "provider_overload",
                    title = "Provider 과부하 보호",
                    passed = dashboard.overload.highRiskCount == 0,
                    warning = dashboard.overload.highRiskCount > 0 && dashboard.overload.safeOnlineProviderCount > 0,
                    evidence =
                        listOf(
                            "highRisk=${dashboard.overload.highRiskCount}",
                            "safeOnline=${dashboard.overload.safeOnlineProviderCount}",
                        ),
                    nextAction = "후보 수/깊은 답변/다중응답을 낮추고 과부하 Provider를 쉬게 하세요.",
                ),
                checklistItem(
                    key = "dashboard_projection",
                    title = "대시보드 Projection 최신성",
                    passed = !dashboard.metadata.stale,
                    warning = dashboard.metadata.stale,
                    evidence = listOf("freshness=${dashboard.metadata.freshnessStatus}", "source=${dashboard.metadata.source}"),
                    nextAction = dashboard.metadata.degradedReason ?: "projection freshness를 확인하세요.",
                ),
                checklistItem(
                    key = "unsafe_quality_reports",
                    title = "품질 신고 검토",
                    passed = dashboard.quality.openReports == 0,
                    warning = dashboard.quality.openReports > 0,
                    evidence = listOf("openReports=${dashboard.quality.openReports}", "feedback=${dashboard.quality.feedbackCount}"),
                    nextAction = "열린 품질 신고를 resolved/dismissed 로 정리하세요.",
                ),
                checklistItem(
                    key = "change_approval_queue",
                    title = "AI 설정 변경 승인 대기열",
                    passed = dashboard.changeApproval.pendingCount == 0 && dashboard.changeApproval.staleCount == 0,
                    warning = dashboard.changeApproval.pendingCount > 0,
                    evidence = listOf("pending=${dashboard.changeApproval.pendingCount}", "stale=${dashboard.changeApproval.staleCount}"),
                    nextAction = "승인/거절되지 않은 AI 설정 변경을 처리하세요.",
                ),
                checklistItem(
                    key = "multi_response_safety",
                    title = "다중응답 안전 게이트",
                    passed = dashboard.multiResponseOperations.safeToEnableAdvanced,
                    warning = !dashboard.multiResponseOperations.safeToEnableAdvanced,
                    evidence =
                        listOf(
                            "status=${dashboard.multiResponseOperations.status}",
                            "riskCodes=${dashboard.multiResponseOperations.riskCodes.joinToString(",")}",
                        ),
                    nextAction = dashboard.multiResponseOperations.nextActions.firstOrNull() ?: "다중응답 운영 상태를 점검하세요.",
                ),
            )
        val items = readinessItems + safetyItems
        val blocked = items.count { it.status == "blocked" }
        val warnings = items.count { it.status == "warning" }
        val status =
            when {
                blocked > 0 -> "blocked"
                warnings > 0 -> "warning"
                else -> "ready"
            }
        return AiNetworkLaunchChecklistResponse(
            guildId = dashboard.overview.guildId,
            status = status,
            score = ((items.count { it.status == "ready" }.toDouble() / items.size.coerceAtLeast(1)) * 100).toInt(),
            readyCount = items.count { it.status == "ready" },
            warningCount = warnings,
            blockedCount = blocked,
            items = items,
            releaseGate = if (blocked == 0) "pass" else "fail",
            nextActions =
                items
                    .filter { it.status != "ready" }
                    .take(8)
                    .map { it.nextAction },
        )
    }

    private fun checklistItem(
        key: String,
        title: String,
        passed: Boolean,
        warning: Boolean,
        evidence: List<String>,
        nextAction: String,
    ): AiNetworkLaunchChecklistItemResponse =
        AiNetworkLaunchChecklistItemResponse(
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
}
