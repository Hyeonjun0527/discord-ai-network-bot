package com.discordassistant.central.socialmemory.domain.service.niarelationship

import com.discordassistant.central.socialmemory.domain.model.niarelationship.AxisProfile
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipAxis
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipGrade
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState
import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 관계 4축 bounded 수학 — core `relationship.py`(B4) 이식. **순수·결정적(랜덤 0).** Emotion 과 같은 수식,
 * 다른 시정수(관계는 21일~수개월급으로 매우 느리다).
 *
 * - bounded: `x_t = μ + (x−μ)·2^(−Δt/H) + Δx`. I4 이중 제한(단일 Δ cap + 범위 clamp), I5 감쇠(μ 회귀).
 * - 축 독립: 사건이 *건드리는 축만* 갱신, 나머지는 감쇠만(SSOT §8). [Instant.now] 미사용([now] 주입).
 */
object RelationshipMath {
    private const val DAY_HOURS = 24.0

    /** 4축 보정 프로필(정본 = SSOT §12.1). familiarity 사실상 누적, trust 가장 느림. */
    val DEFAULT_PROFILES: Map<RelationshipAxis, AxisProfile> =
        mapOf(
            RelationshipAxis.FAMILIARITY to
                AxisProfile(RelationshipAxis.FAMILIARITY, 0.0, Duration.ofHours((DAY_HOURS * 90).toLong()), 0.0, 1.0, 0.012),
            RelationshipAxis.AFFINITY to
                AxisProfile(RelationshipAxis.AFFINITY, 0.0, Duration.ofHours((DAY_HOURS * 120).toLong()), -1.0, 1.0, 0.012),
            RelationshipAxis.TRUST to
                AxisProfile(RelationshipAxis.TRUST, 0.0, Duration.ofHours((DAY_HOURS * 180).toLong()), -1.0, 1.0, 0.008),
            RelationshipAxis.COMFORT to
                AxisProfile(RelationshipAxis.COMFORT, 0.0, Duration.ofHours((DAY_HOURS * 120).toLong()), 0.0, 1.0, 0.010),
        )

    fun profile(axis: RelationshipAxis): AxisProfile = DEFAULT_PROFILES.getValue(axis)

    /** 시간 감쇠(I5) — `x_t = μ + (x−μ)·2^(−Δt/H)`. Δt=0 no-op, 음수 거부. 난수 없음. */
    fun decay(
        profile: AxisProfile,
        xPrev: Double,
        lastUpdatedAt: Instant,
        now: Instant,
    ): Double {
        val elapsedMillis = Duration.between(lastUpdatedAt, now).toMillis()
        require(elapsedMillis >= 0) { "now 는 lastUpdatedAt 보다 과거일 수 없다" }
        if (elapsedMillis == 0L) return xPrev
        val halfLives = elapsedMillis.toDouble() / profile.halfLife.toMillis().toDouble()
        return profile.mu + (xPrev - profile.mu) * 2.0.pow(-halfLives)
    }

    /** 등급(부호) → 한 축의 결정적 변화량 Δx — δ_max 클립(I4) · 도배 감쇠(1/√(1+n)). 랜덤 없음(I1). */
    fun eventDelta(
        profile: AxisProfile,
        grade: RelationshipGrade,
        sign: Double,
        nRecent: Int = 0,
    ): Double {
        require(sign == 1.0 || sign == -1.0) { "sign 은 +1.0 또는 -1.0 만 허용한다(랜덤 없음, I1)" }
        require(nRecent >= 0) { "nRecent 는 음수일 수 없다" }
        val raw = (sign * grade.impact).coerceIn(-profile.deltaMax, profile.deltaMax)
        return (raw * dogpile(nRecent)).coerceIn(-profile.deltaMax, profile.deltaMax)
    }

    private fun dogpile(nRecent: Int): Double = 1.0 / sqrt(1.0 + nRecent)

    /**
     * 한 사건을 4축에 적용한 *새* 상태 — [axisGrades] 에 있는 축만 (감쇠+사건), 나머지는 감쇠만(축 독립).
     * 각 축: 감쇠(I5) → 사건 영향(δ 클립·도배) → 범위 clamp(I4). 난수 없음(I1).
     */
    fun applyEvent(
        state: RelationshipState,
        now: Instant,
        axisGrades: Map<RelationshipAxis, Pair<RelationshipGrade, Double>>,
        nRecent: Int = 0,
    ): RelationshipState {
        val last = state.lastUpdatedAt ?: now
        require(!now.isBefore(last)) { "now 는 lastUpdatedAt 보다 과거일 수 없다" }

        var next = state
        for (axis in RelationshipAxis.entries) {
            val p = profile(axis)
            val decayed = decay(p, state.axisValue(axis), last, now)
            val graded = axisGrades[axis]
            val value =
                if (graded == null) {
                    decayed
                } else {
                    val (grade, sign) = graded
                    p.clamp(decayed + eventDelta(p, grade, sign, nRecent))
                }
            next = next.withAxis(axis, value)
        }
        return next.copy(lastUpdatedAt = now)
    }
}
