package com.discordassistant.central.socialmemory.domain.service.appraisal

import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.AppraisalCertainty
import com.discordassistant.central.socialmemory.domain.model.appraisal.EventIntensity
import com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Social Appraiser 이식 검증 — core `tests/test_social_appraiser.py`(B5)가 보증한 성질을 Kotlin 으로 재현.
 * GLM 실호출은 mock provider 로 대체(순수 부분 결정론 검증).
 */
class SocialAppraiserTest {
    private val friendly = RelationshipLens.fromAxes(familiarity = 0.30, affinity = 0.20, trust = 0.15, comfort = 0.25)
    private val stranger = RelationshipLens.fromAxes(familiarity = 0.0, affinity = 0.0, trust = 0.0, comfort = 0.0)

    @Test
    fun `관계 렌즈 — 친함 vs 처음 라벨이 다르다`() {
        assertEquals("친한 사이", friendly.label)
        assertEquals("처음 보는 사이", stranger.label)
    }

    @Test
    fun `서먹·익숙 라벨 파생`() {
        assertEquals("서먹한 사이", RelationshipLens.fromAxes(0.2, -0.1, 0.0, 0.1).label)
        assertEquals("익숙한 사이", RelationshipLens.fromAxes(0.1, 0.05, 0.05, 0.1).label)
    }

    @Test
    fun `프롬프트에 관계 렌즈가 주입되고 현재 기분은 없다(I3)`() {
        val prompt = SocialAppraiser.buildUserPrompt(listOf("니아 나쁜 여자야"), "discord:1", friendly)
        assertTrue(prompt.contains("친한 사이"), "관계 렌즈 주입")
        assertFalse(prompt.contains("mood") || prompt.contains("기분"), "현재 기분 미입력(I3)")
    }

    @Test
    fun `parse — 정상 JSON 을 등급으로`() {
        val a = SocialAppraiser.parse("""{"target_is_nia":true,"kind":"PLAYFUL","intensity":"MILD","certainty":"CLEAR","rationale":"장난"}""")
        assertTrue(a.targetIsNia)
        assertEquals(SocialEventKind.PLAYFUL, a.kind)
        assertEquals(EventIntensity.MILD, a.intensity)
        assertEquals(AppraisalCertainty.CLEAR, a.certainty)
    }

    @Test
    fun `parse — 코드펜스 감싸진 JSON 도 처리`() {
        val a =
            SocialAppraiser.parse(
                "```json\n{\"target_is_nia\":false,\"kind\":\"SMALLTALK\",\"intensity\":\"MICRO\",\"certainty\":\"LOW\"}\n```",
            )
        assertEquals(SocialEventKind.SMALLTALK, a.kind)
    }

    @Test
    fun `parse — enum 밖 certainty 는 보수적으로 LOW`() {
        val a = SocialAppraiser.parse("""{"target_is_nia":true,"kind":"PRAISE","intensity":"CLEAR","certainty":"확실"}""")
        assertEquals(AppraisalCertainty.LOW, a.certainty)
    }

    @Test
    fun `parse — 깨진 JSON 은 보수 폴백`() {
        val a = SocialAppraiser.parse("이건 JSON 이 아니야")
        assertEquals(SocialEventKind.SMALLTALK, a.kind)
        assertEquals(AppraisalCertainty.LOW, a.certainty)
        assertFalse(a.targetIsNia)
        assertNull(a.targetPersonId)
    }

    @Test
    fun `parse — 숫자 델타 키가 있어도 무시(I2 — Appraisal 에 숫자 필드 없음)`() {
        val a =
            SocialAppraiser.parse(
                """{"target_is_nia":true,"kind":"INSULT","intensity":"CLEAR","certainty":"CLEAR","affinity_delta":-0.5}""",
            )
        assertEquals(SocialEventKind.INSULT, a.kind)
        // affinity_delta 는 구조적으로 들어올 곳이 없다 — Appraisal 에 숫자 필드 부재.
        assertEquals(Appraisal::class.java.declaredFields.none { it.name.contains("delta", ignoreCase = true) }, true)
    }

    @Test
    fun `conservativeDefault — 무덤덤 쪽(LOW·target false)`() {
        val a = Appraisal.conservativeDefault(error = "x")
        assertFalse(a.targetIsNia)
        assertEquals(AppraisalCertainty.LOW, a.certainty)
        assertEquals("x", a.error)
    }

    @Test
    fun `appraise — 관계가 판정을 바꾼다(mock provider, 친함 PLAYFUL vs 처음 INSULT)`() {
        // 같은 입력이라도 lens.label 에 따라 다른 등급을 내는 mock — 관계 렌즈가 판정을 바꾸는 구조 검증.
        val provider =
            object : AppraiserProvider {
                override fun appraise(
                    messages: List<String>,
                    speakerPersonId: String,
                    lens: RelationshipLens,
                    candidatePersonIds: List<String>?,
                ): Appraisal {
                    val kind = if (lens.label == "친한 사이") SocialEventKind.PLAYFUL else SocialEventKind.INSULT
                    return Appraisal(true, kind, EventIntensity.MILD, AppraisalCertainty.CLEAR)
                }
            }
        val msg = listOf("니아 나쁜 여자야")
        assertEquals(SocialEventKind.PLAYFUL, SocialAppraiser.appraise(provider, msg, "discord:1", friendly).kind)
        assertEquals(SocialEventKind.INSULT, SocialAppraiser.appraise(provider, msg, "discord:1", stranger).kind)
    }

    @Test
    fun `appraise — 빈 입력은 거부`() {
        val provider =
            object : AppraiserProvider {
                override fun appraise(
                    m: List<String>,
                    s: String,
                    l: RelationshipLens,
                    c: List<String>?,
                ) = Appraisal.conservativeDefault()
            }
        assertThrows(IllegalArgumentException::class.java) {
            SocialAppraiser.appraise(provider, emptyList(), "discord:1", friendly)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SocialAppraiser.appraise(provider, listOf("hi"), "", friendly)
        }
    }
}
