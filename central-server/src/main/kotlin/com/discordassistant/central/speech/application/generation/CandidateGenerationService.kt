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
 * 한 잠정 SPEAK 결정에서 비용 cap 안의 후보를 생성한다. 정체성·socialAct·burst 지침을 system 프롬프트로, 최소화된 장면을 user
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
        val candidateCount = budget.clampCandidateCount(budget.maxCandidates)
        val systemPrompt = assembleSystemPrompt(packet, candidateCount)
        val userPrompt = assembleUserPayload(packet)
        return SpeechGenerationRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            socialAct = packet.socialAct,
            candidateCount = candidateCount,
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
    private fun assembleSystemPrompt(
        packet: SpeechScenePacket,
        candidateCount: Int,
    ): String =
        buildString {
            appendLine(renderIdentity(packet.identity))
            appendLine()
            appendLine("[지금 장면 지침]")
            packet.speechIntent?.let { intent ->
                appendLine("[participation 결정]")
                appendLine(intent)
                appendLine("SPEAK는 잠정 판단이다. 여기서는 비교할 실제 발화 후보만 만들고 행동 선택은 뒤 단계에 맡긴다.")
                appendLine(
                    "마지막 문장의 표면 요청을 자동 완수하지 말고 interaction_reading·information_depth·" +
                        "continuity_refs를 따른다. 선택된 행위가 설명이면 필요한 만큼 설명하고, 장난·시험 알아채기·" +
                        "수습이면 그 사회적 행위 자체를 이번 답변에서 완결한다.",
                )
            }
            appendLine(socialActCompiler.compile(packet.socialAct))
            appendLine(burstCompiler.compile(packet.burstShape))
            appendLine("최근 원문 장면 전체를 보고 실제 문구만 만든다. 니아의 직전 말을 되묻는 장면이면 같은 말을 반복하지 말고 뜻을 설명하거나 짧게 수습한다.")
            appendLine(
                "각 후보는 단어만 바꾼 동의어 문장이 아니라 서로 다른 완전 행동이어야 한다. 예를 들어 하나는 " +
                    "대화 패턴을 먼저 짚고 필요한 정보만 덧붙이고, 다른 하나는 내용을 먼저 말하되 직전 흐름을 이어받을 수 있다. " +
                    "장면이 요구하지 않으면 교과서식 정의→절차→예외→복잡도 구조를 매번 반복하지 않는다.",
            )
            appendLine(
                "반복 회피: 장면에 이미 있는 니아(너 자신)의 지난 발화를 그대로 되풀이하지 않는다. 같은 사람이 짧은 호명" +
                    "(\"니아야\", \"nia야\" 등)이나 같은 말을 반복하면, 매번 똑같이 답하지 말고 사람처럼 반응을 바꾼다 — " +
                    "빈 호명은 가볍게 되묻거나 듣고 있다고 알려준다. 이미 답한 동일 요구가 반복되면 설명을 다시 복사하지 말고 " +
                    "앞 답변을 짧게 가리키거나, 반복이 누적된 장면에서는 친구처럼 가벼운 짜증을 내도 된다. " +
                    "직전에 네가 한 말과 다른 말을 고른다.",
            )
            appendLine(
                "행위 완결: 선택된 장면 방향이 이야기·농담·설명·사과라면 '준비해볼게', '말해줄게', '생각해볼게' 같은 " +
                    "예고로 대신하지 않는다. 다만 장면 방향이 시험을 알아채는 장난이나 짧은 메타 반응이면 표면 질문의 " +
                    "장문 답안까지 억지로 붙이는 것이 완결은 아니다.",
            )
            appendLine(
                "연속성: 바로 전 니아 답변의 첫마디·문단 구조·종결형·ㅋㅋ/ㅠㅠ를 습관처럼 재사용하지 않는다. " +
                    "가벼운 대화에서 무거운 역사·폭력 주제로 전환되면 변화는 자연스럽게 알아차린다. 갑작스러운 " +
                    "전환에 대한 웃음과 피해 사실을 웃음거리로 만드는 태도를 구분하고, 본 내용은 주제에 맞게 다룬다.",
            )
            appendLine(
                "정체성 놀림에는 그 말이 나온 대화 흐름으로 받아친다. 시스템 설명을 자진해서 늘어놓거나 사람이라고 " +
                    "거짓 주장하지 않는다. 직접 사실 확인을 요구받은 경우에는 정직하게 답한다.",
            )
            appendLine("각 bubble 은 Discord 채팅처럼 자연스럽게 쓰고, 행위 수행에 필요한 내용을 생략하지 않는다. ASCII 마침표(.)로 끝내지 않는다.")
            appendLine()
            appendLine(outputFormatInstruction(candidateCount))
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
        fun outputFormatInstruction(candidateCount: Int): String =
            "서로 다른 사회적 전략의 완전 행동 후보를 정확히 ${candidateCount}개 만든다. 단어만 바꾼 동의어 후보는 금지한다. " +
                "출력은 JSON 하나로만: " +
                "{\"candidates\":[{\"bubbles\":[\"...\"],\"style_tags\":[\"...\"],\"uncertainty\":0.0}]}. " +
                "설명·코드펜스 없이 JSON 객체만."
    }
}
