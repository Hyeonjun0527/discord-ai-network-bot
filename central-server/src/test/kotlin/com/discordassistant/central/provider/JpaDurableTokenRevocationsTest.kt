package com.discordassistant.central.provider

import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderDurableRevocationRepository
import com.discordassistant.central.provider.application.JpaDurableTokenRevocations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

/** Flyway(H2) 스키마에 대해 durable 토큰 폐기 저장소를 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaDurableTokenRevocationsTest
    @Autowired
    constructor(
        val repo: ProviderDurableRevocationRepository,
    ) {
        private fun store() = JpaDurableTokenRevocations(repo)

        @Test
        fun `폐기 기록·조회`() {
            val s = store()
            assertNull(s.revokedAtEpoch(1, 10))
            s.revoke(1, 10, 1_000)
            assertEquals(1_000L, s.revokedAtEpoch(1, 10))
        }

        @Test
        fun `폐기 시각은 단조 증가(되돌리지 않음)`() {
            val s = store()
            s.revoke(2, 20, 1_000)
            s.revoke(2, 20, 900) // 더 이른 시각 → 무시
            assertEquals(1_000L, s.revokedAtEpoch(2, 20))
            s.revoke(2, 20, 1_100) // 더 늦은 시각 → 갱신
            assertEquals(1_100L, s.revokedAtEpoch(2, 20))
        }

        @Test
        fun `provider-guild 별로 독립`() {
            val s = store()
            s.revoke(3, 30, 1_000)
            assertEquals(1_000L, s.revokedAtEpoch(3, 30))
            assertNull(s.revokedAtEpoch(3, 31))
            assertNull(s.revokedAtEpoch(4, 30))
        }
    }
