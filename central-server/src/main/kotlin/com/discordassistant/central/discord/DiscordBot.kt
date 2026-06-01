package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
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
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DM(봇과의 1:1 DM)에서도 쓸 수 있는 명령(차수 19): 질문/조회 + 프로바이더 셀프서비스. 관리자/정책 명령은 길드 전용.
 * 이 명령들은 글로벌 + setGuildOnly(false)(봇 DM 허용)로 등록되고, DM 은 글로벌 풀(DM_SCOPE)로 라우팅된다.
 * (JDA 5.2.1 은 신 user-install/InteractionContextType 미지원 — 친구끼리 DM/임의 서버 사용은 JDA 업그레이드 필요.)
 */
private val DM_COMMANDS =
    setOf(
        "ask",
        "models",
        "catalog",
        "my-usage",
        "contributions",
        "community-stats",
        "privacy",
        "help",
        "welcome",
        "menu",
        "ask-long",
        "provider-join",
        "provider-pause",
        "provider-resume",
        "provider-leave",
        "provider-status",
    )

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
    @param:Value("\${central.discord.enabled:false}") private val enabled: Boolean,
    @param:Value("\${central.discord.bot-token:}") private val token: String,
    // 설정 시 해당 길드(서버)에 명령 즉시 등록(전파 지연 없음). 비우면 글로벌 등록(최대 ~1h).
    @param:Value("\${central.discord.guild-id:}") private val guildId: String,
    @param:Value("\${central.discord.message-content-intent-enabled:true}") private val messageContentIntentEnabled: Boolean,
    @param:Value("\${central.discord.fallback-without-message-content-on-4014:true}") private val fallbackWithoutMessageContentOn4014:
        Boolean,
) {
    private val log = LoggerFactory.getLogger(DiscordBot::class.java)
    private var jda: JDA? = null
    private val fallbackAttempted = AtomicBoolean(false)

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
                mentionAskEnabled = messageContentIntent,
                onDisallowedIntents = { handleDisallowedIntents(messageContentIntent) },
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
    }

    private fun registerCommands(action: net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction) {
        // 관리자 명령은 비관리자 UI 에서 숨김(#186). 서버 관리 권한 보유자만 노출.
        val adminPerm = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)
        val cmds =
            listOf<net.dv8tion.jda.api.interactions.commands.build.CommandData>(
                Commands
                    .slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
                    .addOption(OptionType.STRING, "prompt", "질문 내용", true)
                    .addOptions(
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "model", "원하는 모델(비우면 채널/서버 기본값)", false)
                            .setAutoComplete(true),
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "mode", "응답 속도/품질 모드", false)
                            .addChoice("빠른 답변", "fast")
                            .addChoice("균형 모드", "balanced")
                            .addChoice("깊은 답변", "deep")
                            .addChoice("절약 모드", "saving"),
                    ),
                Commands.slash("models", "사용 가능한 모델 수준을 확인합니다"),
                Commands.slash("catalog", "이 서버에서 제공 중인 모델 목록을 봅니다"),
                Commands.slash("my-usage", "내 오늘 사용량을 확인합니다"),
                Commands.slash("contributions", "커뮤니티 기여 리더보드를 봅니다"),
                Commands.slash("community-stats", "익명 커뮤니티 기여 통계를 봅니다"),
                Commands.slash("fairness", "공정성 리포트를 봅니다(관리자)").setDefaultPermissions(adminPerm),
                Commands.slash("privacy", "이 서버의 AI 처리/프라이버시 안내"),
                Commands.slash("help", "명령 종합 도움말을 봅니다"),
                Commands.slash("welcome", "서버 환영/안내 메시지를 봅니다"),
                Commands
                    .slash("llm-welcome-set", "서버 환영/안내 메시지를 설정합니다(관리자)")
                    .addOption(OptionType.STRING, "message", "환영 메시지", true)
                    .setDefaultPermissions(adminPerm),
                Commands.slash("provider-join", "프로바이더로 참여합니다"),
                Commands.slash("provider-pause", "요청 수신을 일시정지합니다"),
                Commands.slash("provider-resume", "요청 수신을 재개합니다"),
                Commands.slash("provider-leave", "풀에서 나갑니다"),
                Commands.slash("provider-status", "내 프로바이더 상태를 확인합니다"),
                Commands
                    .slash("provider-models", "제공 모델 수동 지정(보통 불필요 — 에이전트가 자동 감지)")
                    .addOption(OptionType.STRING, "models", "내 PC 의 Ollama 모델명(쉼표 구분). 비워두면 자동 감지", true),
                Commands
                    .slash("provider-limit", "모델별 한도를 설정합니다")
                    .addOptions(
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "model", "대상 모델", true)
                            .setAutoComplete(true),
                    ).addOption(OptionType.INTEGER, "daily", "하루 한도(0=무제한)", true)
                    .addOption(OptionType.INTEGER, "concurrency", "동시 처리 수", true)
                    .addOption(OptionType.INTEGER, "seconds", "요청당 최대 초", true),
                Commands
                    .slash("provider-scope", "모델 허용 범위를 설정합니다")
                    .addOptions(
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "model", "대상 모델", true)
                            .setAutoComplete(true),
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "role", "허용 범위", true)
                            .addChoice("모두", "all")
                            .addChoice("신뢰 역할", "trusted")
                            .addChoice("관리자만", "admin"),
                    ),
                Commands
                    .slash("provider-schedule", "가용 시간대를 설정합니다(UTC 시, 시간 밖 자동정지)")
                    .addOption(OptionType.INTEGER, "from", "시작 시(0~23, UTC)", true)
                    .addOption(OptionType.INTEGER, "to", "종료 시(0~23, UTC; from==to 면 24시간)", true),
                Commands
                    .slash("llm-allow-channel", "LLM 사용 채널을 허용합니다(관리자)")
                    .addOption(OptionType.CHANNEL, "channel", "허용 채널", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-deny-channel", "LLM 사용 채널을 금지합니다(관리자)")
                    .addOption(OptionType.CHANNEL, "channel", "금지 채널", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-role-policy", "역할별 허용 수준을 설정합니다(관리자)")
                    .addOption(OptionType.ROLE, "role", "대상 역할", true)
                    .addOptions(
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "level", "허용 모델 수준", true)
                            .addChoice("LIGHT (가벼움)", "LIGHT")
                            .addChoice("STANDARD (표준)", "STANDARD")
                            .addChoice("HEAVY (무거움)", "HEAVY"),
                    ).addOption(OptionType.INTEGER, "limit", "하루 한도", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-guild-defaults", "길드 기본 모델/언어를 설정합니다(관리자)")
                    .addOptions(
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "model", "기본 모델(비우면 자동 선택)", false)
                            .setAutoComplete(true),
                        net.dv8tion.jda.api.interactions.commands.build
                            .OptionData(OptionType.STRING, "language", "언어", false)
                            .addChoice("한국어", "ko")
                            .addChoice("English", "en"),
                    ).setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-channel-profile", "이 채널의 AI 프로필 설정 패널을 엽니다(관리자)")
                    .setDefaultPermissions(adminPerm),
                Commands.slash("providers", "프로바이더 풀 상태를 봅니다(관리자)").setDefaultPermissions(adminPerm),
                Commands
                    .slash("provider-approve", "프로바이더 등록을 승인합니다(관리자)")
                    .addOption(OptionType.USER, "user", "대상 유저", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("provider-remove", "프로바이더를 제거합니다(관리자)")
                    .addOption(OptionType.USER, "user", "대상 유저", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-block", "사용자를 차단합니다(관리자)")
                    .addOption(OptionType.USER, "user", "대상 유저", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-unblock", "사용자 차단을 해제합니다(관리자)")
                    .addOption(OptionType.USER, "user", "대상 유저", true)
                    .setDefaultPermissions(adminPerm),
                // 인터랙티브(차수 13): 설정 패널(버튼/Select #147/180), 모달 입력(#189)
                Commands.slash("menu", "시작 패널을 엽니다(질문·기여·설정·도움말 한 곳에서)"),
                Commands.slash("llm-settings", "설정 패널을 엽니다(관리자)").setDefaultPermissions(adminPerm),
                Commands.slash("bot-permissions", "봇 권한과 @멘션 호출 설정을 점검합니다(관리자)").setDefaultPermissions(adminPerm),
                Commands.slash("ask-long", "긴 질문을 모달 창으로 입력합니다"),
                // 컨텍스트 메뉴(#181): 메시지 우클릭 → 그 내용으로 질문
                Commands.message("AI에게 질문"),
            )
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
        private val mentionAskEnabled: Boolean,
        private val onDisallowedIntents: () -> Unit,
    ) : ListenerAdapter() {
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
                    // 먼저 설치할 컴퓨터(OS)를 버튼으로 묻는다(차수 19). 클릭 → 그 OS 복붙 설치 명령.
                    event
                        .reply(
                            "🖥️ **내 컴퓨터의 AI로 함께 도와주기**\n\n" +
                                "내 컴퓨터에 있는 AI가 커뮤니티 질문에 답하는 일을 함께 도와줘요.\n" +
                                "복잡한 설정은 안내를 따라 하면 되고, 원할 때 언제든 멈출 수 있어요.\n\n" +
                                "**설치할 컴퓨터**를 고르세요. 버튼을 누르면 복사해서 붙여넣을 명령을 보여드릴게요.",
                        ).addComponents(ActionRow.of(MenuFactory.osButtons()))
                        .setEphemeral(true)
                        .queue()
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
                            .replyEmbeds(settingsEmbed(ctx))
                            .addComponents(settingsRows(ctx))
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
                            .reply(channelProfilePanelText(ctx))
                            .addComponents(channelProfileRows())
                            .setEphemeral(true)
                            .queue()
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
            try {
                val reply = dispatch(event, ctx)
                if (useWebhookProfile) {
                    completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
                } else {
                    event.hook.editOriginal(reply.content).queue()
                }
            } catch (e: Exception) {
                log.warn("명령 처리 실패: {} — {}", event.name, e.message)
                event.hook.editOriginal("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})
            }
        }

        companion object {
            private val log = LoggerFactory.getLogger(Listener::class.java)

            /** 공개(비-ephemeral) 응답 명령. 나머지는 본인만 보이게(ephemeral). */
            private val PUBLIC_COMMANDS = setOf("ask", "contributions", "community-stats", "welcome")
            private const val WEBHOOK_NAME = "discord-ai-channel-profile"
            private const val CHANNEL_PROFILE_EDIT = "channel-profile:edit"
            private const val CHANNEL_PROFILE_AVATAR = "channel-profile:avatar"
            private const val CHANNEL_PROFILE_RESET = "channel-profile:reset"
            private const val CHANNEL_PROFILE_ROLLBACK = "channel-profile:rollback"
            private const val CHANNEL_PROFILE_SAVE_MODAL = "channel-profile:save-modal"
            private const val CHANNEL_PROFILE_AVATAR_MODAL = "channel-profile:avatar-modal"
            private val pendingSettings = ConcurrentHashMap<String, PendingGuildSettings>()
        }

        private data class PendingGuildSettings(
            var language: String? = null,
            var defaultModel: String? = null,
            var allowedChannelIds: List<Long>? = null,
            var autoApprove: Boolean? = null,
        )

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
            when (event.componentId) {
                MenuFactory.ASK -> {
                    // 질문하기 → 모달로 질문 입력
                    event.replyModal(askModal()).queue()
                    return
                }
                MenuFactory.SETTINGS -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event
                            .replyEmbeds(settingsEmbed(ctx))
                            .addComponents(settingsRows(ctx))
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
                        event.replyModal(channelProfileModal(ctx)).queue()
                    }
                    return
                }
                CHANNEL_PROFILE_AVATAR -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event.replyModal(channelProfileAvatarModal(ctx)).queue()
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
                    // '내 PC 기여' → 먼저 설치할 OS 를 버튼으로 묻는다(차수 19).
                    event
                        .reply(
                            "🖥️ **내 컴퓨터의 AI로 함께 도와주기**\n\n" +
                                "내 컴퓨터에 있는 AI가 커뮤니티 질문에 답하는 일을 함께 도와줘요.\n" +
                                "복잡한 설정은 안내를 따라 하면 되고, 원할 때 언제든 멈출 수 있어요.\n\n" +
                                "**설치할 컴퓨터**를 고르세요. 버튼을 누르면 복사해서 붙여넣을 명령을 보여드릴게요.",
                        ).addComponents(ActionRow.of(MenuFactory.osButtons()))
                        .setEphemeral(true)
                        .queue()
                    return
                }
            }
            // OS 선택 → 그 OS 복붙 설치 명령(토큰 포함, ephemeral).
            if (event.componentId.startsWith(MenuFactory.OS_PREFIX)) {
                val os = event.componentId.removePrefix(MenuFactory.OS_PREFIX)
                event.reply(commands.providerInstallGuide(ctx, os).content).setEphemeral(true).queue()
                return
            }
            val reply =
                when (event.componentId) {
                    MenuFactory.STATUS -> commands.providerStatus(ctx)
                    MenuFactory.AUTO_APPROVE_ON -> {
                        pendingSettings(settingsKey(ctx)).autoApprove = true
                        return updateSettingsPanel(event, ctx)
                    }
                    MenuFactory.AUTO_APPROVE_OFF -> {
                        pendingSettings(settingsKey(ctx)).autoApprove = false
                        return updateSettingsPanel(event, ctx)
                    }
                    MenuFactory.CHANNEL_ALL -> {
                        pendingSettings(settingsKey(ctx)).allowedChannelIds = emptyList()
                        return updateSettingsPanel(event, ctx)
                    }
                    MenuFactory.SAVE_SETTINGS -> return savePendingSettings(event, ctx)
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
            val reply =
                when (event.componentId) {
                    MenuFactory.LANG -> {
                        pendingSettings(settingsKey(ctx)).language = value
                        return updateSettingsPanel(event, ctx)
                    }
                    MenuFactory.MODEL -> {
                        pendingSettings(settingsKey(ctx)).defaultModel = value
                        return updateSettingsPanel(event, ctx)
                    }
                    else -> Reply("알 수 없는 선택입니다.")
                }
            event.reply(reply.content).setEphemeral(true).queue()
        }

        /** 설정 채널 허용(엔티티 선택). */
        override fun onEntitySelectInteraction(event: EntitySelectInteractionEvent) {
            if (event.componentId != MenuFactory.CHANNEL) return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            val channelIds = event.values.map { it.idLong }
            pendingSettings(settingsKey(ctx)).allowedChannelIds = channelIds
            updateSettingsPanel(event, ctx)
        }

        /** 봇이 서버에 들어오면 자동 온보딩 패널 게시. */
        override fun onGuildJoin(event: GuildJoinEvent) {
            val channel = event.guild.systemChannel ?: return // 시스템 채널 없으면 스킵
            channel
                .sendMessageEmbeds(EmbedFactory.mainMenuEmbed(isAdmin = true))
                .setComponents(ActionRow.of(MenuFactory.mainButtons(isAdmin = true)))
                .queue({}, {})
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

        private fun askModal() =
            Modal
                .create("ask-long-modal", "질문 입력")
                .addActionRow(
                    TextInput
                        .create("prompt", "질문", TextInputStyle.PARAGRAPH)
                        .setRequired(true)
                        .setMaxLength(4000)
                        .build(),
                ).build()

        /** 설정 패널 Embed(현재 상태 + 저장 대기 변경사항). */
        private fun settingsEmbed(ctx: CommandContext): MessageEmbed {
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
                pendingSummary = pendingSummary(ctx),
                currentSummary = currentSettingsSummary(ctx),
            )
        }

        private fun settingsKey(ctx: CommandContext) = "${ctx.guildId}:${ctx.channelId}:${ctx.userId}"

        private fun allowedChannelText(ctx: CommandContext): String = formatChannelPolicy(effectiveAllowedChannelIds(ctx))

        private fun currentSettingsSummary(ctx: CommandContext): String {
            val model = commands.guildDefaultModel(ctx) ?: "자동 선택"
            val autoApprove = if (commands.isAutoApprove(ctx)) "켜짐" else "꺼짐"
            return listOf(
                "• 언어: `${commands.guildLanguage(ctx)}`",
                "• 기본 모델: `$model`",
                "• LLM 사용 채널: ${formatChannelPolicy(commands.allowedChannelIds(ctx))}",
                "• 자동 승인: `$autoApprove`",
            ).joinToString("\n")
        }

        private fun effectiveAllowedChannelIds(ctx: CommandContext): List<Long> =
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

        private fun pendingSettings(key: String): PendingGuildSettings = pendingSettings.computeIfAbsent(key) { PendingGuildSettings() }

        private fun savePendingSettings(
            event: ButtonInteractionEvent,
            ctx: CommandContext,
        ) {
            val key = settingsKey(ctx)
            val pending = pendingSettings.remove(key)
            if (pending == null || pending == PendingGuildSettings()) {
                event.reply("아직 저장할 변경사항이 없습니다. 언어/모델/채널/자동 승인을 먼저 선택해주세요.").setEphemeral(true).queue()
                return
            }
            val reply =
                commands.saveGuildSettings(
                    ctx,
                    pending.language,
                    pending.defaultModel,
                    pending.allowedChannelIds,
                    pending.autoApprove,
                )
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
        private fun settingsRows(ctx: CommandContext): List<ActionRow> =
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
                ActionRow.of(MenuFactory.settingsActionButtons()),
            )

        /** 채널 AI 프로필 설정 패널. 긴 옵션 입력 대신 버튼→모달→저장 흐름으로 관리한다. */
        private fun channelProfilePanelText(ctx: CommandContext): String {
            val current = channelProfiles.get(ctx.guildId, ctx.channelId)
            val summary =
                if (current == null) {
                    "아직 이 채널 전용 AI 프로필이 없습니다."
                } else {
                    "현재 이름: **${current.displayName}**\n" +
                        "역할: `${current.purpose}` · 말투: `${current.tone}` · 길이: `${current.answerLength}`\n" +
                        "아이콘: ${if (current.avatarUrl.isNullOrBlank()) "기본 봇 아이콘" else "설정됨"}\n" +
                        "행동 버전: v${current.version}"
                }
            return "❂ **채널 AI 프로필 설정**\n\n" +
                "$summary\n\n" +
                "아래 버튼으로 설정하세요. 긴 명령어 옵션을 직접 외울 필요가 없습니다."
        }

        private fun channelProfileRows(): List<ActionRow> =
            listOf(
                ActionRow.of(
                    Button.primary(CHANNEL_PROFILE_EDIT, "프로필 편집"),
                    Button.secondary(CHANNEL_PROFILE_AVATAR, "아이콘 URL"),
                ),
                ActionRow.of(
                    Button.secondary(CHANNEL_PROFILE_ROLLBACK, "이전 버전으로 롤백"),
                    Button.danger(CHANNEL_PROFILE_RESET, "기본값으로 초기화"),
                ),
            )

        private fun channelProfileModal(ctx: CommandContext): Modal {
            val current = channelProfiles.get(ctx.guildId, ctx.channelId)
            return Modal
                .create(CHANNEL_PROFILE_SAVE_MODAL, "채널 AI 프로필 저장")
                .addActionRow(
                    TextInput
                        .create("name", "이름", TextInputStyle.SHORT)
                        .setRequired(true)
                        .setMaxLength(80)
                        .setValue(current?.displayName ?: "냥시스턴트")
                        .build(),
                ).addActionRow(
                    TextInput
                        .create("purpose", "역할", TextInputStyle.SHORT)
                        .setRequired(false)
                        .setMaxLength(200)
                        .setValue(current?.purpose ?: "general_assistant")
                        .build(),
                ).addActionRow(
                    TextInput
                        .create("tone", "말투", TextInputStyle.SHORT)
                        .setRequired(false)
                        .setMaxLength(80)
                        .setValue(current?.tone ?: "friendly")
                        .build(),
                ).addActionRow(
                    TextInput
                        .create("answer-length", "답변 길이", TextInputStyle.SHORT)
                        .setRequired(false)
                        .setMaxLength(40)
                        .setValue(current?.answerLength ?: "balanced")
                        .build(),
                ).addActionRow(
                    TextInput
                        .create("constitution", "AI 헌법/규칙", TextInputStyle.PARAGRAPH)
                        .setRequired(false)
                        .setMaxLength(2000)
                        .setValue(current?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION)
                        .build(),
                ).build()
        }

        private fun channelProfileAvatarModal(ctx: CommandContext): Modal {
            val current = channelProfiles.get(ctx.guildId, ctx.channelId)
            val avatarInput =
                TextInput
                    .create("avatar-url", "이미지 URL", TextInputStyle.SHORT)
                    .setRequired(false)
                    .setMaxLength(1000)
                    .setPlaceholder("비우고 저장하면 아이콘 URL을 제거합니다.")
                    .apply {
                        current
                            ?.avatarUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { setValue(it) }
                    }.build()
            return Modal
                .create(CHANNEL_PROFILE_AVATAR_MODAL, "채널 AI 아이콘 URL 저장")
                .addActionRow(avatarInput)
                .build()
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
                completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
            } else {
                event.hook.editOriginal(reply.content).queue()
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
                completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
            } else {
                event.hook.editOriginal(reply.content).queue()
            }
        }

        /** 봇 멘션 질문: `@냥시스턴트 질문` 을 기존 /ask 와 같은 Provider Pool 흐름으로 처리한다. */
        override fun onMessageReceived(event: MessageReceivedEvent) {
            if (!mentionAskEnabled || !event.isFromGuild || event.author.isBot) return
            val selfId = event.jda.selfUser.idLong
            val mentionedUsers = event.message.mentions.users
            val mentioned = mentionedUsers.any { it.idLong == selfId }
            if (!mentioned) return

            val prompt = mentionPrompt(event.message.contentRaw, selfId)
            if (prompt.isBlank()) {
                event.message
                    .reply("질문 내용을 같이 적어주세요. 예: `@냥시스턴트 오늘 회의 요약해줘`")
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
                if (useWebhookProfile && sendAnswerWebhook(event.channel, ctx, reply)) {
                    event.message
                        .addReaction(Emoji.fromUnicode("✅"))
                        .queue({}, {})
                } else {
                    event.message
                        .reply(reply.content)
                        .mentionRepliedUser(false)
                        .queue({}, {})
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

        private fun completePublicAnswerWithProfileFallback(
            hook: InteractionHook,
            channelUnion: MessageChannelUnion?,
            ctx: CommandContext,
            reply: Reply,
        ) {
            if (sendAnswerWebhook(channelUnion, ctx, reply)) {
                hook.editOriginal("✅ 답변을 채널 AI 프로필로 보냈어요.").queue()
                return
            }
            if (sendBotChannelAnswer(channelUnion, reply)) {
                hook
                    .editOriginal(
                        "⚠️ 채널 AI 이름/아이콘으로 보내려면 봇에 `웹후크 관리` 권한이 필요해요. " +
                            "이번 답변은 기본 봇 이름으로 보냈습니다.",
                    ).queue()
                return
            }
            hook.editOriginal(reply.content).queue()
        }

        private fun sendBotChannelAnswer(
            channelUnion: MessageChannelUnion?,
            reply: Reply,
        ): Boolean {
            if (reply.ephemeral) return false
            channelUnion ?: return false
            return runCatching {
                channelUnion.asTextChannel().sendMessage(reply.content).complete()
                true
            }.onFailure { e ->
                log.warn("일반 봇 메시지 폴백 전송 실패: {}", e.message)
            }.getOrDefault(false)
        }

        /**
         * 채널별 AI 프로필이 설정된 경우, 답변을 Discord Webhook 으로 보내 표시 이름/아이콘을 채널 단위로 바꾼다.
         * 실패하면 false 를 반환해 일반 인터랙션 응답으로 안전하게 폴백한다.
         */
        private fun sendAnswerWebhook(
            channelUnion: MessageChannelUnion?,
            ctx: CommandContext,
            reply: Reply,
        ): Boolean {
            if (reply.ephemeral) return false
            channelUnion ?: return false
            val profile = channelProfiles.get(ctx.guildId, ctx.channelId) ?: return false
            val channel = runCatching { channelUnion.asTextChannel() }.getOrNull() ?: return false
            return runCatching {
                val webhook =
                    channel
                        .retrieveWebhooks()
                        .complete()
                        .firstOrNull { it.name == WEBHOOK_NAME }
                        ?: channel.createWebhook(WEBHOOK_NAME).complete()
                val action = webhook.sendMessage(reply.content).setUsername(profile.displayName)
                if (!profile.avatarUrl.isNullOrBlank()) {
                    action.setAvatarUrl(profile.avatarUrl)
                }
                action.complete()
                true
            }.onFailure { e ->
                log.warn("채널 AI 프로필 웹훅 전송 실패(channel={}): {}", ctx.channelId, e.message)
            }.getOrDefault(false)
        }

        private fun mentionPrompt(
            raw: String,
            selfId: Long,
        ): String =
            raw
                .replace(Regex("<@!?$selfId>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** 길드면 길드 컨텍스트, DM(유저설치)이면 글로벌 풀(DM_SCOPE) 컨텍스트 — 관리자/역할 없음. */
        private fun ctxOf(interaction: Interaction): CommandContext {
            val guild = interaction.guild
            return if (guild != null) {
                buildCtx(guild.idLong, interaction.member, interaction.channelIdLong, interaction.user.idLong)
            } else {
                CommandContext(
                    guildId = CommandService.DM_SCOPE,
                    channelId = interaction.channelIdLong,
                    userId = interaction.user.idLong,
                    roleIds = emptySet(),
                    isAdmin = false,
                )
            }
        }

        private fun buildCtx(
            guildId: Long,
            member: net.dv8tion.jda.api.entities.Member?,
            channelId: Long,
            userId: Long,
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
                    )
                "models" -> commands.models(ctx)
                "catalog" -> commands.catalog(ctx)
                "my-usage" -> commands.myUsage(ctx)
                "contributions" -> commands.contributions(ctx)
                "community-stats" -> commands.communityStats(ctx)
                "fairness" -> commands.fairness(ctx)
                "privacy" -> commands.privacy(ctx)
                "help" -> commands.help(ctx)
                "bot-permissions" -> commands.botPermissions(ctx)
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
                "provider-scope" ->
                    commands.providerScope(
                        ctx,
                        event.getOption("model")!!.asString,
                        event.getOption("role")!!.asString,
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
