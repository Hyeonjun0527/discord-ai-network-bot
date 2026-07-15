package com.discordassistant.central.global.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class ConfigTreeSecretBindingTest {
    @TempDir
    lateinit var secretDirectory: Path

    @Test
    fun `production secret filenames bind to Spring property names`() {
        val secrets =
            mapOf(
                "spring.datasource.password" to "db-secret",
                "central.discord.enabled" to "true",
                "central.discord.bot-token" to "discord-secret",
                "central.relay.public-url" to "wss://example.test/agent",
                "central.durable.secret" to "durable-secret",
                "nexa.field-enc-key" to "field-secret",
                "central.cloud.zai-api-key" to "zai-secret",
                "central.connect.discord-client-id" to "oauth-client-id",
                "central.connect.discord-client-secret" to "oauth-secret",
                "central.oauth.enabled" to "true",
                "central.dashboard.admin-user-ids" to "123456789",
            )
        secrets.forEach { (propertyName, value) ->
            Files.writeString(secretDirectory.resolve(propertyName), value)
        }

        val application = SpringApplication(ProbeConfiguration::class.java)
        application.webApplicationType = WebApplicationType.NONE
        application.setLogStartupInfo(false)

        val configTreeLocation = secretDirectory.toAbsolutePath().toString() + "/"
        application.run("--spring.config.import=configtree:$configTreeLocation").use { context ->
            secrets.forEach { (propertyName, expected) ->
                assertEquals(expected, context.environment.getProperty(propertyName))
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class ProbeConfiguration
}
