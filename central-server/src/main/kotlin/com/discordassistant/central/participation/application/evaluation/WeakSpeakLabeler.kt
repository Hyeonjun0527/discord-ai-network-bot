package com.discordassistant.central.participation.application.evaluation

/**
 * 자동 SPEAK/SILENT **약지도(weakly-supervised) 라벨러**(NEXA-P09-T014, application 레이어).
 *
 * shadow 예측은 "NEXA 가 무엇을 했을지"의 가설이다. 그 평가에는 정답 label 이 필요한데, 인간이 일일이 라벨하는
 * 비용을 피하려고 **관찰 신호로 약한 label** 을 만든다: 예측 시점 이후 **비슷한 역할의 활성 인간**이 반응했는가.
 *
 * **acceptance(T014) — weak label 임을 명시하고 학습 정답과 바로 동일시하지 않는다**:
 * - 결과는 [WeakSpeakLabel] 이고, 그 안에 [WeakSpeakLabel.isWeak] = true 와 [WeakSpeakLabel.confidence] 를
 *   1급 필드로 둔다 — 이 라벨을 학습 ground-truth 로 바로 쓰지 말라는 신호를 타입이 운반한다.
 * - 모호한 관찰(MULTIPLE_RESPONDERS, late action)은 낮은 confidence 로 표시해 단정하지 않는다([rationale]).
 * - "사람이 도움이 필요했다"는 심리 추론을 하지 않는다 — **오직 관찰된 행동(reply/react/silence)** 만 본다.
 *
 * **결정론·재현(제약)**: 같은 [MatchOutcome] 이면 항상 같은 label(seed·랜덤 없음 — 순수 함수). 도메인 순수성:
 * application 레이어 — 표준 타입·평가 값 객체만. Spring/JPA/JDA·GLM 미참조.
 */
object WeakSpeakLabeler {
    /**
     * counterfactual matcher 결과([outcome])로부터 weak SPEAK/SILENT label 을 만든다.
     *
     * 규칙(관찰 신호만):
     * - 아무도 응답 안 함(NO_RESPONSE) → **SILENT** label(이 장면은 인간도 침묵 → NEXA 침묵이 타당했을 가설).
     * - 한 명 즉시 응답(SINGLE_RESPONDER, !late) → **SPEAK** label, 높은 confidence(분명한 발화 자리).
     * - 여러 명/늦은 응답 → **SPEAK** label 이되 낮은 confidence(누가 NEXA 자리였는지 모호 → 단정 금지).
     */
    fun label(outcome: MatchOutcome): WeakSpeakLabel =
        when (outcome.ambiguity) {
            MatchAmbiguity.NO_RESPONSE ->
                WeakSpeakLabel(
                    label = SpeakLabel.SILENT,
                    confidence = SILENT_CONFIDENCE,
                    rationale = "관찰 창 안에 인간 응답 없음 — 침묵이 타당했을 약한 신호",
                )

            MatchAmbiguity.SINGLE_RESPONDER ->
                if (outcome.isLateAction) {
                    WeakSpeakLabel(
                        label = SpeakLabel.SPEAK,
                        confidence = LATE_OR_AMBIGUOUS_CONFIDENCE,
                        rationale = "한 명이 늦게 응답 — 발화 자리였을 수 있으나 즉시성이 낮아 약함",
                    )
                } else {
                    WeakSpeakLabel(
                        label = SpeakLabel.SPEAK,
                        confidence = CLEAR_SPEAK_CONFIDENCE,
                        rationale = "한 명이 즉시 응답 — 발화 자리였을 약한 신호(그래도 정답 아님)",
                    )
                }

            MatchAmbiguity.MULTIPLE_RESPONDERS ->
                WeakSpeakLabel(
                    label = SpeakLabel.SPEAK,
                    confidence = LATE_OR_AMBIGUOUS_CONFIDENCE,
                    rationale = "여러 명이 응답 — 누가 NEXA 자리였는지 모호해 단정 불가(약함)",
                )
        }

    /** 명백한 즉시 단일 응답: 비교적 높은 약지도 신뢰(그래도 1.0 아님 — 정답 아님). */
    const val CLEAR_SPEAK_CONFIDENCE: Double = 0.75

    /** 침묵 관찰: 발화 자리 부재의 약한 신호. */
    const val SILENT_CONFIDENCE: Double = 0.6

    /** 늦은/다수 응답: 모호 — 낮은 신뢰. */
    const val LATE_OR_AMBIGUOUS_CONFIDENCE: Double = 0.4
}

/**
 * weak SPEAK/SILENT label(application 값 객체·불변). **약지도** label 임을 타입으로 운반한다 — 학습 ground-truth
 * 로 바로 동일시하지 않는다(acceptance T014).
 */
data class WeakSpeakLabel(
    /** 약한 label 값(SPEAK/SILENT). */
    val label: SpeakLabel,
    /** 약지도 신뢰도 [0,1] — 절대 1.0 이 아니다(정답 아님을 명시). 낮을수록 모호한 관찰. */
    val confidence: Double,
    /** 이 label 을 만든 관찰 근거(원문 없음 — 신호 설명만). */
    val rationale: String,
) {
    init {
        require(confidence in 0.0..MAX_WEAK_CONFIDENCE) {
            "weak label confidence 는 [0, $MAX_WEAK_CONFIDENCE] — 1.0(확정 정답)일 수 없다: $confidence"
        }
        require(rationale.isNotBlank()) { "rationale 은 비어 있을 수 없다" }
    }

    /** 이 label 은 항상 약지도다 — 학습 정답과 바로 동일시 금지(acceptance T014). 타입 수준 상수. */
    val isWeak: Boolean
        get() = true

    companion object {
        /** weak label 의 confidence 상한 — 1.0(확정 정답) 미만으로 강제해 ground-truth 오용을 막는다. */
        const val MAX_WEAK_CONFIDENCE: Double = 0.95
    }
}

/** weak SPEAK/SILENT label 값(application enum). 예측을 평가할 약한 이진 라벨. */
enum class SpeakLabel {
    /** 발화 자리였을 약한 신호(인간 응답 관찰). */
    SPEAK,

    /** 침묵이 타당했을 약한 신호(인간 침묵 관찰). */
    SILENT,
}
