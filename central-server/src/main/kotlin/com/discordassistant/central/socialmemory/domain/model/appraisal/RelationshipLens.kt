package com.discordassistant.central.socialmemory.domain.model.appraisal

/**
 * 발화자↔니아 관계를 *읽기용 해석 렌즈*로 압축한 값 — core `social_appraiser.py`(B5)의 RelationshipLens 이식.
 *
 * Appraiser 프롬프트에 "이 사람과 니아는 [친함/처음/서먹] 관계"로 주입되어, 같은 문장도 관계에 따라
 * kind/intensity 가 달라지게 만든다(§0.1 1번 — 관계가 해석을 바꾸는 게 핵심). **읽기 전용** — 여기서
 * 관계 숫자를 바꾸지 않는다. 라벨은 표시용일 뿐 저장하지 않는다(I9).
 */
data class RelationshipLens(
    val label: String,
    val familiarity: Double,
    val affinity: Double,
    val trust: Double,
    val comfort: Double,
) {
    /** 프롬프트에 넣을 한국어 관계 요약(짧고 행동 가능 — SSOT §14.1). */
    fun describe(): String =
        "이 사람과 니아는 '$label'다. " +
            "(익숙함 ${fmt(familiarity)}, 호감 ${fmt(affinity)}, 신뢰 ${fmt(trust)}, 편안함 ${fmt(comfort)})"

    private fun fmt(v: Double): String = (if (v >= 0) "+" else "") + String.format("%.2f", v)

    companion object {
        /**
         * 관계 4축(B4 읽기) → 해석 렌즈. 라벨은 여러 축에서 파생(I9). 순수·결정적(I1).
         * - 처음 보는 사이: 익숙함·편안함 거의 없음(같은 농담도 모욕으로 읽힐 수 있음).
         * - 서먹한 사이: 호감이 음(경계).
         * - 친한 사이: 익숙함·편안함 충분(장난을 장난으로 읽을 근거).
         * - 익숙한 사이: 그 사이.
         */
        fun fromAxes(
            familiarity: Double,
            affinity: Double,
            trust: Double,
            comfort: Double,
        ): RelationshipLens {
            val label =
                when {
                    familiarity < 0.05 && comfort < 0.05 -> "처음 보는 사이"
                    affinity < -0.05 -> "서먹한 사이"
                    comfort >= 0.20 && familiarity >= 0.15 -> "친한 사이"
                    else -> "익숙한 사이"
                }
            return RelationshipLens(label, familiarity, affinity, trust, comfort)
        }
    }
}
