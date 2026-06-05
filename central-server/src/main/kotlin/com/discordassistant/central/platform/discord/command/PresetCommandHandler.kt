package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.preset.application.PresetImportResult
import com.discordassistant.central.preset.application.PresetModerationSummary
import com.discordassistant.central.preset.application.PresetRegistryService
import com.discordassistant.central.preset.application.PublishedPresetSummary
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * AI 프리셋 공유/검수 명령군(presetCatalog, importPresetToCurrentChannel, likePreset, reportPreset,
 * presetModeration, reviewPresetReport)와 그 포매터/URL 정규화(formatPresetCatalog/Moderation/Import,
 * publicWebBaseUrl, presetCatalogUrl). CommandService 에서 응집 단위로 분리 — 사용자 노출 텍스트·URL 정규화 불변.
 */
@Component
class PresetCommandHandler(
    private val presetRegistry: PresetRegistryService,
    private val guards: SharedCommandGuards,
    @param:Value("\${central.relay.public-url:}")
    private val relayPublicUrl: String = "",
) {
    fun presetCatalog(
        ctx: CommandContext,
        query: String? = null,
        category: String? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val presets =
            presetRegistry.searchPublishedPresets(query = query, category = category, sort = "popular", limit = 10)
        return Reply(formatPresetCatalog(presets, query, category))
    }

    fun importPresetToCurrentChannel(
        ctx: CommandContext,
        publishedPresetId: Long,
        confirmConflicts: Boolean = false,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            val imported =
                presetRegistry.importPreset(
                    publishedPresetId = publishedPresetId,
                    targetGuildId = ctx.guildId,
                    targetChannelId = ctx.channelId,
                    importedBy = ctx.userId,
                    confirmConflicts = confirmConflicts,
                )
            Reply(formatPresetImport(imported))
        }.getOrElse { error ->
            val preview =
                runCatching {
                    presetRegistry.previewImport(
                        publishedPresetId = publishedPresetId,
                        targetGuildId = ctx.guildId,
                        targetChannelId = ctx.channelId,
                    )
                }.getOrNull()
            val conflicts =
                preview
                    ?.conflicts
                    ?.joinToString("\n") { "• `${it.severity}` ${it.message}" }
                    ?.ifBlank { null }
            Replies.warn(
                "프리셋을 바로 가져오지 못했어요. ${error.message ?: "원인을 확인해 주세요."}\n" +
                    (conflicts?.let { "\n충돌/확인 필요:\n$it\n" } ?: "") +
                    "적용해도 괜찮다면 `/프리셋가져오기` 에서 `confirm-conflicts: true` 로 다시 실행하세요.",
            )
        }
    }

    fun likePreset(
        ctx: CommandContext,
        publishedPresetId: Long,
    ): Reply {
        val published =
            runCatching { presetRegistry.likePreset(publishedPresetId, ctx.userId) }
                .getOrElse { return Replies.warn("프리셋 좋아요에 실패했어요. ${it.message ?: "프리셋 ID를 확인해 주세요."}") }
        return Replies.ok("프리셋 **${published.title}** 좋아요를 반영했습니다. 현재 ${published.likeCount}개")
    }

    fun reportPreset(
        ctx: CommandContext,
        publishedPresetId: Long,
        reason: String,
    ): Reply {
        val report =
            runCatching { presetRegistry.reportPreset(publishedPresetId, ctx.userId, reason) }
                .getOrElse { return Replies.warn("프리셋 신고에 실패했어요. ${it.message ?: "프리셋 ID와 신고 사유를 확인해 주세요."}") }
        return Replies.ok(
            "프리셋 신고를 접수했습니다. report `${report.id}` · 상태 `${report.status}`\n" +
                "신고된 프리셋은 카탈로그 노출/가져오기 전에 관리자 검토 대상으로 전환됩니다.",
        )
    }

    fun presetModeration(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching { Reply(formatPresetModeration(presetRegistry.moderationSummary())) }
            .getOrElse { Replies.warn("프리셋 신고 큐를 불러오지 못했어요. ${it.message ?: "잠시 후 다시 시도해 주세요."}") }
    }

    fun reviewPresetReport(
        ctx: CommandContext,
        reportId: Long,
        decision: String,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val report =
            runCatching { presetRegistry.reviewReport(reportId, decision, reviewerUserId = ctx.userId) }
                .getOrElse { return Replies.warn("프리셋 신고 검수 처리에 실패했어요. ${it.message ?: "report-id/decision을 확인해 주세요."}") }
        return Replies.ok(
            "프리셋 신고를 처리했습니다. report `${report.id}` · 결정 `${report.status}`\n" +
                "카탈로그 노출 상태는 결정에 맞춰 자동 갱신됩니다.",
        )
    }

    private fun formatPresetCatalog(
        presets: List<PublishedPresetSummary>,
        query: String?,
        category: String?,
    ): String {
        val filter =
            listOfNotNull(
                query?.takeIf { it.isNotBlank() }?.let { "검색 `$it`" },
                category?.takeIf { it.isNotBlank() }?.let { "카테고리 `$it`" },
            ).joinToString(" · ").ifBlank { "인기순" }
        val presetLines =
            presets.take(10).map { preset ->
                val categoryText = preset.category ?: "general"
                val mode = preset.responseMode ?: "balanced"
                val webLink = presetCatalogUrl(preset.slug.ifBlank { preset.id.toString() })?.let { " · <$it>" } ?: ""
                "• `${preset.id}` **${preset.title}** — $categoryText · $mode · 👍 ${preset.likeCount} · " +
                    "가져오기 ${preset.importCount}$webLink"
            }
        val lines = presetLines.joinToString("\n").ifBlank { "• 아직 공개된 프리셋이 없습니다." }
        val webCatalog =
            presetCatalogUrl()?.let { "웹에서 검색·미리보기·가져오기: <$it>\n" }
                ?: "웹 카탈로그가 배포되면 `/presets` 에서 검색·미리보기·가져오기를 할 수 있습니다.\n"
        return "📚 **AI 프리셋 공유 목록** ($filter)\n\n" +
            "$lines\n\n" +
            webCatalog +
            "현재 채널에 적용하려면 `/프리셋가져오기 published-id:<ID>` 를 실행하세요.\n" +
            "부적절하면 `/프리셋신고 published-id:<ID> reason:<사유>` 로 신고할 수 있습니다."
    }

    private fun formatPresetModeration(summary: PresetModerationSummary): String {
        val queue =
            summary.queue
                .take(10)
                .joinToString("\n") { item ->
                    val reasonCodes =
                        item.reportReasonCodes.entries
                            .joinToString(",") { "${it.key}:${it.value}" }
                            .ifBlank { "none" }
                    "• `${item.publishedPresetId}` **${item.title}** — `${item.status}` · 신고 ${item.reportCount} · " +
                        "좋아요 ${item.likeCount} · risk `${item.riskCodes.joinToString(",").ifBlank { "none" }}` · " +
                        "유형 `$reasonCodes`\n" +
                        "  ↳ ${item.recommendedAction}"
                }.ifBlank { "• 검토할 프리셋 신고가 없습니다." }
        val nextActions =
            summary.nextActions
                .take(5)
                .joinToString("\n") { "• $it" }
                .ifBlank { "• 지금은 추가 조치가 없습니다." }
        return "🛡️ **프리셋 신고/검수 큐**\n\n" +
            "게시 ${summary.activePublishedCount} · 검토중 ${summary.underReviewCount} · " +
            "중단 ${summary.suspendedCount} · 제거 ${summary.removedCount}\n" +
            "열린 신고 ${summary.openReportCount} · 처리됨 ${summary.reviewedReportCount}\n\n" +
            "__우선 검토 대상__\n$queue\n\n" +
            "__다음 행동__\n$nextActions\n\n" +
            "처리: `/프리셋신고처리 report-id:<ID> decision:dismiss|suspend|remove`"
    }

    private fun formatPresetImport(imported: PresetImportResult): String =
        "✅ 프리셋을 현재 채널에 가져왔습니다.\n" +
            "상태: `${imported.status}` · 보관함 프리셋: `${imported.importedPresetId ?: "-"}`\n" +
            "원본 revision: `${imported.sourceRevisionId ?: "-"}`\n" +
            "채널 AI: `${imported.createdChannelAiId ?: "-"}` · 행동 버전: `${imported.createdBehaviorVersionId ?: "-"}`\n" +
            "이제 이 채널에서 질문하면 가져온 프리셋의 역할·말투·응답 정책이 적용됩니다."

    private fun publicWebBaseUrl(): String? {
        val raw = relayPublicUrl.trim().trimEnd('/').ifBlank { return null }
        val normalized =
            when {
                raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
                raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
                else -> raw
            }
        return runCatching {
            val uri = java.net.URI.create(normalized)
            val scheme = uri.scheme ?: return@runCatching normalized.substringBefore("/agent").trimEnd('/')
            val authority = uri.rawAuthority ?: return@runCatching normalized.substringBefore("/agent").trimEnd('/')
            "$scheme://$authority"
        }.getOrElse {
            normalized.substringBefore("/agent").trimEnd('/')
        }.ifBlank { null }
    }

    private fun presetCatalogUrl(locator: String? = null): String? {
        val base = publicWebBaseUrl() ?: return null
        val preset = locator?.trim()?.takeIf { it.isNotBlank() } ?: return "$base/presets"
        return "$base/presets?preset=${java.net.URLEncoder.encode(preset, Charsets.UTF_8)}"
    }
}
