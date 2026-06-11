package com.discordassistant.central.platform.discord

import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.onboarding.application.GuildOnboardingResult
import com.discordassistant.central.onboarding.application.GuildOnboardingService
import com.discordassistant.central.provider.application.ContributionPolicyService
import com.discordassistant.central.provider.application.ProviderProtectionService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Service

/** 슬래시 명령 응답(내용 + ephemeral 여부). */
data class Reply(
    val content: String,
    val ephemeral: Boolean = true,
    val pseudoStream: ReplyPseudoStream? = null,
    val feedback: ReplyFeedback? = null,
    val imagePng: ByteArray? = null,
)

/** Discord 긴 답변을 여러 번 수정해 보여주기 위한 의사 스트리밍 계획. */
data class ReplyPseudoStream(
    val editIntervalMs: Long,
    val snapshots: List<String>,
    val warning: String? = null,
)

/** 공개 AI 답변 아래에 붙는 품질 피드백 메타데이터. */
data class ReplyFeedback(
    val requestId: String,
)

/** 명령 호출 컨텍스트(JDA 이벤트에서 추출). */
data class CommandContext(
    val guildId: Long,
    val channelId: Long,
    val userId: Long,
    val roleIds: Set<Long>,
    val isAdmin: Boolean,
    /**
     * 요청자의 Discord 클라이언트 언어를 지원 언어 코드(ko/en/ja)로 정규화한 값. 미지원 로케일이면 null.
     * 응답 언어는 이 값을 우선하고, 없으면 길드 기본 언어로 폴백한다.
     */
    val userLang: String? = null,
)

/** `/ai-onboard` 시작 결과: 제안 카드용 draft 가 만들어졌거나(Started), 권한/기능 게이트로 거부됨(Rejected). */
sealed interface OnboardingStartOutcome {
    data class Started(
        val result: GuildOnboardingResult,
    ) : OnboardingStartOutcome

    data class Rejected(
        val reply: Reply,
    ) : OnboardingStartOutcome
}

/**
 * 슬래시 명령 비즈니스 로직의 얇은 파사드(god class 분해 완료). 실제 로직은 명령군별 핸들러(@Component)에 있고,
 * 이 클래스는 public 시그니처를 그대로 유지하며 한 줄 위임만 한다(호출자 무수정). JDA 이벤트와 분리된 순수
 * 위임층이라 단위 테스트 가능하며, JDA 리스너는 이벤트→CommandContext 변환만 담당한다.
 */
