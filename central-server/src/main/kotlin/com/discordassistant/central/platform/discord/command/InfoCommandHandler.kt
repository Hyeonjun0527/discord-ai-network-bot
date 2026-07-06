package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.global.i18n.I18n
import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandLoc
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.UsageService
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Component

/**
 * 읽기 전용 정보/도움말 명령군(help, privacy, botPermissions, models, catalog, contributions,
 * communityStats, myUsage). CommandService 에서 응집 단위로 분리 — 동작/문구 불변, 시그니처 유지·위임.
 */
@Component
class InfoCommandHandler(
    private val policy: PolicyService,
    private val usage: UsageService,
    private val registry: ConnectionRegistry,
    private val licenses: LicenseService,
    private val guards: SharedCommandGuards,
) {
    fun models(ctx: CommandContext): Reply {
        val max = policy.maxAllowedBurden(ctx.guildId, ctx.roleIds)
        val pool = registry.byGuild(ctx.guildId).size
        val default = policy.guildDefaultModel(ctx.guildId)?.let { "\n서버 기본 모델: `$it`" } ?: ""
        return Reply(
            "사용 가능한 최대 모델 수준: **$max**\n현재 풀 프로바이더: ${pool}명$default\n" +
                "수준: ${ModelBurden.entries.filter { it != ModelBurden.RESTRICTED }.joinToString(" < ")}",
        )
    }

    fun catalog(ctx: CommandContext): Reply {
        val pool = registry.byGuild(ctx.guildId)
        if (pool.isEmpty()) return Reply("현재 풀에 온라인 프로바이더가 없습니다.")
        val byModel =
            pool
                .flatMap { s -> s.capability.models.map { it to s.providerId } }
                .groupBy({ it.first }, { it.second })
        if (byModel.isEmpty()) return Reply("프로바이더가 제공 모델을 아직 보고하지 않았습니다.")
        val lines = byModel.entries.sortedBy { it.key }.joinToString("\n") { "· `${it.key}` — ${it.value.distinct().size}명" }
        return Reply("이 서버에서 제공 중인 모델:\n$lines")
    }

    fun contributions(ctx: CommandContext): Reply {
        val contributions = usage.providerContributions(ctx.guildId)
        if (contributions.isEmpty()) return Reply("아직 누적 기여가 없습니다.")
        val providerCount = contributions.size
        val totalCount = contributions.sumOf { it.second }
        val ownCount = contributions.firstOrNull { it.first == ctx.userId }?.second ?: 0L
        return Reply(
            "🤝 **커뮤니티 기여 현황**\n" +
                "· 이 서버 누적 처리: **${totalCount}건**\n" +
                "· 기여한 멤버: **${providerCount}명**\n" +
                "· 내 기여: **${ownCount}건**\n\n" +
                "_순위 비교 없이 기여 여부와 누적만 기록합니다. 기여는 비금전 인정입니다. 고마워요!_",
            ephemeral = false,
        )
    }

    /** 익명 커뮤니티 기여 통계(차수 12 #177). 개별 식별정보 없이 집계만 공개. */
    fun communityStats(ctx: CommandContext): Reply {
        val pool = registry.byGuild(ctx.guildId)
        val providerCount = pool.size
        val totalContrib = usage.totalContributions(ctx.guildId)
        val models = pool.flatMap { it.capability.models }.distinct().size
        return Reply(
            "📊 커뮤니티 기여(익명 집계)\n" +
                "· 활성 프로바이더: ${providerCount}명\n" +
                "· 제공 모델 종류: ${models}종\n" +
                "· 누적 처리: ${totalContrib}건\n" +
                "_개별 식별정보 없이 집계됩니다._",
            ephemeral = false,
        )
    }

    fun myUsage(ctx: CommandContext): Reply {
        val used = usage.userDailyCount(ctx.guildId, ctx.userId)
        val limit = policy.dailyLimitPolicy(ctx.guildId, ctx.roleIds)
        return Reply("오늘 사용량: $used / ${limit.displayText}")
    }

    fun license(ctx: CommandContext): Reply {
        val e = licenses.view(ctx.userId)
        val status =
            when (e.status) {
                "TRIAL" -> "체험 중"
                "EXPIRED" -> "체험 만료"
                "LICENSED" -> "구매 완료"
                "EVENT_FREE" -> "이벤트 무료"
                "REVOKED" -> "정지됨"
                else -> "무료"
            }
        val trialLine = e.trialEndsAt?.let { "\n· 체험 종료: `$it`" }.orEmpty()
        val access = if (e.hasPaidAccess) "사용 가능" else "잠김"
        return Reply(
            "💳 **Nexa 라이선스**\n" +
                "· 상태: **$status**\n" +
                "· 프리미엄 기능: **$access**$trialLine\n\n" +
                "구매·이벤트 무료 신청은 데스크톱 앱 **설정 → 라이선스**에서 진행하세요.",
        )
    }

    fun privacy(ctx: CommandContext): Reply = Reply(Messages.get(Messages.Key.PRIVACY_NOTICE, guards.lang(ctx)))

    fun botPermissions(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return Reply(
            "**니아 봇 권한 점검**\n" +
                "@니아 질문을 쓰려면 Discord Developer Portal → Bot → " +
                "Privileged Gateway Intents → **Message Content Intent** 를 켜야 합니다.\n" +
                "채널 AI 이름/아이콘으로 답변하려면 서버 초대 권한에 **웹후크 관리(Manage Webhooks)** 가 필요합니다.\n" +
                "기본 슬래시 명령에는 채널 보기, 메시지 보내기, 링크 임베드, 메시지 기록 보기, " +
                "슬래시 명령어 사용 권한을 권장합니다.\n" +
                "권장 Permissions Integer: `2684734528`\n" +
                "문서: `docs/BOT_PERMISSIONS.md`",
        )
    }

    /**
     * 종합 도움말(차수 13 #183). 권한별 섹션 노출(#186).
     * 명령 표기는 보는 사람의 클라이언트 로케일로 표시 — 슬래시 메뉴에 보이는 이름과 일치(차수 19 UX).
     * 예) 한국어 클라이언트: `/질문` `/프로바이더참여`, 영어: `/ask` `/provider-join`.
     */
    fun help(
        ctx: CommandContext,
        locale: net.dv8tion.jda.api.interactions.DiscordLocale = net.dv8tion.jda.api.interactions.DiscordLocale.KOREAN,
    ): Reply {
        fun c(base: String) = "`/${CommandLoc.localName(base, locale)}`"
        // 헤더/인트로/푸터는 보는 사람 로케일로 i18n(명령명은 이미 c() 로 로케일화). 미지원 로케일이면 기본어 폴백.
        val lang = I18n.resolveOrNull(locale) ?: I18n.DEFAULT

        fun m(key: Messages.Key) = Messages.get(key, lang)
        val sb = StringBuilder()
        sb.append("${m(Messages.Key.HELP_TITLE)}\n")
        sb.append("${m(Messages.Key.HELP_INTRO)}\n\n")
        sb.append("${m(Messages.Key.HELP_SECTION_USER)}\n")
        sb.append("· ${c("ask")} `<질문>` — 무료 클라우드 AI(☁️)로 답변, 풀에 로컬 프로바이더가 있으면 로컬(🖥️)로\n")
        sb.append("· ${c("imagine")} `<설명>` — 이미지 생성(무료 클라우드 Stable Diffusion 기본, 로컬 연결 시 로컬)\n")
        sb.append("· ${c("menu")} ${c("my-usage")} ${c("nia")} ${c("privacy")} — 시작 패널 · 내 사용량 · 니아 호감도 · 프라이버시\n\n")
        sb.append("${m(Messages.Key.HELP_SECTION_PROVIDER)}\n")
        sb.append("· ${c("provider-join")} — 내 컴퓨터의 AI로 함께 도와주기(참여 후 운영은 데스크톱 앱에서)\n")
        sb.append("· ${c("provider-status")} — 내 프로바이더 상태 확인\n")
        sb.append("· 봇이 서버에서 제거되면 그 서버의 프로바이더 연결/등록/토큰은 자동 정리됩니다.\n")
        if (ctx.isAdmin) {
            sb.append("\n${m(Messages.Key.HELP_SECTION_ADMIN)}\n")
            sb.append(
                "· ${c("settings")} → 서버 관리·운영은 모두 **웹 대시보드**에서 합니다: 채널 AI 프로필·지식(RAG)·" +
                    "프리셋·다중응답·역할/채널 정책·프로바이더 승인/제거·사용자 차단·온보딩 등.\n",
            )
            sb.append("· 내 컴퓨터로 직접 AI를 돌리려면 ${c("provider-join")} → 데스크톱 앱을 설치해 운영하세요.\n")
        }
        sb.append("\n${m(Messages.Key.HELP_SENSITIVE_NOTICE)}")
        return Reply(sb.toString())
    }
}
