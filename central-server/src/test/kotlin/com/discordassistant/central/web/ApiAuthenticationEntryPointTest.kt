package com.discordassistant.central.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "central.oauth.enabled=true",
        "central.connect.discord-client-id=test-client",
        "central.connect.discord-client-secret=test-secret",
    ],
)
@AutoConfigureMockMvc
class ApiAuthenticationEntryPointTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        @Test
        fun `protected api returns unauthorized without oauth redirect`() {
            mvc
                .perform(get("/api/dashboard/guilds"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().doesNotExist("Location"))
        }

        @Test
        fun `protected admin page still redirects to oauth login`() {
            mvc
                .perform(get("/admin/console/"))
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", containsString("/oauth2/authorization/discord")))
        }
    }
