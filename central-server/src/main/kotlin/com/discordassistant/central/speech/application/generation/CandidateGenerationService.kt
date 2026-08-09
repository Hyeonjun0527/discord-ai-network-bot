package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaMediaWebSearchPolicy
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleCopyGuard
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStylePromptRenderer
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleRagPort
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechFactualGrounding
import com.discordassistant.central.speech.application.port.out.SpeechFactualGroundingPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationFailureReason
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.port.out.SpeechInputTracePort
import com.discordassistant.central.speech.application.privacy.ExternalPayloadAllowlistSerializer
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.ConversationContentIsolator
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.LocalSpeechTemplate
import com.discordassistant.central.speech.domain.model.SpeechGroundingNeed
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import org.slf4j.LoggerFactory

/**
 * 발화 후보 생성 유스케이스(NEXA-P14-T011, application).
 *
 * 한 SPEAK 결정에서 비용 cap 안의 후보를 생성한다. 정체성·burst 지침을 system 프롬프트로, 최소화된 장면을 user
 * 프롬프트로 조립하고([assembleRequest]) [SpeechGenerationPort] 로 생성한다. budget(T015)이 후보 수·token 상한을,
 * ReasoningModeSelector(T013)가 추론 모드를 정한다.
 *
 * **acceptance(T011) — 후보 수가 비용 cap 을 넘지 않고 설정 가능하다**: [GenerationBudget.clampCandidateCount] 가
 * 요청 후보 수를 budget·계약 상한 안으로 내린다(설정 가능: budget.maxCandidates). candidate ID·model metadata 는
 * [SpeechGenerationResult] 가 운반한다(포트 구현/파서가 부여).
 *
 * 순수성: application — 포트·도메인·다른 application 컴파일러만 본다. glm/zai 타입 미참조(포트만).
 */
