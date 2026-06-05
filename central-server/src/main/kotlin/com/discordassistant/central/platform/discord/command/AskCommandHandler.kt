package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.ainetwork.application.AiQualityFeedbackService
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.ainetwork.application.ModelChoiceDecision
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.channelai.application.ChannelAiProfile
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.knowledge.application.KnowledgeSearchService
import com.discordassistant.central.multiresponse.application.MultiResponseService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.platform.discord.ReplyFeedback
import com.discordassistant.central.platform.discord.ReplyPseudoStream
import com.discordassistant.central.quota.application.RateLimiter
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.routing.application.RequestOrchestrator
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.shared.RequestState
import com.discordassistant.central.shared.ResponseMode
import org.springframework.stereotype.Component

/**
 * /ask·/imagine 실행 명령군과 그 가공 로직(의사 스트리밍·Discord 길이제한·모델 대체 안내·웹검색 출처 푸터·
 * 다중응답 런타임 관측·품질 피드백·모델 자동완성). CommandService 에서 가장 협력자가 많은 덩어리를 응집 단위로
 * 분리 — 가공 로직/문구/포맷 본문 그대로 이동, 시그니처 유지·위임.
 */
@Component
class AskCommandHandler(
    private val orchestrator: RequestOrchestrator,
    private val policy: PolicyService,
    private val registry: ConnectionRegistry,
    private val rateLimiter: RateLimiter,
    private val channelProfiles: ChannelAiProfileService,
    private val channelAiCustomization: ChannelAiCustomizationService,
    private val channelRoutingPolicies: ChannelAiRoutingPolicyService,
    private val knowledgeSearch: KnowledgeSearchService,
    private val multiResponse: MultiResponseService,
    private val qualityFeedback: AiQualityFeedbackService,
    private val webSearchAugmenter: com.discordassistant.central.knowledge.application.WebSearchAugmenter =
        com.discordassistant.central.knowledge.application.NoWebSearch,
    private val guards: SharedCommandGuards,
) {
    companion object {
        private const val DISCORD_REPLY_SAFE_LIMIT = 1850
        private const val PSEUDO_STREAM_MIN_CHARS = 600
        private val PSEUDO_STREAM_STEPS = listOf(33, 66, 100)
    }

    fun ask(
        ctx: CommandContext,
        prompt: String,
        requestedModel: String? = null,
        requestedResponseMode: String? = null,
        webSearch: Boolean = false,
    ): Reply {
        // 요청 우선순위(#150): 관리자/긴급 요청은 분당 쿨다운을 우회한다.
        if (!ctx.isAdmin && !rateLimiter.tryAcquire("ask:${ctx.guildId}:${ctx.userId}")) {
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, guards.lang(ctx))) // 쿨다운 피드백(#191, i18n)
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
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, guards.lang(ctx)))
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

    /** 슬래시 옵션 자동완성용 모델 목록(#179). 현재 길드 풀이 제공하는 모델명(중복 제거·정렬). */
    fun autocompleteModels(ctx: CommandContext): List<String> =
        registry
            .byGuild(ctx.guildId)
            .flatMap { it.capability.models }
            .distinct()
            .sorted()
            .take(25) // Discord 자동완성 최대 25개
}
