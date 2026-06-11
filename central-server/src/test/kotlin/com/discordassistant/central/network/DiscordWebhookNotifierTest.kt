package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.application.DiscordWebhookNotifier
import com.discordassistant.central.ainetwork.application.Severity
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class DiscordWebhookNotifierTest {
    @Test
    fun `webhook failure is logged but does not fail caller`() {
        val notifier = DiscordWebhookNotifier("http://127.0.0.1:1/webhook")

        assertDoesNotThrow {
            notifier.notify(Severity.CRITICAL, "critical", "network refused")
            notifier.notify(Severity.WARN, "warn", "network refused")
            notifier.notify(Severity.INFO, "info", "network refused")
        }
    }
}
