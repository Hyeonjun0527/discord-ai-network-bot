package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.ainetwork.application.AiNetworkLaunchChecklistService
import com.discordassistant.central.ainetwork.application.AiNetworkMap
import com.discordassistant.central.ainetwork.application.AiNetworkMapService
import com.discordassistant.central.ainetwork.application.NetworkLaunchChecklist
import com.discordassistant.central.ainetwork.application.NiaAffinityService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.shared.NexaIdentity
import org.springframework.stereotype.Component

/**
 * AI 네트워크/니아 호감도 명령군(niaAffinity, aiNetworkMap, aiNetworkCheck).
 * CommandService 에서 응집 단위로 분리 — 포매터/문구/진행바 출력 불변, 시그니처 유지·위임.
 */
@Component
class AiNetworkCommandHandler(
    private val aiNetworkLaunchChecklist: AiNetworkLaunchChecklistService,
    private val aiNetworkMap: AiNetworkMapService,
    private val niaAffinity: NiaAffinityService,
    private val projectAdmins: ProjectAdmins,
    private val guards: SharedCommandGuards,
) {
    /**
     * 니아의 전체 페르소나(전문 + few-shot)를 보여 준다 — 프로젝트 관리자(admin-user-ids) allow-list 전용.
     * 비관리자는 거부하고, 응답은 항상 ephemeral(요청자만 보임)로 보내 비공개 원칙(전문 비공개)을 지킨다.
     */
    fun niaPersona(ctx: CommandContext): Reply {
        if (!projectAdmins.isAdmin(ctx.userId)) {
            return Reply("이 명령은 프로젝트 관리자만 사용할 수 있어요.", ephemeral = true)
        }
        val full = NexaIdentity.NIA_DEFAULT_PERSONA + "\n\n" + NexaIdentity.NIA_FEWSHOT
        return Reply("🔒 **니아 전체 페르소나(비공개)**\n\n$full", ephemeral = true)
    }

    /** 요청자와 니아의 개인 호감도(public·비관리자). */
    fun niaAffinity(ctx: CommandContext): Reply {
        val view = niaAffinity.view(ctx.userId)
        val bar = affinityProgressBar(view.score, view.stage.threshold, view.nextStage?.threshold)
        val nextLine =
            view.nextStage?.let { next ->
                "다음 단계 **${next.displayName}**까지: **${view.scoreToNext}**\n"
            } ?: "이미 최고 단계예요. 계속 같이 이야기해 주세요.\n"
        return Reply(
            "🐾 **니아 호감도**\n" +
                "현재 단계: **${view.stage.displayName}** · 호감도: **${view.score}**\n" +
                "$bar\n" +
                nextLine +
                "_질문(/ask)이나 그림 생성이 성공할 때마다 니아와 조금 더 가까워져요. 순위 비교는 없습니다._",
            ephemeral = false,
        )
    }

    private fun affinityProgressBar(
        score: Long,
        stageThreshold: Long,
        nextThreshold: Long?,
    ): String {
        if (nextThreshold == null) return "▰▰▰▰▰▰▰▰▰▰ 100%"
        val span = (nextThreshold - stageThreshold).coerceAtLeast(1L)
        val gained = (score - stageThreshold).coerceIn(0L, span)
        val ratio = gained.toDouble() / span.toDouble()
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
            "구성 단계: `${map.networkLevel}`\n" +
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
