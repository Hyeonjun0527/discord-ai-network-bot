package com.discordassistant.central.platform.discord.command

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
        val limit = policy.dailyLimit(ctx.guildId, ctx.roleIds)
        return Reply("오늘 사용량: $used / $limit")
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
        val sb = StringBuilder()
        sb.append("**NEXA AI 네트워크 — 도움말**\n")
        sb.append("커뮤니티 멤버들의 PC LLM 을 모아 공정하게 분배합니다(금전 거래 아님).\n\n")
        sb.append("__유저__\n")
        sb.append("· ${c("ask")} `<질문>` — 풀의 누군가의 PC LLM 으로 답변\n")
        sb.append("· ${c("free-ask")} `<질문>` — 관리자가 제공하는 무료 클라우드 AI(Gemini)로 답변\n")
        sb.append("· ${c("models")} ${c("catalog")} — 사용 가능한 모델 수준·목록\n")
        sb.append("· ${c("my-usage")} ${c("privacy")} — 내 사용량 / 프라이버시 고지\n")
        sb.append("· ${c("license")} — 내 Nexa 라이선스 상태\n")
        sb.append("· ${c("nia")} ${c("contributions")} — 니아 호감도 / 기여 현황(비금전 인정)\n\n")
        sb.append("__프로바이더(내 컴퓨터의 AI로 함께 도와주기)__\n")
        sb.append("· ${c("provider-join")} — 참여 신청(승인 후 토큰→에이전트 실행)\n")
        sb.append("· ${c("provider-pause")} ${c("provider-resume")} ${c("provider-leave")} — 가용성 제어\n")
        sb.append("· ${c("provider-status")} ${c("provider-models")} ${c("provider-limit")} — 내 기여 설정\n")
        sb.append("· 봇이 서버에서 제거되면 그 서버의 프로바이더 연결/등록/토큰은 자동 정리됩니다.\n")
        if (ctx.isAdmin) {
            sb.append("\n__관리자__\n")
            sb.append("· ${c("fairness")} ${c("providers")} — 공정성 리포트·프로바이더 목록\n")
            sb.append("· ${c("provider-approve")} ${c("provider-remove")} — 승인/제거\n")
            sb.append("· ${c("llm-allow-channel")} ${c("llm-deny-channel")} ${c("llm-role-policy")} — 채널·역할 정책\n")
            sb.append("· ${c("llm-channel-profile")} — 이 채널에서 보일 AI 답변 이름/아이콘 설정\n")
            sb.append("· ${c("ai-onboard")} — 이 채널 AI를 자동으로 설정(휴리스틱 draft → 승인 카드)\n")
            sb.append("· ${c("ai-instruction")} — 이 채널 AI에 자연어 자유 지침(페르소나/말투 색깔) 추가·수정\n")
            sb.append("· ${c("ai-network-map")} — Provider·모델·채널AI·RAG 구성을 한눈에 보기\n")
            sb.append("· ${c("ai-knowledge-list")} ${c("ai-knowledge-add")} ${c("ai-knowledge-search")} — 채널 지식공간/RAG 소스 관리\n")
            sb.append("· ${c("ai-knowledge-index-plan")} ${c("ai-knowledge-approve")} ${c("ai-knowledge-delete")} — 색인계획·검토·삭제\n")
            sb.append("· ${c("ai-knowledge-jobs")} ${c("ai-knowledge-job-complete")} — RAG 색인 작업 큐 조회·완료 처리\n")
            sb.append("· ${c("ai-preset-catalog")} ${c("ai-preset-import")} — 프리셋 공유 목록 보기·현재 채널에 가져오기\n")
            sb.append("· ${c("ai-preset-moderation")} ${c("ai-preset-report-review")} — 프리셋 신고 큐 확인·검수 처리\n")
            sb.append(
                "· ${c("ai-multi-response-status")} ${c("ai-multi-response-set")} ${c("ai-multi-response-dry-run")} " +
                    "— 다중응답 정책·상태·안전 드라이런\n",
            )
            sb.append("· ${c("ai-network-check")} — Provider·채널AI·RAG·프리셋·다중응답 운영 체크리스트\n")
            sb.append("· ${c("llm-block")} ${c("llm-unblock")} — 사용자 차단/해제\n")
        }
        sb.append("\n_민감정보(비밀번호·API 키 등)는 입력하지 마세요._")
        return Reply(sb.toString())
    }
}
