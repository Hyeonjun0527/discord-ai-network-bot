package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.AskConversationMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** /질문 단기 멀티턴 기억(채널+유저·링버퍼·TTL) 순수 단위테스트. */
class AskConversationMemoryTest {
    private val channel = 100L
    private val user = 200L

    @Test
    fun `처음엔 히스토리 비어있음`() {
        val mem = AskConversationMemory()
        assertTrue(mem.history(channel, user).isEmpty())
    }

    @Test
    fun `한 turn append 후 다음 호출에서 user assistant 순서로 보임 — 방금 뭐라고 했지 맥락`() {
        val mem = AskConversationMemory()
        mem.append(channel, user, "안녕", "안녕! 반가워")
        val h = mem.history(channel, user)
        assertEquals(2, h.size)
        assertEquals("user", h[0].role)
        assertEquals("안녕", h[0].content)
        assertEquals("assistant", h[1].role)
        assertEquals("안녕! 반가워", h[1].content)
    }

    @Test
    fun `채널 또는 유저가 다르면 격리된다(맥락 안 섞임)`() {
        val mem = AskConversationMemory()
        mem.append(channel, user, "내 비밀은 42", "응 알겠어")
        assertTrue(mem.history(channel, user + 1).isEmpty()) // 다른 유저
        assertTrue(mem.history(channel + 1, user).isEmpty()) // 다른 채널
    }

    @Test
    fun `링버퍼 — maxTurns 초과분은 가장 오래된 turn 부터 버린다`() {
        val mem = AskConversationMemory(maxTurns = 2)
        mem.append(channel, user, "q1", "a1")
        mem.append(channel, user, "q2", "a2")
        mem.append(channel, user, "q3", "a3")
        val h = mem.history(channel, user)
        assertEquals(4, h.size) // 2 turn = 4 메시지
        assertEquals("q2", h[0].content) // q1/a1 은 버려짐
        assertEquals("q3", h[2].content)
    }

    @Test
    fun `TTL 초과면 만료되어 비워진다`() {
        var now = 0L
        val mem = AskConversationMemory(ttlMillis = 1000L, clock = { now })
        mem.append(channel, user, "안녕", "응")
        now = 2000L // TTL(1000) 초과
        assertTrue(mem.history(channel, user).isEmpty())
    }

    @Test
    fun `빈 질문 또는 빈 답은 저장하지 않는다`() {
        val mem = AskConversationMemory()
        mem.append(channel, user, "  ", "답")
        mem.append(channel, user, "질문", "  ")
        assertTrue(mem.history(channel, user).isEmpty())
    }

    /**
     * 멀티턴 시나리오: /질문 "안녕" → 답 → /질문 "방금 뭐라고 했지?" 때 직전 turn 이 히스토리로 제공되어
     * 모델이 "방금 '안녕'이라 했어"라고 이어갈 수 있다(기억 작동 핵심 검증).
     */
    @Test
    fun `방금 뭐라고 했지 — 두 번째 질문이 첫 turn 을 히스토리로 본다`() {
        val mem = AskConversationMemory()
        // turn 1
        val before = mem.history(channel, user)
        assertTrue(before.isEmpty())
        mem.append(channel, user, "안녕", "안녕! 반가워")
        // turn 2: "방금 뭐라고 했지?" — 호출 전 히스토리에 직전 user/assistant 가 들어있다.
        val h = mem.history(channel, user)
        assertEquals(2, h.size)
        assertEquals("안녕", h[0].content)
        assertEquals("안녕! 반가워", h[1].content)
    }
}