@Service
class CommandService(
    private val registration: ProviderRegistrationService,
    private val protection: ProviderProtectionService,
    private val policy: PolicyService,
    private val registry: ConnectionRegistry,
    private val contributionPolicy: ContributionPolicyService,
    private val schedule: com.discordassistant.central.provider.application.ProviderScheduleService,
    private val providerCommands: ProviderSelfServiceCommands =
        ProviderSelfServiceCommands(registration, protection, policy, registry, contributionPolicy, schedule, ""),
    // god class 분해: 명령군별 핸들러. 시그니처 유지·위임(동작 불변).
    private val infoCommands: com.discordassistant.central.platform.discord.command.InfoCommandHandler,
    private val aiNetworkCommands: com.discordassistant.central.platform.discord.command.AiNetworkCommandHandler,
    private val multiResponseCommands: com.discordassistant.central.platform.discord.command.MultiResponseCommandHandler,
    private val knowledgeCommands: com.discordassistant.central.platform.discord.command.KnowledgeCommandHandler,
    private val presetCommands: com.discordassistant.central.platform.discord.command.PresetCommandHandler,
    private val askCommands: com.discordassistant.central.platform.discord.command.AskCommandHandler,
    private val channelAiIdentityCommands: com.discordassistant.central.platform.discord.command.ChannelAiIdentityCommandHandler,
    private val guildOnboardingCommands: com.discordassistant.central.platform.discord.command.GuildOnboardingCommandHandler,
    private val guildAdminCommands: com.discordassistant.central.platform.discord.command.GuildAdminCommandHandler,
    private val freeAskRateLimiter: com.discordassistant.central.quota.application.FreeAskRateLimiter,
) {
    companion object {
        /**
         * DM(길드 없음) 컨텍스트의 guildId sentinel. 실제 길드 ID 는 큰 snowflake 라 0 과 충돌하지 않는다.
         * DM 은 **읽기/안내 명령 전용** — AI 호출(/ask)·기여(/provider-join)는 길드 전용(멤버십 게이트)이라
         * 이 스코프로 라우팅되거나 풀에 등록되지 않는다(예전 DM 글로벌 풀(차수 19)은 폐지).
         */
        const val DM_SCOPE = 0L

        const val PRIVACY_NOTICE =
            "이 서버는 커뮤니티 AI 네트워크를 사용합니다. 질문 내용은 요청을 처리하는 " +
                "커뮤니티 프로바이더의 PC 로 전송될 수 있습니다. 비밀번호·API 키·개인정보·비공개 문서 등 " +
                "민감한 정보는 입력하지 마세요."
    }

    // ── 일반 유저 ───────────────────────────────────────────────────────
    fun ask(
        ctx: CommandContext,
        prompt: String,
        requestedModel: String? = null,
        requestedResponseMode: String? = null,
        webSearch: Boolean = false,
    ): Reply = askCommands.ask(ctx, prompt, requestedModel, requestedResponseMode, webSearch)

    /**
     * /무료질문(free-ask) — 관리자 클라우드 AI(Gemini) 고정 모델로 라우팅하되, **인당 rate limit**(시간당·일일)을
     * 먼저 적용한다(무료 자원 남용 방지). 한도 초과면 거부 문구, 통과면 일반 ask 경로.
     */
    fun freeAsk(
        ctx: CommandContext,
        prompt: String,
        model: String,
    ): Reply {
        freeAskRateLimiter.check(ctx.userId)?.let { return Replies.reject(it) }
        return ask(ctx, prompt, requestedModel = model)
    }

    /** /그림(imagine) — 이미지 생성 가능한 프로바이더의 로컬 ComfyUI(Anima)로 이미지를 만든다. */
    fun imagine(
        ctx: CommandContext,
        prompt: String,
        onStart: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Reply = askCommands.imagine(ctx, prompt, onStart, onProgress)

    /** 취소 버튼 → 진행 중 이미지 생성 중단. */
    fun cancelImage(requestId: String): Boolean = askCommands.cancelImage(requestId)

    fun submitAskFeedback(
        ctx: CommandContext,
        requestId: String,
        rating: Int,
        feedbackType: String,
        reason: String? = null,
    ): Reply = askCommands.submitAskFeedback(ctx, requestId, rating, feedbackType, reason)

    /** 슬래시 옵션 자동완성용 모델 목록(#179). 현재 길드 풀이 제공하는 모델명(중복 제거·정렬). */
    fun autocompleteModels(ctx: CommandContext): List<String> = askCommands.autocompleteModels(ctx)

    fun models(ctx: CommandContext): Reply = infoCommands.models(ctx)

    fun catalog(ctx: CommandContext): Reply = infoCommands.catalog(ctx)

    fun contributions(ctx: CommandContext): Reply = infoCommands.contributions(ctx)

    /** 익명 커뮤니티 기여 통계(차수 12 #177). 개별 식별정보 없이 집계만 공개. */
    fun communityStats(ctx: CommandContext): Reply = infoCommands.communityStats(ctx)

    fun fairness(ctx: CommandContext): Reply = guildAdminCommands.fairness(ctx)

    fun myUsage(ctx: CommandContext): Reply = infoCommands.myUsage(ctx)

    fun license(ctx: CommandContext): Reply = infoCommands.license(ctx)

    fun privacy(ctx: CommandContext): Reply = infoCommands.privacy(ctx)

    fun botPermissions(ctx: CommandContext): Reply = infoCommands.botPermissions(ctx)

    /**
     * 종합 도움말(차수 13 #183). 권한별 섹션 노출(#186).
     * 명령 표기는 보는 사람의 클라이언트 로케일로 표시 — 슬래시 메뉴에 보이는 이름과 일치(차수 19 UX).
     * 예) 한국어 클라이언트: `/질문` `/프로바이더참여`, 영어: `/ask` `/provider-join`.
     */
    fun help(
        ctx: CommandContext,
        locale: net.dv8tion.jda.api.interactions.DiscordLocale = net.dv8tion.jda.api.interactions.DiscordLocale.KOREAN,
    ): Reply = infoCommands.help(ctx, locale)

    fun knowledgeList(
        ctx: CommandContext,
        spaceId: Long? = null,
    ): Reply = knowledgeCommands.knowledgeList(ctx, spaceId)

    fun addKnowledge(
        ctx: CommandContext,
        title: String,
        sourceType: String?,
        sourceUri: String?,
        contentPreview: String?,
        spaceId: Long? = null,
    ): Reply = knowledgeCommands.addKnowledge(ctx, title, sourceType, sourceUri, contentPreview, spaceId)

    fun searchKnowledge(
        ctx: CommandContext,
        query: String,
        spaceId: Long? = null,
        limit: Int = 5,
    ): Reply = knowledgeCommands.searchKnowledge(ctx, query, spaceId, limit)

    fun knowledgeIndexPlan(
        ctx: CommandContext,
        spaceId: Long? = null,
        force: Boolean = false,
    ): Reply = knowledgeCommands.knowledgeIndexPlan(ctx, spaceId, force)

    fun knowledgeIndexJobs(
        ctx: CommandContext,
        spaceId: Long? = null,
        limit: Int = 10,
    ): Reply = knowledgeCommands.knowledgeIndexJobs(ctx, spaceId, limit)

    fun completeKnowledgeIndexJob(
        ctx: CommandContext,
        jobId: Long,
        status: String = "completed",
        reason: String? = null,
    ): Reply = knowledgeCommands.completeKnowledgeIndexJob(ctx, jobId, status, reason)

    fun approveKnowledge(
        ctx: CommandContext,
        spaceId: Long,
        sourceId: Long,
        reason: String = "approved from Discord",
    ): Reply = knowledgeCommands.approveKnowledge(ctx, spaceId, sourceId, reason)

    fun deleteKnowledge(
        ctx: CommandContext,
        spaceId: Long,
        sourceId: Long,
        reason: String = "deleted from Discord",
    ): Reply = knowledgeCommands.deleteKnowledge(ctx, spaceId, sourceId, reason)

    fun presetCatalog(
        ctx: CommandContext,
        query: String? = null,
        category: String? = null,
    ): Reply = presetCommands.presetCatalog(ctx, query, category)

    fun importPresetToCurrentChannel(
        ctx: CommandContext,
        publishedPresetId: Long,
        confirmConflicts: Boolean = false,
    ): Reply = presetCommands.importPresetToCurrentChannel(ctx, publishedPresetId, confirmConflicts)

    fun likePreset(
        ctx: CommandContext,
        publishedPresetId: Long,
    ): Reply = presetCommands.likePreset(ctx, publishedPresetId)

    fun reportPreset(
        ctx: CommandContext,
        publishedPresetId: Long,
        reason: String,
    ): Reply = presetCommands.reportPreset(ctx, publishedPresetId, reason)

    fun presetModeration(ctx: CommandContext): Reply = presetCommands.presetModeration(ctx)

    fun reviewPresetReport(
        ctx: CommandContext,
        reportId: Long,
        decision: String,
    ): Reply = presetCommands.reviewPresetReport(ctx, reportId, decision)

    fun multiResponseStatus(
        ctx: CommandContext,
        channelId: Long? = null,
    ): Reply = multiResponseCommands.multiResponseStatus(ctx, channelId)

    fun setMultiResponsePolicy(
        ctx: CommandContext,
        channelId: Long? = null,
        mode: String = "single",
        maxCandidates: Int = 1,
        synthesisEnabled: Boolean = false,
        requireDistinctModels: Boolean = false,
        timeoutSeconds: Int = 120,
    ): Reply =
        multiResponseCommands.setMultiResponsePolicy(
            ctx,
            channelId,
            mode,
            maxCandidates,
            synthesisEnabled,
            requireDistinctModels,
            timeoutSeconds,
        )

    fun multiResponseDryRun(
        ctx: CommandContext,
        prompt: String,
        channelId: Long? = null,
        responseMode: String? = null,
    ): Reply = multiResponseCommands.multiResponseDryRun(ctx, prompt, channelId, responseMode)

    fun niaAffinity(ctx: CommandContext): Reply = aiNetworkCommands.niaAffinity(ctx)

    fun aiNetworkMap(ctx: CommandContext): Reply = aiNetworkCommands.aiNetworkMap(ctx)

    fun aiNetworkCheck(ctx: CommandContext): Reply = aiNetworkCommands.aiNetworkCheck(ctx)

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
    ): Reply =
        channelAiIdentityCommands.setChannelAiProfile(
            ctx,
            name,
            avatarUrl,
            reset,
            rollback,
            purpose,
            tone,
            answerLength,
            constitution,
        )

    // ── 서버 AI 자동 온보딩(Phase 1) ─────────────────────────────────────

    /**
     * `/ai-onboard` 또는 입장 배너 버튼 → 동의 기록 + 휴리스틱 draft + PENDING 제안 생성.
     * 성공하면 [OnboardingStartOutcome.Started](제안 카드용 데이터), 권한/기능 게이트 실패하면 [OnboardingStartOutcome.Rejected].
     */
    fun startAutoOnboarding(
        ctx: CommandContext,
        channelName: String? = null,
        channelWhitelist: Set<Long> = emptySet(),
        historyLimit: Int = 0,
        backfill: GuildOnboardingService.BackfillInput? = null,
    ): OnboardingStartOutcome = guildOnboardingCommands.startAutoOnboarding(ctx, channelName, channelWhitelist, historyLimit, backfill)

    fun approveOnboarding(
        ctx: CommandContext,
        proposalId: Long,
    ): Reply = guildOnboardingCommands.approveOnboarding(ctx, proposalId)

    fun rejectOnboarding(
        ctx: CommandContext,
        proposalId: Long,
    ): Reply = guildOnboardingCommands.rejectOnboarding(ctx, proposalId)

    /**
     * `/ai-onboard-optout` — 누구나 **본인에 한해** 자신의 메시지를 자동 온보딩 백필 RAG 색인에서 제외/해제한다(관리자 권한 불필요).
     * [enable] = true 면 제외 등록, false 면 해제, null 이면 현재 상태를 토글한다. 길드 단위로 격리된다.
     * 이미 색인된 과거 데이터는 row 삭제(소스 삭제)로 잊을 수 있고, 이 설정은 이후 백필부터 본인 메시지를 색인하지 않게 한다.
     */
    fun setOnboardingOptOut(
        ctx: CommandContext,
        enable: Boolean? = null,
    ): Reply = guildOnboardingCommands.setOnboardingOptOut(ctx, enable)

    // ── 채널 AI 자유 지침(custom instruction) ────────────────────────────

    /**
     * `/ai-instruction` — 이 채널 AI에 자연어 자유 지침을 추가/수정한다.
     * text 가 비어 있으면 현재 지침을 확인만 한다. text 가 있으면 활성 behavior 를 베이스로
     * customInstruction 만 교체한 **새 behavior 버전 제안**을 만든다(위험 지침은 승인 큐로 강제).
     */
    fun setChannelAiInstruction(
        ctx: CommandContext,
        text: String?,
    ): Reply = channelAiIdentityCommands.setChannelAiInstruction(ctx, text)

    // 프로바이더 본인 self-service 명령은 ProviderSelfServiceCommands 로 분리(god class 축소). 시그니처 유지·위임.
    fun providerJoin(ctx: CommandContext): Reply = providerCommands.providerJoin(ctx)

    /** 이 사용자가 ‘연동됨’(앱 연결됨)인가 — DiscordBot 이 가이드 vs 자동참여를 분기하는 데 사용. */
    fun providerLinked(ctx: CommandContext): Boolean = providerCommands.providerLinked(ctx)

    fun providerInstallGuide(
        ctx: CommandContext,
        os: String,
    ): Reply = providerCommands.providerInstallGuide(ctx, os)

    fun providerPause(ctx: CommandContext): Reply = providerCommands.providerPause(ctx)

    fun providerResume(ctx: CommandContext): Reply = providerCommands.providerResume(ctx)

    fun providerLeave(ctx: CommandContext): Reply = providerCommands.providerLeave(ctx)

    fun providerStatus(ctx: CommandContext): Reply = providerCommands.providerStatus(ctx)

    fun providerModels(
        ctx: CommandContext,
        models: List<String>,
    ): Reply = providerCommands.providerModels(ctx, models)

    fun providerLimit(
        ctx: CommandContext,
        model: String,
        daily: Int,
        concurrency: Int,
        seconds: Int,
    ): Reply = providerCommands.providerLimit(ctx, model, daily, concurrency, seconds)

    fun providerSchedule(
        ctx: CommandContext,
        fromHour: Int,
        toHour: Int,
    ): Reply = providerCommands.providerSchedule(ctx, fromHour, toHour)

    // ── 관리자 ──────────────────────────────────────────────────────────

    /** 길드 기본 모델/언어/유저 일일 한도 설정(차수 11 #146). 빈/null 값은 변경하지 않음(dailyLimit 0=무제한). */
    fun setGuildDefaults(
        ctx: CommandContext,
        defaultModel: String?,
        language: String?,
        userDailyLimit: Int? = null,
    ): Reply = guildAdminCommands.setGuildDefaults(ctx, defaultModel, language, userDailyLimit)

    /** 자동 승인 토글(차수 13 #147/#180, 설정 패널 버튼). */
    fun toggleAutoApprove(ctx: CommandContext): Reply = guildAdminCommands.toggleAutoApprove(ctx)

    /** 자동 승인 켜기/끄기(명시적). 패널 버튼용. */
    fun setAutoApprove(
        ctx: CommandContext,
        enabled: Boolean,
    ): Reply = guildAdminCommands.setAutoApprove(ctx, enabled)

    /** 현재 자동 승인 상태(패널 표시용). */
    fun isAutoApprove(ctx: CommandContext): Boolean = guildAdminCommands.isAutoApprove(ctx)

    /** 모든 채널에서 LLM 사용 허용(채널 제한 해제). */
    fun allowAllChannels(ctx: CommandContext): Reply = guildAdminCommands.allowAllChannels(ctx)

    /** 현재 허용 채널 목록(패널 표시용). 비면 전체 허용. */
    fun allowedChannelIds(ctx: CommandContext): List<Long> = guildAdminCommands.allowedChannelIds(ctx)

    /** 설정 패널에서 임시 선택한 서버 언어/기본 모델/허용 채널/자동승인을 저장 버튼 한 번으로 적용한다. */
    fun saveGuildSettings(
        ctx: CommandContext,
        language: String?,
        defaultModel: String?,
        allowedChannelIds: Collection<Long>?,
        autoApprove: Boolean? = null,
    ): Reply = guildAdminCommands.saveGuildSettings(ctx, language, defaultModel, allowedChannelIds, autoApprove)

    /** 풀이 현재 제공하는 모델 목록(패널 표시용). */
    fun poolModels(ctx: CommandContext): List<String> = guildAdminCommands.poolModels(ctx)

    /** 길드 언어(패널 표시용). */
    fun guildLanguage(ctx: CommandContext): String = guildAdminCommands.guildLanguage(ctx)

    /** 길드 기본 모델(패널 표시용, 미설정 시 null). */
    fun guildDefaultModel(ctx: CommandContext): String? = guildAdminCommands.guildDefaultModel(ctx)

    /** 길드 환영/안내 메시지 설정(차수 12 #174, 관리자). */
    fun setWelcome(
        ctx: CommandContext,
        message: String,
    ): Reply = guildAdminCommands.setWelcome(ctx, message)

    /** 환영/안내 메시지 보기(누구나). */
    fun welcome(ctx: CommandContext): Reply = guildAdminCommands.welcome(ctx)

    fun allowChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply = guildAdminCommands.allowChannel(ctx, channelId)

    fun denyChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply = guildAdminCommands.denyChannel(ctx, channelId)

    /** /그림채널 — ComfyUI 웹 생성 이미지를 전송할 채널 설정(전문가 층). */
    fun setForwardChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply = guildAdminCommands.setForwardChannel(ctx, channelId)

    fun setRolePolicy(
        ctx: CommandContext,
        roleId: Long,
        maxBurden: ModelBurden,
        dailyLimit: Int,
    ): Reply = guildAdminCommands.setRolePolicy(ctx, roleId, maxBurden, dailyLimit)

    fun approveProvider(
        ctx: CommandContext,
        providerUserId: Long,
    ): Reply = guildAdminCommands.approveProvider(ctx, providerUserId)

    fun removeProvider(
        ctx: CommandContext,
        providerUserId: Long,
    ): Reply = guildAdminCommands.removeProvider(ctx, providerUserId)

    fun blockUser(
        ctx: CommandContext,
        targetUserId: Long,
    ): Reply = guildAdminCommands.blockUser(ctx, targetUserId)

    fun unblockUser(
        ctx: CommandContext,
        targetUserId: Long,
    ): Reply = guildAdminCommands.unblockUser(ctx, targetUserId)

    fun providers(ctx: CommandContext): Reply = guildAdminCommands.providers(ctx)
}
