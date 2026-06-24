package com.discordassistant.central.socialmemory.domain.service.niarelationship

import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.AppraisalCertainty
import com.discordassistant.central.socialmemory.domain.model.appraisal.EventIntensity
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipAxis
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipGrade
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState
import java.time.Instant

/**
 * 제한된 관계 갱신 엔진 — core `nia_engine/relationship_engine.py`(Stage B B6) 이식. **순수·오프라인.**
 *
 * B5 [Appraisal](등급)을 **버전 고정 보정표**로 4축 (등급, 부호) 매핑으로 바꾼 뒤 [RelationshipMath.applyEvent]
 * 를 호출해 새 상태를 산출한다. GLM 은 등급만(I2) — 숫자는 [RelationshipGrade.impact] 가 정한다.
 *
 * ★누출 금지(핵심): PRAISE·PLAYFUL·SMALLTALK·QUESTION 에 **trust 축이 없다**. trust 는 "좋아 보임"이 아니라
 * 확인 가능한 신뢰 사건(COLLAB)에 의해서만 변한다(SSOT §10.2 — 칭찬→신뢰 누출 0).
 */
object RelationshipEngine {
    /** 사건 종류 → {축: 부호}(+1 긍정·−1 부정). 매핑 안 한 축은 감쇠만(축 독립). */
    val GRADE_AXIS_MAP: Map<SocialEventKind, Map<RelationshipAxis, Double>> =
        mapOf(
            // 칭찬: 호감↑·익숙함↑. **신뢰 없음(누출 금지)**·편안함 없음(보수).
            SocialEventKind.PRAISE to mapOf(RelationshipAxis.AFFINITY to +1.0, RelationshipAxis.FAMILIARITY to +1.0),
            // 친근한 장난: 편안함↑·익숙함↑.
            SocialEventKind.PLAYFUL to mapOf(RelationshipAxis.COMFORT to +1.0, RelationshipAxis.FAMILIARITY to +1.0),
            // 모욕: 호감↓·편안함↓. 신뢰는 매핑 안 함(배신은 COLLAB 실패로 별도).
            SocialEventKind.INSULT to mapOf(RelationshipAxis.AFFINITY to -1.0, RelationshipAxis.COMFORT to -1.0),
            // 협업·약속·도움: 신뢰↑·익숙함↑·편안함↑. **신뢰가 변하는 유일 경로.**
            SocialEventKind.COLLAB to
                mapOf(RelationshipAxis.TRUST to +1.0, RelationshipAxis.FAMILIARITY to +1.0, RelationshipAxis.COMFORT to +1.0),
            // 사과: 보정표 직접 매핑 아님 — repair 훅이 직전 부정 변화를 일부 완화.
            SocialEventKind.APOLOGY to emptyMap(),
            // 질문·잡담: 익숙함 미세↑만(상호성).
            SocialEventKind.QUESTION to mapOf(RelationshipAxis.FAMILIARITY to +1.0),
            SocialEventKind.SMALLTALK to mapOf(RelationshipAxis.FAMILIARITY to +1.0),
        )

    private val INTENSITY_TO_GRADE: Map<EventIntensity, RelationshipGrade> =
        mapOf(
            EventIntensity.MICRO to RelationshipGrade.MICRO,
            EventIntensity.MILD to RelationshipGrade.MILD,
            EventIntensity.CLEAR to RelationshipGrade.CLEAR,
            EventIntensity.STRONG to RelationshipGrade.STRONG,
        )

    /** certainty=LOW 보수 축소: 한 등급 낮춘다(MICRO 면 그대로). */
    private val GRADE_DOWNSHIFT: Map<RelationshipGrade, RelationshipGrade> =
        mapOf(
            RelationshipGrade.STRONG to RelationshipGrade.CLEAR,
            RelationshipGrade.CLEAR to RelationshipGrade.MILD,
            RelationshipGrade.MILD to RelationshipGrade.MICRO,
            RelationshipGrade.MICRO to RelationshipGrade.MICRO,
        )

    /** 사과가 완화하는 부정 축. **신뢰는 사과 말만으로 회복 안 함**(행동 확인 필요). */
    private val REPAIR_AXES = listOf(RelationshipAxis.AFFINITY, RelationshipAxis.COMFORT)

