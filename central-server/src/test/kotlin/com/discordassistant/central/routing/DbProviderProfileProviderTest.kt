package com.discordassistant.central.routing

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyEntity
import com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyRepository
import com.discordassistant.central.routing.adapter.outbound.DbProviderProfileProvider
import com.discordassistant.central.shared.ModelBurden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DbProviderProfileProviderTest
    @Autowired
    constructor(
        private val policies: ProviderContributionPolicyRepository,
        private val capabilities: ProviderCapabilityProfileRepository,
    ) {
        private val provider = DbProviderProfileProvider(policies, capabilities)

        @Test
        fun `provider contribution policy does not own guild channel or role scope`() {
            policies.save(
                ProviderContributionPolicyEntity(
                    providerId = 10,
                    model = "llama3.1:8b",
                    burden = ModelBurden.HEAVY.name,
                ),
            )
            capabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 10,
                    maxBurden = ModelBurden.STANDARD,
                ),
            )

            val profile = provider.profile(guildId = 100, providerId = 10)

            assertEquals(setOf(ModelBurden.LIGHT, ModelBurden.STANDARD), profile.supportedBurdens)
            assertNull(profile.allowedChannelIds)
            assertNull(profile.allowedRoleIds)
        }
    }
