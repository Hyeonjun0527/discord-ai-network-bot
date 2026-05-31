package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChannelAiProfileService::class)
class ChannelAiProfileServiceTest
    @Autowired
    constructor(
        val service: ChannelAiProfileService,
    ) {
        @Test
        fun `채널별 AI 프로필을 저장 조회 초기화한다`() {
            val saved = service.set(guildId = 100, channelId = 200, displayName = " 냥시스턴트 ", avatarUrl = " ")

            assertEquals("냥시스턴트", saved.displayName)
            assertNull(saved.avatarUrl)
            assertEquals("냥시스턴트", service.get(100, 200)?.displayName)

            service.clear(100, 200)
            assertNull(service.get(100, 200))
        }

        @Test
        fun `빈 프로필명은 거부한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.set(guildId = 100, channelId = 200, displayName = " ", avatarUrl = null)
            }
        }
    }