    /** 보정표 적용 결과(설명 가능성 I8). [axisGrades] 비면 어떤 축도 안 움직인다(대상 아님·보류·라벨만). */
    data class UpdatePlan(
        val axisGrades: Map<RelationshipAxis, Pair<RelationshipGrade, Double>>,
        val effectiveGrade: RelationshipGrade?,
        val skippedReason: String? = null,
    ) {
        val changesRelationship: Boolean get() = axisGrades.isNotEmpty()
    }

    data class UpdateResult(
        val before: RelationshipState,
        val after: RelationshipState,
        val plan: UpdatePlan,
    ) {
        val changed: Boolean get() = plan.changesRelationship
    }

    /** Appraisal(등급) → 축별 (등급, 부호) 매핑(I2 보정표·보수성). 숫자는 안 만든다. 순수·결정적(I1). */
    fun planUpdate(appraisal: Appraisal): UpdatePlan {
        if (!appraisal.targetIsNia) {
            return UpdatePlan(emptyMap(), null, "targetIsNia=false — 니아 대상 아님(미변경)")
        }
        val axisSigns = GRADE_AXIS_MAP[appraisal.kind] ?: emptyMap()
        if (axisSigns.isEmpty()) {
            return UpdatePlan(emptyMap(), null, "kind=${appraisal.kind} — 축 매핑 없음(예: 사과는 수리 훅)")
        }
        var grade = INTENSITY_TO_GRADE.getValue(appraisal.intensity)
        if (appraisal.certainty == AppraisalCertainty.LOW) {
            grade = GRADE_DOWNSHIFT.getValue(grade) // 약근거: 한 등급 축소(억지 확정 금지).
        }
        val axisGrades = axisSigns.mapValues { (_, sign) -> grade to sign }
        return UpdatePlan(axisGrades, grade)
    }

    /** B6 진입점 — Appraisal 한 사건을 4축에 적용한 *새* 상태를 산출(오프라인). 사과는 repair 훅. */
    fun updateRelationship(
        state: RelationshipState,
        appraisal: Appraisal,
        now: Instant,
        nRecent: Int = 0,
    ): UpdateResult {
        if (appraisal.kind == SocialEventKind.APOLOGY && appraisal.targetIsNia) {
            return repair(state, appraisal, now, nRecent)
        }
        val plan = planUpdate(appraisal)
        if (!plan.changesRelationship) {
            // 대상 아님·보류: 시각을 전진시키지 않는다(감쇠는 조회 시점에 반영).
            return UpdateResult(state, state, plan)
        }
        val after = RelationshipMath.applyEvent(state, now, plan.axisGrades, nRecent)
        return UpdateResult(state, after, plan)
    }

    /**
     * 수리 훅 — 사과는 직전 *부정* 변화를 일부 완화한다(과하지 않게). 호감·편안함이 μ 아래(부정)일 때만
     * 메우고, μ 이상이면 건드리지 않는다(과회복 금지). **신뢰는 제외**(말뿐 사과로 복구 금지). 보수적으로 한 등급 약하게.
     */
    private fun repair(
        state: RelationshipState,
        appraisal: Appraisal,
        now: Instant,
        nRecent: Int,
    ): UpdateResult {
        var grade = GRADE_DOWNSHIFT.getValue(INTENSITY_TO_GRADE.getValue(appraisal.intensity)) // 늘 보수적으로 약하게.
        if (appraisal.certainty == AppraisalCertainty.LOW) {
            grade = GRADE_DOWNSHIFT.getValue(grade)
        }
        val axisGrades =
            REPAIR_AXES
                .filter { state.axisValue(it) < RelationshipMath.profile(it).mu }
                .associateWith { grade to +1.0 }

        val plan =
            UpdatePlan(
                axisGrades,
                if (axisGrades.isNotEmpty()) grade else null,
                if (axisGrades.isEmpty()) "사과 — 완화할 직전 부정 변화 없음(미변경)" else null,
            )
        if (axisGrades.isEmpty()) return UpdateResult(state, state, plan)
        val after = RelationshipMath.applyEvent(state, now, axisGrades, nRecent)
        return UpdateResult(state, after, plan)
    }
}
