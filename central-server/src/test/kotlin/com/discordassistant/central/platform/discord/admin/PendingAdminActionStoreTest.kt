package com.discordassistant.central.platform.discord.admin

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 확인 토큰 저장소: consume-once·TTL 만료·요청자 보존 검증(가짜 시계로 시간 제어). */
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
}
