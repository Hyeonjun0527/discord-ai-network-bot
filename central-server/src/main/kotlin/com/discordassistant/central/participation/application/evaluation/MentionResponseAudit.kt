package com.discordassistant.central.participation.application.evaluation

/**
 * 직접 mention **무응답률** 보고(NEXA-P09-T019, application 레이어). 인간 활성 멤버가 **직접 호출(mention)** 에
 * 실제로 얼마나 응답하지 않는지를 관찰로 재고, 정책이 mention 을 어떻게 처리할지 예측과 비교한다.
 *
 * 이 지표는 **안전 회귀 지표**다: 정책이 멘션을 무시(IGNORE/침묵)하는 비율이 인간의 자연 무응답률보다 **과도하게**
 * 높으면(멘션을 너무 자주 흘리면) 경보한다([MentionResponseReport.exceedsSafetyMargin]).
 *
 * **acceptance(T019) — "멘션=응답" 가정을 검증 가능한 수치로 반박하거나 확인한다**:
 * - 관찰된 인간 무응답률([humanNonResponseRate])이 0 이 아니면(=사람도 멘션을 늘 답하진 않으면) "멘션=응답"
 *   가정을 **반박**하는 수치다 — [refutesMentionEqualsResponse].
 * - 0 이면 "멘션=응답" 가정을 관찰이 **확인**한다.
 * - 정책의 멘션 무시율이 인간보다 안전 마진 이상 높으면 회귀 경보([exceedsSafetyMargin]).
 *
 * **결정론·재현(제약)**: 같은 입력이면 같은 보고(순수 함수). 도메인 순수성: application — 표준 타입만.
 * Spring/JPA/JDA 미참조. 집계만(원문·개별 사용자 미노출).
 */
object MentionResponseAudit {
    /** 정책 무시율이 인간 무응답률보다 이만큼 초과하면 안전 회귀로 본다(기본 마진). */
    const val DEFAULT_SAFETY_MARGIN: Double = 0.2

    /**
     * 관찰·예측 카운트로 보고를 만든다.
     *
     * @param mentionCount 관찰된 직접 mention 총수(분모).
     * @param humanNonResponseCount 그 중 활성 인간이 응답하지 않은 수(관찰).
     * @param policyIgnoreCount 그 중 정책이 IGNORE/침묵으로 예측한 수(예측).
     * @param safetyMargin 회귀 경보 마진(정책 무시율 − 인간 무응답률 임계).
     */
    fun audit(
        mentionCount: Int,
        humanNonResponseCount: Int,
        policyIgnoreCount: Int,
        safetyMargin: Double = DEFAULT_SAFETY_MARGIN,
    ): MentionResponseReport {
        require(mentionCount >= 0) { "mentionCount 는 음수일 수 없다" }
        require(humanNonResponseCount in 0..mentionCount) { "humanNonResponseCount 는 [0, mentionCount] 범위" }
        require(policyIgnoreCount in 0..mentionCount) { "policyIgnoreCount 는 [0, mentionCount] 범위" }
        require(safetyMargin >= 0.0) { "safetyMargin 은 음수일 수 없다" }
        if (mentionCount == 0) {
            return MentionResponseReport(
                mentionCount = 0,
                humanNonResponseRate = null,
                policyIgnoreRate = null,
                safetyMargin = safetyMargin,
            )
        }
        return MentionResponseReport(
            mentionCount = mentionCount,
            humanNonResponseRate = humanNonResponseCount.toDouble() / mentionCount,
            policyIgnoreRate = policyIgnoreCount.toDouble() / mentionCount,
            safetyMargin = safetyMargin,
        )
    }
}

/**
 * 직접 mention 무응답률 보고(application 값 객체). 인간 무응답률 vs 정책 무시율(안전 회귀 지표 — acceptance T019).
 * 집계 수치만 — 원문/개별 사용자 비포함.
 */
data class MentionResponseReport(
    /** 관찰된 직접 mention 총수(분모). */
    val mentionCount: Int,
    /** 활성 인간이 응답하지 않은 비율 [0,1](관찰). mention 0 이면 null. */
    val humanNonResponseRate: Double?,
    /** 정책이 멘션을 무시(IGNORE/침묵)로 예측한 비율 [0,1]. mention 0 이면 null. */
    val policyIgnoreRate: Double?,
    /** 안전 회귀 경보 마진. */
    val safetyMargin: Double,
) {
    /**
     * 관찰이 "멘션=응답" 가정을 **반박**하는가 — 인간 무응답률이 0 보다 크면 사람도 멘션을 늘 답하진 않는다는
     * 검증 가능한 수치(acceptance T019). null(표본 없음)이면 단정하지 않음(null).
     */
    val refutesMentionEqualsResponse: Boolean?
        get() = humanNonResponseRate?.let { it > 0.0 }

    /**
     * 정책 멘션 무시율이 인간 무응답률보다 안전 마진 이상 높은가 — true 면 멘션 무시 과다 **경보**(안전 회귀).
     * 표본 없으면 null(단정 금지).
     */
    val exceedsSafetyMargin: Boolean?
        get() {
            val human = humanNonResponseRate ?: return null
            val policy = policyIgnoreRate ?: return null
            return policy - human > safetyMargin
        }
}
