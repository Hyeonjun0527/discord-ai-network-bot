package com.discordassistant.central.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
class SeoPublicEndpointTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        @Test
        fun `sitemap stays public xml when oauth is enabled`() {
            mvc
                .perform(get("/sitemap.xml"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<urlset")))
                .andExpect(content().string(containsString("https://discord-ai.yeon.world/")))
                .andExpect(content().string(containsString("https://discord-ai.yeon.world/install")))
        }

        @Test
        fun `robots stays public text when oauth is enabled`() {
            mvc
                .perform(get("/robots.txt"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("Allow: /")))
                .andExpect(content().string(containsString("Sitemap: https://discord-ai.yeon.world/sitemap.xml")))
        }

        @Test
        fun `dashboard still requires oauth`() {
            mvc
                .perform(get("/admin/dashboard/"))
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", containsString("/oauth2/authorization/discord")))
        }
    }
