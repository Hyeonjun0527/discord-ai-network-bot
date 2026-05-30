package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
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
    @param:Value("\${central.discord.enabled:false}") private val enabled: Boolean,
    @param:Value("\${central.discord.bot-token:}") private val token: String,
) {
    private val log = LoggerFactory.getLogger(DiscordBot::class.java)
    private var jda: JDA? = null

    @PostConstruct
    fun start() {
        if (!enabled || token.isBlank()) {
            log.info("Discord 비활성(enabled={}, token={}) — JDA 미기동", enabled, token.isNotBlank())
            return
        }
        val instance = JDABuilder.createLight(token).addEventListeners(Listener(commands)).build()
        registerCommands(instance)
        jda = instance
        log.info("Discord(JDA) 기동 완료")
    }

    @PreDestroy
    fun stop() {
        jda?.shutdown()
    }

    private fun registerCommands(jda: JDA) {
        // 관리자 명령은 비관리자 UI 에서 숨김(#186). 서버 관리 권한 보유자만 노출.
        val adminPerm = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)
        jda.updateCommands().addCommands(
            Commands.slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
                .addOption(OptionType.STRING, "prompt", "질문 내용", true),
            Commands.slash("models", "사용 가능한 모델 수준을 확인합니다"),
            Commands.slash("catalog", "이 서버에서 제공 중인 모델 목록을 봅니다"),
            Commands.slash("my-usage", "내 오늘 사용량을 확인합니다"),
            Commands.slash("contributions", "커뮤니티 기여 리더보드를 봅니다"),
            Commands.slash("fairness", "공정성 리포트를 봅니다(관리자)").setDefaultPermissions(adminPerm),
            Commands.slash("privacy", "이 서버의 AI 처리/프라이버시 안내"),
            Commands.slash("help", "명령 종합 도움말을 봅니다"),
            Commands.slash("provider-join", "프로바이더로 참여합니다"),
            Commands.slash("provider-pause", "요청 수신을 일시정지합니다"),
            Commands.slash("provider-resume", "요청 수신을 재개합니다"),
            Commands.slash("provider-leave", "풀에서 나갑니다"),
            Commands.slash("provider-status", "내 프로바이더 상태를 확인합니다"),
            Commands.slash("provider-models", "내가 제공하는 모델을 설정합니다")
                .addOption(OptionType.STRING, "models", "모델명(쉼표로 구분)", true),
            Commands.slash("provider-limit", "모델별 한도를 설정합니다")
                .addOption(OptionType.STRING, "model", "대상 모델", true)
                .addOption(OptionType.INTEGER, "daily", "하루 한도(0=무제한)", true)
                .addOption(OptionType.INTEGER, "concurrency", "동시 처리 수", true)
                .addOption(OptionType.INTEGER, "seconds", "요청당 최대 초", true),
            Commands.slash("provider-scope", "모델 허용 범위를 설정합니다")
                .addOption(OptionType.STRING, "model", "대상 모델", true)
                .addOption(OptionType.STRING, "role", "all / trusted / admin", true),
            Commands.slash("llm-allow-channel", "LLM 사용 채널을 허용합니다(관리자)")
                .addOption(OptionType.CHANNEL, "channel", "허용 채널", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("llm-deny-channel", "LLM 사용 채널을 금지합니다(관리자)")
                .addOption(OptionType.CHANNEL, "channel", "금지 채널", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("llm-role-policy", "역할별 허용 수준을 설정합니다(관리자)")
                .addOption(OptionType.ROLE, "role", "대상 역할", true)
                .addOption(OptionType.STRING, "level", "LIGHT/STANDARD/HEAVY", true)
                .addOption(OptionType.INTEGER, "limit", "하루 한도", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("providers", "프로바이더 풀 상태를 봅니다(관리자)").setDefaultPermissions(adminPerm),
            Commands.slash("provider-approve", "프로바이더 등록을 승인합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("provider-remove", "프로바이더를 제거합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("llm-block", "사용자를 차단합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("llm-unblock", "사용자 차단을 해제합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
        ).queue()
    }

    /** JDA 이벤트 → CommandContext → CommandService → 응답. */
    class Listener(private val commands: CommandService) : ListenerAdapter() {
        override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
            val guild = event.guild ?: run {
                event.reply("서버에서만 사용할 수 있습니다.").setEphemeral(true).queue()
                return
            }
            val member = event.member
            val ctx = CommandContext(
                guildId = guild.idLong,
                channelId = event.channelIdLong,
                userId = event.user.idLong,
                roleIds = member?.roles?.map { it.idLong }?.toSet() ?: emptySet(),
                isAdmin = member?.let {
                    it.hasPermission(Permission.MANAGE_SERVER) || it.hasPermission(Permission.ADMINISTRATOR)
                } ?: false,
            )
            val reply = dispatch(event, ctx)
            event.reply(reply.content).setEphemeral(reply.ephemeral).queue()
        }

        private fun dispatch(event: SlashCommandInteractionEvent, ctx: CommandContext): Reply = when (event.name) {
            "ask" -> commands.ask(ctx, event.getOption("prompt")?.asString.orEmpty())
            "models" -> commands.models(ctx)
            "catalog" -> commands.catalog(ctx)
            "my-usage" -> commands.myUsage(ctx)
            "contributions" -> commands.contributions(ctx)
            "fairness" -> commands.fairness(ctx)
            "privacy" -> commands.privacy()
            "help" -> commands.help(ctx)
            "provider-join" -> commands.providerJoin(ctx)
            "provider-pause" -> commands.providerPause(ctx)
            "provider-resume" -> commands.providerResume(ctx)
            "provider-leave" -> commands.providerLeave(ctx)
            "provider-status" -> commands.providerStatus(ctx)
            "provider-models" -> commands.providerModels(
                ctx,
                event.getOption("models")!!.asString.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            )
            "provider-limit" -> commands.providerLimit(
                ctx,
                event.getOption("model")!!.asString,
                event.getOption("daily")!!.asInt,
                event.getOption("concurrency")!!.asInt,
                event.getOption("seconds")!!.asInt,
            )
            "provider-scope" -> commands.providerScope(
                ctx,
                event.getOption("model")!!.asString,
                event.getOption("role")!!.asString,
            )
            "llm-allow-channel" -> commands.allowChannel(ctx, event.getOption("channel")!!.asChannel.idLong)
            "llm-deny-channel" -> commands.denyChannel(ctx, event.getOption("channel")!!.asChannel.idLong)
            "llm-role-policy" -> commands.setRolePolicy(
                ctx,
                event.getOption("role")!!.asRole.idLong,
                runCatching { ModelBurden.valueOf(event.getOption("level")!!.asString.uppercase()) }.getOrDefault(ModelBurden.LIGHT),
                event.getOption("limit")!!.asInt,
            )
            "providers" -> commands.providers(ctx)
            "provider-approve" -> commands.approveProvider(ctx, event.getOption("user")!!.asUser.idLong)
            "provider-remove" -> commands.removeProvider(ctx, event.getOption("user")!!.asUser.idLong)
            "llm-block" -> commands.blockUser(ctx, event.getOption("user")!!.asUser.idLong)
            "llm-unblock" -> commands.unblockUser(ctx, event.getOption("user")!!.asUser.idLong)
            else -> Reply("알 수 없는 명령입니다.")
        }
    }
}
