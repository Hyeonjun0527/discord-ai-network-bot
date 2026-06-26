package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.components.ActionRow
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 서버 설정 마법사(settings wizard) 인터랙션 핸들러(god class 분해 — verbatim 이동).
 *
 * 담당: 설정 패널의 진행중 변경(pending)을 모으고(언어/모델/허용채널/자동승인), 패널 embed/rows 를 렌더링하며,
 * 저장 버튼으로 [CommandService.saveGuildSettings] 에 한 번에 반영한다. 본문(JDA 호출 순서·문구·컴포넌트 ID
 * 매칭·적용 순서)은 DiscordBot.Listener 에서 1바이트 불변으로 이동했고 Listener 가 같은 인자로 위임한다.
 *
 * **공유 불변식**: [pendingSettings] 는 4014 안전폴백 재기동으로 생기는 두 번째 Listener 와도 **같은 진행중 설정**을
 * 공유해야 한다. 이를 정적 맵 대신 **@Component 단일 Spring 빈**의 인스턴스 필드로 두고, DiscordBot 이 이 단일 빈을
 * 주입받아 모든 Listener 인스턴스에 그대로 넘긴다 → 모든 Listener 가 같은 빈·같은 맵을 참조해 공유가 보존된다.
 */
@Component
class SettingsWizardHandler(
    private val commands: CommandService,
    channelProfiles: ChannelAiProfileService,
) {
    // 단일 빈 인스턴스 필드 → 모든 Listener 가 같은 맵 공유(정적 companion 대체, 4014 재기동 두 번째 Listener 와도 공유).
    private val pendingSettings = ConcurrentHashMap<String, PendingGuildSettings>()

    // 채널 일괄 모달은 마법사의 effectiveAllowedChannelIds 에 의존하므로 같은 핸들러가 소유한 함수를 주입한다.
    private val channelProfilePanel = ChannelProfilePanelRenderer(channelProfiles, ::effectiveAllowedChannelIds)

    data class PendingGuildSettings(
        var language: String? = null,
        var defaultModel: String? = null,
        var allowedChannelIds: List<Long>? = null,
        var autoApprove: Boolean? = null,
    )

    // ── 설정 마법사 버튼/드롭다운/모달 위임 진입점(컴포넌트 ID 매칭 → 본문은 아래 verbatim 메서드) ──────────────

    /**
     * 설정 패널의 wizard 전용 버튼(자동승인 on/off·모든채널·채널일괄·취소·저장)을 처리한다.
     * 처리했으면 true(호출자는 즉시 반환), 아니면 false(호출자가 비-wizard 분기 계속).
     */
    fun handleButton(
        event: ButtonInteractionEvent,
        ctx: CommandContext,
    ): Boolean {
        when (event.componentId) {
            MenuFactory.AUTO_APPROVE_ON -> {
                pendingSettings(settingsKey(ctx)).autoApprove = true
                updateSettingsPanel(event, ctx)
            }
            MenuFactory.AUTO_APPROVE_OFF -> {
                pendingSettings(settingsKey(ctx)).autoApprove = false
                updateSettingsPanel(event, ctx)
            }
            MenuFactory.CHANNEL_ALL -> {
                pendingSettings(settingsKey(ctx)).allowedChannelIds = emptyList()
                updateSettingsPanel(event, ctx)
            }
            MenuFactory.CHANNEL_BULK -> {
                if (!ctx.isAdmin) {
                    event.reply("⛔ 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                } else {
                    event.replyModal(channelProfilePanel.channelBulkModal(ctx)).queue()
                }
            }
            MenuFactory.CANCEL_SETTINGS -> {
                pendingSettings.remove(settingsKey(ctx))
                updateSettingsPanel(event, ctx)
            }
            MenuFactory.SAVE_SETTINGS -> savePendingSettings(event, ctx)
            MenuFactory.NIA_MEMBER_TOGGLE -> toggleNiaMemberChannel(event, ctx)
            else -> return false
        }
        return true
    }

    /**
     * 설정 드롭다운(언어/모델/자동승인 select)을 처리한다. 처리했으면 true, 아니면 false.
     */
    fun handleStringSelect(
        event: StringSelectInteractionEvent,
        ctx: CommandContext,
        value: String,
    ): Boolean {
        when (event.componentId) {
            MenuFactory.LANG -> {
                pendingSettings(settingsKey(ctx)).language = value
                updateSettingsPanel(event, ctx)
            }
            MenuFactory.MODEL -> {
                pendingSettings(settingsKey(ctx)).defaultModel = value
                updateSettingsPanel(event, ctx)
            }
            MenuFactory.AUTO_APPROVE_SELECT -> {
                pendingSettings(settingsKey(ctx)).autoApprove = value.toBooleanStrictOrNull() ?: false
                updateSettingsPanel(event, ctx)
            }
            else -> return false
        }
        return true
    }

    /** 설정 채널 허용(엔티티 선택). 컴포넌트가 채널 선택이면 처리하고 true, 아니면 false. */
    fun handleEntitySelect(
        event: EntitySelectInteractionEvent,
        ctx: CommandContext,
    ): Boolean {
        if (event.componentId != MenuFactory.CHANNEL) return false
        val channelIds = event.values.map { it.idLong }
        pendingSettings(settingsKey(ctx)).allowedChannelIds = channelIds
        updateSettingsPanel(event, ctx)
        return true
    }

    /** 설정 채널 일괄 입력 모달 제출을 처리한다. 처리했으면 true, 아니면 false. */
    fun handleModal(
        event: ModalInteractionEvent,
        ctx: CommandContext,
    ): Boolean {
        if (event.modalId != SETTINGS_CHANNEL_BULK_MODAL) return false
        val ids = MenuFactory.parseChannelIdsBulk(event.getValue("channels")?.asString.orEmpty())
        pendingSettings(settingsKey(ctx)).allowedChannelIds = ids
        event
            .replyEmbeds(settingsEmbed(ctx))
            .addComponents(settingsRows(ctx))
            .setEphemeral(true)
            .queue()
        return true
    }

    // ── 이하 verbatim 이동(본문 1바이트 불변) ──────────────────────────────────────────────────────────────

    /** 설정 패널 Embed(현재 상태 + 저장 대기 변경사항). */
    fun settingsEmbed(ctx: CommandContext): MessageEmbed {
        val pending = pendingSettings[settingsKey(ctx)]
        val effectiveDefaultModel =
            when (pending?.defaultModel) {
                "__auto__" -> null
                null -> commands.guildDefaultModel(ctx)
                else -> pending.defaultModel
            }
        return EmbedFactory.settingsEmbed(
            language = pending?.language ?: commands.guildLanguage(ctx),
            defaultModel = effectiveDefaultModel,
            poolModelCount = commands.poolModels(ctx).size,
            allowedChannelCount = effectiveAllowedChannelIds(ctx).size,
            allowedChannelText = allowedChannelText(ctx),
            autoApprove = pending?.autoApprove ?: commands.isAutoApprove(ctx),
            niaMemberEnabled = commands.isNiaMemberChannelEnabled(ctx),
            pendingSummary = pendingSummary(ctx),
            currentSummary = currentSettingsSummary(ctx),
        )
    }

    fun settingsKey(ctx: CommandContext) = "${ctx.guildId}:${ctx.channelId}:${ctx.userId}"

    private fun allowedChannelText(ctx: CommandContext): String = formatChannelPolicy(effectiveAllowedChannelIds(ctx))

    private fun currentSettingsSummary(ctx: CommandContext): String {
        val model = commands.guildDefaultModel(ctx) ?: "자동 선택"
        val autoApprove = if (commands.isAutoApprove(ctx)) "켜짐" else "꺼짐"
        val niaMember = if (commands.isNiaMemberChannelEnabled(ctx)) "켜짐" else "꺼짐"
        return listOf(
            "• 언어: `${commands.guildLanguage(ctx)}`",
            "• 기본 모델: `$model`",
            "• LLM 사용 채널: ${formatChannelPolicy(commands.allowedChannelIds(ctx))}",
            "• 자동 승인: `$autoApprove`",
            "• 사람같은 니아(현재 채널): `$niaMember`",
        ).joinToString("\n")
    }

    fun effectiveAllowedChannelIds(ctx: CommandContext): List<Long> =
        pendingSettings[settingsKey(ctx)]?.allowedChannelIds ?: commands.allowedChannelIds(ctx)

    private fun formatChannelPolicy(channelIds: Collection<Long>): String {
        if (channelIds.isEmpty()) return "모든 채널 허용"
        val distinct = channelIds.distinct()
        val visible = distinct.take(12).joinToString(" ") { "<#$it>" }
        val suffix = if (distinct.size > 12) " 외 ${distinct.size - 12}개" else ""
        return "${distinct.size}개 채널: $visible$suffix"
    }

    private fun pendingSummary(ctx: CommandContext): String? {
        val pending = pendingSettings[settingsKey(ctx)] ?: return null
        val lines = mutableListOf<String>()
        pending.language?.let { lines += "• 언어 → `$it`" }
        pending.defaultModel?.let { lines += "• 기본 모델 → `${if (it == "__auto__") "자동 선택" else it}`" }
        pending.allowedChannelIds?.let { ids ->
            lines += "• LLM 사용 채널 → ${formatChannelPolicy(ids)}"
        }
        pending.autoApprove?.let { lines += "• 자동 승인 → `${if (it) "켜짐" else "꺼짐"}`" }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun pendingSettings(key: String): PendingGuildSettings = pendingSettings.computeIfAbsent(key) { PendingGuildSettings() }

    /**
     * 저장 버튼: 진행중 pending 을 commands 로 한 번에 적용하고 pending 을 비운다(순수 적용 로직 — JDA 비의존).
     * 변경사항이 없으면 null(호출자는 "저장할 변경사항 없음" 안내). 적용 순서/값은 verbatim 보존.
     */
    fun applyPendingSettings(ctx: CommandContext): Reply? {
        val key = settingsKey(ctx)
        val pending = pendingSettings.remove(key)
        if (pending == null || pending == PendingGuildSettings()) {
            return null
        }
        return commands.saveGuildSettings(
            ctx,
            pending.language,
            pending.defaultModel,
            pending.allowedChannelIds,
            pending.autoApprove,
        )
    }

    private fun savePendingSettings(
        event: ButtonInteractionEvent,
        ctx: CommandContext,
    ) {
        val reply = applyPendingSettings(ctx)
        if (reply == null) {
            event.reply("아직 저장할 변경사항이 없습니다. 언어/모델/채널/자동 승인을 먼저 선택해주세요.").setEphemeral(true).queue()
            return
        }
        event
            .editMessageEmbeds(settingsEmbed(ctx))
            .setComponents(settingsRows(ctx))
            .queue({
                event.hook
                    .sendMessage(reply.content)
                    .setEphemeral(true)
                    .queue({}, {})
            }, {})
    }

    private fun toggleNiaMemberChannel(
        event: ButtonInteractionEvent,
        ctx: CommandContext,
    ) {
        val next = !commands.isNiaMemberChannelEnabled(ctx)
        val reply = commands.setNiaMemberChannel(ctx, next)
        event
            .editMessageEmbeds(settingsEmbed(ctx))
            .setComponents(settingsRows(ctx))
            .queue({
                event.hook
                    .sendMessage(reply.content)
                    .setEphemeral(true)
                    .queue({}, {})
            }, {})
    }

    private fun updateSettingsPanel(
        event: ButtonInteractionEvent,
        ctx: CommandContext,
    ) {
        event.editMessageEmbeds(settingsEmbed(ctx)).setComponents(settingsRows(ctx)).queue()
    }

    private fun updateSettingsPanel(
        event: StringSelectInteractionEvent,
        ctx: CommandContext,
    ) {
        event.editMessageEmbeds(settingsEmbed(ctx)).setComponents(settingsRows(ctx)).queue()
    }

    private fun updateSettingsPanel(
        event: EntitySelectInteractionEvent,
        ctx: CommandContext,
    ) {
        event.editMessageEmbeds(settingsEmbed(ctx)).setComponents(settingsRows(ctx)).queue()
    }

    /** 설정 패널 액션 로우(언어·모델·채널 드롭다운 + 명시 버튼). */
    fun settingsRows(ctx: CommandContext): List<ActionRow> =
        listOf(
            ActionRow.of(
                MenuFactory.languageSelect(current = pendingSettings[settingsKey(ctx)]?.language ?: commands.guildLanguage(ctx)),
            ),
            ActionRow.of(
                MenuFactory.modelSelect(
                    models = commands.poolModels(ctx),
                    current = pendingSettings[settingsKey(ctx)]?.defaultModel ?: commands.guildDefaultModel(ctx),
                ),
            ),
            ActionRow.of(MenuFactory.channelSelect(effectiveAllowedChannelIds(ctx))),
            ActionRow.of(
                MenuFactory.autoApproveSelect(
                    current = pendingSettings[settingsKey(ctx)]?.autoApprove ?: commands.isAutoApprove(ctx),
                ),
            ),
            ActionRow.of(MenuFactory.settingsActionButtons(commands.isNiaMemberChannelEnabled(ctx))),
        )

    companion object {
        private const val SETTINGS_CHANNEL_BULK_MODAL = "settings:channel-bulk-modal"
    }
}
