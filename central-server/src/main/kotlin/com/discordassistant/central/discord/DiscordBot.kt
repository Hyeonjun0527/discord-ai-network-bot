package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
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
import net.dv8tion.jda.api.interactions.DiscordLocale
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
 * Discord(JDA) 부트스트랩 + 슬래시 명령 등록/디스패치 (K-차수 13).
 * central.discord.enabled=true 이고 토큰이 있을 때만 연결한다(테스트/CI 는 비활성).
 */
@Component
class DiscordBot(
    private val commands: CommandService,
    private val metrics: CommandMetrics,
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
                .addEventListeners(Listener(commands, metrics))
                .build()
        jda = instance
        if (guildId.isNotBlank()) {
            // 길드 즉시 등록: READY 대기 후 해당 길드에 등록(전파 즉시 — 테스트/단일 서버 권장).
            instance.awaitReady()
            val guild = instance.getGuildById(guildId)
            if (guild != null) {
                registerCommands(guild.updateCommands())
                log.info("Discord 길드 {} 슬래시 명령 즉시 등록", guildId)
            } else {
                log.warn("길드 {} 를 못 찾음(봇이 그 서버에 없음?) — 글로벌 등록으로 폴백", guildId)
                registerCommands(instance.updateCommands())
            }
        } else {
            registerCommands(instance.updateCommands()) // 글로벌(전파 최대 ~1h)
        }
        log.info("Discord(JDA) 기동 완료")
    }

    @PreDestroy
    fun stop() {
        jda?.shutdown()
    }

    private fun registerCommands(action: net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction) {
        // 관리자 명령은 비관리자 UI 에서 숨김(#186). 서버 관리 권한 보유자만 노출.
        val adminPerm = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)
        action
            .addCommands(
                Commands
                    .slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
                    .setDescriptionLocalization(DiscordLocale.ENGLISH_US, "Ask the community local AI")
                    .addOption(OptionType.STRING, "prompt", "질문 내용", true),
                Commands
                    .slash("models", "사용 가능한 모델 수준을 확인합니다")
                    .setDescriptionLocalization(DiscordLocale.ENGLISH_US, "Check available model levels"),
                Commands
                    .slash("catalog", "이 서버에서 제공 중인 모델 목록을 봅니다")
                    .setDescriptionLocalization(DiscordLocale.ENGLISH_US, "List models offered in this server"),
                Commands
                    .slash("my-usage", "내 오늘 사용량을 확인합니다")
                    .setDescriptionLocalization(DiscordLocale.ENGLISH_US, "Check your usage today"),
                Commands.slash("contributions", "커뮤니티 기여 리더보드를 봅니다"),
                Commands.slash("community-stats", "익명 커뮤니티 기여 통계를 봅니다"),
                Commands.slash("fairness", "공정성 리포트를 봅니다(관리자)").setDefaultPermissions(adminPerm),
                Commands
                    .slash("privacy", "이 서버의 AI 처리/프라이버시 안내")
                    .setDescriptionLocalization(DiscordLocale.ENGLISH_US, "AI processing & privacy notice"),
                Commands
                    .slash("help", "명령 종합 도움말을 봅니다")
                    .setDescriptionLocalization(DiscordLocale.ENGLISH_US, "Show the full command help"),
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
                    .slash("provider-models", "내가 제공하는 모델을 설정합니다")
                    .addOption(OptionType.STRING, "models", "모델명(쉼표로 구분)", true),
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
                    ).addOption(OptionType.STRING, "role", "all / trusted / admin", true),
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
                    .addOption(OptionType.STRING, "level", "LIGHT/STANDARD/HEAVY", true)
                    .addOption(OptionType.INTEGER, "limit", "하루 한도", true)
                    .setDefaultPermissions(adminPerm),
                Commands
                    .slash("llm-guild-defaults", "길드 기본 모델/언어를 설정합니다(관리자)")
                    .addOption(OptionType.STRING, "model", "기본 모델(비우면 자동 선택)", false)
                    .addOption(OptionType.STRING, "language", "언어 코드(예: ko, en)", false)
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
            ).queue()
    }

    /** JDA 이벤트 → CommandContext → CommandService → 응답. */
    class Listener(
        private val commands: CommandService,
        private val metrics: CommandMetrics,
    ) : ListenerAdapter() {
        override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
            metrics.record(event.name) // 명령 사용 통계(#190)
            val guild =
                event.guild ?: run {
                    event.reply("서버에서만 사용할 수 있습니다.").setEphemeral(true).queue()
                    return
                }
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            // 인터랙티브 명령은 컴포넌트/모달로 응답(온보딩/설정 판).
            when (event.name) {
                "menu" -> {
                    event
                        .reply("🧭 **시작 패널** — 버튼으로 시작하세요!")
                        .addComponents(ActionRow.of(MenuFactory.mainButtons(ctx.isAdmin)))
                        .setEphemeral(true)
                        .queue()
                    return
                }
                "llm-settings" -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 관리자만 사용할 수 있습니다.").setEphemeral(true).queue()
                    } else {
                        event
                            .reply("⚙️ **설정 패널** — 드롭다운/버튼으로 설정하세요.")
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
            val isPublic = event.name in PUBLIC_COMMANDS
            event.deferReply(!isPublic).queue()
            try {
                val reply = dispatch(event, ctx)
                event.hook.editOriginal(reply.content).queue()
            } catch (e: Exception) {
                log.warn("명령 처리 실패: {} — {}", event.name, e.message)
                event.hook.editOriginal("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})
            }
        }

        companion object {
            private val log = LoggerFactory.getLogger(Listener::class.java)

            /** 공개(비-ephemeral) 응답 명령. 나머지는 본인만 보이게(ephemeral). */
            private val PUBLIC_COMMANDS = setOf("ask", "contributions", "community-stats", "welcome")
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
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
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
                            .reply("⚙️ **설정** — 드롭다운/버튼으로 바로 적용됩니다.")
                            .addComponents(settingsRows(ctx))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
            }
            val reply =
                when (event.componentId) {
                    MenuFactory.PROVIDER -> commands.providerJoin(ctx)
                    MenuFactory.STATUS -> commands.providerStatus(ctx)
                    MenuFactory.HELP, "settings:help" -> Reply(MenuFactory.slimHelp(ctx.isAdmin))
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

        /** 설정 패널 액션 로우(언어·모델·채널 드롭다운 + 자동승인 버튼). */
        private fun settingsRows(ctx: CommandContext): List<ActionRow> =
            listOf(
                ActionRow.of(MenuFactory.languageSelect(current = "")),
                ActionRow.of(MenuFactory.modelSelect(commands.autocompleteModels(ctx))),
                ActionRow.of(MenuFactory.channelSelect()),
                ActionRow.of(Button.secondary(MenuFactory.AUTO_APPROVE, "프로바이더 자동승인 토글")),
            )

        /** 긴 질문 모달 제출(#189). */
        override fun onModalInteraction(event: ModalInteractionEvent) {
            if (event.modalId != "ask-long-modal") return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            val prompt = event.getValue("prompt")?.asString.orEmpty()
            event.deferReply(false).queue()
            val reply = commands.ask(ctx, prompt)
            event.hook.editOriginal(reply.content).queue()
        }

        /** 메시지 컨텍스트 메뉴(#181): 그 메시지 내용으로 질문. */
        override fun onMessageContextInteraction(event: MessageContextInteractionEvent) {
            if (event.name != "AI에게 질문") return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            event.deferReply(false).queue()
            val reply = commands.ask(ctx, event.target.contentRaw)
            event.hook.editOriginal(reply.content).queue()
        }

        /** 만족도 리액션 수집(#171): 👍/👎 를 메트릭으로 집계. */
        override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
            if (event.user?.isBot == true) return
            when (event.emoji.formatted) {
                "👍" -> metrics.record("reaction:up")
                "👎" -> metrics.record("reaction:down")
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
