package com.discordassistant.central.socialmemory.domain.model.niarelationship

import java.time.Instant

/**
 * 니아↔한 사람의 관계 4축 상태 — core `relationship.py`(B4)의 RelationshipState 이식. **사람별**(personId).
 *
 * 4축은 독립이며 각자 μ(=0, 평소 니아)에서 시작한다. 갱신은 *새* 인스턴스를 만든다(불변). 외부가 축 숫자를
 * 직접 쓰는 공개 경로는 없다 — 변화는 오직 등급→수학(RelationshipMath/Engine)뿐이다(I2).
 */
data class RelationshipState(
    val personId: String,
    val familiarity: Double = 0.0,
    val affinity: Double = 0.0,
    val trust: Double = 0.0,
    val comfort: Double = 0.0,
    val lastUpdatedAt: Instant? = null,
) {
    fun axisValue(axis: RelationshipAxis): Double =
        when (axis) {
            RelationshipAxis.FAMILIARITY -> familiarity
            RelationshipAxis.AFFINITY -> affinity
            RelationshipAxis.TRUST -> trust
            RelationshipAxis.COMFORT -> comfort
        }

    fun withAxis(
        axis: RelationshipAxis,
        value: Double,
    ): RelationshipState =
        when (axis) {
            RelationshipAxis.FAMILIARITY -> copy(familiarity = value)
            RelationshipAxis.AFFINITY -> copy(affinity = value)
            RelationshipAxis.TRUST -> copy(trust = value)
            RelationshipAxis.COMFORT -> copy(comfort = value)
        }
}
