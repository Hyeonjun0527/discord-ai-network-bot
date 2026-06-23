package com.discordassistant.central.platform.discord.admin

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 확인 토큰 저장소: consume-once·TTL 만료·요청자 보존·SecureRandom 토큰 형식 검증. */
class PendingAdminActionStoreTest {
    private val plan = AdminActionPlan(AdminActionType.BAN_MEMBER, mapOf("userId" to "1"))

    @Test
    fun `저장한 pending 을 토큰으로 한 번만 꺼낸다`() {
        val store = PendingAdminActionStore()
        val token = store.put(plan, requesterUserId = 9L, guildId = 7L)
        val first = store.consume(token)
        assertNotNull(first)
        assertNull(store.consume(token)) // 두 번째는 없음(리플레이 방지)
    }

    @Test
    fun `TTL 이 지나면 만료로 null`() {
        var now = 1_000L
        val store = PendingAdminActionStore(ttlMillis = 100L, clock = { now })
        val token = store.put(plan, 9L, 7L)
        now += 101L
        assertNull(store.consume(token))
    }

    @Test
    fun `요청자와 길드 정보를 보존한다`() {
        val store = PendingAdminActionStore()
        val token = store.put(plan, requesterUserId = 42L, guildId = 99L)
        val pending = store.consume(token)!!
        assertNotNull(pending)
        assert(pending.requesterUserId == 42L)
        assert(pending.guildId == 99L)
    }

    @Test
    fun `토큰은 URL-safe Base64 형식이고 예측 불가한 길이를 가진다`() {
        val store = PendingAdminActionStore()
        val token = store.put(plan, 9L, 7L)
        // 16바이트 SecureRandom → Base64(no-padding) = 22자. URL-safe 문자만 포함(+/= 없음).
        assertTrue(token.length >= 22, "토큰 길이(${ token.length })가 22자 미만")
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "URL-safe 문자가 아닌 문자 포함: $token")
    }

    @Test
    fun `연속으로 생성한 토큰은 서로 달라야 한다(unguessable)`() {
        val store = PendingAdminActionStore()
        val t1 = store.put(plan, 9L, 7L)
        val t2 = store.put(plan, 9L, 7L)
        assertNotEquals(t1, t2)
    }
}
