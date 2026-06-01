package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.network.ChannelAiRoutingPolicyService
import com.discordassistant.central.network.KnowledgeSearchService
import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.provider.ContributionPolicyService
import com.discordassistant.central.provider.ProviderProtectionService
import com.discordassistant.central.provider.ProviderRegistrationService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.routing.AiRequestInput
import com.discordassistant.central.routing.RequestOrchestrator
import com.discordassistant.central.usage.UsageService
import org.springframework.stereotype.Service

/** 슬래시 명령 응답(내용 + ephemeral 여부). */
data class Reply(
    val content: String,
    val ephemeral: Boolean = true,
)

/** 명령 호출 컨텍스트(JDA 이벤트에서 추출). */
data class CommandContext(
    val guildId: Long,
    val channelId: Long,
    val userId: Long,
    val roleIds: Set<Long>,
    val isAdmin: Boolean,
)

/**
 * 슬래시 명령 비즈니스 로직 (K-차수 13). JDA 이벤트와 분리된 순수 로직이라 단위 테스트 가능하다.
 * JDA 리스너는 이벤트→CommandContext 변환만 담당한다.
 */
@Service
class CommandService(
    private val orchestrator: RequestOrchestrator,
    private val registration: ProviderRegistrationService,
    private val protection: ProviderProtectionService,
    private val policy: PolicyService,
    private val usage: UsageService,
    private val registry: ConnectionRegistry,
    private val privacy: PrivacyService,
    private val rateLimiter: RateLimiter,
    private val contributionPolicy: ContributionPolicyService,
    private val blocklist: com.discordassistant.central.provider.BlocklistService,
    private val schedule: com.discordassistant.central.provider.ProviderScheduleService,
    private val channelProfiles: ChannelAiProfileService,
    private val channelRoutingPolicies: ChannelAiRoutingPolicyService,
    private val knowledgeSearch: KnowledgeSearchService,
    @param:org.springframework.beans.factory.annotation.Value("\${central.relay.public-url:}")
    private val relayPublicUrl: String = "",
) {
    companion object {
        /**
         * DM/유저설치(길드 없음)용 글로벌 풀 스코프 sentinel(차수 19). 실제 길드 ID 는 큰 snowflake 라 0 과 충돌하지 않는다.
         * DM 에서 /provider-join 한 사람들이 이 스코프(byGuild(0))로 하나의 공용 풀을 이루고, DM /ask 는 이 풀로 라우팅된다.
         */
        const val DM_SCOPE = 0L

        const val PRIVACY_NOTICE =
            "이 서버는 커뮤니티 로컬 AI Provider Pool 을 사용합니다. 질문 내용은 요청을 처리하는 " +
                "커뮤니티 프로바이더의 PC 로 전송될 수 있습니다. 비밀번호·API 키·개인정보·비공개 문서 등 " +
                "민감한 정보는 입력하지 마세요."
    }

    private fun lang(ctx: CommandContext): String = policy.guildLanguage(ctx.guildId)

    private fun adminOnly(ctx: CommandContext): Reply? =
        if (!ctx.isAdmin) Replies.reject(Messages.get(Messages.Key.ADMIN_DENIED, lang(ctx))) else null

    // ── 일반 유저 ───────────────────────────────────────────────────────
    fun ask(
        ctx: CommandContext,
        prompt: String,
        requestedModel: String? = null,
        requestedResponseMode: String? = null,
    ): Reply {
        // 요청 우선순위(#150): 관리자/긴급 요청은 분당 쿨다운을 우회한다.
        if (!ctx.isAdmin && !rateLimiter.tryAcquire("ask:${ctx.guildId}:${ctx.userId}")) {
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, lang(ctx))) // 쿨다운 피드백(#191, i18n)
        }
        val effectivePrompt = prompt.composeExecutionPrompt(ctx)
        val routingPolicy = channelRoutingPolicies.effective(ctx.guildId, ctx.channelId, policy.guildDefaultModel(ctx.guildId))
        val modelChoice =
            channelRoutingPolicies.resolveModelChoice(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                requestedModel = requestedModel,
                guildDefaultModel = policy.guildDefaultModel(ctx.guildId),
            )
        val selectedModel =
            modelChoice.selectedModel
                ?: requestedModel?.trim()?.ifBlank { null }
                ?: routingPolicy.preferredModel
        val responseMode = normalizeAskResponseMode(requestedResponseMode) ?: routingPolicy.responseMode
        val result =
            orchestrator.handle(
                AiRequestInput(
                    ctx.guildId,
                    ctx.channelId,
                    ctx.userId,
                    effectivePrompt,
                    ctx.roleIds,
                    isAdmin = ctx.isAdmin,
                    preferredModel = selectedModel,
                    responseMode = responseMode,
                ),
            )
        return when (result.state) {
            RequestState.COMPLETED -> Reply(result.text.orEmpty(), ephemeral = false)
            RequestState.REJECTED -> Replies.reject(result.failReason ?: "요청이 거부되었습니다.")
            else -> Replies.warn(result.failReason ?: "요청을 처리하지 못했습니다.")
        }
    }

    private fun normalizeAskResponseMode(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            null, "" -> null
            "fast", "빠른", "빠른 답변" -> "fast"
            "deep", "깊은", "깊은 답변" -> "deep"
            "saving", "절약", "절약 모드" -> "saving"
            "balanced", "균형", "균형 모드" -> "balanced"
            else -> null
        }

    private fun String.composeExecutionPrompt(ctx: CommandContext): String {
        val behaviorPrompt = channelProfiles.get(ctx.guildId, ctx.channelId)?.let { withChannelAiBehavior(it) } ?: this
        val knowledgeContext =
            runCatching {
                knowledgeSearch.promptContext(
                    guildId = ctx.guildId,
                    query = this,
                    maxChars = 1200,
                    channelId = ctx.channelId,
                )
            }.getOrNull()
        val contextText = knowledgeContext?.contextText?.takeIf { it.isNotBlank() } ?: return behaviorPrompt
        return buildString {
            appendLine("[채널 지식 컨텍스트]")
            appendLine(contextText)
            appendLine()
            appendLine("위 지식은 이 채널에 등록된 참고자료입니다.")
            appendLine("지식과 질문이 충돌하면 확실하지 않다고 말하고, 민감정보는 요구하지 마세요.")
            appendLine()
            appendLine("[질문 실행 입력]")
            append(behaviorPrompt)
        }
    }

    /** 슬래시 옵션 자동완성용 모델 목록(#179). 현재 길드 풀이 제공하는 모델명(중복 제거·정렬). */
    fun autocompleteModels(ctx: CommandContext): List<String> =
        registry
            .byGuild(ctx.guildId)
            .flatMap { it.capability.models }
            .distinct()
            .sorted()
            .take(25) // Discord 자동완성 최대 25개

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
        val ranked = usage.providerContributions(ctx.guildId)
        if (ranked.isEmpty()) return Reply("아직 누적 기여가 없습니다.")
        val lines = ranked.mapIndexed { i, (pid, c) -> "${i + 1}. <@$pid> — ${c}건" }.joinToString("\n")
        return Reply(
            "🏆 커뮤니티 기여 리더보드\n$lines\n\n" +
                "_한 번이라도 기여한 사람은 오프라인이어도 계속 기록됩니다. 기여는 비금전 인정입니다. 고마워요!_",
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

    fun fairness(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        val pool = registry.byGuild(ctx.guildId)
        if (pool.isEmpty()) return Reply("연결된 프로바이더가 없습니다.")
        val counts = pool.map { it.providerId to usage.providerContributionCount(it.providerId) }
        val total = counts.sumOf { it.second }
        val lines =
            counts.sortedByDescending { it.second }.joinToString("\n") { (pid, c) ->
                val pct = if (total > 0) (c * 100 / total) else 0
                "· <@$pid>: ${c}건 ($pct%) · 실패 ${usage.providerFailures(pid)}"
            }
        return Reply("⚖️ 공정성 리포트 (총 ${total}건)\n$lines")
    }

    fun myUsage(ctx: CommandContext): Reply {
        val used = usage.userDailyCount(ctx.guildId, ctx.userId)
        val limit = policy.dailyLimit(ctx.guildId, ctx.roleIds)
        return Reply("오늘 사용량: $used / $limit")
    }

    fun privacy(ctx: CommandContext): Reply = Reply(Messages.get(Messages.Key.PRIVACY_NOTICE, lang(ctx)))

    fun botPermissions(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        return Reply(
            "**냥시스턴트 봇 권한 점검**\n" +
                "@냥시스턴트 질문을 쓰려면 Discord Developer Portal → Bot → " +
                "Privileged Gateway Intents → **Message Content Intent** 를 켜야 합니다.\n" +
                "채널 AI 이름/아이콘으로 답변하려면 서버 초대 권한에 **웹후크 관리(Manage Webhooks)** 가 필요합니다.\n" +
                "기본 슬래시 명령에는 채널 보기, 메시지 보내기, 링크 임베드, 메시지 기록 보기, " +
                "슬래시 명령어 사용 권한을 권장합니다.\n" +
                "권장 Permissions Integer: `2684734528`\n" +
                "문서: `docs/BOT_PERMISSIONS.md`",
        )
    }

    private fun String.withChannelAiBehavior(profile: ChannelAiProfile): String =
        buildString {
            appendLine("[채널 AI 행동 설정]")
            appendLine("이름: ${profile.displayName}")
            appendLine("역할: ${profile.purpose}")
            appendLine("말투: ${profile.tone}")
            appendLine("답변 길이: ${profile.answerLength}")
            appendLine("안전 규칙: ${profile.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION}")
            appendLine()
            appendLine("위 설정을 이 채널의 AI 정체성으로 지키되, 사용자의 질문에만 답하세요.")
            appendLine("민감정보나 비밀키 입력을 유도하지 말고, 모르면 모른다고 말하세요.")
            appendLine()
            appendLine("[사용자 질문]")
            append(this@withChannelAiBehavior)
        }

    /** 종합 도움말(차수 13 #183). 권한별 섹션 노출(#186). */
    fun help(ctx: CommandContext): Reply {
        val sb = StringBuilder()
        sb.append("**커뮤니티 로컬 AI Provider Pool — 도움말**\n")
        sb.append("커뮤니티 멤버들의 PC LLM 을 모아 공정하게 분배합니다(금전 거래 아님).\n\n")
        sb.append("__유저__\n")
        sb.append("· `/ask <질문>` — 풀의 누군가의 PC LLM 으로 답변\n")
        sb.append("· `/models` `/catalog` — 사용 가능한 모델 수준·목록\n")
        sb.append("· `/my-usage` `/privacy` — 내 사용량 / 프라이버시 고지\n")
        sb.append("· `/contributions` — 기여 리더보드(비금전 인정)\n\n")
        sb.append("__프로바이더(내 컴퓨터의 AI로 함께 도와주기)__\n")
        sb.append("· `/provider-join` — 참여 신청(승인 후 토큰→에이전트 실행)\n")
        sb.append("· `/provider-pause` `/provider-resume` `/provider-leave` — 가용성 제어\n")
        sb.append("· `/provider-status` `/provider-models` `/provider-limit` `/provider-scope` — 내 기여 설정\n")
        sb.append("· 봇이 서버에서 제거되면 그 서버의 프로바이더 연결/등록/토큰은 자동 정리됩니다.\n")
        if (ctx.isAdmin) {
            sb.append("\n__관리자__\n")
            sb.append("· `/공정성`(`/fairness`) `/프로바이더목록`(`/providers`) — 공정성 리포트·프로바이더 목록\n")
            sb.append("· `/프로바이더승인`(`/provider-approve`) `/프로바이더제거`(`/provider-remove`) — 승인/제거\n")
            sb.append("· `/채널허용`(`/llm-allow-channel`) `/채널금지`(`/llm-deny-channel`) `/역할정책`(`/llm-role-policy`) — 채널·역할 정책\n")
            sb.append("· `/채널프로필`(`/llm-channel-profile`) — 이 채널에서 보일 AI 답변 이름/아이콘 설정\n")
            sb.append("· `/사용자차단`(`/llm-block`) `/차단해제`(`/llm-unblock`) — 사용자 차단/해제\n")
        }
        sb.append("\n_민감정보(비밀번호·API 키 등)는 입력하지 마세요._")
        return Reply(sb.toString())
    }

    // ── 프로바이더 ──────────────────────────────────────────────────────

    fun setChannelAiProfile(
        ctx: CommandContext,
        name: String?,
        avatarUrl: String?,
        reset: Boolean,
        rollback: Boolean = false,
        purpose: String? = null,
        tone: String? = null,
        answerLength: String? = null,
        constitution: String? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        if (reset) {
            channelProfiles.clear(ctx.guildId, ctx.channelId)
            return Reply("✅ 이 채널의 AI 응답 프로필을 기본 봇 표시로 되돌렸습니다.")
        }
        if (rollback) {
            val profile =
                channelProfiles.rollback(ctx.guildId, ctx.channelId, actorId = ctx.userId)
                    ?: return Reply("현재 이 채널의 AI 응답 프로필은 설정되지 않았습니다.")
            return Reply("↩️ 이 채널 AI 행동 설정을 v${profile.version}(으)로 롤백했습니다. 현재 이름: **${profile.displayName}**")
        }
        val displayName = name?.trim().orEmpty()
        if (displayName.isBlank()) {
            val current = channelProfiles.get(ctx.guildId, ctx.channelId)
            return if (current == null) {
                Reply("현재 이 채널의 AI 응답 프로필은 설정되지 않았습니다. `name` 옵션으로 설정하세요.")
            } else {
                val constitutionText = current.constitution ?: "기본 안전 규칙"
                Reply(
                    "현재 이 채널 AI: **${current.displayName}**\n" +
                        "행동 버전: v${current.version}\n" +
                        "역할: `${current.purpose}` · 말투: `${current.tone}` · 길이: `${current.answerLength}`\n" +
                        "헌법: $constitutionText",
                )
            }
        }
        val profile =
            channelProfiles.set(
                ctx.guildId,
                ctx.channelId,
                displayName,
                avatarUrl,
                actorId = ctx.userId,
                purpose = purpose,
                tone = tone,
                answerLength = answerLength,
                constitution = constitution,
            )
        val avatarLine = if (profile.avatarUrl.isNullOrBlank()) "" else "아이콘 이미지도 함께 설정했습니다.\n"
        return Reply(
            "✅ 이 채널 AI를 **${profile.displayName}**(으)로 설정했습니다.\n" +
                avatarLine +
                "행동 버전: v${profile.version}\n" +
                "역할: `${profile.purpose}` · 말투: `${profile.tone}` · 길이: `${profile.answerLength}`\n" +
                "이후 `/ask` 답변은 이 채널에서 그 이름으로 보입니다. 봇에 `웹후크 관리` 권한이 필요해요.",
        )
    }

    fun providerJoin(ctx: CommandContext): Reply {
        // DM 글로벌 풀은 승인할 관리자가 없으므로 자동 승인(본인 PC 를 자발적으로 기여). 길드는 기존 정책대로.
        val auto = ctx.guildId == DM_SCOPE || policy.isAutoApprove(ctx.guildId)
        val r = registration.requestJoin(ctx.userId, ctx.guildId, autoApprove = auto)
        return if (r.token != null) {
            Reply(ProviderOnboarding.message(r.token, relayPublicUrl), ephemeral = true)
        } else {
            Reply("📋 등록 요청이 접수되었습니다(${r.state}). 관리자 승인을 기다려 주세요.")
        }
    }

    /**
     * OS 선택(버튼) 후 설치 가이드(차수 19). 등록(멱등) 후 토큰을 발급해 그 OS 의 복붙 명령을 반환한다.
     * 수동 승인 길드에서 아직 미승인이면 승인 대기를 안내(승인 후 DM 으로 안내).
     */
    fun providerInstallGuide(
        ctx: CommandContext,
        os: String,
    ): Reply {
        val auto = ctx.guildId == DM_SCOPE || policy.isAutoApprove(ctx.guildId)
        val join = registration.requestJoin(ctx.userId, ctx.guildId, autoApprove = auto)
        val token = join.token ?: registration.reissueToken(ctx.userId, ctx.guildId)
        return if (token != null) {
            Reply(ProviderOnboarding.installCommand(os, token, relayPublicUrl), ephemeral = true)
        } else {
            Reply("📋 등록 요청이 접수되었습니다(${join.state}). 관리자 승인 후 DM 으로 설치 안내를 보냅니다.", ephemeral = true)
        }
    }

    fun providerPause(ctx: CommandContext): Reply =
        if (protection.pause(ctx.userId, ctx.guildId)) Reply("⏸️ 일시정지했습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerResume(ctx: CommandContext): Reply =
        if (protection.resume(ctx.userId, ctx.guildId)) Reply("▶️ 재개했습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerLeave(ctx: CommandContext): Reply =
        if (protection.leave(ctx.userId, ctx.guildId)) Reply("👋 풀에서 나갔습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerStatus(ctx: CommandContext): Reply {
        val s = registry.byProvider(ctx.guildId, ctx.userId) ?: return Reply("연결 상태: 오프라인")
        val queued = s.queueDepth().let { if (it > 0) " · 대기 $it" else "" }
        val base = "상태: ${s.state} · 처리중 ${s.activeRequests}$queued · 일일잔여 ${s.remainingDailyRequests} · 실패 ${s.failures}"
        val hint = RestHint.forStatus(s.state, s.activeRequests, s.remainingDailyRequests)
        return Reply(if (hint != null) "$base\n$hint" else base)
    }

    fun providerModels(
        ctx: CommandContext,
        models: List<String>,
    ): Reply {
        contributionPolicy.setModels(ctx.userId, models, ModelBurden.STANDARD)
        return Reply("✅ 제공 모델 설정: ${models.joinToString(", ")}")
    }

    fun providerLimit(
        ctx: CommandContext,
        model: String,
        daily: Int,
        concurrency: Int,
        seconds: Int,
    ): Reply {
        contributionPolicy.setLimit(ctx.userId, model, daily, concurrency, seconds)
        return Reply("✅ `$model` 한도: 하루 $daily · 동시 $concurrency · 최대 ${seconds}초")
    }

    fun providerScope(
        ctx: CommandContext,
        model: String,
        role: String,
    ): Reply {
        contributionPolicy.setScope(ctx.userId, model, role)
        return Reply("✅ `$model` 허용 범위: $role")
    }

    /** 가용 시간대 스케줄 설정(차수 12 #159). UTC 시 0~23, from==to 면 24시간. */
    fun providerSchedule(
        ctx: CommandContext,
        fromHour: Int,
        toHour: Int,
    ): Reply {
        if (fromHour !in 0..23 || toHour !in 0..23) return Replies.warn("시(hour)는 0~23 사이여야 합니다.")
        schedule.setSchedule(ctx.userId, ctx.guildId, fromHour, toHour)
        val span = if (fromHour == toHour) "24시간 가용" else "${fromHour}시~${toHour}시(UTC)"
        return Replies.ok("가용 시간대 설정: $span. 시간 밖에는 자동으로 일시정지됩니다.")
    }

    // ── 관리자 ──────────────────────────────────────────────────────────

    /** 길드 기본 모델/언어 설정(차수 11 #146). 빈 값은 변경하지 않음. */
    fun setGuildDefaults(
        ctx: CommandContext,
        defaultModel: String?,
        language: String?,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        policy.setGuildDefaults(ctx.guildId, defaultModel, language, ctx.userId)
        val m = policy.guildDefaultModel(ctx.guildId) ?: "(자동 선택)"
        return Reply("✅ 길드 기본값 — 모델: `$m` · 언어: `${policy.guildLanguage(ctx.guildId)}`")
    }

    /** 자동 승인 토글(차수 13 #147/#180, 설정 패널 버튼). */
    fun toggleAutoApprove(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        val now = !policy.isAutoApprove(ctx.guildId)
        policy.setAutoApprove(ctx.guildId, now, ctx.userId)
        return Replies.ok("프로바이더 자동 승인: ${if (now) "켜짐" else "꺼짐"}")
    }

    /** 자동 승인 켜기/끄기(명시적). 패널 버튼용. */
    fun setAutoApprove(
        ctx: CommandContext,
        enabled: Boolean,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        policy.setAutoApprove(ctx.guildId, enabled, ctx.userId)
        return if (enabled) {
            Replies.ok("프로바이더 **자동 승인** — 이제 `/provider-join` 한 사람은 관리자 승인 없이 바로 참여합니다.")
        } else {
            Replies.ok("프로바이더 **수동 승인** — `/provider-join` 신청 후 관리자가 `/approve-provider` 해야 참여합니다.")
        }
    }

    /** 현재 자동 승인 상태(패널 표시용). */
    fun isAutoApprove(ctx: CommandContext): Boolean = policy.isAutoApprove(ctx.guildId)

    /** 모든 채널에서 LLM 사용 허용(채널 제한 해제). */
    fun allowAllChannels(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        policy.allowAllChannels(ctx.guildId, ctx.userId)
        return Replies.ok("이제 **모든 채널**에서 `/ask` 를 쓸 수 있습니다(채널 제한 해제).")
    }

    /** 현재 허용 채널 목록(패널 표시용). 비면 전체 허용. */
    fun allowedChannelIds(ctx: CommandContext): List<Long> = policy.allowedChannelIds(ctx.guildId)

    /** 설정 패널에서 임시 선택한 서버 언어/기본 모델/허용 채널/자동승인을 저장 버튼 한 번으로 적용한다. */
    fun saveGuildSettings(
        ctx: CommandContext,
        language: String?,
        defaultModel: String?,
        allowedChannelIds: Collection<Long>?,
        autoApprove: Boolean? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        when (defaultModel) {
            "__auto__" -> {
                policy.clearGuildDefaultModel(ctx.guildId, ctx.userId)
                language?.takeIf { it.isNotBlank() }?.let { policy.setGuildDefaults(ctx.guildId, null, it, ctx.userId) }
            }
            null -> language?.takeIf { it.isNotBlank() }?.let { policy.setGuildDefaults(ctx.guildId, null, it, ctx.userId) }
            else -> policy.setGuildDefaults(ctx.guildId, defaultModel, language, ctx.userId)
        }
        allowedChannelIds?.let { policy.replaceAllowedChannels(ctx.guildId, it, ctx.userId) }
        autoApprove?.let { policy.setAutoApprove(ctx.guildId, it, ctx.userId) }

        val model = policy.guildDefaultModel(ctx.guildId) ?: "자동 선택"
        val lang = policy.guildLanguage(ctx.guildId)
        val channels = policy.allowedChannelIds(ctx.guildId)
        val channelText = if (channels.isEmpty()) "모든 채널" else channels.joinToString(" ") { "<#$it>" }
        val autoApproveText = if (policy.isAutoApprove(ctx.guildId)) "켜짐" else "꺼짐"
        return Replies.ok(
            "서버 설정을 저장했습니다.\n" +
                "언어: `$lang`\n" +
                "기본 모델: `$model`\n" +
                "LLM 사용 채널: $channelText\n" +
                "자동 승인: `$autoApproveText`",
        )
    }

    /** 풀이 현재 제공하는 모델 목록(패널 표시용). */
    fun poolModels(ctx: CommandContext): List<String> = autocompleteModels(ctx)

    /** 길드 언어(패널 표시용). */
    fun guildLanguage(ctx: CommandContext): String = policy.guildLanguage(ctx.guildId)

    /** 길드 기본 모델(패널 표시용, 미설정 시 null). */
    fun guildDefaultModel(ctx: CommandContext): String? = policy.guildDefaultModel(ctx.guildId)

    /** 길드 환영/안내 메시지 설정(차수 12 #174, 관리자). */
    fun setWelcome(
        ctx: CommandContext,
        message: String,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        policy.setWelcomeMessage(ctx.guildId, message, ctx.userId)
        return Replies.ok("환영 메시지를 설정했습니다.")
    }

    /** 환영/안내 메시지 보기(누구나). */
    fun welcome(ctx: CommandContext): Reply {
        val msg =
            policy.guildWelcomeMessage(ctx.guildId)
                ?: return Replies.info("이 서버는 아직 환영 메시지를 설정하지 않았습니다. `/help` 로 사용법을 확인하세요.")
        return Reply("👋 $msg", ephemeral = false)
    }

    fun allowChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        policy.allowChannel(ctx.guildId, channelId, ctx.userId)
        return Reply("✅ 채널 <#$channelId> 에서 LLM 사용을 허용했습니다.")
    }

    fun denyChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        policy.denyChannel(ctx.guildId, channelId, ctx.userId)
        return Reply("🚫 채널 <#$channelId> 에서 LLM 사용을 금지했습니다.")
    }

    fun setRolePolicy(
        ctx: CommandContext,
        roleId: Long,
        maxBurden: ModelBurden,
        dailyLimit: Int,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        policy.setRolePolicy(ctx.guildId, roleId, maxBurden, dailyLimit, ctx.userId)
        return Reply("✅ 역할 <@&$roleId> 정책: 최대 $maxBurden, 하루 $dailyLimit 회")
    }

    fun approveProvider(
        ctx: CommandContext,
        providerUserId: Long,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val token =
            registration.approve(providerUserId, ctx.guildId, ctx.userId)
                ?: return Reply("승인할 대기 중 프로바이더가 없습니다.")
        // 승인 안내(온보딩 가이드) — DiscordBot 이 이 내용을 대상 유저에게 DM 으로도 보낸다(#162).
        return Reply(ProviderOnboarding.message(token, relayPublicUrl), ephemeral = true)
    }

    fun removeProvider(
        ctx: CommandContext,
        providerUserId: Long,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return if (registration.remove(providerUserId, ctx.guildId, ctx.userId)) {
            protection.leave(providerUserId, ctx.guildId)
            Reply("🗑️ <@$providerUserId> 를 풀에서 제거했습니다.")
        } else {
            Reply("해당 프로바이더를 찾을 수 없습니다.")
        }
    }

    fun blockUser(
        ctx: CommandContext,
        targetUserId: Long,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        blocklist.block(ctx.guildId, targetUserId, ctx.userId)
        return Reply("🚫 <@$targetUserId> 를 차단했습니다.")
    }

    fun unblockUser(
        ctx: CommandContext,
        targetUserId: Long,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        blocklist.unblock(ctx.guildId, targetUserId, ctx.userId)
        return Reply("✅ <@$targetUserId> 차단을 해제했습니다.")
    }

    fun providers(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        val pending = registration.pending(ctx.guildId)
        val pool = registry.byGuild(ctx.guildId)
        val fairness =
            pool.joinToString("\n") {
                "· provider #${it.providerId}: 기여 ${usage.providerContributionCount(it.providerId)}회 · ${it.state}"
            }
        return Reply("승인 대기: ${pending.size} · 온라인: ${pool.size}\n$fairness\n대기 목록: $pending")
    }
}
