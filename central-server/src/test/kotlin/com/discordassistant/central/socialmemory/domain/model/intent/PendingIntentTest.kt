package com.discordassistant.central.socialmemory.domain.model.intent

import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T006: topic·target·socialAct·activation·urgency·source·expiry, 구조화 필드만(사고 사슬 없음). */
class PendingIntentTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")
    private val source =
        MemorySource(sourceEventIds = setOf("e1"), extractionVersion = 1, consentGranted = true, createdAt = t0)

    private fun intent(expiresAt: Instant?) =
        PendingIntent(
            id = "i1",
            visibility = VisibilityScope.Guild("g#1"),
            topic = "자료 찾아주기",
            targetPseudonym = "m#2",
            socialAct = SocialAct.FIND_INFORMATION,
            activation = IntentActivation.WHEN_TARGET_RETURNS,
            urgency = IntentUrgency.NORMAL,
            source = source,
            expiresAt = expiresAt,
        )

    @Test
    fun `acceptance - 구조화 필드만 가진다 (닫힌 enum, 사고 사슬 필드 없음)`() {
        val i = intent(null)
        assertTrue(i.socialAct is SocialAct)
        assertTrue(i.activation is IntentActivation)
        assertTrue(i.urgency is IntentUrgency)
    }

    @Test
    fun `빈 target 가명은 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { intent(null).copy(targetPseudonym = "") }
    }

    @Test
    fun `만료되면 더 이상 처리 대상이 아니다`() {
        val i = intent(t0.plusSeconds(100))
        assertTrue(i.isActiveAt(t0))
        assertFalse(i.isActiveAt(t0.plusSeconds(100)))
    }
}
