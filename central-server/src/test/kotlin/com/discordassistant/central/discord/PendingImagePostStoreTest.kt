package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.PendingImagePostStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** /그림 게시 확인 임시 보관소 — put/take/discard·소유자 검증·TTL 만료·상한 제거(차수: 본인 확인 게이트). */
class PendingImagePostStoreTest {
    private fun bytes() = byteArrayOf(1, 2, 3)

    @Test
    fun `put 후 같은 토큰·소유자로 take 하면 항목을 돌려주고 제거한다`() {
        val store = PendingImagePostStore(nowNanos = { 0L })
        val token = store.put(bytes(), "🖼️ ☁️ \"고양이\"", guildId = 10, channelId = 20, userId = 7)

        val taken = store.take(token, userId = 7)
        assertNotNull(taken)
        assertEquals("🖼️ ☁️ \"고양이\"", taken!!.caption)
        assertEquals(20L, taken.channelId)
        assertEquals(7L, taken.userId)

        // 한 번 꺼내면 제거된다.
        assertNull(store.take(token, userId = 7))
    }

    @Test
    fun `다른 유저는 take·discard 할 수 없다(소유자 검증)`() {
        val store = PendingImagePostStore(nowNanos = { 0L })
        val token = store.put(bytes(), "c", guildId = 10, channelId = 20, userId = 7)

        assertNull(store.take(token, userId = 99)) // 타인 → null
        assertFalse(store.discard(token, userId = 99)) // 타인 → false, 보관은 유지
        assertNotNull(store.take(token, userId = 7)) // 소유자는 여전히 꺼낼 수 있다
    }

    @Test
    fun `discard 는 소유자 항목을 제거하면 true`() {
        val store = PendingImagePostStore(nowNanos = { 0L })
        val token = store.put(bytes(), "c", guildId = 10, channelId = 20, userId = 7)

        assertTrue(store.discard(token, userId = 7))
        assertNull(store.take(token, userId = 7)) // 이미 제거됨
        assertFalse(store.discard("없는토큰", userId = 7))
    }

    @Test
    fun `TTL 이 지나면 take 는 null(만료)`() {
        var now = 0L
        val store = PendingImagePostStore(ttlMillis = 1000, nowNanos = { now })
        val token = store.put(bytes(), "c", guildId = 10, channelId = 20, userId = 7)

        now += 2_000L * 1_000_000 // 2초 경과(TTL 1초 초과)
        assertNull(store.take(token, userId = 7)) // 만료 → null
    }

    @Test
    fun `상한을 넘기면 가장 오래된 항목이 제거된다`() {
        var now = 0L
        val store = PendingImagePostStore(maxEntries = 2, nowNanos = { now })
        val t1 = store.put(bytes(), "1", guildId = 10, channelId = 20, userId = 7)
        now += 1_000_000
        val t2 = store.put(bytes(), "2", guildId = 10, channelId = 20, userId = 7)
        now += 1_000_000
        val t3 = store.put(bytes(), "3", guildId = 10, channelId = 20, userId = 7) // 상한 초과 → 가장 오래된 t1 제거

        assertNull(store.take(t1, userId = 7)) // 가장 오래된 항목은 밀려났다
        assertNotNull(store.take(t2, userId = 7))
        assertNotNull(store.take(t3, userId = 7))
    }
}
