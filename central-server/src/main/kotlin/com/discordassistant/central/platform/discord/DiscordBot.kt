package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.global.i18n.I18n
import com.discordassistant.central.guild.application.GuildRemovalCleanupService
import com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository
import com.discordassistant.central.onboarding.application.GuildHistoryBackfillService
import com.discordassistant.central.shared.ModelBurden
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DM(봇과의 1:1 DM)에서 쓸 수 있는 명령: **읽기/안내 전용**(모델 조회·사용량·프라이버시·도움말 등).
 * AI 호출(/ask)과 기여(/provider-*)·관리/정책 명령은 **길드 전용** — 그 서버 멤버만 가능(멤버십 게이트).
 * 이 명령들은 글로벌 + setGuildOnly(false)(봇 DM 허용)로 등록된다. DM 컨텍스트의 guildId 는 DM_SCOPE sentinel.
 * (JDA 5.2.1 은 신 user-install/InteractionContextType 미지원 — 친구끼리 DM/임의 서버 사용은 JDA 업그레이드 필요.)
 */
private val DM_COMMANDS =
    // DM 허용은 **읽기/안내 명령만**. AI 호출(/ask)도, 기여(/provider-*)도 DM 에서 제외한다 —
    // AI 호출은 그 서버 멤버만, 기여도 서버 단위로만(멤버십 게이트). 길드 슬래시는 디스코드가
    // 멤버에게만 노출하므로 별도 검사 불필요. DM 은 멤버 신원이 없어 이 둘을 차단한다.
    setOf(
        "models",
        "catalog",
        "my-usage",
        "contributions",
        "community-stats",
        "privacy",
        "help",
        "welcome",
        "menu",
    )

/** 봇이 들어가 있는 서버(길드) 한 건의 식별 정보(어드민 서버 선택 드롭다운용). */
data class BotGuildInfo(
    val id: Long,
    val name: String,
)

/** 서버 안 텍스트 채널 한 건(어드민 채널 선택 드롭다운용). */
data class BotChannelInfo(
    val id: Long,
    val name: String,
)

/** 봇이 현재 들어가 있는 길드(서버)·채널 목록을 제공한다(웹 ‘토큰 받기’·어드민 선택용). */
interface BotGuildLister {
    fun botGuildIds(): Set<Long>

    /** 봇이 들어가 있는 서버를 이름과 함께 반환(미연결/비활성이면 빈 목록). 어드민 드롭다운용. */
    fun botGuilds(): List<BotGuildInfo>

    /** 한 서버의 텍스트 채널을 이름과 함께 반환(미연결/없으면 빈 목록). 어드민 채널 드롭다운용. */
    fun botChannels(guildId: Long): List<BotChannelInfo>

    /**
     * 이 사용자가 해당 길드의 **관리자**(MANAGE_SERVER 또는 ADMINISTRATOR)인지.
     * 데스크톱 앱의 관리 API 권한 게이트용. JDA 미연결·멤버 캐시 미스·권한 없음이면 false(안전 거부).
     */
    fun isGuildAdmin(
        guildId: Long,
        userId: Long,
    ): Boolean

    /** 길드 멤버의 표시 이름(닉네임 우선). JDA 미연결·멤버 캐시 미스면 null(앱에서 ID 폴백). */
    fun memberName(
        guildId: Long,
        userId: Long,
    ): String?
}

/**
 * Discord(JDA) 부트스트랩 + 슬래시 명령 등록/디스패치 (K-차수 13).
 * central.discord.enabled=true 이고 토큰이 있을 때만 연결한다(테스트/CI 는 비활성).
 */
