package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.global.crypto.FieldCryptoConfig
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudToolResponse
import com.discordassistant.central.routing.application.ImageReview
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class NexaAutonomousSendReadinessGuardTest {
    @AfterEach
    fun resetCrypto() {
        FieldCrypto.configure(null)
    }

    @Test
    fun `암호화 키와 Cloud LLM이 모두 준비되어야 자율 전송을 시작한다`() {
        val crypto = FieldCryptoConfig("test-key").also(FieldCryptoConfig::init)

        assertThatNoException().isThrownBy {
            NexaAutonomousSendReadinessGuard(crypto, FakeCloudLlm(enabled = true)).validate()
        }
    }

    @Test
    fun `암호화 키가 없으면 부팅 가드가 즉시 실패한다`() {
        val crypto = FieldCryptoConfig("").also(FieldCryptoConfig::init)

        assertThatThrownBy {
            NexaAutonomousSendReadinessGuard(crypto, FakeCloudLlm(enabled = true)).validate()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("NEXA_FIELD_ENC_KEY")
    }

    @Test
    fun `Cloud LLM이 비활성이면 부팅 가드가 즉시 실패한다`() {
        val crypto = FieldCryptoConfig("test-key").also(FieldCryptoConfig::init)

        assertThatThrownBy {
            NexaAutonomousSendReadinessGuard(crypto, FakeCloudLlm(enabled = false)).validate()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("CloudLlm")
    }

    private class FakeCloudLlm(
        private val enabled: Boolean,
    ) : CloudLlm {
        override fun isEnabled(): Boolean = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult = CloudLlmResult("ok")

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): CloudToolResponse = CloudToolResponse(text = "ok")

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = ImageReview(allowed = true)

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = prompt
    }
}
