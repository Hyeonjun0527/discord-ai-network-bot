package com.discordassistant.central.socialmemory.domain.service.emotion

import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import com.discordassistant.central.socialmemory.domain.model.emotion.ToneHint
import com.discordassistant.central.socialmemory.domain.model.emotion.ToneIntensity
import com.discordassistant.central.socialmemory.domain.model.emotion.ToneValence
import kotlin.math.abs

/**
 * 감정의 표현 렌더링 — core `nia_engine/emotion_renderer.py`(Stage D D2, 게이트 G5) 이식. **순수·결정적(랜덤 0).**
 *
 * 가장 보수적으로(SSOT §0.1 "변화는 극도로 보수적"): 내부 상태(작은 수치)가 곧바로 말투에 드러나지 않는다.
 * **표현 임계값을 넘을 때만** 매우 약한 톤 힌트로 변환한다(§13). 임계 아래면 평소 니아 그대로([ToneHint.NONE],
 * 하위호환·회귀 0). 힌트는 정체성·핵심 성격·답변 길이를 **절대 건드리지 않는다(I11)** — 오직 "반응 온도"(§4.1)만.
 *
 * 렌더 임계값 + 히스테리시스(I12, SSOT §13):
 * - |mood| < [RENDER_ENTER_THRESHOLD] (0.025) → 미주입(평소 니아).
 * - ≥ 0.025 → SLIGHT("아주 조금"), ≥ [RENDER_TEMPERED_THRESHOLD] (0.060) → TEMPERED(절제하되 약간).
 * - 한번 켜진 뒤 [RENDER_RELEASE_THRESHOLD] (0.015) *미만*까지 내려와야 끈다(진입선≠해제선 → 채터링 방지).
 *
 * 강제 금지(§13.2 되돌림 조건): 부정 기분이어도 공격 인격 안 됨, 답변 길이 불변(I11), 고정 말버릇·이모지 미연결.
 */
object EmotionRenderer {
    /** 진입선 — 이 이상이면 톤 힌트 켜짐("아주 조금"). SSOT §13. */
    const val RENDER_ENTER_THRESHOLD: Double = 0.025

    /** 해제선 — 켜진 뒤 이 *미만*까지 내려와야 꺼짐(히스테리시스 I12 — 채터링 방지). */
    const val RENDER_RELEASE_THRESHOLD: Double = 0.015

    /** 절제 상한(§13 0.060 행) — 이 이상이면 "차이 느껴지나 절제". 그래도 "약간"에 머문다. */
    const val RENDER_TEMPERED_THRESHOLD: Double = 0.060

    /**
     * 현재 감정 상태(+이전 활성 여부) → 발화에 주입할 **아주 약한** 톤 힌트(I11·I12).
     *
     * @param emotion 현재 감정. null 이거나 |mood| 임계 아래면 미주입(평소 니아, 하위호환).
     * @param relationshipLabel 관계 라벨(맥락만 — 관계가 톤을 뒤집지 않게 강도에 곱하지 않는다, 보수적).
     * @param wasActive 직전 렌더에서 켜져 있었는지(히스테리시스 — 켜졌으면 해제선까지 내려와야 끈다).
     * @return 결정적 [ToneHint] — 같은 (mood, wasActive) → 같은 결과. 정체성·길이 불변(I11).
     */
    fun renderToneHint(
        emotion: EmotionState?,
        relationshipLabel: String? = null,
        wasActive: Boolean = false,
    ): ToneHint {
        if (emotion == null) return ToneHint.NONE

        val magnitude = abs(emotion.mood)

        // 히스테리시스(I12): 진입선과 해제선을 분리한다(채터링 방지).
        val active =
            if (wasActive) {
                magnitude >= RENDER_RELEASE_THRESHOLD
            } else {
                magnitude >= RENDER_ENTER_THRESHOLD
            }
        if (!active) return ToneHint.NONE

        // 강도: 절제선 이상이면 TEMPERED, 아니면 SLIGHT. 둘 다 "약간"에 머문다(상한).
        val intensity =
            if (magnitude >= RENDER_TEMPERED_THRESHOLD) ToneIntensity.TEMPERED else ToneIntensity.SLIGHT
        val valence = valenceOf(emotion.mood)
        // mood 부호가 정확히 0 이면 방향이 없어 표현할 게 없다 — 평소 니아.
        if (valence == ToneValence.NEUTRAL) return ToneHint.NONE

        var directive = directiveOf(intensity, valence)
        if (!relationshipLabel.isNullOrBlank()) {
            // 관계는 *맥락 한 줄*만(강도·임계 불변) — 관계가 감정 온도를 뒤집지 않는다(보수적).
            directive += " (관계 맥락: 이 사람과는 '$relationshipLabel' — 평소 결은 유지.)"
        }
        return ToneHint(active = true, intensity = intensity, valence = valence, directive = directive)
    }

    private fun valenceOf(mood: Double): ToneValence =
        when {
            mood > 0.0 -> ToneValence.WARM
            mood < 0.0 -> ToneValence.COOL
            else -> ToneValence.NEUTRAL
        }

    /**
     * 톤 힌트 한 줄 — **아주 약한 미세 지시**(고정 말버릇·이모지 금지, §13.2). 답변 길이·핵심 성격·이름·존중은
     * 절대 안 건드린다(I11). 부정(COOL)이어도 공격 인격이 아니라 차분하고 말수가 적은 정도까지만 허용한다.
     */
    private fun directiveOf(
        intensity: ToneIntensity,
        valence: ToneValence,
    ): String {
        val degree = if (intensity == ToneIntensity.SLIGHT) "아주 조금" else "조금(그래도 절제해서)"
        val feel =
            when (valence) {
                ToneValence.WARM -> "평소보다 $degree 기분이 좋은 결"
                ToneValence.COOL -> "평소보다 $degree 차분하고 말수가 적은 결"
                ToneValence.NEUTRAL -> return ""
            }
        return "(말투 미세 지시: 지금 니아는 ${feel}이다. 반응 온도에만 *아주 약하게* 반영해라 — " +
            "성격·말버릇·답변 길이는 그대로 두고, 기분을 직접 말하거나 과장하지 마라. " +
            "부정이어도 공격적으로 변하지 말고, 긍정이어도 갑자기 지나치게 다정해지지 마라.)"
    }
}
