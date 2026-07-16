package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdaExecutionErrorsTest {
    @Test
    fun `JDA가 전송 전에 거부한 요청은 재시도하지 않는 invalid request다`() {
        val failure = JdaExecutionErrors.toFailure(IllegalArgumentException("invalid snowflake"))

        assertThat(failure.reason).isEqualTo(ActionFailureReason.INVALID_REQUEST)
        assertThat(failure.reason.isRetryable).isFalse()
    }
}
