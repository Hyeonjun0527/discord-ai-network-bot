package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.privacy.ExternalPayloadAllowlistSerializer
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.ConversationContentIsolator
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 발화 후보 생성 유스케이스(NEXA-P14-T011, application).
 *
 * 한 SPEAK 결정에서 비용 cap 안의 후보를 생성한다. 현재 운영 계약은 후보 1개 고정이다. 정체성·socialAct·burst 지침을 system 프롬프트로, 최소화된 장면을 user
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
        val userPrompt = assembleUserPayload(packet)
        return SpeechGenerationRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            socialAct = packet.socialAct,
            candidateCount = budget.clampCandidateCount(budget.maxCandidates),
            reasoningMode = reasoningModeSelector.select(packet),
            maxOutputTokens = budget.clampOutputTokens(budget.maxOutputTokens),
        )
    }

    /**
     * 외부로 나갈 user payload 를 조립한다(NEXA-P17-T002/T004 enforcement). allowlist 직렬화(명시 허용 필드 +
     * minimizer 스크럽)로 deny-by-default payload 를 만들고, content 격리기로 최근 turn 을 **인용된 장면 데이터**로
     * 감싸 붙인다. participation raw context 는 이미 quote-isolated scene data 로 만든 값만 붙인다 — raw content 가 새
     * 지시문이 되지 못한다. 두 방어선이 실제 GLM payload 경로에 함께 적용된다.
     */
    private fun assembleUserPayload(packet: SpeechScenePacket): String =
        buildString {
            append(payloadSerializer.serialize(packet))
            appendLine()
            appendLine()
            append(contentIsolator.serializeAsQuotedScene(packet))
            packet.rawContextSceneData?.let { rawContextScene ->
                appendLine()
                appendLine()
                append(rawContextScene)
            }
        }.trim()

    /** 정체성 + socialAct/burst 장면 지침 + 후보 출력 형식을 system 프롬프트로 조립한다. */
    private fun assembleSystemPrompt(packet: SpeechScenePacket): String =
        buildString {
            appendLine(renderIdentity(packet.identity))
            appendLine()
            appendLine("[지금 장면 지침]")
            packet.speechIntent?.let { intent ->
                appendLine("[participation 결정]")
                appendLine(intent)
                appendLine("SPEAK 여부는 이미 확정됐다. 여기서는 실제 문구만 만들고 WAIT/REACT/IGNORE 로 다시 뒤집지 않는다.")
            }
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
