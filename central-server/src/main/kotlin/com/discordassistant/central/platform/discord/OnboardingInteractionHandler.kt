package com.discordassistant.central.platform.discord

import com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository
import com.discordassistant.central.onboarding.application.GuildHistoryBackfillService
import com.discordassistant.central.onboarding.application.GuildOnboardingResult
import com.discordassistant.central.onboarding.application.GuildOnboardingService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.slf4j.LoggerFactory

/**
 * 서버 AI 자동 온보딩 인터랙션 핸들러(god class 분해 — verbatim 이동).
 * 온보딩 버튼(시작/승인/거절) 처리, 제안 카드 reply/deferred 응답, embed/buttons 빌더,
 * JDA 핀/본문 백필 수집·정제까지 담당한다. 본문(JDA 호출 순서·문구·백필 로직)은
 * DiscordBot.Listener 에서 1바이트 불변으로 이동했고 Listener 가 동일 인자로 위임한다.
 */
class OnboardingInteractionHandler(
    private val commands: CommandService,
    private val historyBackfill: GuildHistoryBackfillService,
    private val onboardingOptOuts: GuildOnboardingOptOutRepository,
    private val messageContentIntentEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(OnboardingInteractionHandler::class.java)

    /**
     * 서버 AI 자동 온보딩 버튼(Phase 1):
     *  - `onboard:start` — 입장 배너에서 시작 → 제안 카드 + 승인/거절 버튼.
     *  - `onboard:approve:<proposalId>` / `onboard:reject:<proposalId>` — 제안 검토.
     */
    fun handleOnboardingButton(
        event: ButtonInteractionEvent,
        ctx: CommandContext,
    ) {
        val payload = event.componentId.removePrefix(ONBOARD_PREFIX)
        val action = payload.substringBefore(':', missingDelimiterValue = payload)
        if (action == ONBOARD_ACTION_START) {
            replyOnboardingProposal(event, ctx, event.channel.name)
            return
        }
        if (action != ONBOARD_ACTION_APPROVE && action != ONBOARD_ACTION_REJECT) {
            event.reply("알 수 없는 온보딩 동작입니다.").setEphemeral(true).queue()
            return
        }
        val proposalId = payload.substringAfter(':', missingDelimiterValue = "").trim().toLongOrNull()
        if (proposalId == null) {
            event.reply("제안 정보를 읽지 못했어요. `/ai-onboard` 로 다시 시작해 주세요.").setEphemeral(true).queue()
            return
        }
        val reply =
            if (action == ONBOARD_ACTION_APPROVE) {
                commands.approveOnboarding(ctx, proposalId)
            } else {
                commands.rejectOnboarding(ctx, proposalId)
            }
        event.reply(reply.content).setEphemeral(true).queue()
    }

    /**
     * 온보딩 시작 → 제안 카드 embed + 승인/거절 버튼으로 응답(ephemeral, **즉시 reply** 경로).
     * 입장 배너 `onboard:start` 버튼용 — 현재 채널 **핀/공지만** 기본 백필한다(본문 옵션은 슬래시 `/ai-onboard` 전용).
     */
    private fun replyOnboardingProposal(
        event: net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent,
        ctx: CommandContext,
        channelName: String?,
    ) {
        val callback = event as net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
        // 핀 기본 백필(본문 없음). 현재 채널이 GuildMessageChannel 이면 핀만 수집.
        val pinChannel = (event as? Interaction)?.channel as? GuildMessageChannel
        val backfill =
            buildOnboardingBackfill(guildId = ctx.guildId, pinChannel = pinChannel, bodyChannel = null, historyLimit = 0)
        when (
            val outcome =
                commands.startAutoOnboarding(
                    ctx = ctx,
                    channelName = channelName,
                    channelWhitelist = backfill.whitelist,
                    historyLimit = 0,
                    backfill = backfill.input,
                )
        ) {
            is OnboardingStartOutcome.Rejected ->
                callback.reply(outcome.reply.content).setEphemeral(true).queue()
            is OnboardingStartOutcome.Started -> {
                callback
                    .replyEmbeds(onboardingEmbed(outcome.result))
                    .addComponents(onboardingButtons(outcome.result.proposalId))
                    .setEphemeral(true)
                    .queue()
            }
        }
    }

    /**
     * `/ai-onboard` 슬래시 — deferReply 후 전용 풀에서 백필+색인까지 끝내고 editOriginal 로 제안 카드를 보낸다.
     * 핀/공지는 현재 채널에서 기본 수집하고, [bodyChannel] 이 지정되면 그 채널 본문도(MESSAGE_CONTENT 활성 시) 수집한다.
     */
    fun replyOnboardingProposalDeferred(
        event: SlashCommandInteractionEvent,
        ctx: CommandContext,
        channelName: String?,
        bodyChannel: GuildMessageChannel?,
        historyLimit: Int,
    ) {
        val pinChannel = event.channel as? GuildMessageChannel
        val backfill =
            buildOnboardingBackfill(
                guildId = ctx.guildId,
                pinChannel = pinChannel,
                bodyChannel = bodyChannel,
                historyLimit = historyLimit,
            )
        when (
            val outcome =
                commands.startAutoOnboarding(
                    ctx = ctx,
                    channelName = channelName,
                    channelWhitelist = backfill.whitelist,
                    historyLimit = historyLimit,
                    backfill = backfill.input,
                )
        ) {
            is OnboardingStartOutcome.Rejected ->
                event.hook.editOriginal(outcome.reply.content).queue({}, {})
            is OnboardingStartOutcome.Started ->
                event.hook
                    .editOriginalEmbeds(onboardingEmbed(outcome.result))
                    .setComponents(onboardingButtons(outcome.result.proposalId))
                    .queue({}, {})
        }
    }

    private fun onboardingEmbed(r: GuildOnboardingResult): MessageEmbed =
        EmbedFactory.onboardingProposalEmbed(
            name = r.name,
            purpose = r.job,
            tone = r.tone,
            answerLength = r.answerLength,
            constitution = r.constitution,
            backfilledMessageCount = r.backfilledMessageCount,
            scrubbedCount = r.scrubbedCount,
            knowledgeIndexed = r.knowledgeIndexed,
            knowledgeSpaceCreated = r.knowledgeSpaceId != null,
            analysisSource = r.analysisSource,
            customInstruction = r.customInstruction,
        )

    private fun onboardingButtons(proposalId: Long): ActionRow =
        ActionRow.of(
            Button.success("$ONBOARD_PREFIX$ONBOARD_ACTION_APPROVE:$proposalId", "✅ 승인하고 적용"),
            Button.danger("$ONBOARD_PREFIX$ONBOARD_ACTION_REJECT:$proposalId", "🚫 거절"),
        )

    /** 백필 입력(정제된 텍스트 + 카운트)과 화이트리스트를 묶는 보조 결과. */
    private data class OnboardingBackfill(
        val input: GuildOnboardingService.BackfillInput?,
        val whitelist: Set<Long>,
    )

    /**
     * JDA 로 핀/본문을 수집(JDA I/O 어댑터 글루)해 [GuildHistoryBackfillService.sanitizeMessages] 로 정제하고
     * [GuildOnboardingService.BackfillInput] 으로 변환한다. 핀은 [pinChannel] 에서 항상,
     * 본문은 [bodyChannel] 이 지정되고 MESSAGE_CONTENT 활성일 때만(권한 없으면 graceful skip).
     */
    private fun buildOnboardingBackfill(
        guildId: Long,
        pinChannel: GuildMessageChannel?,
        bodyChannel: GuildMessageChannel?,
        historyLimit: Int,
    ): OnboardingBackfill {
        var collected = 0
        var scrubbed = 0
        val parts = mutableListOf<String>()
        val whitelist = mutableSetOf<Long>()
        // opt-out 사용자 조회는 discord 레이어에서(레이어 규칙 유지) — 본인 메시지를 백필 색인에서 제외.
        val optedOut = onboardingOptOuts.findByGuildId(guildId).map { it.userId }.toSet()

        // 1) 현재 채널 핀/공지(기본). MESSAGE_CONTENT 없이도 핀 본문은 읽힌다.
        pinChannel?.let { channel ->
            val raw = fetchPinned(channel)
            val result = historyBackfill.sanitizeMessages(raw, optedOutUserIds = optedOut)
            collected += result.collectedCount
            scrubbed += result.scrubbedCount
            if (result.indexText.isNotBlank()) parts.add(result.indexText)
        }

        // 2) 화이트리스트 채널 본문(옵션). MESSAGE_CONTENT 활성 + history-limit > 0 일 때만.
        bodyChannel?.let { channel ->
            whitelist.add(channel.idLong)
            if (historyLimit > 0 && messageContentIntentEnabled) {
                val raw = fetchHistory(channel, historyLimit)
                val result = historyBackfill.sanitizeMessages(raw, optedOutUserIds = optedOut)
                collected += result.collectedCount
                scrubbed += result.scrubbedCount
                if (result.indexText.isNotBlank()) parts.add(result.indexText)
            }
        }

        val text = parts.joinToString("\n\n").trim()
        val input =
            if (text.isBlank()) {
                null
            } else {
                GuildOnboardingService.BackfillInput(
                    indexText = text,
                    backfilledMessageCount = collected,
                    scrubbedCount = scrubbed,
                )
            }
        return OnboardingBackfill(input = input, whitelist = whitelist)
    }

    /** 핀/공지 조회(JDA I/O). MESSAGE_HISTORY 권한 없거나 실패하면 graceful 하게 빈 목록. */
    private fun fetchPinned(channel: GuildMessageChannel): List<GuildHistoryBackfillService.RawMsg> {
        if (!channel.guild.selfMember.hasPermission(channel, Permission.MESSAGE_HISTORY)) return emptyList()
        return runCatching { channel.retrievePinnedMessages().complete() }
            .getOrElse {
                log.warn("핀 메시지 조회 실패 channel={}: {}", channel.idLong, it.message)
                emptyList()
            }.map { it.toRawMsg() }
    }

    /** 최근 메시지 본문 조회(JDA I/O). 권한 없거나 실패하면 graceful 하게 빈 목록. */
    private fun fetchHistory(
        channel: GuildMessageChannel,
        limit: Int,
    ): List<GuildHistoryBackfillService.RawMsg> {
        if (!channel.guild.selfMember.hasPermission(channel, Permission.MESSAGE_HISTORY)) return emptyList()
        val capped = limit.coerceAtMost(GuildHistoryBackfillService.MAX_HISTORY_PER_CALL)
        return runCatching { channel.history.retrievePast(capped).complete() }
            .getOrElse {
                log.warn("히스토리 조회 실패 channel={}: {}", channel.idLong, it.message)
                emptyList()
            }.map { it.toRawMsg() }
    }

    private fun Message.toRawMsg(): GuildHistoryBackfillService.RawMsg =
        GuildHistoryBackfillService.RawMsg(
            authorId = author.idLong,
            isBot = author.isBot,
            isWebhook = isWebhookMessage,
            isSystem = type.isSystem,
            pinned = isPinned,
            // contentRaw 로 받아 멘션을 `<@id>`/`<@&id>`/`<#id>` 토큰으로 유지 → sanitizeMessages 가 마스킹(표시이름 비노출).
            content = contentRaw,
        )

    companion object {
        private const val ONBOARD_PREFIX = "onboard:"
        private const val ONBOARD_ACTION_START = "start"
        private const val ONBOARD_ACTION_APPROVE = "approve"
        private const val ONBOARD_ACTION_REJECT = "reject"
    }
}
