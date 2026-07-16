package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest

/**
 * token·cost budget(NEXA-P14-T015, application 값 객체·불변).
 *
 * 길드/행동별 context·output token 상한과 후보 수 상한을 적용한다(폭주 방지).
 *
 * **acceptance(T015) — 침묵 판단에 token 이 차감되지 않고 후보 과생성이 차단된다**:
 * - 침묵(IGNORE/REACT): speech 자체가 호출되지 않으므로 budget 도 적용되지 않는다(token 0). 즉 발화 결정이
 *   났을 때만 budget 이 소비된다 — 이 타입은 "발화 시" 에만 [clampCandidateCount]/[clampOutputTokens] 로 상한을
 *   강제한다.
 * - 후보 과생성 차단: [clampCandidateCount] 가 요청 후보 수를 [maxCandidates] 와 계약 상한
 *   ([SpeechGenerationRequest.MAX_CANDIDATES])의 **더 작은 값**으로 내린다. 현재 운영 계약은 한 호출당 후보 2개다.
 */
data class GenerationBudget(
    /** 후보 수 상한(비용 cap). [SpeechGenerationRequest.MIN_CANDIDATES] 이상. */
    val maxCandidates: Int,
    /** 후보 1개당 출력 token 상한. */
    val maxOutputTokens: Int,
    /** 입력(context) token 상한 — context selector 가 이 안에서 turn 을 고른다(T007 연동). */
    val maxContextTokens: Int,
) {
    init {
        require(maxCandidates >= SpeechGenerationRequest.MIN_CANDIDATES) {
            "maxCandidates 는 ${SpeechGenerationRequest.MIN_CANDIDATES} 이상이어야 한다: $maxCandidates"
        }
        require(maxOutputTokens > 0) { "maxOutputTokens 는 양수여야 한다: $maxOutputTokens" }
        require(maxContextTokens > 0) { "maxContextTokens 는 양수여야 한다: $maxContextTokens" }
    }

    /**
     * 요청 후보 수를 budget·계약 상한 안으로 clamp 한다(후보 과생성 차단). 하한
     * [SpeechGenerationRequest.MIN_CANDIDATES] 도 보장한다.
     */
    fun clampCandidateCount(requested: Int): Int {
        val ceiling = minOf(maxCandidates, SpeechGenerationRequest.MAX_CANDIDATES)
        return requested.coerceIn(SpeechGenerationRequest.MIN_CANDIDATES, ceiling)
    }

    /** 요청 출력 token 을 budget 상한 안으로 clamp 한다. */
    fun clampOutputTokens(requested: Int): Int = requested.coerceIn(1, maxOutputTokens)

    companion object {
        /** 기본 budget(설정 미지정 길드 폴백). 한 모델 호출에서 후보 2개를 받아 critic 이 고른다. */
        val DEFAULT =
            GenerationBudget(
                maxCandidates = 2,
                maxOutputTokens = 512,
                maxContextTokens = 1024,
            )
    }
}
