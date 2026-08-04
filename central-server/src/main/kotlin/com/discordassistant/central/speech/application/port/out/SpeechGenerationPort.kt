package com.discordassistant.central.speech.application.port.out

import com.discordassistant.central.speech.domain.model.SpeechImageInput
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * 발화 후보 생성 **provider-neutral 아웃바운드 포트**(NEXA-P14-T001, 헥사고날).
 *
 * scene packet 과 speech plan 에서 조립된 프롬프트로 후보 목록을 생성한다. 구현은 routing 의 `CloudLlm` 포트를
 * anti-corruption adapter(T002)로 감싸 호출한다 — 이 계약에는 **Z.AI SDK·HTTP DTO·glm 모델 식별자가 노출되지
 * 않는다**(acceptance T001). speech application/domain 은 이 포트만 보고, 모델 선택·쿼터·requestlog 는 routing
 * 이 책임진다(ADR 0006·speech-context.md anti-corruption).
 *
 * 순수성: application 레이어 — 도메인 타입·표준 타입만. Spring/JPA/JDA·glm/zai 타입 미참조.
 */
interface SpeechGenerationPort {
    /**
     * [request] 로 1~N 개 발화 후보를 생성한다. 외부 호출 실패·비활성·malformed 응답은 구현이 안전하게 흡수해
     * **빈 후보 결과**([SpeechGenerationResult.EMPTY])로 돌려준다(예외를 던지지 않는다) — fallback 정책(T016)이
     * 빈 결과를 보고 무발화/리액션-only 로 안전 하강한다.
     */
    fun generate(request: SpeechGenerationRequest): SpeechGenerationResult
}

/**
 * 발화 생성 요청 계약(NEXA-P14-T001/T011/T013/T015). 조립된 프롬프트 + 생성 예산을 운반한다.
 * 모델 식별자 문자열은 담지 않는다 — 모델 선택은 routing adapter(T003 설정)가 정한다(provider-neutral).
 */
data class SpeechGenerationRequest(
    /** 조립된 system 지시(정체성 + socialAct/burst 장면 지침 + 안전). */
    val systemPrompt: String,
    /** 조립된 user 맥락(최소화된 scene/turn/memory). */
    val userPrompt: String,
    /**
     * 실행 trace에 보관해도 되는 user 맥락. private 사람 말투 예시가 붙은 경우 실제 provider payload 대신 예시를
     * 생략한 요약을 담아, debug API/메모리 trace가 원문 카드의 우회 경로가 되지 않게 한다.
     */
    val traceUserPrompt: String = userPrompt,
    /** 발화 종류(추적·모델 메타용). */
    val socialAct: SpeechSocialAct,
    /** 생성할 후보 수(T011 — 명백한 장면 1개, 모호한 장면 최대 3개). */
    val candidateCount: Int,
    /** 추론 모드(T013 — 짧은 잡담=비추론, 복잡 사실/코드=추론). */
    val reasoningMode: ReasoningMode,
    /** 출력 token 상한(T015 — 후보 1개당). */
    val maxOutputTokens: Int,
    /** [systemPrompt] 앞부분 중 장면마다 변하지 않아 explicit prompt cache에 넣을 수 있는 문자 수. */
    val stableSystemPromptChars: Int = 0,
    /** 원문 Discord ID가 아닌 채널 가명 키. provider 호출 직전 토큰 예산에만 사용한다. */
    val channelTokenBudgetKey: String? = null,
    /** 별도 Vision 입력. base64를 [userPrompt]나 trace에 섞지 않는다. */
    val speechImageInput: SpeechImageInput? = null,
    /**
     * 니아 감정 톤 힌트(D2 EmotionRenderer)의 *아주 약한* 미세 지시 — 기본 "" = 평소 니아(무영향·하위호환).
     * 비어 있지 않으면 생성 프롬프트에 약하게 얹는다. 정체성·답변 길이는 안 건드린다(I11 — 반응 온도만).
     */
    val toneDirective: String = "",
) {
    init {
        require(systemPrompt.isNotBlank()) { "systemPrompt 는 비어 있을 수 없다" }
        require(userPrompt.isNotBlank()) { "userPrompt 는 비어 있을 수 없다" }
        require(candidateCount in MIN_CANDIDATES..MAX_CANDIDATES) {
            "candidateCount 는 [$MIN_CANDIDATES,$MAX_CANDIDATES] 범위여야 한다: $candidateCount"
        }
        require(maxOutputTokens > 0) { "maxOutputTokens 는 양수여야 한다: $maxOutputTokens" }
        require(stableSystemPromptChars in 0..systemPrompt.length) {
            "stableSystemPromptChars 는 systemPrompt 길이 안이어야 한다: $stableSystemPromptChars/${systemPrompt.length}"
        }
        channelTokenBudgetKey?.let {
            require(it.matches(Regex("[A-Za-z0-9_:.=-]{1,200}"))) { "channelTokenBudgetKey 형식이 잘못됐다" }
        }
    }

    companion object {
        const val MIN_CANDIDATES: Int = 1
        const val MAX_CANDIDATES: Int = 3
    }
}

