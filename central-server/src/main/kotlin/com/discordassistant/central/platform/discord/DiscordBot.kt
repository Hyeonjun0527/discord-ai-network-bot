package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.global.i18n.I18n
import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.guild.application.GuildRemovalCleanupService
import com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository
import com.discordassistant.central.onboarding.application.GuildHistoryBackfillService
import com.discordassistant.central.platform.discord.command.SettingsCommandHandler
import com.discordassistant.central.quota.application.RateLimiter
import com.discordassistant.central.socialmemory.application.niamind.NiaSocialMindService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
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
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
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
        "my-usage",
        "nia",
        "privacy",
        "help",
        "menu",
        "settings",
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
    private val autoRespondChannels: AutoRespondChannelRegistry,
    private val guildCleanup: GuildRemovalCleanupService,
    private val reconciliation: ProviderPoolReconciliationService,
    private val gatewayStatus: DiscordGatewayStatus,
    private val historyBackfill: GuildHistoryBackfillService,
    private val onboardingOptOuts: GuildOnboardingOptOutRepository,
    // 설정 마법사(pendingSettings 단일 빈 소유). 모든 Listener 인스턴스가 같은 빈을 참조해 진행중 설정을 공유한다.
    private val settingsWizard: SettingsWizardHandler,
    // 자동응답 채널 단위 비용 캡(분당 상한)에 재사용 — per-user ask 쿨다운과 같은 빈.
    private val rateLimiter: RateLimiter,
    // /그림 결과를 공개 채널에 올리기 전 본인 확인 게이트(설정 영속 + 완성 이미지 임시 보관).
    private val imaginePostConfirm: ImaginePostConfirmService,
    private val pendingImagePosts: PendingImagePostStore,
    // AI 관리 비서: 어드민의 /질문 자연어 관리 명령을 GLM tool calling 으로 해석·실행(JDA 변경은 여기서 게이트웨이로).
    private val adminAssistant: com.discordassistant.central.platform.discord.admin.AdminAssistantService,
    // 니아 사회기억/감정 — 채널AI 발화 경로에서 한 메시지 관찰 → 발화 톤 힌트(D2). 발화 전 호출만 연결(신규 로직 없음).
    private val niaSocialMind: NiaSocialMindService,
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
            // 멤버 조회 실패는 '관리자 아님'으로 보수 처리(broad 유지) — 단 소유자↔권한 진단을 위해 남긴다(예외 원칙 3).
            log.debug("멤버 조회 실패(guild={}, user={}) — 관리자 아님으로 처리: {}", guildId, userId, e.message)
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
                autoRespondChannels,
                guildCleanup,
                reconciliation,
                gatewayStatus,
                historyBackfill,
                onboardingOptOuts,
                settingsWizard,
                rateLimiter,
                imaginePostConfirm,
                pendingImagePosts,
                adminAssistant,
                niaSocialMind,
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
        private val autoRespondChannels: AutoRespondChannelRegistry,
        private val guildCleanup: GuildRemovalCleanupService,
        private val reconciliation: ProviderPoolReconciliationService,
        private val gatewayStatus: DiscordGatewayStatus,
        private val historyBackfill: GuildHistoryBackfillService,
        private val onboardingOptOuts: GuildOnboardingOptOutRepository,
        private val settingsWizard: SettingsWizardHandler,
        private val rateLimiter: RateLimiter,
        private val imaginePostConfirm: ImaginePostConfirmService,
        private val pendingImagePosts: PendingImagePostStore,
        private val adminAssistant: com.discordassistant.central.platform.discord.admin.AdminAssistantService,
        private val niaSocialMind: NiaSocialMindService,
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
        private val setupChannels = NiaChannelSetupHandler(channelProfiles, autoRespondChannels)

        // 니아 톤 히스테리시스(I12): scope 별 직전 렌더 활성 여부. observe 의 wasToneActive 입력으로 재공급.
        private val niaToneActive = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
                    // 관리자는 봇 입장 배너 외에 /메뉴 에서도 채널 자동 만들기 버튼을 상시 호출할 수 있다(이미 입장한
                    // 서버 대응). 첫 행 버튼 수 초과를 피하려고 별도 ActionRow 로 붙인다.
                    val rows =
                        buildList {
                            add(ActionRow.of(MenuFactory.mainButtons(ctx.isAdmin)))
                            if (ctx.isAdmin) {
                                add(
                                    ActionRow.of(
                                        Button.primary(
                                            NiaChannelSetup.COMPONENT_ID,
                                            I18n.get("niaSetupChannelsButton", I18n.DEFAULT),
                                        ),
                                    ),
                                )
                            }
                        }
                    event
                        .replyEmbeds(EmbedFactory.mainMenuEmbed(ctx.isAdmin))
                        .addComponents(rows)
                        .setEphemeral(true)
                        .queue()
                    return
                }
                "settings" -> {
                    // 공개 명령: 누구나 ephemeral 안내 + 웹 대시보드로 가는 링크 버튼. 베이스 URL 이 없으면 안내 폴백(빈 link 버튼 금지).
                    when (val links = commands.settings(ctx)) {
                        is SettingsCommandHandler.SettingsLinks.WithLinks ->
                            event
                                .replyEmbeds(EmbedFactory.settingsLinkEmbed(links.title, links.description))
                                .addComponents(ActionRow.of(links.buttons.map { Button.link(it.url, it.label) }))
                                .setEphemeral(true)
                                .queue()
                        is SettingsCommandHandler.SettingsLinks.Unavailable ->
                            event
                                .replyEmbeds(EmbedFactory.settingsLinkEmbed(links.title, links.description))
                                .setEphemeral(true)
                                .queue()
                    }
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
                // 관리/운영 명령(설정·채널 AI·온보딩·지식·프리셋·다중응답 등)은 슬래시에서 제거되고 웹 대시보드로
                // 이관됐다(/설정 → 웹). 채널 AI 자동 만들기는 입장 배너 버튼/웹에서, 긴 질문은 메시지 컨텍스트 메뉴로.
            }
            // 모든 명령을 defer 로 먼저 ack(3초 제한 회피) 후 결과 편집. 공유/원격 서버의 지연에도 안전.
            // 공개 명령만 비-ephemeral, 나머지는 ephemeral. defer 시점에 결정.
            val useWebhookProfile = event.name == "ask" && channelProfiles.get(ctx.guildId, ctx.channelId) != null
            val isPublic = event.name in PUBLIC_COMMANDS
            // 어드민 /질문이 관리 액션 경로(AI 관리 비서)로 처리될 가능성이 있으면 반드시 ephemeral 로 defer 해야 한다.
            // 차단 대상·관리 행위 결과가 채널에 공개되는 것을 방지. 일반(비어드민) /질문은 isPublic 경로 그대로.
            val isAdminAskPath = event.name == "ask" && ctx.isAdmin && adminAssistant.isAvailable()
            // /그림 게시 확인 게이트: confirm 옵션이 주어지면 먼저 유저 설정을 갱신한 뒤, 그 설정대로 이번 요청을 처리한다.
            // 게이트 ON 이면 전 과정을 본인만 보이게(ephemeral) 진행하고 완성 후 게시 확인 버튼을 띄운다(아래 work 에서 분기).
            val imagineGateOn =
                if (event.name == "imagine") {
                    event.getOption("confirm")?.asBoolean?.let { imaginePostConfirm.setEnabled(ctx.userId, it) }
                    imaginePostConfirm.isEnabled(ctx.userId)
                } else {
                    false
                }
            event
                .deferReply(
                    if (useWebhookProfile || imagineGateOn) {
                        true
                    } else if (isAdminAskPath) {
                        true
                    } else {
                        !isPublic
                    },
                ).queue()
            val work =
                Runnable {
                    try {
                        // 어드민 /질문: 자연어 관리 명령이면 GLM tool calling 으로 실행/확인(아니면 false → 일반 /질문).
                        if (event.name == "ask" && ctx.isAdmin && handleAdminAsk(event, ctx)) return@Runnable
                        val reply =
                            if (event.name == "imagine") {
                                // 이미지 생성 진행률을 '생각 중' 메시지에 N% 로 라이브 편집(SD progress 청크 → onProgress).
                                val lastPct =
                                    java.util.concurrent.atomic
                                        .AtomicInteger(-1)
                                commands.imagine(
                                    ctx,
                                    event.getOption("prompt")?.asString.orEmpty(),
                                    onStart = { requestId ->
                                        // 생성 시작 → '취소' 버튼을 메시지에 붙인다(클릭 시 중간 취소).
                                        event.hook
                                            .editOriginal("🖼️ 생성 시작… (취소하려면 아래 버튼)")
                                            .setActionRow(Button.danger("$IMG_CANCEL_PREFIX$requestId", "🛑 취소"))
                                            .queue({}, {})
                                    },
                                ) { pct ->
                                    if (pct > lastPct.get()) {
                                        lastPct.set(pct)
                                        // 내용만 편집 — 취소 버튼(컴포넌트)은 유지된다.
                                        event.hook.editOriginal("🖼️ 그리는 중… $pct%").queue({}, {})
                                    }
                                }
                            } else {
                                dispatch(event, ctx)
                            }
                        if (imagineGateOn && reply.imagePng != null) {
                            // 게이트 ON: 채널에 바로 붙이지 않고 본인만 보이는 미리보기 + 게시/버리기/안묻기 버튼을 띄운다.
                            renderImaginePostConfirm(event, ctx, reply)
                        } else if (useWebhookProfile) {
                            answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
                        } else {
                            // 게이트 OFF(기존 동작) 또는 게이트 ON 이라도 imagePng==null(실패/취소) → 그대로 편집.
                            // 게이트 ON 폴백은 hook 이 ephemeral 이라 본인만 본다.
                            answers.editOriginalWithPseudoStream(event.hook, reply)
                        }
                    } catch (e: Exception) {
                        // 명령 처리 실패는 사용자에겐 일반 안내, 서버엔 명령·채널 맥락과 스택을 남긴다(예외 원칙 4).
                        log.warn("명령 처리 실패: {} (channel={}) — {}", event.name, event.channel.idLong, e.message, e)
                        event.hook.editOriginal("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})
                    }
                }
            // 추론(ask/imagine)은 길어서 게이트웨이 스레드 밖에서. 나머지 빠른 명령은 그대로 실행.
            if (event.name in SLOW_COMMANDS) slowCommandExecutor.execute(work) else work.run()
        }

        /**
         * AI 관리 비서(어드민 전용): 어드민의 /질문 자연어를 GLM tool calling 으로 해석한다. 관리 의도가 없으면
         * false 를 돌려 호출자가 기존 /질문 으로 폴백한다(회귀 0). SAFE 면 즉시 실행 후 결과 보고, CONFIRM 이면
         * 확인 버튼([실행]/[취소])을 단다(클릭 시 [onButtonInteraction] 에서 권한 재검증 후 실행).
         */
        private fun handleAdminAsk(
            event: SlashCommandInteractionEvent,
            ctx: CommandContext,
        ): Boolean {
            if (!adminAssistant.isAvailable()) return false
            val guild = event.guild ?: return false
            val prompt = event.getOption("prompt")?.asString.orEmpty()
            val decision = adminAssistant.plan(ctx.guildId, ctx.userId, prompt)
            return when (decision) {
                is com.discordassistant.central.platform.discord.admin.AdminAssistantDecision.NotAdminAction -> false
                is com.discordassistant.central.platform.discord.admin.AdminAssistantDecision.ReadyToRun -> {
                    val result =
                        adminAssistant.runSafe(
                            ctx.guildId,
                            ctx.userId,
                            decision.plan,
                            com.discordassistant.central.platform.discord.admin
                                .JdaAdminGuildGateway(guild),
                        )
                    event.hook
                        .editOriginal(result.message)
                        .setComponents()
                        .queue({}, {})
                    true
                }
                is com.discordassistant.central.platform.discord.admin.AdminAssistantDecision.NeedsConfirm -> {
                    event.hook
                        .editOriginal(adminAssistant.confirmPrompt(decision.plan))
                        .setActionRow(
                            Button.danger("$ADMIN_ACT_RUN_PREFIX${decision.token}", "✅ 실행"),
                            Button.secondary("$ADMIN_ACT_CANCEL_PREFIX${decision.token}", "✖️ 취소"),
                        ).queue({}, {})
                    true
                }
            }
        }

        /**
         * AI 관리 비서 확인 버튼 처리: 클릭한 사용자가 어드민인지 **재검증**하고(다른 사람이 누르면 거부),
         * 요청자 일치·토큰 만료까지 확인한 뒤 실행한다(안전장치 1·5). JDA 변경은 클릭 이벤트의 길드로 만든 게이트웨이로.
         */
        private fun handleAdminActionButton(
            event: ButtonInteractionEvent,
            ctx: CommandContext,
        ) {
            val isRun = event.componentId.startsWith(ADMIN_ACT_RUN_PREFIX)
            val token = event.componentId.removePrefix(if (isRun) ADMIN_ACT_RUN_PREFIX else ADMIN_ACT_CANCEL_PREFIX)
            if (!isRun) {
                event.editMessage("✖️ 작업을 취소했어요.").setComponents().queue({}, {})
                return
            }
            val guild = event.guild
            if (guild == null) {
                event.reply("이 작업은 서버에서만 실행할 수 있어요.").setEphemeral(true).queue()
                return
            }
            event.deferEdit().queue({}, {})
            val result =
                adminAssistant.confirmAndRun(token, ctx.userId, ctx.isAdmin) {
                    com.discordassistant.central.platform.discord.admin
                        .JdaAdminGuildGateway(guild)
                }
            event.hook
                .editOriginal(result.message)
                .setComponents()
                .queue({}, {})
        }

        /**
         * /그림 게시 확인 게이트 ON 경로: 완성 이미지를 본인만 보이는(ephemeral) 미리보기로 보여주고,
         * 채널 게시에 필요한 PNG/캡션/대상 채널을 토큰으로 잠깐 보관한 뒤 버튼 3개(게시/버리기/안묻기)를 단다.
         * 토큰은 PendingImagePostStore 가 소유·TTL 관리하며, 버튼 클릭 시 onButtonInteraction 에서 꺼내 처리한다.
         */
        private fun renderImaginePostConfirm(
            event: SlashCommandInteractionEvent,
            ctx: CommandContext,
            reply: Reply,
        ) {
            val bytes = reply.imagePng ?: return // 호출부에서 null 아님을 보장하지만 방어적으로.
            val token =
                pendingImagePosts.put(
                    pngBytes = bytes,
                    caption = reply.content,
                    guildId = ctx.guildId,
                    channelId = event.channel.idLong,
                    userId = ctx.userId,
                )
            event.hook
                .editOriginal("🖼️ 그림이 완성됐어요! 본인만 보이는 미리보기예요.\n채널에 올릴지 선택해 주세요.")
                .setFiles(
                    net.dv8tion.jda.api.utils.FileUpload
                        .fromData(bytes, "image.png"),
                ).setActionRow(
                    Button.success("$IMG_POST_PREFIX$token", "✅ 채널에 게시"),
                    Button.danger("$IMG_DISCARD_PREFIX$token", "🗑️ 버리기"),
                    Button.secondary("$IMG_MUTE_PREFIX$token", "🔕 게시하고 다음부터 안 묻기"),
                ).queue({}, { e ->
                    log.warn("이미지 게시 확인 미리보기 응답 실패: {}", e.message)
                    event.hook.editOriginal("⚠️ 이미지를 전송하지 못했어요.").queue({}, {})
                })
        }

        /** /그림 게시 확인 버튼(게시/버리기/안묻기) 처리. 토큰 소유자 검증·TTL 은 PendingImagePostStore 가 한다. */
        private fun handleImaginePostButton(event: ButtonInteractionEvent) {
            val userId = event.user.idLong
            // 채널 게시(complete, 블로킹)는 큰 이미지에서 수백 ms~수 초 걸릴 수 있다. 먼저 deferEdit 로 ack 해
            // 버튼 인터랙션 3초 타임아웃("상호작용 실패")을 없앤 뒤, 결과는 hook 으로 편집한다.
            event.deferEdit().queue({}, {})
            val hook = event.hook
            when {
                event.componentId.startsWith(IMG_POST_PREFIX) -> {
                    val token = event.componentId.removePrefix(IMG_POST_PREFIX)
                    val pending = pendingImagePosts.take(token, userId)
                    if (pending == null) {
                        editExpired(hook)
                        return
                    }
                    val msg =
                        if (postPendingToChannel(event, pending)) {
                            "✅ 채널에 게시했어요."
                        } else {
                            "⚠️ 채널에 게시하지 못했어요. 권한을 확인하고 다시 시도해 주세요."
                        }
                    hook.editOriginal(msg).setReplace(true).queue({}, {})
                }
                event.componentId.startsWith(IMG_DISCARD_PREFIX) -> {
                    val token = event.componentId.removePrefix(IMG_DISCARD_PREFIX)
                    pendingImagePosts.discard(token, userId)
                    hook
                        .editOriginal("🗑️ 버렸어요. 채널엔 아무것도 올라가지 않았어요.")
                        .setReplace(true)
                        .queue({}, {})
                }
                event.componentId.startsWith(IMG_MUTE_PREFIX) -> {
                    val token = event.componentId.removePrefix(IMG_MUTE_PREFIX)
                    val pending = pendingImagePosts.take(token, userId)
                    if (pending == null) {
                        editExpired(hook)
                        return
                    }
                    // 이번 이미지는 채널 게시 + 다음부터 확인 안 묻도록 설정 OFF 저장.
                    imaginePostConfirm.setEnabled(userId, false)
                    val msg =
                        if (postPendingToChannel(event, pending)) {
                            "✅ 게시했어요. 다음부터는 확인 없이 바로 올라가요. (`/그림 confirm:켜기` 로 다시 켤 수 있어요)"
                        } else {
                            "⚠️ 채널 게시는 실패했지만 다음부터 확인은 끄도록 했어요. (`/그림 confirm:켜기` 로 다시 켤 수 있어요)"
                        }
                    hook.editOriginal(msg).setReplace(true).queue({}, {})
                }
            }
        }

        /** 보관 항목을 원본 채널에 공개 메시지로 전송(요청자가 만든 그림임을 캡션에 표시). 성공 시 true. */
        private fun postPendingToChannel(
            event: ButtonInteractionEvent,
            pending: PendingImagePostStore.Pending,
        ): Boolean =
            runCatching {
                val channel = event.guild?.getTextChannelById(pending.channelId) ?: event.channel.asTextChannel()
                channel
                    .sendMessage("${pending.caption}\n— <@${pending.userId}> 님이 만든 그림")
                    .setFiles(
                        net.dv8tion.jda.api.utils.FileUpload
                            .fromData(pending.pngBytes, "image.png"),
                    ).complete()
                true
            }.onFailure { e ->
                log.warn("이미지 채널 게시 실패(channel={}): {}", pending.channelId, e.message)
            }.getOrDefault(false)

        private fun editExpired(hook: InteractionHook) {
            // 토큰 만료/부재/타인 → 안전 안내(미리보기는 본인 ephemeral 이라 보통 본인 클릭).
            hook
                .editOriginal("⌛ 이 미리보기는 만료됐어요. 다시 `/그림` 으로 만들어 주세요.")
                .setReplace(true)
                .queue({}, {})
        }

        companion object {
            private val log = LoggerFactory.getLogger(Listener::class.java)

            // 추론으로 오래 걸리는 명령 — 게이트웨이 스레드 밖(전용 풀)에서 처리한다.
            // ai-onboard 는 위 switch 에서 직접 defer + executor 로 처리하므로 여기 포함하지 않는다.
            private val SLOW_COMMANDS = setOf("ask", "imagine")

            /** /ai-onboard 본문 백필 수집 상한(JDA 레이트리밋·메모리 보호). */
            private const val MAX_ONBOARD_HISTORY_LIMIT = 200

            /** 공개(비-ephemeral) 응답 명령. 나머지는 본인만 보이게(ephemeral). */
            private val PUBLIC_COMMANDS = setOf("ask", "imagine", "contributions", "community-stats", "welcome", "nia")
            private const val CHANNEL_PROFILE_EDIT = "channel-profile:edit"
            private const val CHANNEL_PROFILE_AVATAR = "channel-profile:avatar"
            private const val CHANNEL_PROFILE_RESET = "channel-profile:reset"
            private const val CHANNEL_PROFILE_ROLLBACK = "channel-profile:rollback"
            private const val CHANNEL_PROFILE_SAVE_MODAL = "channel-profile:save-modal"
            private const val CHANNEL_PROFILE_AVATAR_MODAL = "channel-profile:avatar-modal"
            private const val ASK_FEEDBACK_PREFIX = "ask-feedback:"
            private const val ONBOARD_PREFIX = "onboard:"

            // AI 관리 비서 확인 게이트 버튼 customId 접두사(뒤에 PendingAdminActionStore 토큰).
            private const val ADMIN_ACT_RUN_PREFIX = "adminact:run:" // ✅ 실행
            private const val ADMIN_ACT_CANCEL_PREFIX = "adminact:cancel:" // ✖️ 취소

            // 이미지 생성 취소 버튼 customId 접두사(뒤에 requestId). 누르면 ComfyUI /interrupt 유발.
            private const val IMG_CANCEL_PREFIX = "img-cancel:"

            // /그림 게시 확인 게이트 버튼 customId 접두사(뒤에 PendingImagePostStore 토큰).
            private const val IMG_POST_PREFIX = "img-post:" // ✅ 채널에 게시
            private const val IMG_DISCARD_PREFIX = "img-discard:" // 🗑️ 버리기
            private const val IMG_MUTE_PREFIX = "img-mute:" // 🔕 게시하고 다음부터 안 묻기
            private const val ONBOARD_ACTION_START = "start"

            /**
             * 온보딩 배너를 보낼 채널 선택(순수 로직 — 단위 테스트 가능). 봇이 쓸 수 있는 시스템 채널을 우선하고,
             * 없으면(시스템 채널 미설정/권한 없음) 쓰기 가능한 첫 텍스트 채널로 폴백한다. 둘 다 없으면 null(graceful 무시).
             */
            fun <T> selectOnboardingChannel(
                systemChannel: T?,
                writableChannels: List<T>,
            ): T? = systemChannel ?: writableChannels.firstOrNull()
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
            if (event.componentId.startsWith(IMG_CANCEL_PREFIX)) {
                // 이미지 생성 취소: 진행 중 요청을 중단(ComfyUI /interrupt). 최종 '취소됨' 응답은 imagine 흐름이 편집.
                val requestId = event.componentId.removePrefix(IMG_CANCEL_PREFIX)
                commands.cancelImage(requestId)
                event.editButton(Button.danger(event.componentId, "🛑 취소됨").asDisabled()).queue({}, {})
                return
            }
            if (event.componentId.startsWith(IMG_POST_PREFIX) ||
                event.componentId.startsWith(IMG_DISCARD_PREFIX) ||
                event.componentId.startsWith(IMG_MUTE_PREFIX)
            ) {
                // /그림 게시 확인 게이트: 본인만 보이는 미리보기에서 채널 게시/버리기/안묻기 선택.
                handleImaginePostButton(event)
                return
            }
            if (event.componentId.startsWith(ASK_FEEDBACK_PREFIX)) {
                handleAskFeedbackButton(event, ctx)
                return
            }
            if (event.componentId.startsWith(ADMIN_ACT_RUN_PREFIX) || event.componentId.startsWith(ADMIN_ACT_CANCEL_PREFIX)) {
                handleAdminActionButton(event, ctx)
                return
            }
            if (event.componentId.startsWith(ONBOARD_PREFIX)) {
                onboarding.handleOnboardingButton(event, ctx)
                return
            }
            if (event.componentId == NiaChannelSetup.COMPONENT_ID) {
                // "🏗️ 니아 채널 자동 만들기" — 권한/멱등/생성은 핸들러가 자체 처리(RAG 온보딩과는 별개 기능).
                setupChannels.handle(event, ctx, ctx.userLang ?: commands.guildLanguage(ctx))
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
                    else -> Replies.reject(Messages.get(Messages.Key.UNKNOWN_ACTION, langOf(ctx)))
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
            event.reply(Replies.reject(Messages.get(Messages.Key.UNKNOWN_SELECTION, langOf(ctx))).content).setEphemeral(true).queue()
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
            // 시스템 채널이 꺼져 있어도 안내 0 이 되지 않게, 봇이 쓸 수 있는 첫 텍스트 채널로 폴백한다.
            // 시스템 채널이 있어도 봇이 못 쓰면(권한 없음) 다른 쓰기 가능한 채널을 고른다. 없으면 graceful 무시.
            val systemChannel = event.guild.systemChannel?.takeIf { it.canTalk() }
            val writableChannels = event.guild.textChannels.filter { it.canTalk() }
            val channel = selectOnboardingChannel(systemChannel, writableChannels) ?: return
            // 입장 즉시 자동 실행 금지 — consent-first. 관리자가 "AI 자동 설정하기" 버튼을 눌러야 시작된다.
            channel
                .sendMessageEmbeds(EmbedFactory.mainMenuEmbed(isAdmin = true))
                .setComponents(
                    ActionRow.of(MenuFactory.mainButtons(isAdmin = true)),
                    // 🐾 = RAG 자동 온보딩(별개), 🏗️ = 니아 채널 자동 만들기(카테고리/채널/가이드 생성). 둘 다 노출.
                    ActionRow.of(
                        Button.primary("$ONBOARD_PREFIX$ONBOARD_ACTION_START", "🐾 AI 자동 설정하기"),
                        Button.primary(NiaChannelSetup.COMPONENT_ID, I18n.get("niaSetupChannelsButton", I18n.DEFAULT)),
                    ),
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

        /**
         * 메시지 자동 처리(콘텐츠 인텐트 필요):
         *  ① 봇 멘션(@니아) → 멘션 텍스트로 /ask 흐름.
         *  ② "AI 채팅 채널"(자동응답 켜진 채널) → 멘션 없이 모든 텍스트 메시지에 자동 응답.
         * 자동응답은 카미봇처럼 `.` 로 시작하는 메시지·빈 내용·봇/웹훅 메시지는 제외한다.
         * 자동응답 채널 판정은 [autoRespondChannels] 인메모리 캐시(O(1))로 — 메시지당 DB 조회를 하지 않는다.
         */
        override fun onMessageReceived(event: MessageReceivedEvent) {
            if (!mentionAskEnabled || !event.isFromGuild || event.author.isBot) return
            val selfId = event.jda.selfUser.idLong
            val mentioned =
                event.message.mentions.users
                    .any { it.idLong == selfId }
            if (mentioned) {
                handleMentionAsk(event, selfId)
                return
            }
            handleAutoRespond(event)
        }

        /** 봇 멘션 질문: `@니아 질문` 을 기존 /ask 와 같은 Provider Pool 흐름으로 처리한다. */
        private fun handleMentionAsk(
            event: MessageReceivedEvent,
            selfId: Long,
        ) {
            val prompt = mentionPrompt(event.message.contentRaw, selfId)
            if (prompt.isBlank()) {
                event.message
                    .reply("질문 내용을 같이 적어주세요. 예: `@니아 오늘 회의 요약해줘`")
                    .mentionRepliedUser(false)
                    .queue()
                return
            }
            metrics.record("mention-ask")
            respondInChannel(event, prompt)
        }

        /**
         * AI 채팅 채널 자동응답: 자동응답이 켜진 채널의 모든 텍스트 메시지에 멘션 없이 응답한다.
         * `.` 로 시작하는 메시지(카미봇 컨벤션)·빈 내용은 무시한다(스킵). 캐시 조회가 O(1) 라 비-AI채팅 채널은 즉시 return.
         *
         * **비용 캡**: 자동응답 채널은 N명이 떠들면 N배의 LLM 추론이 일어나 관리자 클라우드 키 비용이 폭주한다.
         * per-user ask 쿨다운은 관리자가 우회하지만, 이 **채널 단위 분당 상한은 누구도(관리자 포함) 우회하지 못한다**.
         * 한도를 넘으면 채널에 안내를 도배하지 않고 조용히 드롭(⏳ 리액션만) — 일반 /ask 의 쿨다운 안내는 그대로 유지된다.
         */
        private fun handleAutoRespond(event: MessageReceivedEvent) {
            val guildId = event.guild.idLong
            val channelId = event.channel.idLong
            if (!autoRespondChannels.isAutoRespond(guildId, channelId)) return
            val content = event.message.contentRaw
            if (!AutoRespondChannelRegistry.shouldRespond(content)) return // `.` 시작·빈 내용 제외
            if (!rateLimiter.tryAcquire("autorespond:$guildId:$channelId")) {
                metrics.record("auto-respond-throttled")
                event.message.addReaction(Emoji.fromUnicode("⏳")).queue({}, {}) // 조용히 드롭(도배 금지)
                return
            }
            metrics.record("auto-respond")
            respondInChannel(event, content.trim())
        }

        /** 멘션/자동응답 공통: typing → /ask → (프로필 있으면 웹훅 페르소나, 없으면 답장 스트림). 에러는 동일 처리. */
        private fun respondInChannel(
            event: MessageReceivedEvent,
            prompt: String,
        ) {
            val ctx =
                buildCtx(
                    event.guild.idLong,
                    event.member,
                    event.channel.idLong,
                    event.author.idLong,
                )
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.channel.sendTyping().queue({}, {})
            // 니아 사회기억/감정 관찰 → 발화 톤 힌트(D2). 서버 격리 I7, 표시이름·원문 미저장(가명적) I10.
            // 관찰 실패(getOrNull null)해도 발화는 그대로 진행 — graceful(톤 ""=평소 니아).
            val scope = "discord:guild:${ctx.guildId}"
            val speaker = "discord:${ctx.userId}"
            val tone =
                runCatching {
                    niaSocialMind.observe(
                        scope,
                        speaker,
                        listOf(prompt),
                        java.time.Instant.now(),
                        persist = true,
                        wasToneActive = niaToneActive[scope] ?: false,
                    )
                }.getOrNull()
            tone?.let { niaToneActive[scope] = it.tone.active }
            val toneDirective = tone?.tone?.directive ?: ""
            try {
                val reply = commands.ask(ctx, prompt, toneDirective = toneDirective)
                if (useWebhookProfile && answers.sendAnswerWebhook(event.channel, ctx, reply)) {
                    event.message
                        .addReaction(Emoji.fromUnicode("✅"))
                        .queue({}, {})
                } else {
                    answers.replyToMessageWithPseudoStream(event.message, reply)
                }
            } catch (e: Exception) {
                log.warn(
                    "메시지 자동 처리 실패(channel={}, user={}): {}",
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

        /** 응답 언어 코드(ko/en/ja): 요청자 로케일 우선 → 길드 기본. 어댑터에서 i18n 문구 직접 조회용. */
        private fun langOf(ctx: CommandContext): String = ctx.userLang ?: commands.guildLanguage(ctx)

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
                        requestedThinking = event.getOption("thinking")?.asString,
                    )
                "imagine" -> commands.imagine(ctx, event.getOption("prompt")?.asString.orEmpty())
                "my-usage" -> commands.myUsage(ctx)
                "nia" -> commands.niaAffinity(ctx)
                "privacy" -> commands.privacy(ctx)
                "help" -> commands.help(ctx, event.userLocale)
                "provider-join" -> commands.providerJoin(ctx)
                "provider-status" -> commands.providerStatus(ctx)
                else -> Reply("알 수 없는 명령입니다.")
            }
    }
}
