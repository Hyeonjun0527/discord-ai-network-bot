package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.ainetwork.application.AiQualityFeedbackService
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.ainetwork.application.ModelChoiceDecision
import com.discordassistant.central.ainetwork.application.NiaAffinityService
import com.discordassistant.central.ainetwork.domain.model.AffinityStage
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.channelai.application.ChannelAiProfile
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
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
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.RemoteCancelledException
import com.discordassistant.central.routing.application.CloudThinkingOption
import com.discordassistant.central.routing.application.RequestOrchestrator
import com.discordassistant.central.routing.application.ThinkingRouter
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.shared.ContentSafety
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.shared.RequestState
import com.discordassistant.central.shared.ResponseMode
import org.slf4j.LoggerFactory
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
    private val globalPromptSets: GlobalPromptSetService,
    private val channelRoutingPolicies: ChannelAiRoutingPolicyService,
    private val knowledgeSearch: KnowledgeSearchService,
    private val multiResponse: MultiResponseService,
    private val qualityFeedback: AiQualityFeedbackService,
    private val niaAffinity: NiaAffinityService,
    // 이미지 안전 심사·번역을 central 이 직접 z.ai(GLM)로 처리하는 백엔드(ADR 0006 단계2). 키 있으면 isEnabled().
    private val cloudLlm: com.discordassistant.central.routing.application.CloudLlm,
    // 이미지 픽셀까지 central 이 직접 만드는 클라우드 SD 백엔드(ADR 0006 단계4 — 완전 앱리스). 키 있으면 isEnabled().
    private val cloudImageBackend: com.discordassistant.central.routing.application.CloudImageBackend,
    // 무료 클라우드 폴백(로컬 프로바이더 부재 시 glm-5.1)의 인당 rate limit — 무료 자원 남용 방지.
    private val freeCloudRateLimiter: com.discordassistant.central.quota.application.FreeAskRateLimiter,
    // /질문 전용 단기 멀티턴 대화 기억(채널+유저·인메모리·TTL). "방금 뭐라고 했지?" 맥락을 클라우드 직결에 제공.
    private val askMemory: com.discordassistant.central.routing.application.AskConversationMemory,
    // 어드민(프로젝트 운영자) 전용 모델/thinking 강제 지정 게이트(=central.dashboard.admin-user-ids). 비어드민은 무시.
    private val projectAdmins: com.discordassistant.central.provider.adapter.inbound.web.ProjectAdmins,
    private val webSearchAugmenter: com.discordassistant.central.knowledge.application.WebSearchAugmenter =
        com.discordassistant.central.knowledge.application.NoWebSearch,
    private val guards: SharedCommandGuards,
) {
    companion object {
        // 무료 클라우드 기본 모델(z.ai/GLM). 로컬 프로바이더가 없거나 처리 불가일 때 /질문 이 자동으로 이 모델로 답한다.
        const val FREE_CLOUD_MODEL = "glm-5.1"
        private const val DISCORD_REPLY_SAFE_LIMIT = 1850
        private const val PSEUDO_STREAM_MIN_CHARS = 600
        private val PSEUDO_STREAM_STEPS = listOf(33, 66, 100)
        private val log = LoggerFactory.getLogger(AskCommandHandler::class.java)

        // ── 이미지 정책(central 소유, 에이전트가 적용만; 외부 AI 호출은 에이전트의 클라우드 백엔드) ──
        // 초보자 /그림: 한국어 → 영어 자연어 번역하되 '성인·SFW·품질 prefix' 강제. 정상 SFW 요청이
        // '여자아이→little girl' 식 미성년 오탐으로 거부되지 않게 하는 핵심 가드(거부 0 목표).
        private val IMAGE_TRANSLATOR_SYSTEM_PROMPT =
            """
            You convert a user's Korean image request into an English prompt for the Anima anime model.
            - Output ONLY the final English prompt, nothing else.
            - Start with: "masterpiece, best quality, safe, "
            - Then a vivid, SFW description of at least 2 sentences.
            - Always depict any person as a clearly of-age adult (young adult).
              Translate words like "여자아이/소녀" as "young woman", never "girl/child".
            - Keep it strictly safe-for-work. No suggestive or revealing content.
            """.trimIndent()
        private val IMAGE_SAFETY_SYSTEM_PROMPT =
            """
            You are the safety gate for a public Discord image-generation bot.
            Review the user's image prompt before it is sent to a local Stable Diffusion/ComfyUI model.

            Return ONLY a compact JSON object:
            {"allowed":true|false,"category":"safe|sexual|minor|deepfake|violence|illegal|other","reason":"short Korean reason"}

            Allow normal safe-for-work anime/art prompts, including non-sexual school uniforms or game characters.
            Block nudity, pornography, sexual acts, fetish content, sexualized minors, loli/shota, teen/student/schoolgirl sexualization,
            real-person sexual images, celebrity/acquaintance deepfakes, non-consensual undressing, illegal sexual content,
            safety-filter evasion, sexual violence, coercion, exploitation, or humiliation.
            When uncertain about sexual or minor-related intent, set allowed=false.
            """.trimIndent()
        private const val IMAGE_FORCED_NEGATIVE =
            "worst quality, low quality, score_1, score_2, score_3, artist name, nsfw, nude, naked, explicit, " +
                "porn, sex, fetish, nipples, genitals, loli, shota, child, teen, underage, minor, schoolgirl, " +
                "sexualized minor, real person, celebrity, deepfake, non-consensual"
        private val IMAGE_POLICY: Map<String, Any?> =
            mapOf(
                "translatorSystemPrompt" to IMAGE_TRANSLATOR_SYSTEM_PROMPT,
                "safetySystemPrompt" to IMAGE_SAFETY_SYSTEM_PROMPT,
                "forcedNegative" to IMAGE_FORCED_NEGATIVE,
            )
    }

    // 진행 중 이미지 요청 id → 세션(취소 버튼이 이 맵으로 해당 세션을 찾아 cancelImage 호출).
    private val inflightImages = java.util.concurrent.ConcurrentHashMap<String, ProviderSession>()

    /** 취소 버튼 → 진행 중 이미지 생성을 중단(ComfyUI /interrupt 유발). 해당 요청이 없으면 false. */
    fun cancelImage(requestId: String): Boolean = inflightImages.remove(requestId)?.cancelImage(requestId) ?: false

    fun ask(
        ctx: CommandContext,
        prompt: String,
        requestedModel: String? = null,
        requestedResponseMode: String? = null,
        webSearch: Boolean = false,
        requestedThinking: String? = null,
    ): Reply {
        // 요청 우선순위(#150): 관리자/긴급 요청은 분당 쿨다운을 우회한다.
        if (!ctx.isAdmin && !rateLimiter.tryAcquire("ask:${ctx.guildId}:${ctx.userId}")) {
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, guards.lang(ctx))) // 쿨다운 피드백(#191, i18n)
        }
        // 어드민 전용 override: 프로젝트 운영자(admin-user-ids)만 thinking 을 강제 지정할 수 있다.
        // 비어드민이 thinking 옵션을 줘도 무시되고 규칙 기반 라우터가 자동 결정한다(게이트 = ProjectAdmins 재사용).
        // (model 옵션은 기존대로 채널/서버 모델 정책으로 검증·반영된다 — 동작 보존. 어드민이 cloud 모델을 고르면
        //  아래 클라우드 경로에서 그 모델로 직결되어 "모델 강제"가 자연스럽게 적용된다.)
        val isProjectAdmin = projectAdmins.isProjectAdmin(ctx.userId)
        val adminThinkingOverride = if (isProjectAdmin) CloudThinkingOption.parse(requestedThinking) else null
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

        // 기본은 무료 클라우드(☁️). 단 커뮤니티 로컬 프로바이더가 있고 클라우드 모델을 명시적으로 고르지
        // 않았다면 로컬을 우선(🖥️) 시도하고, 로컬이 처리 못하면(FAILED) 무료 클라우드로 폴백한다.
        val preferLocal = !isCloudModel(selectedModel) && hasLocalProvider(ctx.guildId)
        if (preferLocal) {
            // 로컬 에이전트 경로는 멀티턴 기억/thinking 을 쓰지 않는다(클라우드 직결 전용 기능).
            val local = runOrchestrator(ctx, prompt, selectedModel, responseMode, webSearch, routingPolicy.maxCandidates)
            if (local.state == RequestState.COMPLETED) {
                return completedAskReply(
                    local.text.orEmpty().withWebSources(local.sources),
                    modelChoice,
                    local.requestId,
                    usedCloud = false,
                )
            }
            // 로컬이 완료가 아니면(무프로바이더·프로바이더 실패·정책 거부) 무료 클라우드로 폴백한다.
            // 유저 정책 거부(차단·일일 한도·채널 금지·부담 권한)는 클라우드 호출에서도 동일하게 재검사되어
            // 같은 결과를 내므로 우회가 되지 않는다(오케스트레이터가 모델과 무관하게 정책을 재적용).
        }

        // 무료 클라우드 z.ai — 기본 경로이자 로컬 폴백. 무료 자원 인당 상한 적용.
        freeCloudRateLimiter.check(ctx.userId)?.let { return Replies.reject(it) }
        // 선택 모델이 클라우드(glm-*)면 그 모델로 직결(어드민이 cloud 모델 지정 시 강제 적용), 아니면 기본 무료 클라우드 모델.
        val cloudModel = selectedModel?.trim()?.takeIf { isCloudModel(it) } ?: FREE_CLOUD_MODEL
        // thinking 속도 라우팅: 어드민 override 가 있으면 그 값, 없으면 규칙 기반 라우터(기본 disabled).
        val thinking = adminThinkingOverride ?: ThinkingRouter.route(prompt)
        // 멀티턴 단기 기억(채널+유저)을 z.ai messages 앞에 붙인다("방금 뭐라고 했지?" 맥락).
        val history = askMemory.history(ctx.channelId, ctx.userId)
        val cloud =
            runOrchestrator(
                ctx,
                prompt,
                cloudModel,
                responseMode,
                webSearch,
                routingPolicy.maxCandidates,
                dedup = false,
                history = history,
                thinking = thinking,
            )
        return when (cloud.state) {
            RequestState.COMPLETED -> {
                val answer = cloud.text.orEmpty()
                // 이번 turn(원문 질문 + 원문 답)을 기억에 append — 다음 질문이 맥락을 이어가게.
                askMemory.append(ctx.channelId, ctx.userId, prompt, answer)
                completedAskReply(answer.withWebSources(cloud.sources), modelChoice, cloud.requestId, usedCloud = true)
            }
            RequestState.REJECTED -> Replies.reject(cloud.failReason ?: Messages.get(Messages.Key.ASK_REJECTED, guards.lang(ctx)))
            else -> Replies.warn(cloud.failReason ?: Messages.get(Messages.Key.ASK_FAILED, guards.lang(ctx)))
        }
    }

    /**
     * 길드 풀에 로컬(비-클라우드) 프로바이더가 있는지 — /질문 의 로컬 우선 판단(없으면 무료 클라우드 기본).
     * "클라우드 전용"은 광고 모델이 있고 그게 전부 클라우드(glm-*)인 경우만. 모델 미광고(빈 목록)·
     * 비-클라우드 모델 보유 프로바이더는 로컬로 본다.
     */
    private fun hasLocalProvider(guildId: Long): Boolean =
        registry.byGuild(guildId).any { session -> session.capability.models.none { isCloudModel(it) } }

    /** 한 번의 오케스트레이터 호출(+멀티응답 런타임 관측). /질문 의 로컬 1차·클라우드 2차에서 재사용. */
    private fun runOrchestrator(
        ctx: CommandContext,
        prompt: String,
        model: String?,
        responseMode: String,
        webSearch: Boolean,
        maxCandidates: Int,
        dedup: Boolean = true,
        history: List<com.discordassistant.central.routing.application.CloudTurn> = emptyList(),
        thinking: com.discordassistant.central.routing.application.CloudThinking? = null,
    ): com.discordassistant.central.routing.domain.model.OrchestrationResult {
        val run = startRuntimeMultiResponseObservation(ctx, prompt, responseMode, maxCandidates)
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
                    preferredModel = model,
                    responseMode = responseMode,
                    webSearch = webSearch && webSearchAugmenter.isEnabled(),
                    // 부담 수준은 사용자 실제 질문 길이로 — 항상 주입되는 시스템 프롬프트(정체성·few-shot)가 등급을 부풀리지 않게.
                    weighChars = prompt.length,
                ),
                // 클라우드 폴백(2차)은 dedup=false — 1차에서 이미 멱등성 통과했고, 같은 프롬프트라 중복으로 막히면 폴백이 영구 실패한다.
                dedup = dedup,
                // 멀티턴 기억·thinking 은 클라우드 직결(glm-*) 경로에서만 적용된다(로컬 경로는 빈 리스트/null).
                history = history,
                thinking = thinking,
            )
        run?.let {
            recordRuntimeMultiResponseResult(
                runId = it.id,
                providerId = result.providerId,
                modelName = model,
                result = result,
                latencyMs = elapsedMillis(startedAtNanos),
            )
        }
        return result
    }

    /** 클라우드(무료 z.ai/GLM) 모델인지. 출처 아이콘(☁️/🖥️)·폴백 재시도 판단에 쓴다. */
    private fun isCloudModel(model: String?): Boolean = model?.trim()?.lowercase()?.startsWith("glm") == true

    /**
     * /그림(imagine) — 기본은 **무료 클라우드 Stable Diffusion**(관리자 유료 SD API 키 1개로 전원 무료, ☁️).
     * 서버가 **로컬 ComfyUI 를 따로 연결**해 두면 그 서버만 로컬로 처리(🖥️). 텍스트 /질문 의 클라우드 기본과 동일 패턴.
     * 한국어 프롬프트는 에이전트 백엔드에서 영어 자연어 번역(IMAGE_POLICY 적용·성인/SFW 가드) 후 생성.
     * onStart(requestId): 취소 버튼 부착·취소 매핑 등록용. onProgress: 진행률 라이브 편집용.
     */
    fun imagine(
        ctx: CommandContext,
        prompt: String,
        onStart: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Reply {
        if (prompt.isBlank()) return Replies.warn("이미지로 만들 내용을 입력해 주세요.")
        if (!ctx.isAdmin && !rateLimiter.tryAcquire("imagine:${ctx.guildId}:${ctx.userId}")) {
            return Replies.cooldown(Messages.get(Messages.Key.COOLDOWN, guards.lang(ctx)))
        }
        val imageProviders = registry.byGuild(ctx.guildId).filter { "image" in it.capability.capabilities }
        // 로컬 ComfyUI(따로 설정)가 있으면 로컬 우선, 없으면 무료 클라우드 SD 기본. 서브타입 미광고(구버전)는 그대로 사용.
        val local = imageProviders.filter { "image-local" in it.capability.capabilities }
        val cloud = imageProviders.filter { "image-cloud" in it.capability.capabilities }
        val pool = local.ifEmpty { cloud }.ifEmpty { imageProviders }

        // ADR 0006 단계4: central 이 클라우드 SD(Stability/RunPod) 키를 가지면 **에이전트 풀 없이도** 픽셀까지
        // 직접 만든다(완전 앱리스). fail-closed — 심사(cloudLlm)도 활성일 때만 직접 경로를 쓴다(심사 보장).
        // central 직접 키가 없으면 기존 에이전트 풀 경로(아래)로 폴백한다.
        if (cloudImageBackend.isEnabled() && cloudLlm.isEnabled()) {
            return imagineViaCentral(ctx, prompt)
        }

        if (pool.isEmpty()) {
            return Replies.warn(
                "🖼️ 지금은 이미지를 만들 수 있는 곳이 없어요.\n" +
                    "관리자가 무료 클라우드 Stable Diffusion(유료 SD API 키)을 연결하면 전원이 바로 쓸 수 있고, " +
                    "직접 돌리려면 로컬 ComfyUI 를 켜고 에이전트를 `--enable-image` 로 실행하세요.",
            )
        }
        val usedCloud = local.isEmpty() && cloud.isNotEmpty()
        val sourceIcon = if (usedCloud) "☁️" else "🖥️"
        val session = pool.minByOrNull { it.activeRequests } ?: pool.first()

        // 캡션은 항상 사용자가 입력한 원문 prompt 로 표기한다(동작보존). 픽셀 생성에 보내는 프롬프트만
        // central 심사/번역 여부에 따라 달라진다.
        if (cloudLlm.isEnabled()) {
            // central 안전 심사(fail-closed) — 심사 자체가 실패하면 안전하지 않은 이미지가 새지 않게 차단한다.
            val review =
                reviewOrBlock(prompt) ?: return Replies.warn("이미지 안전 심사를 완료하지 못해 만들 수 없어요.")
            if (!review.allowed) {
                return Replies.warn("안전 정책상 만들 수 없어요${review.reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            // 픽셀 생성만 에이전트로 위임: 정제된 영어 프롬프트 + forcedNegative + preTranslated=true.
            // 구버전 에이전트는 preTranslated 를 모르고 자기 GLM 으로 영→영 재번역·이중 재심사하나(무해) 하위호환.
            // 단계3에서 에이전트가 preTranslated 를 인식해 심사/번역을 스킵하도록 바꾼다.
            val centralPolicy: Map<String, Any?> =
                mapOf("forcedNegative" to IMAGE_FORCED_NEGATIVE, "preTranslated" to true)
            return runImage(session, translateOrFallback(prompt), sourceIcon, prompt, centralPolicy, onStart, onProgress)
        }

        // central 키 없음 → 기존 동작 그대로: 시스템 프롬프트 포함 IMAGE_POLICY 로 에이전트가 심사/번역(하위호환·롤백 안전).
        return runImage(session, prompt, sourceIcon, prompt, IMAGE_POLICY, onStart, onProgress)
    }

    /**
     * ADR 0006 단계4 — central 이 심사→번역→**픽셀 생성**까지 직접(에이전트/onStart/onProgress 없음, 단발 API).
     * fail-closed: 호출 전제로 cloudLlm·cloudImageBackend 가 모두 활성. 심사 실패/거부 시 픽셀을 만들지 않는다.
     * 캡션·Reply 형식은 에이전트 경로(☁️)와 동일 — 게시확인 게이트가 imagePng 를 그대로 받는다.
     */
    private fun imagineViaCentral(
        ctx: CommandContext,
        prompt: String,
    ): Reply {
        val review = reviewOrBlock(prompt) ?: return Replies.warn("이미지 안전 심사를 완료하지 못해 만들 수 없어요.")
        if (!review.allowed) {
            return Replies.warn("안전 정책상 만들 수 없어요${review.reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
        }
        val positive = translateOrFallback(prompt)
        val (w, h) = cloudImageBackend.defaultResolution()
        return try {
            val bytes = cloudImageBackend.txt2img(positive, w, h, IMAGE_FORCED_NEGATIVE)
            Reply("🖼️ ☁️ \"${prompt.take(200)}\"", ephemeral = false, imagePng = bytes)
        } catch (e: Exception) {
            // 사용자에겐 친화 메시지, 서버엔 원인을 남긴다(예외 원칙). central 직접 생성 실패는 명령을 깨지 않는다.
            log.warn("central 클라우드 이미지 생성 실패: {}", e.message, e)
            Replies.warn("이미지 생성에 실패했어요. 잠시 후 다시 시도해 주세요.")
        }
    }

    /** central 안전 심사(fail-closed). 통과/거부 결과를 반환하고, 심사 호출 자체가 실패하면 null(상위가 차단). */
    private fun reviewOrBlock(prompt: String): com.discordassistant.central.routing.application.ImageReview? =
        runCatching { cloudLlm.reviewImagePrompt(prompt, IMAGE_SAFETY_SYSTEM_PROMPT) }
            .getOrElse {
                log.warn("이미지 안전 심사 실패(차단): {}", it.message)
                null
            }

    /** central 번역(실패=원문 폴백, 로깅) — 번역 실패는 차단 사유가 아니다(원문으로 생성 시도). */
    private fun translateOrFallback(prompt: String): String =
        runCatching { cloudLlm.translateImagePrompt(prompt, IMAGE_TRANSLATOR_SYSTEM_PROMPT) }
            .getOrElse {
                log.warn("이미지 번역 실패(원문 폴백): {}", it.message)
                prompt
            }

    /**
     * 한 이미지 요청을 에이전트로 보내 PNG 를 받아 Reply 로 감싼다. [generationPrompt] 는 픽셀 생성에 보낼
     * 프롬프트(central 번역 시 정제 영어, 아니면 원문), [captionPrompt] 는 항상 사용자 원문(캡션 표기·동작보존).
     */
    private fun runImage(
        session: ProviderSession,
        generationPrompt: String,
        sourceIcon: String,
        captionPrompt: String,
        imagePolicy: Map<String, Any?>,
        onStart: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): Reply {
        var requestId: String? = null
        return try {
            val bytes =
                session
                    .sendImage(generationPrompt, imagePolicy, { id ->
                        requestId = id
                        inflightImages[id] = session
                        onStart(id)
                    }, onProgress)
                    .get()
            Reply("🖼️ $sourceIcon \"${captionPrompt.take(200)}\"", ephemeral = false, imagePng = bytes)
        } catch (e: Exception) {
            if (e is RemoteCancelledException || e.cause is RemoteCancelledException) {
                Replies.warn("🛑 이미지 생성을 취소했어요.")
            } else {
                // 사용자에겐 친화 메시지를, 서버엔 원인(스택 포함)을 남긴다(예외 원칙 3·4). broad catch 는
                // 한 이미지 요청 실패가 명령 전체를 깨지 않게 하는 의도적 경계.
                log.warn("이미지 생성 실패: {}", e.message, e)
                Replies.warn("이미지 생성에 실패했어요. 잠시 후 다시 시도해 주세요.")
            }
        } finally {
            requestId?.let { inflightImages.remove(it) }
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
        usedCloud: Boolean,
    ): Reply {
        // 출처 아이콘 하나만 앞에 붙인다(문구 안내 없이): ☁️=무료 클라우드, 🖥️=커뮤니티 로컬 프로바이더.
        // 클라우드 폴백 답변엔 "모델 대체" 문구를 더하지 않는다(아이콘이 출처를 대신 알려줌).
        val sourceIcon = if (usedCloud) "☁️" else "🖥️"
        val body = if (usedCloud) answer else answer.withModelFallbackNotice(modelChoice)
        val fullContent = "$sourceIcon $body"
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
            }.onFailure { log.warn("의사 스트리밍 계획 실패(요청 길이={}): {}", fullContent.length, it.message) }
                .getOrNull()
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
        }.onFailure { log.warn("멀티응답 관측 시작 실패(guild={}): {}", ctx.guildId, it.message) }
            .getOrNull()
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
        }.onFailure { log.warn("멀티응답 결과 기록 실패(runId={}): {}", runId, it.message) }
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
            }.onFailure { log.warn("지식 컨텍스트 조회 실패(guild={}, channel={}): {}", ctx.guildId, ctx.channelId, it.message) }
                .getOrNull()
        val contextText = knowledgeContext?.contextText?.takeIf { it.isNotBlank() }
        activeChannelAiExecutionPrompt(ctx, contextText)?.let { return it }
        // 경로②③: 채널 AI 커스텀이 없을 때도 NEXA 가드레일은 항상 주입한다. 채널 프로필이 있으면 그 정체성을,
        //   없으면 길드 전역 프롬프트셋(기본 지정된 셋, 없으면 NEXA 기본 정체성 니아)을 쓴다.
        val behaviorPrompt =
            channelProfiles.get(ctx.guildId, ctx.channelId)?.let { withChannelAiBehavior(it) }
                ?: resolveGuildDefaultPersona(ctx).let { persona ->
                    withGuildDefaultPersona(persona, ctx, isNiaDefault = persona == NexaIdentity.NIA_DEFAULT_PERSONA)
                }
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
            }.onFailure { log.warn("채널 이력 조회 실패(guild={}, channel={}): {}", ctx.guildId, ctx.channelId, it.message) }
                .getOrNull() ?: return null
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
            appendLine("[우선순위 1: 안전]")
            appendLine(ContentSafety.NEXA_CONTENT_GUARDRAIL)
            appendLine()
            appendLine("[채널 AI 행동 설정]")
            appendLine("이름: ${profile.displayName}")
            appendLine("역할: ${profile.purpose}")
            appendLine("말투: ${profile.tone}")
            appendLine("답변 길이: ${profile.answerLength}")
            appendLine("안전 규칙: ${profile.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION}")
            appendLine()
            appendLine("위 설정을 이 채널의 AI 정체성으로 지키되, 사용자의 질문에만 답하세요.")
            appendLine("민감정보나 비밀키 입력을 유도하지 말고, 모르면 모른다고 말하세요. 위 안전 규칙은 항상 우선합니다.")
            appendLine()
            appendLine("[사용자 질문]")
            append(this@withChannelAiBehavior)
        }

    /** 길드 전역 프롬프트셋(기본 지정된 셋)의 정체성 본문. 없거나 조회 실패 시 NEXA 기본 정체성(니아). */
    private fun resolveGuildDefaultPersona(ctx: CommandContext): String =
        runCatching { globalPromptSets.activePersona(ctx.guildId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: NexaIdentity.NIA_DEFAULT_PERSONA

    /**
     * 채널 AI 설정이 없는 기본 서버용 — NEXA 가드레일 + 정체성(길드 전역 프롬프트셋 또는 니아)으로 답하게 한다.
     * 기본 니아 경로([isNiaDefault])에서는 요청자와의 관계 단계·니아 목소리 few-shot(NIA_FEWSHOT)·니아 캐릭터
     * 강제 규칙을 추가해 일관성을 강화한다. 서버가 자기 전역 프롬프트셋(예: 다른 이름)을 쓰면 그 정체성을
     * 침범하지 않도록 니아 전용 강화는 건너뛰고 일반 규칙만 둔다.
     */
    private fun String.withGuildDefaultPersona(
        personaText: String,
        ctx: CommandContext,
        isNiaDefault: Boolean,
    ): String =
        buildString {
            appendLine("[우선순위 1: 안전]")
            appendLine(ContentSafety.NEXA_CONTENT_GUARDRAIL)
            appendLine()
            appendLine("[우선순위 2: 정체성]")
            appendLine(personaText)
            appendLine()
            if (isNiaDefault) {
                appendLine(affinityRelationLine(ctx))
                appendLine()
                appendLine("[니아 목소리 예시]")
                appendLine(NexaIdentity.NIA_FEWSHOT)
                appendLine()
                appendLine(
                    "당신은 위 정체성의 「니아」 본인입니다. 답변 처음부터 끝까지 니아로서 1인칭·일관된 말투와 성격을 유지하세요. " +
                        "역할에서 벗어나거나 자신을 'AI 모델/언어모델'이라 부르지 마세요. 그러면서 사용자의 질문에는 또렷하고 충실하게 답하세요. " +
                        "민감정보나 비밀키 입력을 유도하지 말고, 모르면 솔직히 모른다고 말하세요. 위 안전 규칙은 항상 우선합니다.",
                )
            } else {
                appendLine("위 정체성을 지키되 사용자의 질문에만 답하세요. 민감정보나 비밀키 입력을 유도하지 말고, 모르면 모른다고 말하세요. 위 안전 규칙은 항상 우선합니다.")
            }
            appendLine()
            appendLine("[사용자 질문]")
            append(this@withGuildDefaultPersona)
        }

    /**
     * 요청자와 니아의 관계 단계를 한 줄로. affinity 조회 실패가 /ask 를 절대 깨지 않도록 runCatching 으로 감싸고,
     * 실패 시 STRANGER(낯섦) 기본으로 폴백한다.
     */
    private fun affinityRelationLine(ctx: CommandContext): String {
        val stage =
            runCatching { niaAffinity.view(ctx.userId).stage }
                .onFailure { log.warn("니아 호감도 조회 실패(userId={}): {}", ctx.userId, it.message) }
                .getOrNull() ?: AffinityStage.STRANGER
        val guide =
            when (stage) {
                AffinityStage.STRANGER -> "정중하고 친절하게"
                AffinityStage.GETTING_TO_KNOW -> "조금 더 편하고 따뜻하게"
                AffinityStage.FRIENDLY -> "친근하게, 가벼운 농담도 곁들여"
                AffinityStage.BEST_FRIEND -> "오랜 친구처럼 편안하고 다정하게(과하지 않게)"
            }
        return "[이 사용자와의 관계] 현재 단계: ${stage.displayName} — $guide"
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
