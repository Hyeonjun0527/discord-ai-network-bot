package com.discordassistant.central.dashboard

import com.discordassistant.central.network.ChannelAiRoutingPolicyService
import com.discordassistant.central.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.provider.AuditLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChannelAiRoutingPolicyController::class, ChannelAiRoutingPolicyService::class, PolicyService::class, AuditLog::class)
class ChannelAiRoutingPolicyControllerTest
    @Autowired
    constructor(
        private val controller: ChannelAiRoutingPolicyController,
        private val providerCapabilities: ProviderCapabilityProfileRepository,
    ) {
        @Test
        fun `model choice response exposes routing block when safe allowed model is unavailable`() {
            controller.save(
                guildId = 100,
                channelId = 200,
                request =
                    SaveChannelAiRoutingPolicyRequest(
                        allowedModels = listOf("qwen-coder"),
                        minQualityTier = "standard",
                    ),
            )
            providerCapabilities.save(
                ProviderCapabilityProfileEntity(
                    guildId = 100,
                    providerUserId = 10,
                    providerState = "ONLINE",
                    modelNames = "qwen-coder",
                    qualityTier = "specialized",
                    overloadRisk = "critical",
                ),
            )

            val choice = controller.modelChoice(100, 200, requestedModel = "qwen-coder")

            assertEquals(null, choice["selectedModel"])
            assertEquals("no_available_model", choice["fallbackReason"])
            assertEquals(true, choice["requiresAvailableModel"])
            assertEquals(true, choice["routingBlocked"])
            assertEquals("retry_later_or_adjust_channel_model_policy", choice["nextAction"])
            assertEquals(true, choice["userMessage"].toString().contains("요청을 보내지 않았습니다"))
        }
    }
