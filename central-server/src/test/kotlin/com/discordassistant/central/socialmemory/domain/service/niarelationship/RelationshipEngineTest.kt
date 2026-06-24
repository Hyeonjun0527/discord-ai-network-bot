package com.discordassistant.central.socialmemory.domain.service.niarelationship

import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.AppraisalCertainty
import com.discordassistant.central.socialmemory.domain.model.appraisal.EventIntensity
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * 관계 4축 + 갱신 엔진 이식 검증 — core `tests/test_relationship*.py`(B4·B6, 게이트 G3)가 보증한 성질 재현.
 */
class RelationshipEngineTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun fresh() = RelationshipState(personId = "discord:1")

    private fun ev(
        kind: SocialEventKind,
        intensity: EventIntensity = EventIntensity.CLEAR,
        certainty: AppraisalCertainty = AppraisalCertainty.CLEAR,
        targetIsNia: Boolean = true,
    ) = Appraisal(targetIsNia, kind, intensity, certainty)

    @Test
    fun `칭찬은 trust 를 절대 안 건드린다(누출 0) — 핵심 종료조건`() {
        var s = fresh()
        var now = t0
        repeat(5) {
            s = RelationshipEngine.updateRelationship(s, ev(SocialEventKind.PRAISE), now).after
            now = now.plus(Duration.ofMinutes(1))
        }
        assertEquals(0.0, s.trust, 0.0, "칭찬 5회 후에도 trust=0 (누출 없음)")
        assertTrue(s.affinity > 0.0, "affinity 는 올랐다")
        assertTrue(s.familiarity > 0.0, "familiarity 도 올랐다")
        assertEquals(0.0, s.comfort, 0.0, "칭찬은 comfort 안 건드림(보수)")
    }

    @Test
    fun `신뢰는 협업(COLLAB)으로만 변한다`() {
        val s = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.COLLAB), t0).after
        assertTrue(s.trust > 0.0, "협업으로 trust 상승")
    }

    @Test
    fun `한 사건으로 급변하지 않는다(이중 제한) — 작은 범위`() {
        val s = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.PRAISE, EventIntensity.STRONG), t0).after
        assertTrue(abs(s.affinity) <= 0.012 + 1e-9, "affinity 단일 사건 δ_max(0.012) 이하")
    }

    @Test
    fun `targetIsNia=false 면 관계 미변경`() {
        val r = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.INSULT, targetIsNia = false), t0)
        assertEquals(fresh(), r.after)
        assertTrue(!r.changed)
    }

    @Test
    fun `LOW 확신은 한 등급 축소한다(보수)`() {
        // MILD(0.025→δ_max 0.012 클립) vs LOW-MILD(→MICRO 0.010, 클립 안 됨)로 축소 차이가 드러난다.
        val mild = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.PRAISE, EventIntensity.MILD), t0).after
        val low =
            RelationshipEngine
                .updateRelationship(
                    fresh(),
                    ev(SocialEventKind.PRAISE, EventIntensity.MILD, AppraisalCertainty.LOW),
                    t0,
                ).after
        assertTrue(low.affinity < mild.affinity, "LOW 가 더 작게 변한다")
    }

    @Test
    fun `도배 감쇠 — 반복 사건일수록 한 번의 영향이 비선형 약화`() {
        val first =
            RelationshipEngine
                .updateRelationship(
                    fresh(),
                    ev(SocialEventKind.SMALLTALK, EventIntensity.MILD),
                    t0,
                    nRecent = 0,
                ).after
        val tenth =
            RelationshipEngine
                .updateRelationship(
                    fresh(),
                    ev(SocialEventKind.SMALLTALK, EventIntensity.MILD),
                    t0,
                    nRecent = 9,
                ).after
        assertTrue(tenth.familiarity < first.familiarity, "10번째 사건 영향이 더 약하다")
    }

    @Test
    fun `사과는 직전 부정(호감·편안함)만 회복하고 신뢰는 안 건드린다`() {
        // 모욕으로 호감·편안함을 내린 뒤, 사과로 일부 회복.
        var s = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.INSULT), t0).after
        val afterInsult = s.affinity
        assertTrue(afterInsult < 0.0)
        s = RelationshipEngine.updateRelationship(s, ev(SocialEventKind.APOLOGY), t0.plus(Duration.ofMinutes(1))).after
        assertTrue(s.affinity > afterInsult, "사과로 호감 일부 회복")
        assertEquals(0.0, s.trust, 0.0, "사과는 trust 안 건드림")
    }

    @Test
    fun `사과 — 완화할 부정 변화 없으면 미변경(과회복 금지)`() {
        val s = fresh() // 모든 축 μ(0)
        val r = RelationshipEngine.updateRelationship(s, ev(SocialEventKind.APOLOGY), t0)
        assertEquals(s.copy(), r.after.copy(lastUpdatedAt = null).copy(personId = s.personId), "부정 없으면 미변경")
        assertTrue(!r.changed)
    }

    @Test
    fun `결정적 — 같은 입력은 같은 결과(랜덤 0)`() {
        val a = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.COLLAB), t0).after
        val b = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.COLLAB), t0).after
        assertEquals(a, b)
    }

    @Test
    fun `시간 지나면 μ(평소)로 감쇠 — affinity 회귀`() {
        val s = RelationshipEngine.updateRelationship(fresh(), ev(SocialEventKind.PRAISE, EventIntensity.STRONG), t0).after
        val decayed =
            RelationshipMath.decay(
                RelationshipMath.profile(com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipAxis.AFFINITY),
                s.affinity,
                t0,
                t0.plus(Duration.ofDays(365)),
            )
        assertTrue(abs(decayed) < abs(s.affinity), "1년 뒤 μ 쪽으로 감쇠")
    }
}
