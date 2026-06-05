package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.shared.ResponseMode
import org.springframework.stereotype.Component

/**
 * 다중응답(fan-out) 운영 명령군(multiResponseStatus, setMultiResponsePolicy, multiResponseDryRun).
 * CommandService 에서 응집 단위로 분리 — 문구/포맷 출력 불변, 시그니처 유지·위임.
 */
@Component
class MultiResponseCommandHandler(
    private val channelRoutingPolicies: ChannelAiRoutingPolicyService,
    private val multiResponse: MultiResponseService,
    private val guards: SharedCommandGuards,
) {
    fun multiResponseStatus(
        ctx: CommandContext,
        channelId: Long? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val targetChannelId = channelId ?: ctx.channelId
        return runCatching {
            val summary = multiResponse.operationsSummary(ctx.guildId, targetChannelId)
            val riskText = summary.riskCodes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"
            val nextActions =
                summary.nextActions
                    .take(4)
                    .joinToString("\n") { "• $it" }
                    .ifBlank { "• 지금은 추가 조치가 없습니다." }
            val topLoad =
                summary.providerLoads
                    .take(3)
                    .joinToString("\n") { load ->
                        "• Provider ${kotlin.math.abs(load.providerUserId.hashCode()).toString(36).take(6)} — " +
                            "후보 ${load.candidateCount} · 완료 ${load.completedCount} · timeout ${load.timeoutCount} · risk `${load.loadRisk}`"
                    }.ifBlank { "• 최근 fan-out 부하 기록 없음" }
            val averageFanout = "%.1f".format(summary.averageActualFanout)
            Reply(
                "🧪 **다중응답 운영 상태** <#$targetChannelId>\n\n" +
                    "상태: `${summary.status}` · 고급 모드 안전: `${summary.safeToEnableAdvanced}`\n" +
                    "최근 실행: ${summary.recentRunCount} · 완료 ${summary.completedRunCount} · fallback ${summary.fallbackRunCount}\n" +
                    "평균 fan-out: $averageFanout · 선택 후보 ${summary.acceptedCandidateCount} · " +
                    "timeout ${summary.timeoutCandidateCount}\n" +
                    "Provider 부하: high ${summary.highLoadProviderCount} · critical ${summary.criticalLoadProviderCount}\n" +
                    "RAG fallback ${summary.ragFallbackRunCount} · 민감질문 차단 ${summary.blockedSensitiveRunCount} · " +
                    "Provider 없음 ${summary.noProviderRunCount}\n" +
                    "위험 코드: `$riskText`\n\n" +
                    "__Provider 부하__\n$topLoad\n\n" +
                    "__다음 행동__\n$nextActions",
            )
        }.getOrElse { error ->
            Replies.warn("다중응답 상태를 불러오지 못했어요. ${error.message ?: "설정/기능 플래그를 확인해 주세요."}")
        }
    }

    fun setMultiResponsePolicy(
        ctx: CommandContext,
        channelId: Long? = null,
        mode: String = "single",
        maxCandidates: Int = 1,
        synthesisEnabled: Boolean = false,
        requireDistinctModels: Boolean = false,
        timeoutSeconds: Int = 120,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val normalizedMode =
            when (mode.trim().lowercase()) {
                "single", "단일" -> "single"
                "compare", "비교" -> "compare"
                "debate", "토론" -> "debate"
                "deep", "깊은" -> "compare"
                else -> "single"
            }
        val targetChannelId = channelId ?: ctx.channelId
        return runCatching {
            val policy =
                multiResponse.savePolicy(
                    guildId = ctx.guildId,
                    channelId = targetChannelId,
                    channelAiId = null,
                    mode = normalizedMode,
                    maxCandidates = maxCandidates,
                    requireDistinctModels = requireDistinctModels,
                    providerDailyLimit = 0,
                    timeoutSeconds = timeoutSeconds,
                    synthesisEnabled = synthesisEnabled,
                )
            val safetyNote =
                if (policy.maxCandidates > 1 || policy.synthesisEnabled) {
                    "\n⚠️ 고급 fan-out은 `multi-response` 태그로 opt-in 한 Provider만 쓰고, 과부하/민감질문이면 자동 차단됩니다."
                } else {
                    ""
                }
            Replies.ok(
                "다중응답 정책을 저장했습니다. <#$targetChannelId>\n" +
                    "mode: `${policy.mode}` · 후보: `${policy.maxCandidates}` · 서로 다른 모델 우선: `${policy.requireDistinctModels}`\n" +
                    "합성: `${policy.synthesisEnabled}` · 타임아웃: `${policy.timeoutSeconds}s`$safetyNote",
            )
        }.getOrElse { error ->
            Replies.warn("다중응답 정책을 저장하지 못했어요. ${error.message ?: "입력값을 확인해 주세요."}")
        }
    }

    fun multiResponseDryRun(
        ctx: CommandContext,
        prompt: String,
        channelId: Long? = null,
        responseMode: String? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val targetChannelId = channelId ?: ctx.channelId
        val mode =
            normalizeAskResponseMode(responseMode)
                ?: channelRoutingPolicies
                    .effective(ctx.guildId, targetChannelId, null)
                    .responseMode
        return runCatching {
            val run =
                multiResponse.startRun(
                    guildId = ctx.guildId,
                    channelId = targetChannelId,
                    requestId = "discord-dry-${System.currentTimeMillis()}",
                    promptPreview = prompt,
                    responseMode = mode,
                )
            val next =
                when (run.status) {
                    "running" -> "후보 Provider가 계획되었습니다. 실제 답변 fan-out은 옵트인 단계에서만 연결하세요."
                    "blocked_sensitive" -> "민감정보처럼 보여 fan-out을 차단했습니다. 단일 안전 경로로 안내하세요."
                    "no_provider" -> "온라인 Provider, `multi-response` opt-in 태그, 과부하 상태를 확인하세요."
                    else -> run.failureReason ?: "상태를 확인하세요."
                }
            Reply(
                "🧪 **다중응답 드라이런** <#$targetChannelId>\n" +
                    "run: `${run.id}` · status: `${run.status}` · 후보: `${run.candidateCount}`\n" +
                    "RAG: `${run.ragContextStatus ?: "unknown"}` · context chars: `${run.ragContextChars}`\n" +
                    "모드: `$mode`\n\n" +
                    "다음 행동: $next",
            )
        }.getOrElse { error ->
            Replies.warn("다중응답 드라이런을 만들지 못했어요. ${error.message ?: "정책/Provider 상태를 확인해 주세요."}")
        }
    }

    private fun normalizeAskResponseMode(value: String?): String? = ResponseMode.normalizeOrNull(value)?.wire
}
