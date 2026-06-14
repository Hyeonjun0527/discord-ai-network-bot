package com.discordassistant.central.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files

@SpringBootTest
@AutoConfigureMockMvc
class DownloadCacheControlTest
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) {
        @Test
        fun `download assets are not cached because release files are overwritten in place`() {
            mvc
                .perform(get("/download/latest.json"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
        }

        companion object {
            private val downloadDir = Files.createTempDirectory("nexa-download-cache-test").also { dir ->
                Files.writeString(dir.resolve("latest.json"), "{}")
            }

            @JvmStatic
            @DynamicPropertySource
            fun downloadProperties(registry: DynamicPropertyRegistry) {
                registry.add("central.download.dir") { downloadDir.toString() }
            }
        }
    }