@Component
class DiscordBot(
    private val commands: CommandService,
    private val metrics: CommandMetrics,
    private val channelProfiles: ChannelAiProfileService,
    private val guildCleanup: GuildRemovalCleanupService,
    private val reconciliation: ProviderPoolReconciliationService,
    private val gatewayStatus: DiscordGatewayStatus,
    private val historyBackfill: GuildHistoryBackfillService,
    private val onboardingOptOuts: GuildOnboardingOptOutRepository,
    // 설정 마법사(pendingSettings 단일 빈 소유). 모든 Listener 인스턴스가 같은 빈을 참조해 진행중 설정을 공유한다.
    private val settingsWizard: SettingsWizardHandler,
    @param:Value("\${central.discord.enabled:false}") private val enabled: Boolean,
    @param:Value("\${central.discord.bot-token:}") private val token: String,
    // 설정 시 해당 길드(서버)에 명령 즉시 등록(전파 지연 없음). 비우면 글로벌 등록(최대 ~1h).
    @param:Value("\${central.discord.guild-id:}") private val guildId: String,
    @param:Value("\${central.discord.message-content-intent-enabled:true}") private val messageContentIntentEnabled: Boolean,
    @param:Value("\${central.discord.fallback-without-message-content-on-4014:true}") private val fallbackWithoutMessageContentOn4014:
        Boolean,
) : BotGuildLister {
    private val log = LoggerFactory.getLogger(DiscordBot::class.java)
    private var jda: JDA? = null

    /** 봇이 들어가 있는 길드 id 집합(JDA 미연결/비활성이면 빈 집합). */
    override fun botGuildIds(): Set<Long> = jda?.guilds?.map { it.idLong }?.toSet() ?: emptySet()

    /** 봇이 들어가 있는 서버를 이름과 함께(이름 오름차순). JDA 미연결/비활성이면 빈 목록. */
    override fun botGuilds(): List<BotGuildInfo> =
        jda
            ?.guilds
            ?.map { BotGuildInfo(it.idLong, it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** 한 서버의 텍스트 채널을 이름과 함께(채널 위치 순). JDA 미연결/서버 없음이면 빈 목록. */
    override fun botChannels(guildId: Long): List<BotChannelInfo> =
        jda
            ?.getGuildById(guildId)
            ?.textChannels
            ?.map { BotChannelInfo(it.idLong, it.name) }
            ?: emptyList()

    /**
     * 관리 API 권한 게이트: 사용자가 길드 관리자(소유자 또는 MANAGE_SERVER|ADMINISTRATOR)인지. 미충족은 false(안전 거부).
     *
     * 봇은 `createLight`(GUILD_MEMBERS 인텐트 미사용)라 멤버 캐시가 비어 있을 수 있다 → `getMemberById` 만으로는
     * 데스크톱 관리 probe 에서 소유자조차 null 로 떨어져 "관리자 아님"이 된다(실측 버그). 그래서:
     *   ① 서버 소유자는 항상 관리자 — `ownerIdLong` 은 GUILD_CREATE 로 캐시/인텐트 없이도 안다(가장 흔한 케이스 즉시 해결).
     *   ② 캐시에 멤버가 있으면 캐시로 권한 판정.
     *   ③ 캐시 미스면 REST 로 1회 조회(GUILD_MEMBERS 인텐트 불필요) 후 위임 관리자(MANAGE_SERVER)까지 판정.
     */
    override fun isGuildAdmin(
        guildId: Long,
        userId: Long,
    ): Boolean {
        val guild = jda?.getGuildById(guildId) ?: return false
        if (guild.ownerIdLong == userId) return true // ① 소유자 = 항상 관리자
        guild.getMemberById(userId)?.let { return it.hasAdminPower() } // ② 캐시 적중
        return try {
            // ③ 캐시 미스 → REST 조회(위임 관리자 포함)
            guild.retrieveMemberById(userId).complete()?.hasAdminPower() ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun net.dv8tion.jda.api.entities.Member.hasAdminPower(): Boolean =
        hasPermission(Permission.MANAGE_SERVER) || hasPermission(Permission.ADMINISTRATOR)

    /** 길드 멤버 표시 이름(닉네임 우선). 캐시 미스/미연결이면 null. */
    override fun memberName(
        guildId: Long,
        userId: Long,
    ): String? = jda?.getGuildById(guildId)?.getMemberById(userId)?.effectiveName

    private val fallbackAttempted = AtomicBoolean(false)

    // 추론(ask/imagine)은 길어서 JDA 게이트웨이 스레드를 점유하면 안 된다(동시 요청 시 봇 전체 stall).
    // deferReply 로 ack 한 뒤 실제 처리는 이 전용 풀에서(나머지 빠른 명령은 리스너 스레드 그대로).
    private val commandExecutor: ExecutorService =
        Executors.newFixedThreadPool(8) { r -> Thread(r, "discord-cmd").apply { isDaemon = true } }

    @PostConstruct
    fun start() {
        if (!enabled || token.isBlank()) {
            log.info("Discord 비활성(enabled={}, token={}) — JDA 미기동", enabled, token.isNotBlank())
            return
        }
        launchJda(messageContentIntentEnabled)
    }

    private fun launchJda(messageContentIntent: Boolean) {
        gatewayStatus.markStarting(messageContentIntent)
        val intents = GatewayIntentPolicy.intents(messageContentIntent)
        val builder = JDABuilder.createLight(token, intents)
        val listener =
            Listener(
                commands,
                metrics,
                channelProfiles,
                guildCleanup,
                reconciliation,
                gatewayStatus,
                historyBackfill,
                onboardingOptOuts,
                settingsWizard,
                mentionAskEnabled = messageContentIntent,
                messageContentIntentEnabled = messageContentIntent,
                onDisallowedIntents = { handleDisallowedIntents(messageContentIntent) },
                slowCommandExecutor = commandExecutor,
            )
        val instance = builder.addEventListeners(listener).build()
        jda = instance
        registerCommands(instance.updateCommands())
        if (guildId.isNotBlank()) {
            instance.awaitReady()
            instance.getGuildById(guildId)?.updateCommands()?.queue({}, {})
        }
        log.info(
            "Discord(JDA) 기동 완료 — 슬래시 명령 글로벌 등록(봇 DM 포함), messageContentIntent={}",
            messageContentIntent,
        )
    }

    private fun handleDisallowedIntents(messageContentIntent: Boolean) {
        val guide =
            "Discord gateway 4014 DISALLOWED_INTENTS — @멘션 질문에는 Developer Portal Bot 설정의 " +
                "Message Content Intent가 필요합니다. 설정하지 않을 경우 " +
                "central.discord.message-content-intent-enabled=false 로 슬래시 명령만 안전 부팅하세요."
        log.error(guide)
        gatewayStatus.markShutdown(4014, guide)
        if (!messageContentIntent || !fallbackWithoutMessageContentOn4014 || !fallbackAttempted.compareAndSet(false, true)) return
        gatewayStatus.markSafeFallback("Message Content Intent 거부로 @멘션 질문을 끄고 슬래시 명령만 재기동합니다.")
        Thread({
            runCatching { jda?.shutdownNow() }
            runCatching { launchJda(messageContentIntent = false) }
                .onFailure { e ->
                    log.error("Message Content Intent 없는 안전 재기동 실패: {}", e.message, e)
                    gatewayStatus.markShutdown(4014, "Message Content Intent 없는 안전 재기동 실패: ${e.message}")
                }
        }, "discord-safe-intent-restart").start()
    }

    @PreDestroy
    fun stop() {
        jda?.shutdown()
        commandExecutor.shutdown()
    }

    private fun registerCommands(action: net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction) {
        // 관리자 명령은 비관리자 UI 에서 숨김(#186). 서버 관리 권한 보유자만 노출.
        val cmds = SlashCommandCatalog.all()
        // 슬래시 명령을 클라이언트 언어(ko 기본/en/ru)로 로컬라이즈.
        // 설치/컨텍스트(차수 19): DM 대상 명령은 봇 DM 허용(setGuildOnly=false), 그 외(관리자/정책)는 길드 전용.
        cmds.forEach { cmd -> cmd.setGuildOnly(cmd.name !in DM_COMMANDS) }
        cmds
            .filterIsInstance<net.dv8tion.jda.api.interactions.commands.build.SlashCommandData>()
            .forEach { CommandLoc.localize(it) }
        action.addCommands(cmds).queue()
    }

    /** JDA 이벤트 → CommandContext → CommandService → 응답. */
    class Listener(
        private val commands: CommandService,
        private val metrics: CommandMetrics,
        private val channelProfiles: ChannelAiProfileService,
        private val guildCleanup: GuildRemovalCleanupService,
        private val reconciliation: ProviderPoolReconciliationService,
        private val gatewayStatus: DiscordGatewayStatus,
        private val historyBackfill: GuildHistoryBackfillService,
        private val onboardingOptOuts: GuildOnboardingOptOutRepository,
        private val settingsWizard: SettingsWizardHandler,
        private val mentionAskEnabled: Boolean,
        private val messageContentIntentEnabled: Boolean,
        private val onDisallowedIntents: () -> Unit,
        private val slowCommandExecutor: ExecutorService,
    ) : ListenerAdapter() {
        // god class 분해(verbatim 이동): 응답 렌더링·채널 프로필 패널·온보딩 인터랙션·설정 마법사를 협력자로 위임한다.
        // 동일 의존성 인스턴스를 그대로 넘겨 동작을 구조적으로 보존한다(로직 불변).
        // settingsWizard 는 단일 빈을 그대로 받아 모든 Listener 가 같은 pendingSettings(진행중 설정)를 공유한다.
        private val answers = DiscordAnswerRenderer(channelProfiles)
        private val channelProfilePanel =
            ChannelProfilePanelRenderer(channelProfiles, settingsWizard::effectiveAllowedChannelIds)
        private val onboarding =
            OnboardingInteractionHandler(commands, historyBackfill, onboardingOptOuts, messageContentIntentEnabled)

        override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
            metrics.record(event.name) // 명령 사용 통계(#190)
            // DM(유저설치, 길드 없음)이면 글로벌 풀 스코프. 길드 전용 명령이 DM 으로 오면 막는다(방어적 — 컨텍스트로도 차단됨).
            if (event.guild == null && event.name !in DM_COMMANDS) {
                event.reply("이 명령은 서버에서만 사용할 수 있어요.").setEphemeral(true).queue()
                return
            }
            val ctx = ctxOf(event)
            // 인터랙티브 명령은 컴포넌트/모달로 응답(온보딩/설정 판).
            when (event.name) {
                "menu" -> {
                    event
                        .replyEmbeds(EmbedFactory.mainMenuEmbed(ctx.isAdmin))
                        .addComponents(ActionRow.of(MenuFactory.mainButtons(ctx.isAdmin)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                "provider-join" -> {
                    if (commands.providerLinked(ctx)) {
                        // 이미 연동된(앱 연결된) 사용자 → 가이드 대신 실제 참여 완료(앱이 동기화로 자동 연결).
                        event.reply(commands.providerJoin(ctx).content).setEphemeral(true).queue()
                    } else {
                        // 미연동 → 먼저 설치할 컴퓨터(OS)를 버튼으로 묻는다(차수 19). 클릭 → 그 OS 복붙 설치 명령.
                        event
                            .reply(
                                "🖥️ **내 컴퓨터의 AI로 함께 도와주기**\n\n" +
                                    "내 컴퓨터에 있는 AI가 커뮤니티 질문에 답하는 일을 함께 도와줘요.\n" +
                                    "복잡한 설정은 안내를 따라 하면 되고, 원할 때 언제든 멈출 수 있어요.\n\n" +
                                    "**설치할 컴퓨터**를 고르세요. 버튼을 누르면 복사해서 붙여넣을 명령을 보여드릴게요.",
                            ).addComponents(ActionRow.of(MenuFactory.osButtons()))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
                "help" -> {
                    // 명령 이름을 보는 사람 클라이언트 언어로 표시(슬래시 메뉴와 일치).
                    event.replyEmbeds(EmbedFactory.helpEmbed(ctx.isAdmin, event.userLocale)).setEphemeral(true).queue()
                    return
                }
                "llm-settings" -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 관리자만 사용할 수 있습니다.").setEphemeral(true).queue()
                    } else {
                        event
                            .replyEmbeds(settingsWizard.settingsEmbed(ctx))
                            .addComponents(settingsWizard.settingsRows(ctx))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
                "llm-channel-profile" -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event
                            .reply(channelProfilePanel.channelProfilePanelText(ctx))
                            .addComponents(channelProfilePanel.channelProfileRows())
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
                "ai-onboard" -> {
                    // 백필은 JDA 히스토리 조회로 느릴 수 있어 deferReply 후 전용 풀에서 처리(게이트웨이 스레드 비점유).
                    val bodyChannel = event.getOption("backfill-channel")?.asChannel as? GuildMessageChannel
                    val historyLimit =
                        (event.getOption("history-limit")?.asLong ?: 0L)
                            .coerceIn(0, MAX_ONBOARD_HISTORY_LIMIT.toLong())
                            .toInt()
                    event.deferReply(true).queue()
                    slowCommandExecutor.execute {
                        runCatching {
                            onboarding.replyOnboardingProposalDeferred(
                                event = event,
                                ctx = ctx,
                                channelName = event.channel.name,
                                bodyChannel = bodyChannel,
                                historyLimit = historyLimit,
                            )
                        }.onFailure {
                            log.warn("ai-onboard 처리 실패: {}", it.message)
                            event.hook.editOriginal("⚠️ AI 자동 설정 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})
                        }
                    }
                    return
                }
                "ask-long" -> {
                    val modal =
                        Modal
                            .create("ask-long-modal", "긴 질문 입력")
                            .addActionRow(
                                TextInput
                                    .create("prompt", "질문", TextInputStyle.PARAGRAPH)
                                    .setRequired(true)
                                    .setMaxLength(4000)
                                    .build(),
                            ).build()
                    event.replyModal(modal).queue()
                    return
                }
            }
            // 모든 명령을 defer 로 먼저 ack(3초 제한 회피) 후 결과 편집. 공유/원격 서버의 지연에도 안전.
            // 공개 명령만 비-ephemeral, 나머지는 ephemeral. defer 시점에 결정.
            val useWebhookProfile = event.name == "ask" && channelProfiles.get(ctx.guildId, ctx.channelId) != null
            val isPublic = event.name in PUBLIC_COMMANDS
            event.deferReply(if (useWebhookProfile) true else !isPublic).queue()
            val work =
                Runnable {
                    try {
                        val reply =
                            if (event.name == "imagine") {
                                // 이미지 생성 진행률을 '생각 중' 메시지에 N% 로 라이브 편집(SD progress 청크 → onProgress).
                                val lastPct =
                                    java.util.concurrent.atomic
                                        .AtomicInteger(-1)
                                commands.imagine(ctx, event.getOption("prompt")?.asString.orEmpty()) { pct ->
                                    if (pct > lastPct.get()) {
                                        lastPct.set(pct)
                                        event.hook.editOriginal("🖼️ 생각 중… $pct%").queue({}, {})
                                    }
                                }
                            } else {
                                dispatch(event, ctx)
                            }
                        if (useWebhookProfile) {
                            answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
                        } else {
                            answers.editOriginalWithPseudoStream(event.hook, reply)
                        }
                    } catch (e: Exception) {
                        log.warn("명령 처리 실패: {} — {}", event.name, e.message)
                        event.hook.editOriginal("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})
                    }
                }
            // 추론(ask/imagine)은 길어서 게이트웨이 스레드 밖에서. 나머지 빠른 명령은 그대로 실행.
            if (event.name in SLOW_COMMANDS) slowCommandExecutor.execute(work) else work.run()
        }

        companion object {
            private val log = LoggerFactory.getLogger(Listener::class.java)

            // 추론으로 오래 걸리는 명령 — 게이트웨이 스레드 밖(전용 풀)에서 처리한다.
            // ai-onboard 는 위 switch 에서 직접 defer + executor 로 처리하므로 여기 포함하지 않는다.
            private val SLOW_COMMANDS = setOf("ask", "free-ask", "imagine")

            /** /ai-onboard 본문 백필 수집 상한(JDA 레이트리밋·메모리 보호). */
            private const val MAX_ONBOARD_HISTORY_LIMIT = 200

            /** /무료질문 이 고정 라우팅하는 클라우드 무료 모델(provider-agent 의 Gemini 백엔드가 광고). */
            private const val FREE_CLOUD_MODEL = "gemini-2.5-flash-lite"

            /** 공개(비-ephemeral) 응답 명령. 나머지는 본인만 보이게(ephemeral). */
            private val PUBLIC_COMMANDS = setOf("ask", "free-ask", "imagine", "contributions", "community-stats", "welcome", "level")
            private const val CHANNEL_PROFILE_EDIT = "channel-profile:edit"
            private const val CHANNEL_PROFILE_AVATAR = "channel-profile:avatar"
            private const val CHANNEL_PROFILE_RESET = "channel-profile:reset"
            private const val CHANNEL_PROFILE_ROLLBACK = "channel-profile:rollback"
            private const val CHANNEL_PROFILE_SAVE_MODAL = "channel-profile:save-modal"
            private const val CHANNEL_PROFILE_AVATAR_MODAL = "channel-profile:avatar-modal"
            private const val ASK_FEEDBACK_PREFIX = "ask-feedback:"
            private const val ONBOARD_PREFIX = "onboard:"
            private const val ONBOARD_ACTION_START = "start"
        }

        override fun onReady(event: ReadyEvent) {
            gatewayStatus.markReady(mentionAskEnabled)
            log.info(
                "Discord gateway ready — guilds available={} unavailable={} mentionAskEnabled={}",
                event.guildAvailableCount,
                event.guildUnavailableCount,
                mentionAskEnabled,
            )
        }

        override fun onShutdown(event: ShutdownEvent) {
            val problem =
                if (event.code == 4014) {
                    "DISALLOWED_INTENTS: Message Content Intent 권한/설정 불일치 가능"
                } else {
                    event.closeCode?.meaning ?: "Discord gateway shutdown"
                }
            gatewayStatus.markShutdown(event.code, problem)
            if (event.code == 4014) onDisallowedIntents()
        }

        /** 슬래시 옵션 자동완성(#179): model 옵션에 풀 제공 모델 제안. */
        override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
            if (event.focusedOption.name != "model") return
            val guild = event.guild ?: return
            val ctx =
                CommandContext(
                    guildId = guild.idLong,
                    channelId = event.channelIdLong,
                    userId = event.user.idLong,
                    roleIds = emptySet(),
                    isAdmin = false,
                )
            val typed = event.focusedOption.value
            val choices =
                commands
                    .autocompleteModels(ctx)
                    .filter { it.startsWith(typed, ignoreCase = true) }
                    .take(25)
                    .map { Command.Choice(it, it) }
            event.replyChoices(choices).queue()
        }

        /** 패널 버튼: 온보딩(질문/기여/상태/도움말) + 설정. */
        override fun onButtonInteraction(event: ButtonInteractionEvent) {
            val ctx = ctxOf(event) // DM(유저설치)에서도 패널 버튼 동작(관리자 버튼은 isAdmin=false 로 거부됨)
            if (event.componentId.startsWith(ASK_FEEDBACK_PREFIX)) {
                handleAskFeedbackButton(event, ctx)
                return
            }
            if (event.componentId.startsWith(ONBOARD_PREFIX)) {
                onboarding.handleOnboardingButton(event, ctx)
                return
            }
            when (event.componentId) {
                MenuFactory.ASK -> {
                    // 질문하기 → 모달로 질문 입력
                    event.replyModal(channelProfilePanel.askModal()).queue()
                    return
                }
                MenuFactory.SETTINGS -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event
                            .replyEmbeds(settingsWizard.settingsEmbed(ctx))
                            .addComponents(settingsWizard.settingsRows(ctx))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
                MenuFactory.HELP, "settings:help" -> {
                    event.replyEmbeds(EmbedFactory.helpEmbed(ctx.isAdmin, event.userLocale)).setEphemeral(true).queue()
                    return
                }
                CHANNEL_PROFILE_EDIT -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event.replyModal(channelProfilePanel.channelProfileModal(ctx)).queue()
                    }
                    return
                }
                CHANNEL_PROFILE_AVATAR -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event.replyModal(channelProfilePanel.channelProfileAvatarModal(ctx)).queue()
                    }
                    return
                }
                CHANNEL_PROFILE_RESET -> {
                    val reply = commands.setChannelAiProfile(ctx, null, null, reset = true)
                    event.reply(reply.content).setEphemeral(true).queue()
                    return
                }
                CHANNEL_PROFILE_ROLLBACK -> {
                    val reply = commands.setChannelAiProfile(ctx, null, null, reset = false, rollback = true)
                    event.reply(reply.content).setEphemeral(true).queue()
                    return
                }
                MenuFactory.PROVIDER -> {
                    if (commands.providerLinked(ctx)) {
                        // 이미 연동된 사용자 → 실제 참여 완료(앱이 동기화로 자동 연결).
                        event.reply(commands.providerJoin(ctx).content).setEphemeral(true).queue()
                    } else {
                        // 미연동: '내 PC 기여' → 먼저 설치할 OS 를 버튼으로 묻는다(차수 19).
                        event
                            .reply(
                                "🖥️ **내 컴퓨터의 AI로 함께 도와주기**\n\n" +
                                    "내 컴퓨터에 있는 AI가 커뮤니티 질문에 답하는 일을 함께 도와줘요.\n" +
                                    "복잡한 설정은 안내를 따라 하면 되고, 원할 때 언제든 멈출 수 있어요.\n\n" +
                                    "**설치할 컴퓨터**를 고르세요. 버튼을 누르면 복사해서 붙여넣을 명령을 보여드릴게요.",
                            ).addComponents(ActionRow.of(MenuFactory.osButtons()))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
            }
            // OS 선택 → 그 OS 복붙 설치 명령(토큰 포함, ephemeral).
            if (event.componentId.startsWith(MenuFactory.OS_PREFIX)) {
                val os = event.componentId.removePrefix(MenuFactory.OS_PREFIX)
                event.reply(commands.providerInstallGuide(ctx, os).content).setEphemeral(true).queue()
                return
            }
            // 설정 마법사 전용 버튼(자동승인 on/off·모든채널·채널일괄·취소·저장)은 단일 빈 핸들러로 위임.
            if (settingsWizard.handleButton(event, ctx)) return
            val reply =
                when (event.componentId) {
                    MenuFactory.STATUS -> commands.providerStatus(ctx)
                    MenuFactory.AUTO_APPROVE, "settings:autoapprove" -> commands.toggleAutoApprove(ctx)
                    else -> Reply("알 수 없는 동작입니다.")
                }
            event.reply(reply.content).setEphemeral(true).queue()
        }

        /** 설정 드롭다운(언어/모델). */
        override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            val value = event.values.firstOrNull().orEmpty()
            // 설정 마법사 전용 드롭다운(언어/모델/자동승인)은 단일 빈 핸들러로 위임.
            if (settingsWizard.handleStringSelect(event, ctx, value)) return
            event.reply(Reply("알 수 없는 선택입니다.").content).setEphemeral(true).queue()
        }

        /** 설정 채널 허용(엔티티 선택). */
        override fun onEntitySelectInteraction(event: EntitySelectInteractionEvent) {
            if (event.componentId != MenuFactory.CHANNEL) return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            settingsWizard.handleEntitySelect(event, ctx)
        }

        /** 봇이 서버에 들어오면 자동 온보딩 패널 게시. */
        override fun onGuildJoin(event: GuildJoinEvent) {
            val channel = event.guild.systemChannel ?: return // 시스템 채널 없으면 스킵
            // 입장 즉시 자동 실행 금지 — consent-first. 관리자가 "AI 자동 설정하기" 버튼을 눌러야 시작된다.
            channel
                .sendMessageEmbeds(EmbedFactory.mainMenuEmbed(isAdmin = true))
                .setComponents(
                    ActionRow.of(MenuFactory.mainButtons(isAdmin = true)),
                    ActionRow.of(Button.primary("$ONBOARD_PREFIX$ONBOARD_ACTION_START", "🐾 AI 자동 설정하기")),
                ).queue({}, {})
        }

        /** 봇이 서버에서 제거되면 그 서버의 프로바이더 연결/등록/설정을 정리한다. */
        override fun onGuildLeave(event: GuildLeaveEvent) {
            guildCleanup.cleanup(event.guild.idLong)
        }

        /** 프로바이더 유저가 서버를 떠나면 해당 서버의 provider 상태만 정리한다. 기여 로그는 유지한다. */
        override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
            reconciliation.cleanupMember(event.guild.idLong, event.user.idLong)
        }

        /** 채널 삭제 이벤트가 오면 허용 채널 정책과 채널 AI 프로필을 같이 정리한다. */
        override fun onChannelDelete(event: ChannelDeleteEvent) {
            if (event.isFromGuild) {
                reconciliation.cleanupChannel(event.guild.idLong, event.channel.idLong)
            }
        }

        /** 긴 질문 모달 제출(#189). */
        override fun onModalInteraction(event: ModalInteractionEvent) {
            val ctx = ctxOf(event) // DM(유저설치)에서도 긴 질문 모달 동작
            if (event.modalId == CHANNEL_PROFILE_SAVE_MODAL) {
                val reply =
                    commands.setChannelAiProfile(
                        ctx,
                        event.getValue("name")?.asString,
                        channelProfiles.get(ctx.guildId, ctx.channelId)?.avatarUrl,
                        reset = false,
                        purpose = event.getValue("purpose")?.asString,
                        tone = event.getValue("tone")?.asString,
                        answerLength = event.getValue("answer-length")?.asString,
                        constitution = event.getValue("constitution")?.asString,
                    )
                event.reply(reply.content).setEphemeral(true).queue()
                return
            }
            // 설정 마법사 채널 일괄 모달은 단일 빈 핸들러로 위임(pending 갱신 + 패널 재렌더).
            if (settingsWizard.handleModal(event, ctx)) return
            if (event.modalId == CHANNEL_PROFILE_AVATAR_MODAL) {
                val current = channelProfiles.get(ctx.guildId, ctx.channelId)
                if (current == null) {
                    event.reply("먼저 `프로필 편집`에서 이름을 저장한 뒤 아이콘을 설정하세요.").setEphemeral(true).queue()
                    return
                }
                val reply =
                    commands.setChannelAiProfile(
                        ctx,
                        current.displayName,
                        event.getValue("avatar-url")?.asString,
                        reset = false,
                        purpose = current.purpose,
                        tone = current.tone,
                        answerLength = current.answerLength,
                        constitution = current.constitution,
                    )
                event.reply(reply.content).setEphemeral(true).queue()
                return
            }
            if (event.modalId != "ask-long-modal") return
            val prompt = event.getValue("prompt")?.asString.orEmpty()
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.deferReply(useWebhookProfile).queue()
            val reply = commands.ask(ctx, prompt)
            if (useWebhookProfile) {
                answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
            } else {
                answers.editOriginalWithPseudoStream(event.hook, reply)
            }
        }

        /** 메시지 컨텍스트 메뉴(#181): 그 메시지 내용으로 질문. */
        override fun onMessageContextInteraction(event: MessageContextInteractionEvent) {
            if (event.name != "AI에게 질문") return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.deferReply(useWebhookProfile).queue()
            val reply = commands.ask(ctx, event.target.contentRaw)
            if (useWebhookProfile) {
                answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
            } else {
                answers.editOriginalWithPseudoStream(event.hook, reply)
            }
        }

        /** 봇 멘션 질문: `@니아 질문` 을 기존 /ask 와 같은 Provider Pool 흐름으로 처리한다. */
        override fun onMessageReceived(event: MessageReceivedEvent) {
            if (!mentionAskEnabled || !event.isFromGuild || event.author.isBot) return
            val selfId = event.jda.selfUser.idLong
            val mentionedUsers = event.message.mentions.users
            val mentioned = mentionedUsers.any { it.idLong == selfId }
            if (!mentioned) return

            val prompt = mentionPrompt(event.message.contentRaw, selfId)
            if (prompt.isBlank()) {
                event.message
                    .reply("질문 내용을 같이 적어주세요. 예: `@니아 오늘 회의 요약해줘`")
                    .mentionRepliedUser(false)
                    .queue()
                return
            }

            metrics.record("mention-ask")
            val ctx =
                buildCtx(
                    event.guild.idLong,
                    event.member,
                    event.channel.idLong,
                    event.author.idLong,
                )
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.channel.sendTyping().queue({}, {})
            try {
                val reply = commands.ask(ctx, prompt)
                if (useWebhookProfile && answers.sendAnswerWebhook(event.channel, ctx, reply)) {
                    event.message
                        .addReaction(Emoji.fromUnicode("✅"))
                        .queue({}, {})
                } else {
                    answers.replyToMessageWithPseudoStream(event.message, reply)
                }
            } catch (e: Exception) {
                log.warn(
                    "멘션 질문 처리 실패(channel={}, user={}): {}",
                    event.channel.idLong,
                    event.author.idLong,
                    e.message,
                )
                event.message
                    .reply("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.")
                    .mentionRepliedUser(false)
                    .queue({}, {})
            }
        }

        /** 만족도 리액션 수집(#171): 👍/👎 를 메트릭으로 집계. */
        override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
            if (event.user?.isBot == true) return
            when (event.emoji.formatted) {
                "👍" -> metrics.record("reaction:up")
                "👎" -> metrics.record("reaction:down")
            }
        }

        private enum class FeedbackAction(
            val id: String,
            val rating: Int,
            val feedbackType: String,
        ) {
            UP("up", 1, "positive"),
            DOWN("down", -1, "negative"),
            REPORT("report", -1, "report"),
        }

        private fun handleAskFeedbackButton(
            event: ButtonInteractionEvent,
            ctx: CommandContext,
        ) {
            val payload = event.componentId.removePrefix(ASK_FEEDBACK_PREFIX)
            val actionId = payload.substringBefore(':', missingDelimiterValue = "")
            val requestId = payload.substringAfter(':', missingDelimiterValue = "").trim()
            val action = FeedbackAction.entries.firstOrNull { it.id == actionId }
            if (action == null || requestId.isBlank()) {
                event.reply("피드백 버튼 정보를 읽지 못했어요. 다시 질문한 뒤 답변 아래 버튼을 눌러주세요.").setEphemeral(true).queue()
                return
            }
            val reply = commands.submitAskFeedback(ctx, requestId, action.rating, action.feedbackType)
            event.reply(reply.content).setEphemeral(true).queue()
        }

        private fun mentionPrompt(
            raw: String,
            selfId: Long,
        ): String =
            raw
                .replace(Regex("<@!?$selfId>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** 길드면 길드 컨텍스트, DM 이면 DM_SCOPE sentinel 컨텍스트(읽기/안내 명령 전용 — 관리자/역할 없음). */
        private fun ctxOf(interaction: Interaction): CommandContext {
            val userLang = I18n.resolveOrNull(interaction.userLocale) // ko/en/ja 또는 null(미지원 → 길드 기본 폴백)
            val guild = interaction.guild
            return if (guild != null) {
                buildCtx(guild.idLong, interaction.member, interaction.channelIdLong, interaction.user.idLong, userLang)
            } else {
                CommandContext(
                    guildId = CommandService.DM_SCOPE,
                    channelId = interaction.channelIdLong,
                    userId = interaction.user.idLong,
                    roleIds = emptySet(),
                    isAdmin = false,
                    userLang = userLang,
                )
            }
        }

        private fun buildCtx(
            guildId: Long,
            member: net.dv8tion.jda.api.entities.Member?,
            channelId: Long,
            userId: Long,
            userLang: String? = null,
        ): CommandContext =
            CommandContext(
                guildId = guildId,
                channelId = channelId,
                userId = userId,
                roleIds = member?.roles?.map { it.idLong }?.toSet() ?: emptySet(),
                isAdmin =
                    member?.let {
                        it.hasPermission(Permission.MANAGE_SERVER) || it.hasPermission(Permission.ADMINISTRATOR)
                    } ?: false,
                userLang = userLang,
            )

        private fun dispatch(
            event: SlashCommandInteractionEvent,
            ctx: CommandContext,
        ): Reply =
            when (event.name) {
                "ask" ->
                    commands.ask(
                        ctx,
                        event.getOption("prompt")?.asString.orEmpty(),
                        requestedModel = event.getOption("model")?.asString,
                        requestedResponseMode = event.getOption("mode")?.asString,
                        webSearch = event.getOption("web")?.asBoolean ?: false,
                    )
                // 무료질문 = 관리자 클라우드 AI(Gemini) 고정. 인당 rate limit(시간당 30·일 100) 적용 후 라우팅.
                "free-ask" ->
                    commands.freeAsk(ctx, event.getOption("prompt")?.asString.orEmpty(), FREE_CLOUD_MODEL)
                "imagine" -> commands.imagine(ctx, event.getOption("prompt")?.asString.orEmpty())
                "models" -> commands.models(ctx)
                "catalog" -> commands.catalog(ctx)
                "my-usage" -> commands.myUsage(ctx)
                "contributions" -> commands.contributions(ctx)
                "community-stats" -> commands.communityStats(ctx)
                "level" -> commands.aiLevel(ctx)
                "fairness" -> commands.fairness(ctx)
                "privacy" -> commands.privacy(ctx)
                "help" -> commands.help(ctx, event.userLocale)
                "bot-permissions" -> commands.botPermissions(ctx)
                "ai-network-map" -> commands.aiNetworkMap(ctx)
                "ai-network-check" -> commands.aiNetworkCheck(ctx)
                "ai-knowledge-list" ->
                    commands.knowledgeList(
                        ctx,
                        spaceId = event.getOption("space-id")?.asString?.toLongOrNull(),
                    )
                "ai-knowledge-add" ->
                    commands.addKnowledge(
                        ctx,
                        title = event.getOption("title")!!.asString,
                        sourceType = event.getOption("source-type")?.asString,
                        sourceUri = event.getOption("url")?.asString,
                        contentPreview = event.getOption("text")?.asString,
                        spaceId = event.getOption("space-id")?.asString?.toLongOrNull(),
                    )
                "ai-knowledge-search" ->
                    commands.searchKnowledge(
                        ctx,
                        query = event.getOption("query")!!.asString,
                        spaceId = event.getOption("space-id")?.asString?.toLongOrNull(),
                        limit = event.getOption("limit")?.asInt ?: 5,
                    )
                "ai-knowledge-index-plan" ->
                    commands.knowledgeIndexPlan(
                        ctx,
                        spaceId = event.getOption("space-id")?.asString?.toLongOrNull(),
                        force = event.getOption("force")?.asBoolean ?: false,
                    )
                "ai-knowledge-jobs" ->
                    commands.knowledgeIndexJobs(
                        ctx,
                        spaceId = event.getOption("space-id")?.asString?.toLongOrNull(),
                        limit = event.getOption("limit")?.asInt ?: 10,
                    )
                "ai-knowledge-job-complete" ->
                    commands.completeKnowledgeIndexJob(
                        ctx,
                        jobId = event.getOption("job-id")!!.asString.toLongOrNull() ?: -1L,
                        status = event.getOption("status")?.asString ?: "completed",
                        reason = event.getOption("reason")?.asString,
                    )
                "ai-knowledge-approve" ->
                    commands.approveKnowledge(
                        ctx,
                        spaceId = event.getOption("space-id")!!.asString.toLongOrNull() ?: -1L,
                        sourceId = event.getOption("source-id")!!.asString.toLongOrNull() ?: -1L,
                        reason = event.getOption("reason")?.asString ?: "approved from Discord",
                    )
                "ai-knowledge-delete" ->
                    commands.deleteKnowledge(
                        ctx,
                        spaceId = event.getOption("space-id")!!.asString.toLongOrNull() ?: -1L,
                        sourceId = event.getOption("source-id")!!.asString.toLongOrNull() ?: -1L,
                        reason = event.getOption("reason")?.asString ?: "deleted from Discord",
                    )
                "ai-preset-catalog" ->
                    commands.presetCatalog(
                        ctx,
                        query = event.getOption("query")?.asString,
                        category = event.getOption("category")?.asString,
                    )
                "ai-preset-import" ->
                    commands.importPresetToCurrentChannel(
                        ctx,
                        publishedPresetId = event.getOption("published-id")!!.asString.toLongOrNull() ?: -1L,
                        confirmConflicts = event.getOption("confirm-conflicts")?.asBoolean ?: false,
                    )
                "ai-preset-like" -> commands.likePreset(ctx, event.getOption("published-id")!!.asString.toLongOrNull() ?: -1L)
                "ai-preset-report" ->
                    commands.reportPreset(
                        ctx,
                        publishedPresetId = event.getOption("published-id")!!.asString.toLongOrNull() ?: -1L,
                        reason = event.getOption("reason")!!.asString,
                    )
                "ai-preset-moderation" -> commands.presetModeration(ctx)
                "ai-preset-report-review" ->
                    commands.reviewPresetReport(
                        ctx,
                        reportId = event.getOption("report-id")!!.asString.toLongOrNull() ?: -1L,
                        decision = event.getOption("decision")!!.asString,
                    )
                "ai-multi-response-status" ->
                    commands.multiResponseStatus(
                        ctx,
                        channelId = event.getOption("channel")?.asChannel?.idLong,
                    )
                "ai-multi-response-set" ->
                    commands.setMultiResponsePolicy(
                        ctx,
                        channelId = event.getOption("channel")?.asChannel?.idLong,
                        mode = event.getOption("mode")!!.asString,
                        maxCandidates = event.getOption("candidates")!!.asInt,
                        synthesisEnabled = event.getOption("synthesis")?.asBoolean ?: false,
                        requireDistinctModels = event.getOption("distinct-models")?.asBoolean ?: false,
                        timeoutSeconds = event.getOption("timeout")?.asInt ?: 120,
                    )
                "ai-multi-response-dry-run" ->
                    commands.multiResponseDryRun(
                        ctx,
                        prompt = event.getOption("prompt")!!.asString,
                        channelId = event.getOption("channel")?.asChannel?.idLong,
                        responseMode = event.getOption("mode")?.asString,
                    )
                "welcome" -> commands.welcome(ctx)
                "llm-welcome-set" -> commands.setWelcome(ctx, event.getOption("message")!!.asString)
                "provider-join" -> commands.providerJoin(ctx)
                "provider-pause" -> commands.providerPause(ctx)
                "provider-resume" -> commands.providerResume(ctx)
                "provider-leave" -> commands.providerLeave(ctx)
                "provider-status" -> commands.providerStatus(ctx)
                "provider-models" ->
                    commands.providerModels(
                        ctx,
                        event
                            .getOption("models")!!
                            .asString
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                    )
                "provider-limit" ->
                    commands.providerLimit(
                        ctx,
                        event.getOption("model")!!.asString,
                        event.getOption("daily")!!.asInt,
                        event.getOption("concurrency")!!.asInt,
                        event.getOption("seconds")!!.asInt,
                    )
                "provider-schedule" ->
                    commands.providerSchedule(
                        ctx,
                        event.getOption("from")!!.asInt,
                        event.getOption("to")!!.asInt,
                    )
                "llm-guild-defaults" ->
                    commands.setGuildDefaults(
                        ctx,
                        event.getOption("model")?.asString,
                        event.getOption("language")?.asString,
                        event.getOption("user-daily-limit")?.asLong?.toInt(),
                    )
                "llm-allow-channel" -> commands.allowChannel(ctx, event.getOption("channel")!!.asChannel.idLong)
                "llm-deny-channel" -> commands.denyChannel(ctx, event.getOption("channel")!!.asChannel.idLong)
                "llm-role-policy" ->
                    commands.setRolePolicy(
                        ctx,
                        event.getOption("role")!!.asRole.idLong,
                        runCatching {
                            ModelBurden.valueOf(
                                event.getOption("level")!!.asString.uppercase(),
                            )
                        }.getOrDefault(ModelBurden.LIGHT),
                        event.getOption("limit")!!.asInt,
                    )
                "llm-channel-profile" -> {
                    val avatar = event.getOption("avatar")?.asAttachment
                    if (avatar != null && !avatar.isImage) {
                        Reply("⚠️ 아이콘에는 PNG/JPG/GIF/WEBP 같은 이미지 파일만 올려주세요.")
                    } else {
                        commands.setChannelAiProfile(
                            ctx,
                            event.getOption("name")?.asString,
                            avatar?.url ?: event.getOption("avatar-url")?.asString,
                            event.getOption("reset")?.asBoolean ?: false,
                            rollback = event.getOption("rollback")?.asBoolean ?: false,
                            purpose = event.getOption("purpose")?.asString,
                            tone = event.getOption("tone")?.asString,
                            answerLength = event.getOption("answer-length")?.asString,
                            constitution = event.getOption("constitution")?.asString,
                        )
                    }
                }
                "ai-instruction" -> commands.setChannelAiInstruction(ctx, event.getOption("text")?.asString)
                "ai-onboard-optout" -> commands.setOnboardingOptOut(ctx, event.getOption("enable")?.asBoolean)
                "providers" -> commands.providers(ctx)
                "provider-approve" -> {
                    val target = event.getOption("user")!!.asUser
                    val reply = commands.approveProvider(ctx, target.idLong)
                    // 승인 성공(온보딩 안내) 시 대상에게 그대로 DM(#162). 실패 메시지는 DM 안 함.
                    if (reply.content.contains("프로바이더로 승인되었습니다")) {
                        target.openPrivateChannel().queue(
                            { ch -> ch.sendMessage(reply.content).queue({}, {}) },
                            {},
                        )
                    }
                    reply
                }
                "provider-remove" -> commands.removeProvider(ctx, event.getOption("user")!!.asUser.idLong)
                "llm-block" -> commands.blockUser(ctx, event.getOption("user")!!.asUser.idLong)
                "llm-unblock" -> commands.unblockUser(ctx, event.getOption("user")!!.asUser.idLong)
                else -> Reply("알 수 없는 명령입니다.")
            }
    }
}
