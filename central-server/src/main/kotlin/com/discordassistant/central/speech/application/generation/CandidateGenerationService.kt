package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.privacy.ExternalPayloadMinimizer
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 후보 다중 생성 유스케이스(NEXA-P14-T011, application).
 *
 * 한 SPEAK 결정에서 2~5개 후보를 생성한다. 정체성·socialAct·burst 지침을 system 프롬프트로, 최소화된 장면을 user
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
    private val socialActCompiler: SocialActPromptCompiler,
    private val burstCompiler: BurstPromptCompiler,
    private val reasoningModeSelector: ReasoningModeSelector,
    private val payloadMinimizer: ExternalPayloadMinimizer,
) {
    /**
     * [packet] 으로 후보를 생성한다. [budget] 으로 후보 수·token 을 clamp 한다. 외부 실패·malformed 는 포트가 빈
     * 결과로 흡수하므로 이 메서드는 예외를 던지지 않는다 — fallback 정책(T016)이 빈 결과를 다룬다.
     */
    fun generate(
        packet: SpeechScenePacket,
        budget: GenerationBudget = GenerationBudget.DEFAULT,
    ): SpeechGenerationResult {
        val request = assembleRequest(packet, budget)
        return generationPort.generate(request)
    }

    /** 패킷·budget 으로 provider-neutral 생성 요청을 조립한다(프롬프트 조립 + 후보 수/token clamp + 추론 모드). */
    fun assembleRequest(
        packet: SpeechScenePacket,
        budget: GenerationBudget,
    ): SpeechGenerationRequest {
        val systemPrompt = assembleSystemPrompt(packet)
        val userPrompt = payloadMinimizer.minimizeUserPayload(packet)
        return SpeechGenerationRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            socialAct = packet.socialAct,
            candidateCount = budget.clampCandidateCount(budget.maxCandidates),
            reasoningMode = reasoningModeSelector.select(packet),
            maxOutputTokens = budget.clampOutputTokens(budget.maxOutputTokens),
        )
    }

    /** 정체성 + socialAct/burst 장면 지침 + 후보 출력 형식을 system 프롬프트로 조립한다. */
    private fun assembleSystemPrompt(packet: SpeechScenePacket): String =
        buildString {
            appendLine(renderIdentity(packet.identity))
            appendLine()
            appendLine("[지금 장면 지침]")
            appendLine(socialActCompiler.compile(packet.socialAct))
            appendLine(burstCompiler.compile(packet.burstShape))
            appendLine()
            appendLine(OUTPUT_FORMAT_INSTRUCTION)
        }.trim()

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
        /** GLM 이 후보 버블 배열·style tag·uncertainty 를 JSON 으로 돌려주게 하는 출력 지시(T012 파서와 짝). */
        const val OUTPUT_FORMAT_INSTRUCTION =
            "출력은 JSON 하나로만: {\"candidates\":[{\"bubbles\":[\"...\"],\"style_tags\":[\"...\"]," +
                "\"uncertainty\":0.0}]}. 설명·코드펜스 없이 JSON 객체만."
    }
}
