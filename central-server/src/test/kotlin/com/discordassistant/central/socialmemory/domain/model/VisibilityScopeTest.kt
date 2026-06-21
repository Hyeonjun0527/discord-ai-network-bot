package com.discordassistant.central.socialmemory.domain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P07-T011: guild/channel/thread/private scope. 한 서버 기억이 다른 서버 prompt 에 노출되지 않는다. */
class VisibilityScopeTest {
    @Test
    fun `acceptance - 다른 guild 에는 절대 노출되지 않는다`() {
        val memoryInG1 = VisibilityScope.Guild("g#1")
        val requesterInG2 = VisibilityScope.Guild("g#2")
        assertFalse(memoryInG1.isVisibleTo(requesterInG2))
        // 같은 guild 면 노출.
        assertTrue(memoryInG1.isVisibleTo(VisibilityScope.Guild("g#1")))
    }

    @Test
    fun `channel 기억은 그 채널 요청에만 노출된다`() {
        val mem = VisibilityScope.Channel("g#1", "c#1")
        assertTrue(mem.isVisibleTo(VisibilityScope.Channel("g#1", "c#1")))
        assertFalse(mem.isVisibleTo(VisibilityScope.Channel("g#1", "c#2")))
        // 그 채널의 스레드 요청에도 노출.
        assertTrue(mem.isVisibleTo(VisibilityScope.Thread("g#1", "c#1", "t#1")))
        // guild 전체 요청에는 채널 기억이 노출되지 않는다(더 좁음).
        assertFalse(mem.isVisibleTo(VisibilityScope.Guild("g#1")))
    }

    @Test
    fun `thread 기억은 그 스레드 요청에만 노출된다`() {
        val mem = VisibilityScope.Thread("g#1", "c#1", "t#1")
        assertTrue(mem.isVisibleTo(VisibilityScope.Thread("g#1", "c#1", "t#1")))
        assertFalse(mem.isVisibleTo(VisibilityScope.Thread("g#1", "c#1", "t#2")))
        assertFalse(mem.isVisibleTo(VisibilityScope.Channel("g#1", "c#1")))
    }

    @Test
    fun `private 기억은 그 대상 본인에게만 노출된다`() {
        val mem = VisibilityScope.Private("g#1", "m#1")
        assertTrue(mem.isVisibleTo(VisibilityScope.Private("g#1", "m#1")))
        assertFalse(mem.isVisibleTo(VisibilityScope.Private("g#1", "m#2")))
        assertFalse(mem.isVisibleTo(VisibilityScope.Guild("g#1")))
        // 다른 guild 의 같은 가명도 노출 금지.
        assertFalse(mem.isVisibleTo(VisibilityScope.Private("g#2", "m#1")))
    }
}