/** GLM thinking 모드 선택(NEXA-P14-T013). 정책이 정하며 모델에 맡기지 않는다. */
enum class ReasoningMode {
    /** 비추론(짧은 잡담·리액션) — 빠르고 저렴. */
    NONE,

    /** 추론(복잡한 사실·코드) — 품질 우선. */
    THINKING,
}

/**
 * 발화 후보 생성 결과(NEXA-P14-T001/T011/T012). 후보 목록 + 모델 메타. 빈 목록은 안전 실패(T016 하강 신호)다.
 */
data class SpeechGenerationResult(
    /** 생성된 후보(순서 무의미 — 이후 선택 단계가 고른다). 빈 목록이면 생성 실패/무응답. */
    val candidates: List<SpeechCandidate>,
    /** 응답을 만든 모델 메타(추적용 — 예: "glm-5.1" 라벨). 빈 결과면 비어 있을 수 있다. */
    val modelMetadata: String = "",
    /** 외부 호출이 실행되기 전에 차단된 명시 원인. 일반 실패·빈 응답은 null로 기존 안전 하강을 유지한다. */
    val failureReason: SpeechGenerationFailureReason? = null,
) {
    /** 사용 가능한 후보가 하나도 없는가 — fallback(T016)이 안전 하강할지의 가드. */
    val isEmpty: Boolean
        get() = candidates.isEmpty()

    companion object {
        /** 안전 실패(무응답) — 호출/파싱/비활성 시 구현이 이 값을 돌려준다. */
        val EMPTY = SpeechGenerationResult(candidates = emptyList())

        fun failed(reason: SpeechGenerationFailureReason): SpeechGenerationResult =
            SpeechGenerationResult(candidates = emptyList(), failureReason = reason)
    }
}

enum class SpeechGenerationFailureReason {
    CHANNEL_TOKEN_BUDGET_EXHAUSTED,
}

/**
 * 발화 후보 한 건(NEXA-P14-T011/T012). candidate ID + 버블 배열 + style tags + uncertainty.
 * 버블은 burst 형태에 맞춘 메시지 조각이다(텍스트). 실제 전송은 actionruntime 이 한다(speech 는 계획만).
 */
data class SpeechCandidate(
    /** 후보 식별자(추적·선택·로그용). */
    val candidateId: String,
    /** 메시지 버블 배열(각 조각 텍스트). 빈 배열이면 무발화 후보(리액션-only 로 접힐 수 있음). */
    val bubbles: List<String>,
    /** 모델이 붙인 style tag(예: "casual", "warm"). 선택 휴리스틱이 참고. */
    val styleTags: List<String> = emptyList(),
    /** 모델이 보고한 불확실성 [0,1]. 높으면 선택 단계가 보수적으로 다룬다. */
    val uncertainty: Double = 0.0,
) {
    init {
        require(candidateId.isNotBlank()) { "candidateId 는 비어 있을 수 없다" }
        require(uncertainty in 0.0..1.0) { "uncertainty 는 [0,1] 범위여야 한다: $uncertainty" }
    }
}
