package com.discordassistant.central.global.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer
import org.springframework.security.web.SecurityFilterChain

class SecurityConfigTest {
    @Test
    fun `non-web import process skips HTTP security configuration`() {
        ApplicationContextRunner()
            .withUserConfiguration(SecurityConfig::class.java)
            .run { context ->
                assertThat(context).doesNotHaveBean(SecurityFilterChain::class.java)
                assertThat(context).doesNotHaveBean(WebSecurityCustomizer::class.java)
            }
    }
}