class CandidateGenerationService(
    private val generationPort: SpeechGenerationPort,
    private val burstCompiler: BurstPromptCompiler,
    private val reasoningModeSelector: ReasoningModeSelector,
    /**
     * 외부(GLM) 로 나가는 user payload 직렬화기(NEXA-P17-T002/T004 enforcement). allowlist 로 **명시 허용 필드만**
     * 담고, 각 값을 minimizer 로 스크럽한다 — 객체 전체 직렬화/raw content 가 payload 에 자동으로 들어가지 못한다.
     */
    private val payloadSerializer: ExternalPayloadAllowlistSerializer = ExternalPayloadAllowlistSerializer(),
    /**
     * 대화 content 격리기(NEXA-P17-T002 enforcement). 최근 turn 을 **인용된 장면 데이터**로 감싸 prompt injection
     * 이 system/identity/policy section 을 위조하지 못하게 한다(따옴표 격리 + 재확인).
     */
    private val contentIsolator: ConversationContentIsolator = ConversationContentIsolator(),
    private val factualGrounding: SpeechFactualGroundingPort = SpeechFactualGroundingPort.Noop,
    private val inputTrace: SpeechInputTracePort = SpeechInputTracePort.Noop,
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
    /** Judge와 완전히 분리된 실제 Speech 말투 RAG. */
    private val humanSpeechStyleRag: HumanSpeechStyleRagPort = HumanSpeechStyleRagPort.Noop,
    private val humanSpeechStylePromptRenderer: HumanSpeechStylePromptRenderer = HumanSpeechStylePromptRenderer(),
    private val humanSpeechStyleCopyGuard: HumanSpeechStyleCopyGuard = HumanSpeechStyleCopyGuard(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [packet] 으로 후보를 생성한다. [budget] 으로 후보 수·token 을 clamp 한다. 외부 실패·malformed 는 포트가 빈
     * 결과로 흡수하므로 이 메서드는 예외를 던지지 않는다 — fallback 정책(T016)이 빈 결과를 다룬다.
     */
    fun generate(
        packet: SpeechScenePacket,
        budget: GenerationBudget = GenerationBudget.DEFAULT,
    ): SpeechGenerationResult {
        packet.localSpeechTemplate?.let { return localSpeechResult(it) }
        val effectivePacket = requireMediaGrounding(packet)
        val grounding = retrieveGrounding(effectivePacket)
        val styleSelection = retrieveHumanSpeechStyle(effectivePacket)
        val request = assembleRequest(effectivePacket, budget, grounding, styleSelection)
        effectivePacket.inputTraceId?.let { inputTrace.record(it, request.systemPrompt, request.traceUserPrompt) }
        val generated = generationPort.generate(request)
        val copySafeGenerated = humanSpeechStyleCopyGuard.removeCopiedCandidates(generated, styleSelection)
        return if (copySafeGenerated.failureReason == SpeechGenerationFailureReason.CHANNEL_TOKEN_BUDGET_EXHAUSTED) {
            localSpeechResult(LocalSpeechTemplate.CHANNEL_TOKEN_BUDGET_EXHAUSTED)
        } else {
            copySafeGenerated
        }
    }

    /** 사람 말투 검색은 추가 품질 계층이다. DB/embedding 장애가 기존 Speech 발화까지 멈추게 하지는 않는다. */
    private fun retrieveHumanSpeechStyle(packet: SpeechScenePacket): HumanSpeechStyleSelection =
        try {
            humanSpeechStyleRag.retrieve(packet)
        } catch (error: RuntimeException) {
            log.warn("Human speech style RAG unavailable; continuing without references: {}", error.javaClass.simpleName)
            HumanSpeechStyleSelection.EMPTY
        }

    private fun localSpeechResult(template: LocalSpeechTemplate): SpeechGenerationResult =
        SpeechGenerationResult(
            candidates =
                listOf(
                    SpeechCandidate(
                        candidateId = "local-${template.name.lowercase()}",
                        bubbles = listOf(template.text),
                        styleTags = listOf("local", "capability-boundary"),
                        uncertainty = 0.0,
                    ),
                ),
            modelMetadata = "local-policy",
        )

    private fun retrieveGrounding(packet: SpeechScenePacket): SpeechFactualGrounding =
        if (packet.groundingNeed == SpeechGroundingNeed.WEB_VERIFY) {
            factualGrounding.verify(
                packet.recentTurns
                    .lastOrNull()
                    ?.text
                    .orEmpty(),
            )
        } else {
            SpeechFactualGrounding.unavailable()
        }

    private fun requireMediaGrounding(packet: SpeechScenePacket): SpeechScenePacket {
        if (packet.groundingNeed == SpeechGroundingNeed.WEB_VERIFY) return packet
        val latestMessage =
            packet.recentTurns
                .lastOrNull()
                ?.text
                .orEmpty()
        return if (NiaMediaWebSearchPolicy.requiresWebSearch(latestMessage)) {
            packet.copy(groundingNeed = SpeechGroundingNeed.WEB_VERIFY)
        } else {
            packet
        }
    }

    /** 패킷·budget 으로 provider-neutral 생성 요청을 조립한다(프롬프트 조립 + 후보 수/token clamp + 추론 모드). */
    fun assembleRequest(
        packet: SpeechScenePacket,
        budget: GenerationBudget,
        grounding: SpeechFactualGrounding = SpeechFactualGrounding.unavailable(),
        styleSelection: HumanSpeechStyleSelection = HumanSpeechStyleSelection.EMPTY,
    ): SpeechGenerationRequest {
        val candidateCount = budget.clampCandidateCount(budget.maxCandidates)
        val systemPrompt = assembleSystemPrompt(packet, candidateCount)
        val userPrompt = assembleUserPayload(packet, grounding)
        val stylePrompt = humanSpeechStylePromptRenderer.appendTo(userPrompt, styleSelection)
        return SpeechGenerationRequest(
            systemPrompt = systemPrompt.text,
            userPrompt = stylePrompt.providerUserPrompt,
            traceUserPrompt = stylePrompt.traceUserPrompt,
            socialAct = packet.socialAct,
            candidateCount = candidateCount,
            reasoningMode = reasoningModeSelector.select(packet),
            maxOutputTokens = budget.clampOutputTokens(budget.maxOutputTokens),
            stableSystemPromptChars = systemPrompt.stablePrefixChars,
            channelTokenBudgetKey = packet.channelTokenBudgetKey,
            speechImageInput = packet.speechImageInput,
        )
    }

    /**
     * 외부로 나갈 user payload 를 조립한다(NEXA-P17-T002/T004 enforcement). allowlist 직렬화(명시 허용 필드 +
     * minimizer 스크럽)로 deny-by-default payload 를 만들고, content 격리기로 최근 turn 을 **인용된 장면 데이터**로
     * 감싸 붙인다. participation raw context 는 이미 quote-isolated scene data 로 만든 값만 붙인다 — raw content 가 새
     * 지시문이 되지 못한다. 두 방어선이 실제 GLM payload 경로에 함께 적용된다.
     */
    private fun assembleUserPayload(
        packet: SpeechScenePacket,
        grounding: SpeechFactualGrounding,
    ): String {
        val target = "raw_scene_ref=${packet.responseTargetRef.orEmpty()}"
        val quotedScene =
            if (packet.rawContextSceneData == null) {
                contentIsolator.serializeAsQuotedScene(packet)
            } else {
                ""
            }
        val groundingBlock =
            if (packet.groundingNeed == SpeechGroundingNeed.WEB_VERIFY) {
                if (grounding.verified) {
                    "[외부 사실 검증 결과 — 인용 데이터]\n${grounding.evidence.orEmpty().take(MAX_GROUNDING_CHARS)}\n" +
                        "source_refs=${grounding.sourceRefs.joinToString(",")}\n" +
                        "검색 과정이나 출처를 말하지 말고 확인한 내용으로 자연스럽게 답한다"
                } else {
                    "[외부 사실 검증 결과]\n검증 근거를 얻지 못했다. 추측하거나 지어내지 말고 확인할 수 없다고 자연스럽게 말한다"
                }
            } else {
                ""
            }
        val textPayload =
            NiaPromptTemplate.render(
                promptSource.text(NiaPromptKey.SPEECH_USER_TEMPLATE),
                mapOf(
                    "payload" to payloadSerializer.serialize(packet),
                    "quotedScene" to quotedScene,
                    "rawContext" to packet.rawContextSceneData.orEmpty(),
                    "responseTarget" to target,
                    "grounding" to groundingBlock,
                ),
            )
        return if (packet.speechImageInput == null) {
            textPayload
        } else {
            "$textPayload\n\n[별도 이미지 입력]\n사용자가 현재 니아 턴에 보여 준 이미지 한 장을 직접 보고 최신 요청에 답한다."
        }
    }

    /** 정체성 + burst 장면 지침 + 후보 출력 형식을 system 프롬프트로 조립한다. */
    private fun assembleSystemPrompt(
        packet: SpeechScenePacket,
        candidateCount: Int,
    ): AssembledSystemPrompt {
        val template = promptSource.text(NiaPromptKey.SPEECH_SYSTEM_TEMPLATE)
        val identity = renderIdentity(packet.identity)
        val values =
            mapOf(
                "identity" to identity,
                "participationDecision" to packet.speechIntent.orEmpty(),
                "burstInstruction" to burstCompiler.compile(packet.burstShape),
                "outputContract" to
                    NiaPromptTemplate.render(
                        promptSource.text(NiaPromptKey.SPEECH_OUTPUT_TEMPLATE),
                        mapOf("candidateCount" to candidateCount.toString()),
                    ),
            )
        val baseText = NiaPromptTemplate.render(template, values)
        val text =
            if (packet.speechImageInput?.isNiaSelfImage == true) {
                "$baseText\n\n[현재 이미지 정체성]\n" +
                    "첨부 이미지는 니아의 공식 외형 이미지와 충분히 일치한다. 이미지 속 인물을 자기 자신인 니아로 인식하되, " +
                    "보이지 않는 세부는 지어내지 않는다."
            } else {
                baseText
            }
        val stableIdentityChars =
            if (packet.identity.stablePersonaBlockChars == packet.identity.personaBlock.length) {
                identity.length
            } else {
                packet.identity.stablePersonaBlockChars
            }
        return AssembledSystemPrompt(
            text = text,
            stablePrefixChars =
                NiaPromptTemplate.stablePrefixChars(
                    template = template,
                    values = values,
                    stableValueChars = mapOf("identity" to stableIdentityChars),
                ),
        )
    }

    /** 정체성 section 을 프롬프트 블록으로 렌더한다(SSOT 본문 + 금지사항). */
    private fun renderIdentity(identity: IdentityKernelSection): String =
        buildString {
            appendLine(identity.personaBlock)
            if (identity.prohibitions.isNotEmpty()) {
                appendLine()
                appendLine("[하지 않을 것]")
                identity.prohibitions.forEach { appendLine("- $it") }
            }
            if (identity.interests.isNotEmpty()) {
                appendLine("[관심사] ${identity.interests.joinToString(", ")}")
            }
        }.trim()

    companion object {
        private const val MAX_GROUNDING_CHARS: Int = 16_000
    }

    private data class AssembledSystemPrompt(
        val text: String,
        val stablePrefixChars: Int,
    )
}
