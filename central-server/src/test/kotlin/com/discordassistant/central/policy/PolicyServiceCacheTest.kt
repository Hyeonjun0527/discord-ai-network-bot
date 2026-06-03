package com.discordassistant.central.policy

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.persistence.AllowedChannelRepository
import com.discordassistant.central.persistence.GuildRepository
import com.discordassistant.central.persistence.RolePolicyRepository
import com.discordassistant.central.provider.AuditLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 길드정책 읽기 캐시(감사 2026-06-03 B-5) 동작 검증.
 * - 짧은 TTL 내 반복 조회는 캐시에서 응답(staleness 허용).
 * - TTL 경과 후 재조회로 변경 반영.
 * - 쓰기 메서드는 끝에서 즉시 무효화 → 같은 인스턴스에서 관리자 변경 즉시 반영.
 *
 * `MutableClock`/짧은 TTL 로 캐시 분기를 직접 제어하려고 PolicyService 를 손수 구성한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PolicyServiceCacheTest
    @Autowired
    constructor(
        private val channels: AllowedChannelRepository,
        private val roles: RolePolicyRepository,
        private val guilds: GuildRepository,
    ) {
        /** 테스트에서 시간을 임의로 전진시킬 수 있는 Clock. */
        private class MutableClock(
            var nowMs: Long,
        ) : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

            fun advance(ms: Long) {
                nowMs += ms
            }
        }

        private val clock = MutableClock(1_000_000L)

        private fun newService(ttlMs: Long = 5000): PolicyService =
            PolicyService(
                channels = channels,
                roles = roles,
                guilds = guilds,
                audit = AuditLog(),
                aiAdminRoles = null,
                cacheTtlMs = ttlMs,
                clock = clock,
            )

        @Test
        fun `TTL 내 staleness — DB 직접 변경은 TTL 경과 전까지 안 보임`() {
            val policy = newService(ttlMs = 5000)
            val gid = 7001L

            // 최초 조회로 캐시 적재(빈 목록 → 제한 없음)
            assertTrue(policy.isChannelAllowed(gid, 555))

            // 서비스를 우회해 DB 에 채널을 직접 추가(캐시 무효화 없이)
            channels.save(
                com.discordassistant.central.persistence
                    .AllowedChannelEntity(guildId = gid, channelId = 200),
            )

            // TTL 내 → 여전히 캐시된 "빈 목록(제한 없음)" 응답
            assertTrue(policy.isChannelAllowed(gid, 555))

            // TTL 경과 후 → 재조회되어 새 정책 반영(이제 200 만 허용)
            clock.advance(5001)
            assertFalse(policy.isChannelAllowed(gid, 555))
            assertTrue(policy.isChannelAllowed(gid, 200))
        }

        @Test
        fun `쓰기 후 즉시 반영 — 채널 무효화`() {
            val policy = newService(ttlMs = 60_000)
            val gid = 7002L

            assertTrue(policy.isChannelAllowed(gid, 999)) // 캐시 적재(빈=제한없음)
            policy.allowChannel(gid, 200, adminId = 1) // 쓰기 → evict
            // TTL 안 지났지만 무효화돼서 즉시 반영
            assertTrue(policy.isChannelAllowed(gid, 200))
            assertFalse(policy.isChannelAllowed(gid, 201))
            assertEquals(listOf(200L), policy.allowedChannelIds(gid))

            policy.denyChannel(gid, 200, adminId = 1)
            assertTrue(policy.isChannelAllowed(gid, 999)) // 다시 제한 없음(즉시)
        }

        @Test
        fun `쓰기 후 즉시 반영 — 역할 정책 무효화`() {
            val policy = newService(ttlMs = 60_000)
            val gid = 7003L

            assertEquals(ModelBurden.LIGHT, policy.maxAllowedBurden(gid, listOf(1))) // 캐시 적재(기본)
            assertEquals(20, policy.dailyLimit(gid, listOf(1)))

            policy.setRolePolicy(gid, roleId = 1, ModelBurden.HEAVY, dailyLimit = 50, adminId = 1)
            // 즉시 반영
            assertEquals(ModelBurden.HEAVY, policy.maxAllowedBurden(gid, listOf(1)))
            assertEquals(50, policy.dailyLimit(gid, listOf(1)))
        }

        @Test
        fun `쓰기 후 즉시 반영 — 길드 설정 무효화`() {
            val policy = newService(ttlMs = 60_000)
            val gid = 7004L

            assertFalse(policy.isAutoApprove(gid)) // 캐시 적재(기본 false)
            assertEquals("ko", policy.guildLanguage(gid))
            assertEquals(null, policy.guildDefaultModel(gid))
            assertEquals(null, policy.guildWelcomeMessage(gid))

            policy.setAutoApprove(gid, true, adminId = 1)
            assertTrue(policy.isAutoApprove(gid)) // 즉시

            policy.setGuildDefaults(gid, defaultModel = "llama3", language = "en", adminId = 1)
            assertEquals("llama3", policy.guildDefaultModel(gid))
            assertEquals("en", policy.guildLanguage(gid))

            policy.clearGuildDefaultModel(gid, adminId = 1)
            assertEquals(null, policy.guildDefaultModel(gid))

            policy.setWelcomeMessage(gid, "환영!", adminId = 1)
            assertEquals("환영!", policy.guildWelcomeMessage(gid))
        }

        @Test
        fun `쓰기 후 즉시 반영 — replaceAllowedChannels·allowAllChannels·cleanup`() {
            val policy = newService(ttlMs = 60_000)
            val gid = 7005L

            policy.replaceAllowedChannels(gid, listOf(10, 20), adminId = 1)
            assertEquals(listOf(10L, 20L), policy.allowedChannelIds(gid).sorted())

            policy.allowAllChannels(gid, adminId = 1)
            assertTrue(policy.allowedChannelIds(gid).isEmpty())

            policy.replaceAllowedChannels(gid, listOf(30), adminId = 1)
            assertEquals(listOf(30L), policy.allowedChannelIds(gid))
            policy.cleanupChannel(gid, 30)
            assertTrue(policy.allowedChannelIds(gid).isEmpty()) // 즉시 반영
        }

        @Test
        fun `TTL 내 반복 조회는 단일 적재 — 캐시 히트`() {
            val policy = newService(ttlMs = 60_000)
            val gid = 7006L

            policy.setRolePolicy(gid, roleId = 5, ModelBurden.STANDARD, dailyLimit = 33, adminId = 1)
            // 적재 후 DB 를 우회해 직접 바꿔도 TTL 내에는 캐시 값 유지
            roles.findByGuildIdAndRoleId(gid, 5)!!.let {
                it.dailyLimit = 99
                roles.save(it)
            }
            // 위 setRolePolicy 가 evict 했고, 아래 첫 호출이 새로 적재(99)
            assertEquals(99, policy.dailyLimit(gid, listOf(5)))
            // 다시 DB 변경(우회) → TTL 내라 캐시 히트(여전히 99)
            roles.findByGuildIdAndRoleId(gid, 5)!!.let {
                it.dailyLimit = 1
                roles.save(it)
            }
            assertEquals(99, policy.dailyLimit(gid, listOf(5)))
        }
    }
