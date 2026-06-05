package com.discordassistant.central.persistence

import com.discordassistant.central.guild.adapter.outbound.persistence.AllowedChannelEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.AllowedChannelRepository
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderRepository
import com.discordassistant.central.provider.domain.model.ProviderState
import com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogRepository
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogEntity
import com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant

/** Flyway(H2)로 만든 스키마에 대해 Repository CRUD 를 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceTest
    @Autowired
    constructor(
        val providerRepo: ProviderRepository,
        val allowedChannelRepo: AllowedChannelRepository,
        val usageRepo: UsageLogRepository,
        val contributionRepo: ContributionLogRepository,
    ) {
        @Test
        fun `provider 저장·조회`() {
            providerRepo.save(
                ProviderEntity(providerUserId = 11, guildId = 100, state = ProviderState.PENDING, createdAt = Instant.now()),
            )
            val found = providerRepo.findByProviderUserIdAndGuildId(11, 100)
            assertNotNull(found)
            assertEquals(ProviderState.PENDING, found!!.state)
            assertEquals(1, providerRepo.findByGuildIdAndState(100, ProviderState.PENDING).size)
        }

        @Test
        fun `허용 채널 존재 확인`() {
            allowedChannelRepo.save(AllowedChannelEntity(guildId = 100, channelId = 200))
            assertTrue(allowedChannelRepo.existsByGuildIdAndChannelId(100, 200))
            assertEquals(1, allowedChannelRepo.findByGuildId(100).size)
        }

        @Test
        fun `사용량·기여 카운트`() {
            usageRepo.save(UsageLogEntity(guildId = 100, userId = 5, requestId = "r1", createdAt = Instant.now()))
            usageRepo.save(UsageLogEntity(guildId = 100, userId = 5, requestId = "r2", createdAt = Instant.now()))
            assertEquals(2, usageRepo.countByGuildIdAndUserId(100, 5))
            contributionRepo.save(ContributionLogEntity(providerId = 1, requestId = "r1", createdAt = Instant.now()))
            assertEquals(1, contributionRepo.countByProviderId(1))
        }
    }
