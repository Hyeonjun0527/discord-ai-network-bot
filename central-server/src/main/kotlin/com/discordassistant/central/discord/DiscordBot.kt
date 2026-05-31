package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

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
    @param:Value("\${central.discord.enabled:false}") private val enabled: Boolean,
    @param:Value("\${central.discord.bot-token:}") private val token: String,
    // 설정 시 해당 길드(서버)에 명령 즉시 등록(전파 지연 없음). 비우면 글로벌 등록(최대 ~1h).
    @param:Value("\${central.discord.guild-id:}") private val guildId: String,
) {
    private val log = LoggerFactory.getLogger(DiscordBot::class.java)
    private var jda: JDA? = null

    @PostConstruct
    fun start() {
        if (!enabled || token.isBlank()) {
            log.info("Discord 비활성(enabled={}, token={}) — JDA 미기동", enabled, token.isNotBlank())
            return
        }
        // createLight + 리액션 인텐트(#171 만족도 수집은 GUILD_MESSAGE_REACTIONS 필요).
        val instance =
            JDABuilder
                .createLight(token, GatewayIntent.GUILD_MESSAGE_REACTIONS)
                .addEventListeners(Listener(commands, metrics, channelProfiles))
                .build()
        jda = instance
        // 봇 DM 지원을 위해 항상 글로벌 등록(봇 DM 허용은 글로벌 명령 + dm_permission 으로 동작). 전파 최대 ~1h.
        registerCommands(instance.updateCommands())
        if (guildId.isNotBlank()) {
            // 글로벌+길드 명령을 동시에 두면 Discord 클라이언트에 영어/한국어 명령이 중복 노출될 수 있다.
            // 운영 길드에 남아 있는 길드 스코프 명령은 비우고, 로컬라이즈된 글로벌 명령만 사용한다.
            instance.awaitReady()
            instance.getGuildById(guildId)?.updateCommands()?.queue({}, {})
        }
        log.info("Discord(JDA) 기동 완료 — 슬래시 명령 글로벌 등록(봇 DM 포함)")
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
                    .addOption(OptionType.STRING, "prompt", "질문 내용", true),
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
                    .slash("llm-channel-profile", "이 채널의 AI 응답 프로필명을 설정합니다(관리자)")
                    .addOption(OptionType.STRING, "name", "이 채널에서 보일 AI 응답 이름(예: 냥시스턴트)", false)
                    .addOption(OptionType.ATTACHMENT, "avatar", "선택: 응답 프로필 아이콘 이미지 파일", false)
                    .addOption(OptionType.STRING, "avatar-url", "선택: 이미지 URL(파일 업로드가 어려울 때)", false)
                    .addOption(OptionType.BOOLEAN, "reset", "설정을 지우고 기본 봇 표시로 되돌립니다", false)
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
                        .reply(
                            "🤖 **AI에게 묻고, 함께 도와주기**\n\n" +
                                "궁금한 건 AI에게 바로 물어보세요.\n" +
                                "내 컴퓨터의 AI로 커뮤니티 질문 답변을 도울 수도 있어요.",
                        ).addComponents(ActionRow.of(MenuFactory.mainButtons(ctx.isAdmin)))
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
                if (useWebhookProfile && sendAnswerWebhook(event.channel, ctx, reply)) {
                    event.hook.editOriginal("✅ 답변을 채널 AI 프로필로 보냈어요.").queue()
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
                    MenuFactory.AUTO_APPROVE_ON -> commands.setAutoApprove(ctx, enabled = true)
                    MenuFactory.AUTO_APPROVE_OFF -> commands.setAutoApprove(ctx, enabled = false)
                    MenuFactory.CHANNEL_ALL -> commands.allowAllChannels(ctx)
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
                    MenuFactory.LANG -> commands.setGuildDefaults(ctx, defaultModel = null, language = value)
                    MenuFactory.MODEL ->
                        if (value == "__auto__") {
                            Reply("✅ 기본 모델: 자동 선택")
                        } else {
                            commands.setGuildDefaults(ctx, defaultModel = value, language = null)
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
            val channelId = event.values.firstOrNull()?.idLong
            val reply =
                if (channelId != null) commands.allowChannel(ctx, channelId) else Reply("채널을 선택하세요.")
            event.reply(reply.content).setEphemeral(true).queue()
        }

        /** 봇이 서버에 들어오면 자동 온보딩 패널 게시. */
        override fun onGuildJoin(event: GuildJoinEvent) {
            val channel = event.guild.systemChannel ?: return // 시스템 채널 없으면 스킵
            channel
                .sendMessage(
                    "👋 **커뮤니티 로컬 AI Provider Pool** 에 오신 걸 환영합니다!\n" +
                        "버튼으로 바로 시작하세요. 언제든 `/menu` 로 이 패널을 다시 엽니다.",
                ).setComponents(ActionRow.of(MenuFactory.mainButtons(isAdmin = true)))
                .queue({}, {})
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

        /** 설정 패널 Embed(현재 상태). */
        private fun settingsEmbed(ctx: CommandContext) =
            EmbedFactory.settingsEmbed(
                language = commands.guildLanguage(ctx),
                defaultModel = commands.guildDefaultModel(ctx),
                poolModelCount = commands.poolModels(ctx).size,
                allowedChannelCount = commands.allowedChannelIds(ctx).size,
                autoApprove = commands.isAutoApprove(ctx),
            )

        /** 설정 패널 액션 로우(언어·모델·채널 드롭다운 + 명시 버튼). */
        private fun settingsRows(ctx: CommandContext): List<ActionRow> =
            listOf(
                ActionRow.of(MenuFactory.languageSelect(current = "")),
                ActionRow.of(MenuFactory.modelSelect(commands.poolModels(ctx))),
                ActionRow.of(MenuFactory.channelSelect()),
                ActionRow.of(
                    Button.success(MenuFactory.CHANNEL_ALL, "모든 채널 허용"),
                    Button.primary(MenuFactory.AUTO_APPROVE_ON, "자동 승인 켜기"),
                    Button.secondary(MenuFactory.AUTO_APPROVE_OFF, "자동 승인 끄기"),
                ),
            )

        /** 긴 질문 모달 제출(#189). */
        override fun onModalInteraction(event: ModalInteractionEvent) {
            if (event.modalId != "ask-long-modal") return
            val ctx = ctxOf(event) // DM(유저설치)에서도 긴 질문 모달 동작
            val prompt = event.getValue("prompt")?.asString.orEmpty()
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.deferReply(useWebhookProfile).queue()
            val reply = commands.ask(ctx, prompt)
            if (useWebhookProfile && sendAnswerWebhook(event.channel, ctx, reply)) {
                event.hook.editOriginal("✅ 답변을 채널 AI 프로필로 보냈어요.").queue()
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
            if (useWebhookProfile && sendAnswerWebhook(event.channel, ctx, reply)) {
                event.hook.editOriginal("✅ 답변을 채널 AI 프로필로 보냈어요.").queue()
            } else {
                event.hook.editOriginal(reply.content).queue()
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
                "ask" -> commands.ask(ctx, event.getOption("prompt")?.asString.orEmpty())
                "models" -> commands.models(ctx)
                "catalog" -> commands.catalog(ctx)
                "my-usage" -> commands.myUsage(ctx)
                "contributions" -> commands.contributions(ctx)
                "community-stats" -> commands.communityStats(ctx)
                "fairness" -> commands.fairness(ctx)
                "privacy" -> commands.privacy(ctx)
                "help" -> commands.help(ctx)
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
