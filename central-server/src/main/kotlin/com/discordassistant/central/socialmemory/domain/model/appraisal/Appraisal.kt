package com.discordassistant.central.socialmemory.domain.model.appraisal

/**
 * Social Appraiser 판정 결과 — **등급만**(I2). core `social_appraiser.py`(B5)의 Appraisal 이식.
 *
 * [targetIsNia]·[kind]·[intensity]·[certainty]·[targetPersonId] 만 담는다. 관계 델타(숫자)는 이 구조에
 * *존재하지 않는다* — B6 수학(`RelationshipOnlineUpdate`)이 등급을 충격량으로 바꾼다. 이 분리가
 * "프롬프트 바뀌어도 감정 민감도 안정"(I2)을 만든다.
 */
data class Appraisal(
    val targetIsNia: Boolean,
    val kind: SocialEventKind,
    val intensity: EventIntensity,
    val certainty: AppraisalCertainty,
    val targetPersonId: String? = null,
    val rationale: String = "",
    val error: String? = null,
) {
    companion object {
        /**
         * 보수 폴백 — 판정 불가/약근거 시 "무덤덤한 쪽"(SSOT 부록 C). 대상 불명확 → targetIsNia=false,
         * 잡담·미세·LOW 로 두어 B6 에서 상태 갱신을 막는다(과민반응보다 무반응이 안전).
         */
        fun conservativeDefault(error: String? = null): Appraisal =
            Appraisal(
                targetIsNia = false,
                kind = SocialEventKind.SMALLTALK,
                intensity = EventIntensity.MICRO,
                certainty = AppraisalCertainty.LOW,
                targetPersonId = null,
                rationale = "근거 부족 — 보수적으로 무덤덤 처리(LOW)",
                error = error,
            )
    }
}
