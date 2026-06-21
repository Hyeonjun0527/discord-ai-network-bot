package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 발화 생성 **호출 경계 게이트**(NEXA-P14-T023 핵심, application).
 *
 * GLM(generation) 호출은 **오직 SPEAK 결정**에서, 그리고 **stale 하지 않고 consent 가 살아 있을 때만** 일어난다.
 * IGNORE/WAIT/REACT·stale SPEAK·consent revoke 는 [SpeechGenerationPort] 를 **한 번도** 호출하지 않는다 —
 * 비용·안전의 핵심이다(quota/requestlog 에 generation 요청 자체가 생기지 않는다).
 *
 * **acceptance(T023) — IGNORE/WAIT/REACT, stale SPEAK, consent revoke 에서 routing 호출 0회**: 이 게이트가
 * [trigger]·[stale]·[consentRevoked] 를 먼저 검사해 SPEAK 이고 유효할 때만 [CandidateGenerationService.generate]
 * 로 내려간다. 그 외에는 [SpeechGenerationResult.EMPTY] 를 즉시 돌려준다(포트 미호출) — fallback(T016)이 침묵 처리.
 *
 * 순수성: application — speech 자기 trigger 어휘만 쓴다(participation 타입 import 금지, 도메인 순수성·경계 분리).
 * participation→speech 매핑은 상위 유스케이스가 [SpeechTrigger.fromActionKind] 로 수행한다.
 */
class SpeechGenerationGate(
    private val candidateGenerationService: CandidateGenerationService,
) {
    /**
     * [trigger]·유효성 검사를 통과(SPEAK·not stale·not revoked)하면 후보를 생성하고, 아니면 포트를 호출하지 않고
     * 빈 결과를 돌려준다. [invokedGeneration] 으로 실제 생성 시도 여부를 노출한다(테스트·관측).
     */
    fun generateIfSpeaking(
        trigger: SpeechTrigger,
        packet: SpeechScenePacket,
        stale: Boolean = false,
        consentRevoked: Boolean = false,
        budget: GenerationBudget = GenerationBudget.DEFAULT,
    ): GateResult {
        if (trigger != SpeechTrigger.SPEAK) return GateResult.skipped(SkipReason.NOT_SPEAK)
        if (consentRevoked) return GateResult.skipped(SkipReason.CONSENT_REVOKED)
        if (stale) return GateResult.skipped(SkipReason.STALE)

        val result = candidateGenerationService.generate(packet, budget)
        return GateResult(result = result, invokedGeneration = true, skipReason = null)
    }
}

/**
 * speech 가 보는 발화 trigger(NEXA-P14-T023, application). participation 의 최종 action 종류를 speech 어휘로 다시
 * 표현한 값 — SPEAK 만 generation 을 유발한다. 미지/기타는 SPEAK 가 아니므로 안전하게 비호출된다.
 */
enum class SpeechTrigger {
    /** 발화(유일하게 generation 을 유발). */
    SPEAK,

    /** 침묵. */
    IGNORE,

    /** 나중에 다시 평가. */
    WAIT,

    /** 말 없이 리액션. */
    REACT,

    /** 미지/기타(보수적으로 비호출). */
    OTHER,
    ;

    companion object {
        /** participation action kind 안정 wireName 으로부터 매핑한다(경계 매핑 — speech 가 participation 타입을 import 하지 않음). */
        fun fromActionKind(wireName: String): SpeechTrigger =
            when (wireName.lowercase()) {
                "speak" -> SPEAK
                "ignore" -> IGNORE
                "wait" -> WAIT
                "react" -> REACT
                else -> OTHER
            }
    }
}

/** 발화 생성 건너뛴 사유(NEXA-P14-T023). 비용·안전 추적용. */
enum class SkipReason {
    /** SPEAK 결정이 아님(IGNORE/WAIT/REACT/OTHER). */
    NOT_SPEAK,

    /** stale SPEAK(deadline 초과 — 늦은 발화 금지). */
    STALE,

    /** consent revoke(사용자 동의 철회). */
    CONSENT_REVOKED,
}

/**
 * 게이트 결과(NEXA-P14-T023). [invokedGeneration] 이 false 면 routing 포트를 **한 번도** 호출하지 않았다는 증거다.
 */
data class GateResult(
    val result: SpeechGenerationResult,
    /** 실제로 generation 포트를 호출했는가(false = 0회 호출 — 비용 발생 없음). */
    val invokedGeneration: Boolean,
    /** 건너뛴 사유(호출했으면 null). */
    val skipReason: SkipReason?,
) {
    companion object {
        /** 포트 미호출(빈 결과 + 사유). */
        fun skipped(reason: SkipReason) = GateResult(result = SpeechGenerationResult.EMPTY, invokedGeneration = false, skipReason = reason)
    }
}
