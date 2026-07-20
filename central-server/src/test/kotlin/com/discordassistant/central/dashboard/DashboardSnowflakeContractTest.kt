package com.discordassistant.central.dashboard

import com.discordassistant.central.ainetwork.adapter.inbound.web.DashboardController
import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.platform.discord.BotChannelInfo
import com.discordassistant.central.platform.discord.BotGuildInfo
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.AnalyticsService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class DashboardSnowflakeContractTest {
    private val botGuilds =
        object : BotGuildLister {
            override fun botGuildIds(): Set<Long> = setOf(GUILD_ID)

            override fun botGuilds(): List<BotGuildInfo> = listOf(BotGuildInfo(GUILD_ID, "test guild"))

            override fun botChannels(guildId: Long): List<BotChannelInfo> =
                if (guildId == GUILD_ID) listOf(BotChannelInfo(CHANNEL_ID, "general")) else emptyList()

            override fun isGuildAdmin(
                guildId: Long,
                userId: Long,
            ): Boolean = false

            override fun memberName(
                guildId: Long,
                userId: Long,
            ): String? = null
        }
    private val controller =
        DashboardController(
            registry = mock(ConnectionRegistry::class.java),
            policy = mock(PolicyService::class.java),
            analytics = mock(AnalyticsService::class.java),
            featureGate = AiNetworkFeatureGate(),
            botGuilds = botGuilds,
        )
    private val mapper = jacksonObjectMapper()

    @Test
    fun `Discord snowflakes stay exact strings in dashboard picker responses`() {
        val guildJson = mapper.readTree(mapper.writeValueAsString(controller.guilds())).single()
        val channelJson = mapper.readTree(mapper.writeValueAsString(controller.channels(GUILD_ID))).single()

        assertTrue(guildJson["id"].isTextual)
        assertEquals(GUILD_ID.toString(), guildJson["id"].asText())
        assertTrue(channelJson["id"].isTextual)
        assertEquals(CHANNEL_ID.toString(), channelJson["id"].asText())
    }

    private companion object {
        const val GUILD_ID = 1_380_395_592_336_805_928L
        const val CHANNEL_ID = 1_509_347_932_665_675_867L
    }
}
