package com.discordassistant.central.socialmemory.domain.model.emotion

/** 톤 힌트 강도 — 미세함의 단계. 어떤 강도도 정체성·답변 길이 불변(I11), *반응 온도*(§4.1)만 조절. */
enum class ToneIntensity {
    /** 미반영 — 평소 니아 그대로. */
    OFF,

    /** "평소보다 아주 조금" — SSOT §13 0.025 행. */
    SLIGHT,

    /** "차이 느껴지나 절제" — §13 0.060 행(여전히 약간). */
    TEMPERED,
}

/** 정서가(valence) 방향 — 부호만(SSOT §11.1 정서가 우선). 활성도는 초기 미사용. */
enum class ToneValence {
    /** mood > 0 — 아주 조금 들뜸/좋음. */
    WARM,

    /** mood < 0 — 아주 조금 시큰둥/처짐. */
    COOL,

    /** mood == 0. */
    NEUTRAL,
}

/**
 * 발화 생성에 주입할 **아주 약한** 톤 힌트 — core `emotion_renderer.py`(D2) 이식.
 *
 * [active]=false 면 미주입(평소 니아 그대로) — 발화는 기존과 *완전히 동일*하다(하위호환·I12).
 * active=true 라도 [intensity]는 SLIGHT/TEMPERED 둘뿐이며 둘 다 *반응 온도*(§4.1)만 미세 조정한다.
 * **답변 길이·핵심 성격·이름·존중은 어떤 값에서도 불변(I11).** [directive]는 생성 프롬프트에 약하게
 * 얹는 한 줄 — 고정 말버릇·이모지를 박지 않고(§13.2) "온도만 살짝". 부정이어도 공격 인격으로 안 바뀐다.
 */
data class ToneHint(
    val active: Boolean,
    val intensity: ToneIntensity = ToneIntensity.OFF,
    val valence: ToneValence = ToneValence.NEUTRAL,
    val directive: String = "",
) {
    companion object {
        /** 미주입 톤 힌트(평소 니아) — 모든 임계-아래·미주어짐 경로가 이 값을 돌려준다. */
        val NONE = ToneHint(active = false)
    }
}
