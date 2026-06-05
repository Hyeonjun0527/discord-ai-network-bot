package com.discordassistant.central.platform.discord

import com.discordassistant.central.ainetwork.application.AiQualityFeedbackService
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.ainetwork.application.ModelChoiceDecision
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.channelai.application.ChannelAiProfile
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.guild.application.PrivacyService
import com.discordassistant.central.knowledge.application.KnowledgeSearchService
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.onboarding.application.GuildOnboardingResult
import com.discordassistant.central.onboarding.application.GuildOnboardingService
import com.discordassistant.central.onboarding.application.OnboardingAnalysisContext
import com.discordassistant.central.provider.application.ContributionPolicyService
import com.discordassistant.central.provider.application.ProviderProtectionService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.quota.application.RateLimiter
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.UsageService
import com.discordassistant.central.routing.application.RequestOrchestrator
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestState
import com.discordassistant.central.shared.ResponseMode
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
     * 응답 언어는 이 값을 우선하고, 없으면 길드 기본 언어로 폴백한다([CommandService.lang]).
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
    private val blocklist: com.discordassistant.central.quota.application.BlocklistService,
    private val schedule: com.discordassistant.central.provider.application.ProviderScheduleService,
    private val channelProfiles: ChannelAiProfileService,
    private val channelAiCustomization: ChannelAiCustomizationService,
    private val channelRoutingPolicies: ChannelAiRoutingPolicyService,
    private val knowledgeSearch: KnowledgeSearchService,
    private val multiResponse: MultiResponseService,
    private val qualityFeedback: AiQualityFeedbackService,
    @param:org.springframework.beans.factory.annotation.Value("\${central.relay.public-url:}")
    private val relayPublicUrl: String = "",
    private val webSearchAugmenter: com.discordassistant.central.knowledge.application.WebSearchAugmenter =
        com.discordassistant.central.knowledge.application.NoWebSearch,
    private val providerCommands: ProviderSelfServiceCommands =
        ProviderSelfServiceCommands(registration, protection, policy, registry, contributionPolicy, schedule, ""),
    private val guildOnboarding: GuildOnboardingService,
    private val onboardingOptOuts: com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository,
    // god class 분해: 명령군별 핸들러(읽기/단일협력자 위주). 시그니처 유지·위임(동작 불변).
    private val infoCommands: com.discordassistant.central.platform.discord.command.InfoCommandHandler,
    private val aiNetworkCommands: com.discordassistant.central.platform.discord.command.AiNetworkCommandHandler,
    private val multiResponseCommands: com.discordassistant.central.platform.discord.command.MultiResponseCommandHandler,
    private val knowledgeCommands: com.discordassistant.central.platform.discord.command.KnowledgeCommandHandler,
    private val presetCommands: com.discordassistant.central.platform.discord.command.PresetCommandHandler,
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

        private const val DISCORD_REPLY_SAFE_LIMIT = 1850
        private const val PSEUDO_STREAM_MIN_CHARS = 600
        private val PSEUDO_STREAM_STEPS = listOf(33, 66, 100)
    }

    // 응답 언어: 요청자 Discord 언어(ko/en/ja) 우선 → 없으면 길드 기본 언어. (유저 로케일 우선 정책)
    private fun lang(ctx: CommandContext): String = ctx.userLang ?: policy.guildLanguage(ctx.guildId)

    private fun adminOnly(ctx: CommandContext): Reply? =
        if (!ctx.isAdmin) Replies.reject(Messages.get(Messages.Key.ADMIN_DENIED, lang(ctx))) else null

    private fun channelAiAdminOnly(
        ctx: CommandContext,
        action: String,
    ): Reply? {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            channelAiCustomization.requireCanManageChannelAi(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                actorUserId = ctx.userId,
                actorRoleIds = ctx.roleIds,
                actorIsGuildAdmin = ctx.isAdmin,
                action = action,
            )
            null
        }.getOrElse {
            Replies.reject(it.message ?: "AI 설정 변경 권한이 없습니다.")
        }
    }

    // ── 일반 유저 ───────────────────────────────────────────────────────
    fun ask(
        ctx: CommandContext,
        prompt: String,
        requestedModel: String? = null,
        requestedResponseMode: String? = null,
        webSearch: Boolean = false,
    ): Reply {
        // 요청 우선순위(#150): 관리자/긴급 요청은 분당 쿨다운을 우회한다.
        if (!ctx.isAdmin && !rateLimiter.tryAcquire("ask:${ctx.guildId}:${ctx.userId}")) {
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, lang(ctx))) // 쿨다운 피드백(#191, i18n)
        }
        val guildDefaultModel = policy.guildDefaultModel(ctx.guildId) // 1회 조회 후 재사용(중복 SELECT 제거)
        val routingPolicy = channelRoutingPolicies.effective(ctx.guildId, ctx.channelId, guildDefaultModel)
        val modelChoice =
            channelRoutingPolicies.resolveModelChoice(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                requestedModel = requestedModel,
                guildDefaultModel = guildDefaultModel,
            )
        if (modelChoice.selectedModel == null && modelChoice.requiresAvailableModel) {
            return Replies.warn(
                "⚠️ ${modelChoice.explanation}\n\n" +
                    (modelChoice.userMessage ?: "다른 모델을 선택하거나, 잠시 후 다시 시도하거나, 관리자에게 채널 모델 정책을 확인해 달라고 해주세요."),
            )
        }
        val selectedModel =
            modelChoice.selectedModel
                ?: requestedModel?.trim()?.ifBlank { null }
                ?: routingPolicy.preferredModel
        val responseMode = normalizeAskResponseMode(requestedResponseMode) ?: routingPolicy.responseMode
        val runtimeMultiResponseRun =
            startRuntimeMultiResponseObservation(ctx, prompt, responseMode, routingPolicy.maxCandidates)
        val effectivePrompt = prompt.composeExecutionPrompt(ctx, responseMode)
        val startedAtNanos = System.nanoTime()
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
                    webSearch = webSearch && webSearchAugmenter.isEnabled(),
                ),
            )
        runtimeMultiResponseRun?.let { run ->
            recordRuntimeMultiResponseResult(
                runId = run.id,
                providerId = result.providerId,
                modelName = selectedModel,
                result = result,
                latencyMs = elapsedMillis(startedAtNanos),
            )
        }
        return when (result.state) {
            RequestState.COMPLETED ->
                completedAskReply(result.text.orEmpty().withWebSources(result.sources), modelChoice, result.requestId)
            RequestState.REJECTED -> Replies.reject(result.failReason ?: "요청이 거부되었습니다.")
            else -> Replies.warn(result.failReason ?: "요청을 처리하지 못했습니다.")
        }
    }

    /** /imagine — 이미지 생성 가능한 프로바이더(로컬 SD)에게 이미지를 만들게 한다(SD Phase 2c). */
    fun imagine(
        ctx: CommandContext,
        prompt: String,
    ): Reply {
        if (prompt.isBlank()) return Replies.warn("이미지로 만들 내용을 입력해 주세요.")
        if (!ctx.isAdmin && !rateLimiter.tryAcquire("imagine:${ctx.guildId}:${ctx.userId}")) {
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, lang(ctx)))
        }
        val candidates = registry.byGuild(ctx.guildId).filter { "image" in it.capability.capabilities }
        if (candidates.isEmpty()) {
            return Replies.warn(
                "🖼️ 이미지 생성 가능한 프로바이더가 없습니다. " +
                    "(프로바이더가 로컬 Stable Diffusion 을 켜고 에이전트를 `--enable-image` 로 실행해야 합니다)",
            )
        }
        val session = candidates.minByOrNull { it.activeRequests } ?: candidates.first()
        return try {
            val bytes = session.sendImage(prompt).get()
            Reply("🖼️ \"${prompt.take(200)}\"", ephemeral = false, imagePng = bytes)
        } catch (e: Exception) {
            Replies.warn("이미지 생성에 실패했어요. 잠시 후 다시 시도해 주세요.")
        }
    }

    /** 웹검색 출처를 답변 하단에 [n] URL 형식으로 덧붙인다(없으면 그대로). 최대 5개. */
    private fun String.withWebSources(sources: List<String>): String {
        if (sources.isEmpty()) return this
        val footer =
            buildString {
                append("\n\n🔎 출처(웹검색)")
                sources.take(5).forEachIndexed { i, url -> append("\n[${i + 1}] $url") }
            }
        return this + footer
    }

    private fun completedAskReply(
        answer: String,
        modelChoice: ModelChoiceDecision,
        requestId: String?,
    ): Reply {
        val fullContent = answer.withModelFallbackNotice(modelChoice)
        val plan =
            runCatching {
                if (fullContent.length >= PSEUDO_STREAM_MIN_CHARS) {
                    multiResponse.pseudoStreamPlan(
                        answer = fullContent,
                        requestedSteps = PSEUDO_STREAM_STEPS,
                        maxDiscordChars = DISCORD_REPLY_SAFE_LIMIT,
                    )
                } else {
                    null
                }
            }.getOrNull()
        val rawSnapshots = plan?.snapshots?.map { it.content }.orEmpty()
        val finalContent =
            rawSnapshots.lastOrNull()?.withDiscordLengthNotice(plan?.warning)
                ?: fullContent.toDiscordSafeContent()
        val stream =
            rawSnapshots
                .takeIf { it.size > 1 }
                ?.let { ReplyPseudoStream(plan!!.editIntervalMs.toLong(), it.dropLast(1) + finalContent, plan.warning) }
        return Reply(
            content = finalContent,
            ephemeral = false,
            pseudoStream = stream,
            feedback = requestId?.trim()?.takeIf { it.isNotBlank() }?.let { ReplyFeedback(it) },
        )
    }

    fun submitAskFeedback(
        ctx: CommandContext,
        requestId: String,
        rating: Int,
        feedbackType: String,
        reason: String? = null,
    ): Reply {
        val normalizedRequestId = requestId.trim()
        if (normalizedRequestId.isBlank()) {
            return Replies.warn("피드백 대상을 찾지 못했어요. 다시 질문한 뒤 답변 아래 버튼을 눌러주세요.")
        }
        val saved =
            qualityFeedback.submit(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                requestId = normalizedRequestId,
                userId = ctx.userId,
                rating = rating,
                feedbackType = feedbackType,
                reason = reason,
            )
        val message =
            if (saved.status == "needs_review") {
                "🚩 신고로 접수했어요. 관리자가 품질 피드백 대시보드에서 확인할 수 있습니다."
            } else {
                "고마워요. 이 피드백은 채널 AI 품질 개선 신호로만 사용됩니다."
            }
        return Reply(message, ephemeral = true)
    }

    private fun startRuntimeMultiResponseObservation(
        ctx: CommandContext,
        prompt: String,
        responseMode: String,
        maxCandidates: Int,
    ) = if (shouldObserveMultiResponse(responseMode, maxCandidates)) {
        runCatching {
            multiResponse.startRuntimeObservation(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                promptPreview = prompt,
                responseMode = responseMode,
                maxCandidates = maxCandidates,
            )
        }.getOrNull()
    } else {
        null
    }

    private fun recordRuntimeMultiResponseResult(
        runId: Long,
        providerId: Long?,
        modelName: String?,
        result: com.discordassistant.central.routing.domain.model.OrchestrationResult,
        latencyMs: Int,
    ) {
        runCatching {
            multiResponse.recordRuntimeSingleRouteResult(
                runId = runId,
                providerUserId = providerId,
                modelName = modelName,
                answerRef = result.toRuntimeAnswerRef(),
                completed = result.state == RequestState.COMPLETED,
                latencyMs = latencyMs,
                failureReason = result.failReason,
            )
        }
    }

    private fun shouldObserveMultiResponse(
        responseMode: String,
        maxCandidates: Int,
    ): Boolean = maxCandidates > 1 || responseMode.equals("deep", ignoreCase = true)

    private fun com.discordassistant.central.routing.domain.model.OrchestrationResult.toRuntimeAnswerRef(): String? =
        if (state == RequestState.COMPLETED) {
            "discord-ask-runtime:${java.util.UUID.randomUUID()}"
        } else {
            null
        }

    private fun elapsedMillis(startedAtNanos: Long): Int =
        ((System.nanoTime() - startedAtNanos) / 1_000_000)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun String.withModelFallbackNotice(modelChoice: ModelChoiceDecision): String {
        if (modelChoice.fallbackReason == null) return this
        if (modelChoice.requestedModel == null && modelChoice.preferredModel == null) return this
        val selected = modelChoice.selectedModel ?: "자동 선택"
        return "$this\n\n↪️ 모델 대체: ${modelChoice.explanation} `사용 모델: $selected`"
    }

    private fun String.toDiscordSafeContent(): String =
        if (length <= DISCORD_REPLY_SAFE_LIMIT) {
            this
        } else {
            take(DISCORD_REPLY_SAFE_LIMIT).withDiscordLengthNotice("discord_message_truncated_to_$DISCORD_REPLY_SAFE_LIMIT")
        }

    private fun String.withDiscordLengthNotice(warning: String?): String {
        if (warning == null) return this
        val notice = "\n\n_답변이 Discord 길이 제한으로 일부 줄어들었어요._"
        val safeText = take((DISCORD_REPLY_SAFE_LIMIT - notice.length).coerceAtLeast(100))
        return safeText + notice
    }

    private fun normalizeAskResponseMode(value: String?): String? = ResponseMode.normalizeOrNull(value)?.wire

    private fun String.composeExecutionPrompt(
        ctx: CommandContext,
        responseMode: String,
    ): String {
        val knowledgeContext =
            runCatching {
                knowledgeSearch.contextPlan(
                    guildId = ctx.guildId,
                    query = this,
                    responseMode = responseMode,
                    channelId = ctx.channelId,
                )
            }.getOrNull()
        val contextText = knowledgeContext?.contextText?.takeIf { it.isNotBlank() }
        activeChannelAiExecutionPrompt(ctx, contextText)?.let { return it }
        val behaviorPrompt = channelProfiles.get(ctx.guildId, ctx.channelId)?.let { withChannelAiBehavior(it) } ?: this
        if (contextText == null) return behaviorPrompt
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

    private fun String.activeChannelAiExecutionPrompt(
        ctx: CommandContext,
        contextText: String?,
    ): String? {
        val history =
            runCatching {
                channelAiCustomization.channelHistory(ctx.guildId, ctx.channelId)
            }.getOrNull() ?: return null
        val activeBehaviorId = history.channelAi?.activeBehaviorVersionId ?: return null
        val preview =
            runCatching {
                channelAiCustomization.promptPreview(
                    guildId = ctx.guildId,
                    channelId = ctx.channelId,
                    userQuestion = this,
                    ragContextText = contextText,
                )
            }.getOrNull() ?: return null
        if (preview.behaviorVersionId != activeBehaviorId) return null
        return buildString {
            appendLine(preview.systemPrompt)
            appendLine()
            append(preview.userPrompt)
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

    fun models(ctx: CommandContext): Reply = infoCommands.models(ctx)

    fun catalog(ctx: CommandContext): Reply = infoCommands.catalog(ctx)

    fun contributions(ctx: CommandContext): Reply = infoCommands.contributions(ctx)

    /** 익명 커뮤니티 기여 통계(차수 12 #177). 개별 식별정보 없이 집계만 공개. */
    fun communityStats(ctx: CommandContext): Reply = infoCommands.communityStats(ctx)

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

    fun myUsage(ctx: CommandContext): Reply = infoCommands.myUsage(ctx)

    fun privacy(ctx: CommandContext): Reply = infoCommands.privacy(ctx)

    fun botPermissions(ctx: CommandContext): Reply = infoCommands.botPermissions(ctx)

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

    /** 이 서버 냥시스턴트의 활동 레벨/경험치/진행도(public·비관리자). */
    fun aiLevel(ctx: CommandContext): Reply = aiNetworkCommands.aiLevel(ctx)

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
    ): Reply {
        channelAiAdminOnly(ctx, "channel_ai_profile")?.let { return it }
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
                "이후 `/질문` 답변은 이 채널에서 그 이름으로 보입니다. 봇에 `웹후크 관리` 권한이 필요해요.",
        )
    }

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
    ): OnboardingStartOutcome {
        channelAiAdminOnly(ctx, "auto_onboard_start")?.let { return OnboardingStartOutcome.Rejected(it) }
        return runCatching {
            // LLM 분석은 DB 트랜잭션 밖(여기 — slow 명령 실행 풀)에서 먼저 수행한다(B1). analyze 는 비트랜잭션
            // 메서드라 프록시를 거쳐도 트랜잭션/커넥션을 열지 않는다. startOnboarding 은 그 결과만 받아 짧은 트랜잭션으로 처리.
            // (analyze/startOnboarding 을 여기서 각각 부르므로 self-invocation 프록시 우회 함정도 없다.)
            // 분석은 **실제 길드/채널/actor** 컨텍스트로 라우팅한다(A) — 프로바이더가 길드별 풀이라 실제 guildId 가 필수.
            val analysis =
                guildOnboarding.analyze(
                    backfill,
                    OnboardingAnalysisContext(
                        guildId = ctx.guildId,
                        channelId = ctx.channelId,
                        actorUserId = ctx.userId,
                        actorRoleIds = ctx.roleIds,
                        actorIsGuildAdmin = ctx.isAdmin,
                    ),
                )
            val result =
                guildOnboarding.startOnboarding(
                    guildId = ctx.guildId,
                    channelId = ctx.channelId,
                    actorUserId = ctx.userId,
                    actorRoleIds = ctx.roleIds,
                    actorIsGuildAdmin = ctx.isAdmin,
                    channelName = channelName,
                    channelWhitelist = channelWhitelist,
                    historyLimit = historyLimit,
                    backfill = backfill,
                    analysis = analysis,
                )
            OnboardingStartOutcome.Started(result)
        }.getOrElse {
            OnboardingStartOutcome.Rejected(Replies.warn("AI 자동 설정을 시작하지 못했어요. ${it.message ?: "잠시 후 다시 시도해 주세요."}"))
        }
    }

    fun approveOnboarding(
        ctx: CommandContext,
        proposalId: Long,
    ): Reply {
        channelAiAdminOnly(ctx, "auto_onboard_approve")?.let { return it }
        return runCatching {
            val review =
                guildOnboarding.approveOnboarding(
                    proposalId = proposalId,
                    reviewerUserId = ctx.userId,
                    reviewerRoleIds = ctx.roleIds,
                    reviewerIsGuildAdmin = ctx.isAdmin,
                    reason = "auto onboarding approved",
                )
            Replies.ok("✅ 이 채널 AI 자동 설정을 승인했습니다. 이제 `/ask` 답변에 적용됩니다. (제안 `${review.id}`)")
        }.getOrElse {
            Replies.warn("AI 자동 설정 승인에 실패했어요. ${it.message ?: "이미 처리된 제안인지 확인해 주세요."}")
        }
    }

    fun rejectOnboarding(
        ctx: CommandContext,
        proposalId: Long,
    ): Reply {
        channelAiAdminOnly(ctx, "auto_onboard_reject")?.let { return it }
        return runCatching {
            val review =
                guildOnboarding.rejectOnboarding(
                    proposalId = proposalId,
                    reviewerUserId = ctx.userId,
                    reviewerRoleIds = ctx.roleIds,
                    reviewerIsGuildAdmin = ctx.isAdmin,
                    reason = "auto onboarding rejected",
                )
            Replies.ok("🚫 이 채널 AI 자동 설정 제안을 거절했습니다. 제안은 적용되지 않습니다. (제안 `${review.id}`)")
        }.getOrElse {
            Replies.warn("AI 자동 설정 거절에 실패했어요. ${it.message ?: "이미 처리된 제안인지 확인해 주세요."}")
        }
    }

    /**
     * `/ai-onboard-optout` — 누구나 **본인에 한해** 자신의 메시지를 자동 온보딩 백필 RAG 색인에서 제외/해제한다(관리자 권한 불필요).
     * [enable] = true 면 제외 등록, false 면 해제, null 이면 현재 상태를 토글한다. 길드 단위로 격리된다.
     * 이미 색인된 과거 데이터는 row 삭제(소스 삭제)로 잊을 수 있고, 이 설정은 이후 백필부터 본인 메시지를 색인하지 않게 한다.
     */
    fun setOnboardingOptOut(
        ctx: CommandContext,
        enable: Boolean? = null,
    ): Reply {
        if (ctx.guildId == DM_SCOPE) {
            return Replies.warn("이 명령은 서버에서만 사용할 수 있어요.")
        }
        return runCatching {
            val currentlyOptedOut = onboardingOptOuts.existsByGuildIdAndUserId(ctx.guildId, ctx.userId)
            val target = enable ?: !currentlyOptedOut
            if (target == currentlyOptedOut) {
                if (target) {
                    Reply("이미 이 서버의 AI 자동 학습(백필 색인)에서 내 메시지를 제외하고 있어요.")
                } else {
                    Reply("이미 제외 설정이 없어요. 내 메시지는 (관리자가 백필을 실행하면) 색인 대상이 될 수 있어요.")
                }
            } else if (target) {
                // 유니크 인덱스로 중복 방지 — 경합 시 예외는 runCatching 이 잡는다(아래 getOrElse).
                onboardingOptOuts.save(
                    com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutEntity(
                        guildId = ctx.guildId,
                        userId = ctx.userId,
                        createdAt = java.time.Instant.now(),
                    ),
                )
                Replies.ok("✅ 이 서버의 AI 자동 학습(백필 색인)에서 내 메시지를 제외했어요. 이후 백필부터 내 메시지는 색인되지 않습니다.")
            } else {
                onboardingOptOuts.deleteByGuildIdAndUserId(ctx.guildId, ctx.userId)
                Replies.ok("✅ 제외 설정을 해제했어요. 내 메시지가 다시 백필 색인 대상이 될 수 있어요(관리자가 백필을 실행할 때).")
            }
        }.getOrElse {
            Replies.warn("opt-out 설정을 변경하지 못했어요. 잠시 후 다시 시도해 주세요.")
        }
    }

    // ── 채널 AI 자유 지침(custom instruction) ────────────────────────────

    /**
     * `/ai-instruction` — 이 채널 AI에 자연어 자유 지침을 추가/수정한다.
     * text 가 비어 있으면 현재 지침을 확인만 한다. text 가 있으면 활성 behavior 를 베이스로
     * customInstruction 만 교체한 **새 behavior 버전 제안**을 만든다(위험 지침은 승인 큐로 강제).
     */
    fun setChannelAiInstruction(
        ctx: CommandContext,
        text: String?,
    ): Reply {
        channelAiAdminOnly(ctx, "set_custom_instruction")?.let { return it }
        val instruction = text?.trim().orEmpty()
        if (instruction.isBlank()) {
            return runCatching {
                val current = channelAiCustomization.currentCustomInstruction(ctx.guildId, ctx.channelId)
                if (current.isNullOrBlank()) {
                    Reply("현재 이 채널 AI에는 자유 지침이 없어요. `text` 옵션에 자연어 지침을 적어 추가하세요.")
                } else {
                    Reply("현재 이 채널 AI 자유 지침:\n> ${current.replace("\n", "\n> ")}")
                }
            }.getOrElse {
                Replies.warn("자유 지침을 확인하지 못했어요. ${it.message ?: "이 채널에 채널 AI가 있는지 확인해 주세요."}")
            }
        }
        return runCatching {
            // 자유 지침은 위험어 substring 우회(변형 인젝션) 위험이 있어 즉시 적용하지 않고 항상 사람 검토를 거친다(#5).
            // 온보딩 경로와 동일하게 requireApproval=true 로 PENDING 제안을 만들고, 관리자 승인 후에만 active 가 된다.
            val result =
                channelAiCustomization.proposeCustomInstruction(
                    guildId = ctx.guildId,
                    channelId = ctx.channelId,
                    actorUserId = ctx.userId,
                    actorRoleIds = ctx.roleIds,
                    actorIsGuildAdmin = ctx.isAdmin,
                    customInstruction = instruction,
                    requireApproval = true,
                )
            Replies.ok(
                "📝 자유 지침을 검토 대기열에 올렸어요(v${result.version}). " +
                    "관리자 승인 후 `/ask` 답변에 적용됩니다. (제안 `${result.proposalId}`)",
            )
        }.getOrElse {
            Replies.warn("자유 지침을 적용하지 못했어요. ${it.message ?: "잠시 후 다시 시도해 주세요."}")
        }
    }

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

    fun providerScope(
        ctx: CommandContext,
        model: String,
        role: String,
    ): Reply = providerCommands.providerScope(ctx, model, role)

    fun providerSchedule(
        ctx: CommandContext,
        fromHour: Int,
        toHour: Int,
    ): Reply = providerCommands.providerSchedule(ctx, fromHour, toHour)

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
            Replies.ok("프로바이더 **자동 승인** — 이제 `/프로바이더참여` 한 사람은 관리자 승인 없이 바로 참여합니다.")
        } else {
            Replies.ok("프로바이더 **수동 승인** — `/프로바이더참여` 신청 후 관리자가 `/프로바이더승인` 해야 참여합니다.")
        }
    }

    /** 현재 자동 승인 상태(패널 표시용). */
    fun isAutoApprove(ctx: CommandContext): Boolean = policy.isAutoApprove(ctx.guildId)

    /** 모든 채널에서 LLM 사용 허용(채널 제한 해제). */
    fun allowAllChannels(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        policy.allowAllChannels(ctx.guildId, ctx.userId)
        return Replies.ok("이제 **모든 채널**에서 `/질문` 을 쓸 수 있습니다(채널 제한 해제).")
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
                ?: return Replies.info("이 서버는 아직 환영 메시지를 설정하지 않았습니다. `/도움말` 로 사용법을 확인하세요.")
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
