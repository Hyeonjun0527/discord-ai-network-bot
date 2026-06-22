package com.discordassistant.central.socialmemory.domain.model.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P07-T002: provenance(원천 이벤트 ID·추출 버전·동의 스냅샷·createdAt) + 출처 없는 기억 거부. */
class MemorySourceTest {
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    private fun source(ids: Set<String>) = MemorySource(sourceEventIds = ids, extractionVersion = 1, consentGranted = true, createdAt = t0)

    @Test
    fun `acceptance - 출처 없는 기억 저장을 생성자 수준에서 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { source(emptySet()) }
    }

    @Test
    fun `빈 문자열 이벤트 ID 도 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { source(setOf("e1", "")) }
    }

    @Test
    fun `필수 필드를 보존한다`() {
        val s = source(setOf("e1", "e2"))
        assertEquals(2, s.supportCount)
        assertEquals(1, s.extractionVersion)
        assertTrue(s.consentGranted)
        assertEquals(t0, s.createdAt)
        assertTrue(s.isSupportedBy("e1"))
        assertFalse(s.isSupportedBy("eX"))
    }

    @Test
    fun `부분 출처 제거 - 남으면 차집합 출처를 돌려준다`() {
        val remaining = source(setOf("e1", "e2", "e3")).withoutEvents(setOf("e2"))
        assertEquals(setOf("e1", "e3"), remaining?.sourceEventIds)
    }

    @Test
    fun `전부 제거되면 null - 기억 전체 무효화 신호`() {
        assertNull(source(setOf("e1", "e2")).withoutEvents(setOf("e1", "e2")))
    }
}
