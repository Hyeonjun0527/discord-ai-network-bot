package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.ainetwork.application.AiLevelService
import com.discordassistant.central.ainetwork.application.AiNetworkLaunchChecklistService
import com.discordassistant.central.ainetwork.application.AiNetworkMap
import com.discordassistant.central.ainetwork.application.AiNetworkMapService
import com.discordassistant.central.ainetwork.application.NetworkLaunchChecklist
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Reply
import org.springframework.stereotype.Component

/**
 * AI 네트워크/활동 레벨 명령군(aiLevel, aiNetworkMap, aiNetworkCheck).
 * CommandService 에서 응집 단위로 분리 — 포매터/문구/진행바 출력 불변, 시그니처 유지·위임.
 */
@Component
class AiNetworkCommandHandler(
    private val aiNetworkLaunchChecklist: AiNetworkLaunchChecklistService,
    private val aiNetworkMap: AiNetworkMapService,
    private val aiLevel: AiLevelService,
    private val guards: SharedCommandGuards,
) {
    /** 이 서버 니아의 활동 레벨/경험치/진행도(public·비관리자). */
    fun aiLevel(ctx: CommandContext): Reply {
        val view = aiLevel.levelView(ctx.guildId)
        val bar = xpProgressBar(view.progressInLevel, view.levelSpan)
        return Reply(
            "🐾 **이 서버 니아 활동 레벨**\n" +
                "활동 레벨: **${view.aiLevel}** · 누적 경험치: **${view.totalXp} XP**\n" +
                "$bar\n" +
                "다음 레벨까지: **${view.xpToNext} XP** (현재 구간 ${view.progressInLevel}/${view.levelSpan})\n" +
                "_질문(/ask) 답변이 성공할 때마다 ${com.discordassistant.central.ainetwork.domain.model.AiLevelFormula.XP_PER_ASK_SUCCESS} XP 가 쌓여요._",
            ephemeral = false,
        )
    }

    private fun xpProgressBar(
        gained: Long,
        needed: Long,
    ): String {
        if (needed <= 0L) return "▰▰▰▰▰▰▰▰▰▰ 100%"
        val ratio = (gained.toDouble() / needed.toDouble()).coerceIn(0.0, 1.0)
        val filled = (ratio * 10).toInt().coerceIn(0, 10)
        val pct = (ratio * 100).toInt()
        return "${"▰".repeat(filled)}${"▱".repeat(10 - filled)} $pct%"
    }

    fun aiNetworkMap(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return Reply(formatAiNetworkMap(aiNetworkMap.map(ctx.guildId)))
    }

    private fun formatAiNetworkMap(map: AiNetworkMap): String {
        val modelLines =
            map.models.take(8).map { model ->
                val topTags = model.tags.take(3)
                val tags = if (topTags.isEmpty()) "태그 없음" else topTags.joinToString(", ")
                val tiers = model.qualityTiers.joinToString(",")
                "• `${model.modelName}` — 온라인 ${model.onlineProviderCount}/${model.providerCount} · $tiers · $tags"
            }
        val models = modelLines.joinToString("\n").ifBlank { "• 아직 보고된 모델이 없습니다." }
        val channelLines =
            map.channels.take(8).map { channel ->
                val behavior = if (channel.hasBehavior) "행동설정 ON" else "행동설정 필요"
                "• <#${channel.channelId}> → **${channel.name}** · $behavior · 지식공간 ${channel.knowledgeSpaceCount}"
            }
        val channels = channelLines.joinToString("\n").ifBlank { "• 아직 채널 AI가 없습니다." }
        val next = map.nextActions.take(5).joinToString("\n") { "• $it" }
        val topCapabilityTags = map.capabilityTags.take(8)
        val tags = if (topCapabilityTags.isEmpty()) "아직 태그 없음" else topCapabilityTags.joinToString(", ")
        val providerSummary =
            "Provider: 온라인 ${map.onlineProviderCount} / 승인 ${map.approvedProviderCount} · " +
                "모델 ${map.modelCount}종 · 채널 AI ${map.channelAiCount}개 · 지식공간 ${map.knowledgeSpaceCount}개"
        return "🗺️ **AI 네트워크 지도**\n\n" +
            "구성 단계: `${map.networkLevel}` · 활동 레벨: `${map.aiLevel}` (XP ${map.totalXp}, 다음까지 ${map.xpToNext})\n" +
            "상태: `${map.healthStatus}` · 과부하 경고: `${map.overloadAlertCount}`\n" +
            "$providerSummary\n" +
            "능력 태그: $tags\n\n" +
            "__모델 지도__\n$models\n\n" +
            "__채널 AI__\n$channels\n\n" +
            "__다음 액션__\n$next"
    }

    fun aiNetworkCheck(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return Reply(formatAiNetworkChecklist(aiNetworkLaunchChecklist.checklist(ctx.guildId)))
    }

    private fun formatAiNetworkChecklist(checklist: NetworkLaunchChecklist): String {
        val topItems =
            checklist.items
                .filter { it.status != "ready" }
                .ifEmpty { checklist.items.take(5) }
                .take(8)
                .joinToString("\n") { item ->
                    val icon =
                        when (item.status) {
                            "ready" -> "✅"
                            "warning" -> "⚠️"
                            else -> "⛔"
                        }
                    "$icon **${item.title}** — ${item.nextAction}"
                }
        val next =
            checklist.nextActions
                .take(5)
                .joinToString("\n") { "• $it" }
                .ifBlank { "• 지금 막을 항목은 없습니다." }
        return "🧭 **AI 네트워크 출시/운영 체크리스트**\n\n" +
            "상태: `${checklist.status}` · Gate: `${checklist.releaseGate}` · 점수: `${checklist.score}`\n" +
            "준비 ${checklist.readyCount} · 주의 ${checklist.warningCount} · 차단 ${checklist.blockedCount}\n\n" +
            "__핵심 항목__\n$topItems\n\n" +
            "__다음 액션__\n$next"
    }
}
