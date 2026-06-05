package com.discordassistant.central.dashboard

import com.discordassistant.central.ainetwork.adapter.inbound.web.ChannelAiRoutingPolicyController
import com.discordassistant.central.ainetwork.adapter.inbound.web.SaveChannelAiRoutingPolicyRequest
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileRepository
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicyService
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.guild.application.PolicyService
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
                    providerState = ProviderAvailability.ONLINE,
                    modelNames = "qwen-coder",
                    qualityTier = ModelQualityTier.SPECIALIZED,
                    overloadRisk = OverloadRisk.CRITICAL,
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
