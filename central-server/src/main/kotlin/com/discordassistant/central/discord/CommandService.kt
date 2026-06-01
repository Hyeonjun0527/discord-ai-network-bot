package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.network.AiNetworkLaunchChecklistService
import com.discordassistant.central.network.AiNetworkMap
import com.discordassistant.central.network.AiNetworkMapService
import com.discordassistant.central.network.AiQualityFeedbackService
import com.discordassistant.central.network.ChannelAiCustomizationService
import com.discordassistant.central.network.ChannelAiRoutingPolicyService
import com.discordassistant.central.network.KnowledgeIndexingService
import com.discordassistant.central.network.KnowledgeIngestionService
import com.discordassistant.central.network.KnowledgeSearchService
import com.discordassistant.central.network.ModelChoiceDecision
import com.discordassistant.central.network.MultiResponseService
import com.discordassistant.central.network.NetworkLaunchChecklist
import com.discordassistant.central.network.PresetModerationSummary
import com.discordassistant.central.network.PresetRegistryService
import com.discordassistant.central.network.PublishedPresetSummary
import com.discordassistant.central.persistence.PresetImportEntity
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
    val pseudoStream: ReplyPseudoStream? = null,
    val feedback: ReplyFeedback? = null,
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
    private val channelAiCustomization: ChannelAiCustomizationService,
    private val channelRoutingPolicies: ChannelAiRoutingPolicyService,
    private val knowledgeIngestion: KnowledgeIngestionService,
    private val knowledgeIndexing: KnowledgeIndexingService,
    private val knowledgeSearch: KnowledgeSearchService,
    private val aiNetworkLaunchChecklist: AiNetworkLaunchChecklistService,
    private val aiNetworkMap: AiNetworkMapService,
    private val presetRegistry: PresetRegistryService,
    private val multiResponse: MultiResponseService,
    private val qualityFeedback: AiQualityFeedbackService,
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

        private const val DISCORD_REPLY_SAFE_LIMIT = 1850
        private const val PSEUDO_STREAM_MIN_CHARS = 600
        private val PSEUDO_STREAM_STEPS = listOf(33, 66, 100)
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
        val routingPolicy = channelRoutingPolicies.effective(ctx.guildId, ctx.channelId, policy.guildDefaultModel(ctx.guildId))
        val modelChoice =
            channelRoutingPolicies.resolveModelChoice(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                requestedModel = requestedModel,
                guildDefaultModel = policy.guildDefaultModel(ctx.guildId),
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
            RequestState.COMPLETED -> completedAskReply(result.text.orEmpty(), modelChoice, result.requestId)
            RequestState.REJECTED -> Replies.reject(result.failReason ?: "요청이 거부되었습니다.")
            else -> Replies.warn(result.failReason ?: "요청을 처리하지 못했습니다.")
        }
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
        result: com.discordassistant.central.routing.OrchestrationResult,
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

    private fun com.discordassistant.central.routing.OrchestrationResult.toRuntimeAnswerRef(): String? =
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

    private fun normalizeAskResponseMode(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            null, "" -> null
            "fast", "빠른", "빠른 답변" -> "fast"
            "deep", "깊은", "깊은 답변" -> "deep"
            "saving", "절약", "절약 모드" -> "saving"
            "balanced", "균형", "균형 모드" -> "balanced"
            else -> null
        }

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
            sb.append("· `/ai-network-map` — Provider·모델·채널AI·RAG 구성을 한눈에 보기\n")
            sb.append("· `/ai-knowledge-list` `/ai-knowledge-add` `/ai-knowledge-search` — 채널 지식공간/RAG 소스 관리\n")
            sb.append("· `/ai-knowledge-index-plan` `/ai-knowledge-approve` `/ai-knowledge-delete` — 색인계획·검토·삭제\n")
            sb.append("· `/ai-knowledge-jobs` `/ai-knowledge-job-complete` — RAG 색인 작업 큐 조회·완료 처리\n")
            sb.append("· `/ai-preset-catalog` `/ai-preset-import` — 프리셋 공유 목록 보기·현재 채널에 가져오기\n")
            sb.append("· `/ai-preset-moderation` `/ai-preset-report-review` — 프리셋 신고 큐 확인·검수 처리\n")
            sb.append("· `/ai-multi-response-status` `/ai-multi-response-set` `/ai-multi-response-dry-run` — 다중응답 정책·상태·안전 드라이런\n")
            sb.append("· `/ai-network-check` — Provider·채널AI·RAG·프리셋·다중응답 운영 체크리스트\n")
            sb.append("· `/사용자차단`(`/llm-block`) `/차단해제`(`/llm-unblock`) — 사용자 차단/해제\n")
        }
        sb.append("\n_민감정보(비밀번호·API 키 등)는 입력하지 마세요._")
        return Reply(sb.toString())
    }

    fun knowledgeList(
        ctx: CommandContext,
        spaceId: Long? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            if (spaceId != null) {
                val status = knowledgeIngestion.spaceStatus(ctx.guildId, spaceId)
                val sources = knowledgeIngestion.listSources(ctx.guildId, spaceId)
                val sourceRows =
                    sources.take(12).map {
                        "• `${it.id}` ${it.title} — ${it.sourceType} · ${it.status} · risk=${it.riskLevel}"
                    }
                val sourceLines = sourceRows.joinToString("\n").ifBlank { "• 아직 지식 소스가 없습니다." }
                Reply(
                    "📚 **채널 지식공간 상세**\n\n" +
                        "space `${status.knowledgeSpaceId}` · <#${status.channelId ?: ctx.channelId}> · ${status.displayName}\n" +
                        "준비상태: `${status.readiness}` · 소스 ${status.sourceCount}개 · indexed ${status.indexedSourceCount}개 · " +
                        "blocked ${status.blockedSourceCount}개\n\n" +
                        "__지식 소스__\n$sourceLines",
                )
            } else {
                val readiness = knowledgeIngestion.guildReadiness(ctx.guildId)
                val spaceRows =
                    readiness.spaces.take(12).map {
                        "• `${it.knowledgeSpaceId}` <#${it.channelId ?: ctx.channelId}> — ${it.displayName} · " +
                            "${it.readiness} · sources ${it.sourceCount}/indexed ${it.indexedSourceCount}"
                    }
                val spaceLines =
                    spaceRows
                        .joinToString("\n")
                        .ifBlank {
                            "• 아직 지식공간이 없습니다. `/ai-knowledge-add title:<제목> url:<https://...>` 로 현재 채널 지식공간을 만들 수 있어요."
                        }
                val next =
                    readiness.nextActions
                        .take(4)
                        .joinToString("\n") { "• $it" }
                        .ifBlank { "• 추가 조치 없음" }
                Reply(
                    "📚 **RAG 지식공간 목록**\n\n" +
                        "상태: `${readiness.status}` · 지식공간 ${readiness.spaceCount}개 · 소스 ${readiness.sourceCount}개 · " +
                        "indexed ${readiness.indexedSourceCount}개 · blocked ${readiness.blockedSourceCount}개\n\n" +
                        "__공간__\n$spaceLines\n\n" +
                        "__다음 행동__\n$next",
                )
            }
        }.getOrElse {
            Replies.warn("지식공간을 조회하지 못했어요. ${it.message ?: "길드/space-id를 확인해 주세요."}")
        }
    }

    fun addKnowledge(
        ctx: CommandContext,
        title: String,
        sourceType: String?,
        sourceUri: String?,
        contentPreview: String?,
        spaceId: Long? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val normalizedUri = sourceUri?.trim()?.ifBlank { null }
        val normalizedPreview = contentPreview?.trim()?.ifBlank { null }
        if (normalizedUri == null && normalizedPreview == null) {
            return Replies.warn("추가할 URL 또는 텍스트를 입력해 주세요. 민감정보·비밀번호·API 키는 넣지 마세요.")
        }
        return runCatching {
            val targetSpaceId =
                spaceId
                    ?: knowledgeIngestion
                        .guildReadiness(ctx.guildId)
                        .spaces
                        .firstOrNull { it.channelId == ctx.channelId }
                        ?.knowledgeSpaceId
                    ?: run {
                        val created =
                            knowledgeIngestion.createSpace(
                                guildId = ctx.guildId,
                                channelId = ctx.channelId,
                                channelAiId = null,
                                displayName = "채널 ${ctx.channelId} 지식공간",
                                createdBy = ctx.userId,
                                embeddingModel = null,
                                indexName = null,
                            )
                        created.id
                    }
            val type = sourceType?.trim()?.ifBlank { null } ?: if (normalizedUri != null) "link" else "text"
            val source =
                knowledgeIngestion.addSource(
                    guildId = ctx.guildId,
                    spaceId = targetSpaceId,
                    sourceType = type,
                    title = title,
                    sourceUri = normalizedUri,
                    contentPreview = normalizedPreview,
                    addedBy = ctx.userId,
                )
            val inlineIndexing =
                knowledgeIndexing.indexInlineSourceIfPossible(
                    guildId = ctx.guildId,
                    spaceId = targetSpaceId,
                    sourceId = source.id,
                    documentText = normalizedPreview,
                    triggeredBy = ctx.userId,
                )
            val plan = knowledgeIngestion.indexingPlan(ctx.guildId, targetSpaceId)
            val effectiveStatus = if (inlineIndexing.indexed) "indexed" else source.status
            val indexingHint =
                when {
                    inlineIndexing.indexed ->
                        "텍스트를 즉시 검색 가능하게 색인했습니다. embedding 재빌드 작업 `${inlineIndexing.jobId}` 도 큐에 넣었어요."
                    source.status == "pending" ->
                        "색인 대기 상태입니다. 운영자는 `${plan.command}` 를 실행해 검색 가능하게 만드세요."
                    else -> "상태가 `${source.status}` 입니다. 위험도 `${source.riskLevel}` 를 검토한 뒤 승인/삭제하세요."
                }
            Replies.ok(
                "지식 소스를 추가했습니다.\n" +
                    "space: `$targetSpaceId` · source: `${source.id}` · status: `$effectiveStatus` · risk: `${source.riskLevel}`\n" +
                    "$indexingHint\n\n" +
                    "`/ai-knowledge-list space-id:$targetSpaceId` 로 현재 목록을 확인할 수 있어요.",
            )
        }.getOrElse {
            Replies.warn("지식 소스 추가에 실패했어요. ${it.message ?: "입력값을 확인해 주세요."}")
        }
    }

    fun searchKnowledge(
        ctx: CommandContext,
        query: String,
        spaceId: Long? = null,
        limit: Int = 5,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            val result =
                knowledgeSearch.search(
                    guildId = ctx.guildId,
                    query = query,
                    limit = limit,
                    channelId = if (spaceId == null) ctx.channelId else null,
                    knowledgeSpaceId = spaceId,
                )
            val resultRows =
                result.results.take(10).map {
                    val ref = it.sourceUri?.let { uri -> " · ${uri.take(80)}" } ?: ""
                    val preview = it.contentPreview?.let { text -> " · ${text.take(100)}" } ?: ""
                    "• `${it.sourceId}` ${it.title} — score ${it.score} · ${it.sourceType}$ref$preview"
                }
            val lines =
                resultRows
                    .joinToString("\n")
                    .ifBlank {
                        when (result.fallbackReason) {
                            "blocked_sensitive_query" -> "• 민감정보처럼 보이는 검색어라 RAG 검색을 막았습니다."
                            "rag_scope_required" -> "• 검색 범위가 없습니다. 현재 채널 또는 space-id를 지정해 주세요."
                            "no_knowledge_space" -> "• 이 채널에 지식공간이 없습니다. 먼저 `/ai-knowledge-add` 로 지식을 추가하세요."
                            "no_indexed_knowledge_match" -> "• 검색 가능한 indexed 지식에서 결과를 찾지 못했습니다. 색인 상태를 확인하세요."
                            else -> "• 결과 없음"
                        }
                    }
            Reply(
                "🔎 **채널 지식 검색**\n" +
                    "query: `$query` · scope: `${spaceId?.let { "space:$it" } ?: "current-channel"}`" +
                    (result.fallbackReason?.let { " · fallback: `$it`" } ?: "") +
                    "\n\n$lines",
            )
        }.getOrElse {
            Replies.warn("지식 검색에 실패했어요. ${it.message ?: "검색어/space-id를 확인해 주세요."}")
        }
    }

    fun knowledgeIndexPlan(
        ctx: CommandContext,
        spaceId: Long? = null,
        force: Boolean = false,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            if (spaceId != null) {
                val plan = knowledgeIngestion.indexingPlan(ctx.guildId, spaceId, force)
                val indexableRows =
                    plan.indexableSources.take(8).map {
                        "• `${it.id}` ${it.title} — ${it.status} · risk=${it.riskLevel}"
                    }
                val indexable = indexableRows.joinToString("\n").ifBlank { "• 색인할 소스 없음" }
                val blockedRows =
                    plan.blockedSources.take(8).map {
                        "• `${it.id}` ${it.title} — ${it.status} · risk=${it.riskLevel}"
                    }
                val blocked = blockedRows.joinToString("\n").ifBlank { "• 차단/검토 소스 없음" }
                Reply(
                    "🧭 **RAG 색인 계획**\n\n" +
                        "space `$spaceId` · ready `${plan.ready}` · runtime `${plan.runtime}`\n" +
                        "명령:\n`${plan.command}`\n\n" +
                        "__색인 대상__\n$indexable\n\n" +
                        "__검토/차단__\n$blocked",
                )
            } else {
                val ops = knowledgeIngestion.indexingOperations(ctx.guildId, force)
                val commandRows = ops.commands.take(5).map { "• `$it`" }
                val commands = commandRows.joinToString("\n").ifBlank { "• 실행할 색인 명령 없음" }
                val nextRows = ops.nextActions.take(5).map { "• $it" }
                val next = nextRows.joinToString("\n").ifBlank { "• 추가 조치 없음" }
                Reply(
                    "🧭 **RAG 색인 운영 계획**\n\n" +
                        "상태 `${ops.status}` · spaces ${ops.spaceCount} · readyPlans ${ops.readyPlanCount} · " +
                        "indexable ${ops.indexableSourceCount} · blocked ${ops.blockedSourceCount}\n\n" +
                        "__명령__\n$commands\n\n" +
                        "__다음 행동__\n$next",
                )
            }
        }.getOrElse {
            Replies.warn("색인 계획을 만들지 못했어요. ${it.message ?: "space-id를 확인해 주세요."}")
        }
    }

    fun knowledgeIndexJobs(
        ctx: CommandContext,
        spaceId: Long? = null,
        limit: Int = 10,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            val jobs = knowledgeIndexing.listIndexJobs(ctx.guildId, spaceId, limit)
            val rows =
                jobs.map {
                    "• `${it.id}` space `${it.knowledgeSpaceId}` · ${it.status} · chunks ${it.chunkCount} · " +
                        "${it.queuedAt}${it.failureReason?.let { reason -> " · $reason" } ?: ""}"
                }
            val lines = rows.joinToString("\n").ifBlank { "• 최근 RAG 색인 작업이 없습니다." }
            Reply(
                "🧱 **RAG 색인 작업 큐**\n" +
                    "scope: `${spaceId?.let { "space:$it" } ?: "guild"}` · limit `$limit`\n\n" +
                    "$lines\n\n" +
                    "완료/실패 처리는 `/ai-knowledge-job-complete job-id:<id> status:<completed|failed|cancelled>` 로 기록하세요.",
            )
        }.getOrElse {
            Replies.warn("색인 작업을 조회하지 못했어요. ${it.message ?: "space-id를 확인해 주세요."}")
        }
    }

    fun completeKnowledgeIndexJob(
        ctx: CommandContext,
        jobId: Long,
        status: String = "completed",
        reason: String? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            val job =
                knowledgeIndexing.completeIndexJobSafely(
                    guildId = ctx.guildId,
                    jobId = jobId,
                    status = status,
                    failureReason = reason,
                )
            Replies.ok(
                "RAG 색인 작업 상태를 기록했습니다.\n" +
                    "job: `${job.id}` · space: `${job.knowledgeSpaceId}` · status: `${job.status}` · chunks: `${job.chunkCount}`" +
                    (job.failureReason?.let { "\nreason: `$it`" } ?: ""),
            )
        }.getOrElse {
            Replies.warn("색인 작업 상태를 기록하지 못했어요. ${it.message ?: "job-id/status를 확인해 주세요."}")
        }
    }

    fun approveKnowledge(
        ctx: CommandContext,
        spaceId: Long,
        sourceId: Long,
        reason: String = "approved from Discord",
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            val source = knowledgeIngestion.approveSourceForIndexing(ctx.guildId, spaceId, sourceId, reason)
            Replies.ok(
                "지식 소스를 색인 대기 상태로 승인했습니다.\n" +
                    "space: `$spaceId` · source: `${source.id}` · status: `${source.status}` · risk: `${source.riskLevel}`\n" +
                    "`/ai-knowledge-index-plan space-id:$spaceId` 로 색인 명령을 확인하세요.",
            )
        }.getOrElse {
            Replies.warn("지식 소스를 승인하지 못했어요. ${it.message ?: "review 위험도 소스인지 확인해 주세요."}")
        }
    }

    fun deleteKnowledge(
        ctx: CommandContext,
        spaceId: Long,
        sourceId: Long,
        reason: String = "deleted from Discord",
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            val source = knowledgeIngestion.removeSource(ctx.guildId, spaceId, sourceId, reason)
            val deletionIndex =
                knowledgeIndexing.tombstoneDeletedSourceIndex(
                    guildId = ctx.guildId,
                    spaceId = spaceId,
                    sourceId = source.id,
                    triggeredBy = ctx.userId,
                )
            Replies.ok(
                "지식 소스를 삭제했습니다. space `$spaceId` · source `${source.id}` · status `${source.status}`\n" +
                    "재색인 작업: `${deletionIndex.jobId}` · 제거된 chunk `${deletionIndex.tombstonedChunkCount}`",
            )
        }.getOrElse {
            Replies.warn("지식 소스를 삭제하지 못했어요. ${it.message ?: "space-id/source-id를 확인해 주세요."}")
        }
    }

    fun presetCatalog(
        ctx: CommandContext,
        query: String? = null,
        category: String? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val presets =
            presetRegistry.searchPublishedPresets(query = query, category = category, sort = "popular", limit = 10)
        return Reply(formatPresetCatalog(presets, query, category))
    }

    fun importPresetToCurrentChannel(
        ctx: CommandContext,
        publishedPresetId: Long,
        confirmConflicts: Boolean = false,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching {
            val imported =
                presetRegistry.importPreset(
                    publishedPresetId = publishedPresetId,
                    targetGuildId = ctx.guildId,
                    targetChannelId = ctx.channelId,
                    importedBy = ctx.userId,
                    confirmConflicts = confirmConflicts,
                )
            Reply(formatPresetImport(imported))
        }.getOrElse { error ->
            val preview =
                runCatching {
                    presetRegistry.previewImport(
                        publishedPresetId = publishedPresetId,
                        targetGuildId = ctx.guildId,
                        targetChannelId = ctx.channelId,
                    )
                }.getOrNull()
            val conflicts =
                preview
                    ?.conflicts
                    ?.joinToString("\n") { "• `${it.severity}` ${it.message}" }
                    ?.ifBlank { null }
            Replies.warn(
                "프리셋을 바로 가져오지 못했어요. ${error.message ?: "원인을 확인해 주세요."}\n" +
                    (conflicts?.let { "\n충돌/확인 필요:\n$it\n" } ?: "") +
                    "적용해도 괜찮다면 `/ai-preset-import` 에서 `confirm-conflicts: true` 로 다시 실행하세요.",
            )
        }
    }

    fun likePreset(
        ctx: CommandContext,
        publishedPresetId: Long,
    ): Reply {
        val published =
            runCatching { presetRegistry.likePreset(publishedPresetId, ctx.userId) }
                .getOrElse { return Replies.warn("프리셋 좋아요에 실패했어요. ${it.message ?: "프리셋 ID를 확인해 주세요."}") }
        return Replies.ok("프리셋 **${published.title}** 좋아요를 반영했습니다. 현재 ${published.likeCount}개")
    }

    fun reportPreset(
        ctx: CommandContext,
        publishedPresetId: Long,
        reason: String,
    ): Reply {
        val report =
            runCatching { presetRegistry.reportPreset(publishedPresetId, ctx.userId, reason) }
                .getOrElse { return Replies.warn("프리셋 신고에 실패했어요. ${it.message ?: "프리셋 ID와 신고 사유를 확인해 주세요."}") }
        return Replies.ok(
            "프리셋 신고를 접수했습니다. report `${report.id}` · 상태 `${report.status}`\n" +
                "신고된 프리셋은 카탈로그 노출/가져오기 전에 관리자 검토 대상으로 전환됩니다.",
        )
    }

    fun presetModeration(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        return runCatching { Reply(formatPresetModeration(presetRegistry.moderationSummary())) }
            .getOrElse { Replies.warn("프리셋 신고 큐를 불러오지 못했어요. ${it.message ?: "잠시 후 다시 시도해 주세요."}") }
    }

    fun reviewPresetReport(
        ctx: CommandContext,
        reportId: Long,
        decision: String,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val report =
            runCatching { presetRegistry.reviewReport(reportId, decision, reviewerUserId = ctx.userId) }
                .getOrElse { return Replies.warn("프리셋 신고 검수 처리에 실패했어요. ${it.message ?: "report-id/decision을 확인해 주세요."}") }
        return Replies.ok(
            "프리셋 신고를 처리했습니다. report `${report.id}` · 결정 `${report.status}`\n" +
                "카탈로그 노출 상태는 결정에 맞춰 자동 갱신됩니다.",
        )
    }

    private fun formatPresetCatalog(
        presets: List<PublishedPresetSummary>,
        query: String?,
        category: String?,
    ): String {
        val filter =
            listOfNotNull(
                query?.takeIf { it.isNotBlank() }?.let { "검색 `$it`" },
                category?.takeIf { it.isNotBlank() }?.let { "카테고리 `$it`" },
            ).joinToString(" · ").ifBlank { "인기순" }
        val presetLines =
            presets.take(10).map { preset ->
                val categoryText = preset.category ?: "general"
                val mode = preset.responseMode ?: "balanced"
                "• `${preset.id}` **${preset.title}** — $categoryText · $mode · 👍 ${preset.likeCount} · 가져오기 ${preset.importCount}"
            }
        val lines = presetLines.joinToString("\n").ifBlank { "• 아직 공개된 프리셋이 없습니다." }
        return "📚 **AI 프리셋 공유 목록** ($filter)\n\n" +
            "$lines\n\n" +
            "현재 채널에 적용하려면 `/ai-preset-import published-id:<ID>` 를 실행하세요.\n" +
            "부적절하면 `/ai-preset-report published-id:<ID> reason:<사유>` 로 신고할 수 있습니다."
    }

    private fun formatPresetModeration(summary: PresetModerationSummary): String {
        val queue =
            summary.queue
                .take(10)
                .joinToString("\n") { item ->
                    "• `${item.publishedPresetId}` **${item.title}** — `${item.status}` · 신고 ${item.reportCount} · " +
                        "좋아요 ${item.likeCount} · risk `${item.riskCodes.joinToString(",").ifBlank { "none" }}`\n" +
                        "  ↳ ${item.recommendedAction}"
                }.ifBlank { "• 검토할 프리셋 신고가 없습니다." }
        val nextActions =
            summary.nextActions
                .take(5)
                .joinToString("\n") { "• $it" }
                .ifBlank { "• 지금은 추가 조치가 없습니다." }
        return "🛡️ **프리셋 신고/검수 큐**\n\n" +
            "게시 ${summary.activePublishedCount} · 검토중 ${summary.underReviewCount} · " +
            "중단 ${summary.suspendedCount} · 제거 ${summary.removedCount}\n" +
            "열린 신고 ${summary.openReportCount} · 처리됨 ${summary.reviewedReportCount}\n\n" +
            "__우선 검토 대상__\n$queue\n\n" +
            "__다음 행동__\n$nextActions\n\n" +
            "처리: `/ai-preset-report-review report-id:<ID> decision:dismiss|suspend|remove`"
    }

    private fun formatPresetImport(imported: PresetImportEntity): String =
        "✅ 프리셋을 현재 채널에 가져왔습니다.\n" +
            "상태: `${imported.status}` · 보관함 프리셋: `${imported.importedPresetId ?: "-"}`\n" +
            "원본 revision: `${imported.sourceRevisionId ?: "-"}`\n" +
            "채널 AI: `${imported.createdChannelAiId ?: "-"}` · 행동 버전: `${imported.createdBehaviorVersionId ?: "-"}`\n" +
            "이제 이 채널에서 질문하면 가져온 프리셋의 역할·말투·응답 정책이 적용됩니다."

    fun multiResponseStatus(
        ctx: CommandContext,
        channelId: Long? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val targetChannelId = channelId ?: ctx.channelId
        return runCatching {
            val summary = multiResponse.operationsSummary(ctx.guildId, targetChannelId)
            val riskText = summary.riskCodes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"
            val nextActions =
                summary.nextActions
                    .take(4)
                    .joinToString("\n") { "• $it" }
                    .ifBlank { "• 지금은 추가 조치가 없습니다." }
            val topLoad =
                summary.providerLoads
                    .take(3)
                    .joinToString("\n") { load ->
                        "• Provider ${kotlin.math.abs(load.providerUserId.hashCode()).toString(36).take(6)} — " +
                            "후보 ${load.candidateCount} · 완료 ${load.completedCount} · timeout ${load.timeoutCount} · risk `${load.loadRisk}`"
                    }.ifBlank { "• 최근 fan-out 부하 기록 없음" }
            val averageFanout = "%.1f".format(summary.averageActualFanout)
            Reply(
                "🧪 **다중응답 운영 상태** <#$targetChannelId>\n\n" +
                    "상태: `${summary.status}` · 고급 모드 안전: `${summary.safeToEnableAdvanced}`\n" +
                    "최근 실행: ${summary.recentRunCount} · 완료 ${summary.completedRunCount} · fallback ${summary.fallbackRunCount}\n" +
                    "평균 fan-out: $averageFanout · 선택 후보 ${summary.acceptedCandidateCount} · " +
                    "timeout ${summary.timeoutCandidateCount}\n" +
                    "Provider 부하: high ${summary.highLoadProviderCount} · critical ${summary.criticalLoadProviderCount}\n" +
                    "RAG fallback ${summary.ragFallbackRunCount} · 민감질문 차단 ${summary.blockedSensitiveRunCount} · " +
                    "Provider 없음 ${summary.noProviderRunCount}\n" +
                    "위험 코드: `$riskText`\n\n" +
                    "__Provider 부하__\n$topLoad\n\n" +
                    "__다음 행동__\n$nextActions",
            )
        }.getOrElse { error ->
            Replies.warn("다중응답 상태를 불러오지 못했어요. ${error.message ?: "설정/기능 플래그를 확인해 주세요."}")
        }
    }

    fun setMultiResponsePolicy(
        ctx: CommandContext,
        channelId: Long? = null,
        mode: String = "single",
        maxCandidates: Int = 1,
        synthesisEnabled: Boolean = false,
        requireDistinctModels: Boolean = false,
        timeoutSeconds: Int = 120,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val normalizedMode =
            when (mode.trim().lowercase()) {
                "single", "단일" -> "single"
                "compare", "비교" -> "compare"
                "debate", "토론" -> "debate"
                "deep", "깊은" -> "compare"
                else -> "single"
            }
        val targetChannelId = channelId ?: ctx.channelId
        return runCatching {
            val policy =
                multiResponse.savePolicy(
                    guildId = ctx.guildId,
                    channelId = targetChannelId,
                    channelAiId = null,
                    mode = normalizedMode,
                    maxCandidates = maxCandidates,
                    requireDistinctModels = requireDistinctModels,
                    providerDailyLimit = 0,
                    timeoutSeconds = timeoutSeconds,
                    synthesisEnabled = synthesisEnabled,
                )
            val safetyNote =
                if (policy.maxCandidates > 1 || policy.synthesisEnabled) {
                    "\n⚠️ 고급 fan-out은 `multi-response` 태그로 opt-in 한 Provider만 쓰고, 과부하/민감질문이면 자동 차단됩니다."
                } else {
                    ""
                }
            Replies.ok(
                "다중응답 정책을 저장했습니다. <#$targetChannelId>\n" +
                    "mode: `${policy.mode}` · 후보: `${policy.maxCandidates}` · 서로 다른 모델 우선: `${policy.requireDistinctModels}`\n" +
                    "합성: `${policy.synthesisEnabled}` · 타임아웃: `${policy.timeoutSeconds}s`$safetyNote",
            )
        }.getOrElse { error ->
            Replies.warn("다중응답 정책을 저장하지 못했어요. ${error.message ?: "입력값을 확인해 주세요."}")
        }
    }

    fun multiResponseDryRun(
        ctx: CommandContext,
        prompt: String,
        channelId: Long? = null,
        responseMode: String? = null,
    ): Reply {
        adminOnly(ctx)?.let { return it }
        val targetChannelId = channelId ?: ctx.channelId
        val mode =
            normalizeAskResponseMode(responseMode)
                ?: channelRoutingPolicies
                    .effective(ctx.guildId, targetChannelId, null)
                    .responseMode
        return runCatching {
            val run =
                multiResponse.startRun(
                    guildId = ctx.guildId,
                    channelId = targetChannelId,
                    requestId = "discord-dry-${System.currentTimeMillis()}",
                    promptPreview = prompt,
                    responseMode = mode,
                )
            val next =
                when (run.status) {
                    "running" -> "후보 Provider가 계획되었습니다. 실제 답변 fan-out은 옵트인 단계에서만 연결하세요."
                    "blocked_sensitive" -> "민감정보처럼 보여 fan-out을 차단했습니다. 단일 안전 경로로 안내하세요."
                    "no_provider" -> "온라인 Provider, `multi-response` opt-in 태그, 과부하 상태를 확인하세요."
                    else -> run.failureReason ?: "상태를 확인하세요."
                }
            Reply(
                "🧪 **다중응답 드라이런** <#$targetChannelId>\n" +
                    "run: `${run.id}` · status: `${run.status}` · 후보: `${run.candidateCount}`\n" +
                    "RAG: `${run.ragContextStatus ?: "unknown"}` · context chars: `${run.ragContextChars}`\n" +
                    "모드: `$mode`\n\n" +
                    "다음 행동: $next",
            )
        }.getOrElse { error ->
            Replies.warn("다중응답 드라이런을 만들지 못했어요. ${error.message ?: "정책/Provider 상태를 확인해 주세요."}")
        }
    }

    fun aiNetworkMap(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        return Reply(formatAiNetworkMap(aiNetworkMap.map(ctx.guildId)))
    }

    private fun formatAiNetworkMap(map: AiNetworkMap): String {
        val modelLines =
            map.models.take(8).map { model ->
                val topTags = model.tags.take(3)
                val tags = if (topTags.isEmpty()) "태그 없음" else topTags.joinToString(", ")
                val tiers = model.qualityTiers.joinToString(",")
                "• `${model.modelName}` — 온라인 ${model.onlineProviderCount}/${model.providerCount} · $tiers · $tags"
            }
        val models = modelLines.joinToString("\n").ifBlank { "• 아직 보고된 모델이 없습니다." }
        val channelLines =
            map.channels.take(8).map { channel ->
                val behavior = if (channel.hasBehavior) "행동설정 ON" else "행동설정 필요"
                "• <#${channel.channelId}> → **${channel.name}** · $behavior · 지식공간 ${channel.knowledgeSpaceCount}"
            }
        val channels = channelLines.joinToString("\n").ifBlank { "• 아직 채널 AI가 없습니다." }
        val next = map.nextActions.take(5).joinToString("\n") { "• $it" }
        val topCapabilityTags = map.capabilityTags.take(8)
        val tags = if (topCapabilityTags.isEmpty()) "아직 태그 없음" else topCapabilityTags.joinToString(", ")
        val providerSummary =
            "Provider: 온라인 ${map.onlineProviderCount} / 승인 ${map.approvedProviderCount} · " +
                "모델 ${map.modelCount}종 · 채널 AI ${map.channelAiCount}개 · 지식공간 ${map.knowledgeSpaceCount}개"
        return "🗺️ **AI 네트워크 지도**\n\n" +
            "레벨: `${map.networkLevel}` · 상태: `${map.healthStatus}` · 과부하 경고: `${map.overloadAlertCount}`\n" +
            "$providerSummary\n" +
            "능력 태그: $tags\n\n" +
            "__모델 지도__\n$models\n\n" +
            "__채널 AI__\n$channels\n\n" +
            "__다음 액션__\n$next"
    }

    fun aiNetworkCheck(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        return Reply(formatAiNetworkChecklist(aiNetworkLaunchChecklist.checklist(ctx.guildId)))
    }

    private fun formatAiNetworkChecklist(checklist: NetworkLaunchChecklist): String {
        val topItems =
            checklist.items
                .filter { it.status != "ready" }
                .ifEmpty { checklist.items.take(5) }
                .take(8)
                .joinToString("\n") { item ->
                    val icon =
                        when (item.status) {
                            "ready" -> "✅"
                            "warning" -> "⚠️"
                            else -> "⛔"
                        }
                    "$icon **${item.title}** — ${item.nextAction}"
                }
        val next =
            checklist.nextActions
                .take(5)
                .joinToString("\n") { "• $it" }
                .ifBlank { "• 지금 막을 항목은 없습니다." }
        return "🧭 **AI 네트워크 출시/운영 체크리스트**\n\n" +
            "상태: `${checklist.status}` · Gate: `${checklist.releaseGate}` · 점수: `${checklist.score}`\n" +
            "준비 ${checklist.readyCount} · 주의 ${checklist.warningCount} · 차단 ${checklist.blockedCount}\n\n" +
            "__핵심 항목__\n$topItems\n\n" +
            "__다음 액션__\n$next"
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
