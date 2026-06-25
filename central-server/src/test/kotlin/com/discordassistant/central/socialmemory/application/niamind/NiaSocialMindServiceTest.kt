package com.discordassistant.central.socialmemory.application.niamind

import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.AppraisalCertainty
import com.discordassistant.central.socialmemory.domain.model.appraisal.EventIntensity
import com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState
import com.discordassistant.central.socialmemory.domain.service.appraisal.AppraiserProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** 사회 마음 오케스트레이션 검증 — Appraiser→관계→감정→톤 체인 + shadow(미저장)/live(저장). */
class NiaSocialMindServiceTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val scope = "guild:1"
    private val speaker = "discord:42"

    /** 고정 등급을 내는 fake — 관계 렌즈/입력과 무관히 지정 Appraisal. */
    private class FakeAppraiser(
        private val appraisal: Appraisal,
    ) : AppraiserProvider {
        override fun appraise(
            messages: List<String>,
            speakerPersonId: String,
            lens: RelationshipLens,
            candidatePersonIds: List<String>?,
        ): Appraisal = appraisal
    }

    private class MemPort : NiaSocialStatePort {
        val rel = mutableMapOf<String, RelationshipState>()
        val emo = mutableMapOf<String, EmotionState>()

        override fun loadRelationship(
            personId: String,
            scope: String,
        ) = rel["$scope|$personId"]

        override fun saveRelationship(
            scope: String,
            state: RelationshipState,
        ) {
            rel["$scope|${state.personId}"] = state
        }

        override fun loadEmotion(scope: String) = emo[scope]

        override fun saveEmotion(state: EmotionState) {
            emo[state.contextScope] = state
        }
    }

    private fun insult(certainty: AppraisalCertainty = AppraisalCertainty.CLEAR) =
        Appraisal(true, SocialEventKind.INSULT, EventIntensity.CLEAR, certainty)

    @Test
    fun `live — 관계·감정 갱신을 저장하고 톤을 낸다`() {
        val port = MemPort()
        val svc = NiaSocialMindService(FakeAppraiser(insult()), port)
        val out = svc.observe(scope, speaker, listOf("니아 진짜 별로야"), t0, persist = true)

        assertTrue(out.persisted)
        // 모욕 → 호감↓·편안함↓ 저장됨.
        val saved = port.loadRelationship(speaker, scope)!!
        assertTrue(saved.affinity < 0.0, "모욕으로 호감 하락 저장")
        // 감정도 저장(reaction 부정).
        assertTrue(port.loadEmotion(scope) != null)
        assertTrue(out.emotion.reaction < 0.0, "부정 reaction")
    }

    @Test
    fun `shadow — 상태를 저장하지 않는다(예측만)`() {
        val port = MemPort()
        val svc = NiaSocialMindService(FakeAppraiser(insult()), port)
        val out = svc.observe(scope, speaker, listOf("별로"), t0, persist = false)

        assertFalse(out.persisted)
        assertEquals(0, port.rel.size, "관계 미저장")
        assertEquals(0, port.emo.size, "감정 미저장")
        // 그래도 outcome 은 계산됨(예측).
        assertTrue(out.relationship.affinity < 0.0)
    }

    @Test
    fun `targetIsNia=false — 감정에 사건 영향 없음(감쇠만)`() {
        val port = MemPort()
        val notNia = Appraisal(false, SocialEventKind.INSULT, EventIntensity.STRONG, AppraisalCertainty.CLEAR)
        val svc = NiaSocialMindService(FakeAppraiser(notNia), port)
        val out = svc.observe(scope, speaker, listOf("쟤 별로야"), t0, persist = true)

        assertEquals(0.0, out.emotion.reaction, 0.0, "대상 아님 → 감정 사건 영향 0")
        assertEquals(0.0, out.relationship.affinity, 0.0, "관계도 미변경")
    }

    @Test
    fun `칭찬 — 호감↑ 저장되고 trust 는 누출 0`() {
        val port = MemPort()
        val praise = Appraisal(true, SocialEventKind.PRAISE, EventIntensity.CLEAR, AppraisalCertainty.CLEAR)
        val svc = NiaSocialMindService(FakeAppraiser(praise), port)
        val out = svc.observe(scope, speaker, listOf("니아 최고야"), t0, persist = true)

        assertTrue(out.relationship.affinity > 0.0)
        assertEquals(0.0, out.relationship.trust, 0.0, "칭찬→trust 누출 0")
    }

    @Test
    fun `빈 입력 거부`() {
        val svc = NiaSocialMindService(FakeAppraiser(insult()), MemPort())
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            svc.observe(scope, speaker, emptyList(), t0, persist = false)
        }
    }
}
