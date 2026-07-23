package com.discordassistant.central.global.observability

import com.discordassistant.central.platform.discord.DiscordBot
import com.discordassistant.central.platform.discord.nexa.NexaParticipationEmitBridge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.util.ReflectionTestUtils

@SpringBootTest
class NiaRuntimeMetricsWiringTest
    @Autowired
    constructor(
        private val metrics: NiaRuntimeMetrics,
        private val discordBot: DiscordBot,
        private val participationBridge: NexaParticipationEmitBridge,
    ) {
        @Test
        fun `Discord ingress와 participation turn이 같은 운영 metrics bean에 연결된다`() {
            assertThat(ReflectionTestUtils.getField(discordBot, "niaRuntimeMetrics")).isSameAs(metrics)
            assertThat(ReflectionTestUtils.getField(participationBridge, "runtimeMetrics")).isSameAs(metrics)
        }
    }
