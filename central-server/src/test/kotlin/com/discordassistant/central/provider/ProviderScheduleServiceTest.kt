package com.discordassistant.central.provider

import com.discordassistant.central.persistence.ProviderScheduleRepository
import com.discordassistant.central.relay.ConnectionRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProviderScheduleService::class, ConnectionRegistry::class, ProviderProtectionService::class, AuditLog::class)
class ProviderScheduleServiceTest @Autowired constructor(
    val service: ProviderScheduleService,
    val repo: ProviderScheduleRepository,
) {
    private fun clockAt(hour: Int): Clock =
        Clock.fixed(Instant.parse("2026-01-10T00:00:00Z").plusSeconds(hour * 3600L), ZoneOffset.UTC)

    @Test
    fun `스케줄 미설정이면 항상 가용`() {
        assertTrue(service.isAvailableNow(1, 100, clockAt(3)))
    }

    @Test
    fun `스케줄 저장 후 시간대 판정(야간 22~6)`() {
        service.setSchedule(providerId = 1, guildId = 100, fromHour = 22, toHour = 6)
        assertTrue(service.isAvailableNow(1, 100, clockAt(23)))
        assertTrue(service.isAvailableNow(1, 100, clockAt(2)))
        assertFalse(service.isAvailableNow(1, 100, clockAt(12)))
    }

    @Test
    fun `upsert — 재설정 시 행이 늘지 않음`() {
        service.setSchedule(1, 100, 9, 18)
        service.setSchedule(1, 100, 10, 20)
        // 같은 (provider,guild) 는 1행
        assertTrue(repo.findByProviderIdAndGuildId(1, 100) != null)
        assertTrue(service.isAvailableNow(1, 100, clockAt(19))) // 10~20 안
    }
}
