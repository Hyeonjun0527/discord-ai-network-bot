package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
            assertEquals(1, saved.version)
            assertEquals(DEFAULT_CHANNEL_AI_PURPOSE, saved.purpose)
            assertEquals("냥시스턴트", service.get(100, 200)?.displayName)

            service.clear(100, 200)
            assertNull(service.get(100, 200))
        }

        @Test
        fun `행동 설정은 새 버전으로 저장되고 직전 버전으로 롤백된다`() {
            service.set(
                guildId = 100,
                channelId = 201,
                displayName = "코드냥",
                avatarUrl = null,
                actorId = 5,
                purpose = "Kotlin 개발 도우미",
                tone = "전문적으로",
                answerLength = "자세히",
                constitution = "코드는 실행 가능한 예시 위주로 답합니다.",
            )
            val v2 =
                service.set(
                    guildId = 100,
                    channelId = 201,
                    displayName = "코드냥",
                    avatarUrl = null,
                    actorId = 5,
                    purpose = "Kotlin/Spring Boot 리뷰어",
                    tone = "짧고 명확하게",
                    answerLength = "짧게",
                    constitution = "추측하지 말고 근거를 먼저 확인합니다.",
                )

            assertEquals(2, v2.version)
            assertEquals("Kotlin/Spring Boot 리뷰어", service.get(100, 201)?.purpose)

            val rolledBack = service.rollback(100, 201, actorId = 5)!!
            assertEquals(1, rolledBack.version)
            assertEquals("Kotlin 개발 도우미", rolledBack.purpose)
            assertTrue(service.recentAudit(100, 201).any { it.startsWith("rollback") })
        }

        @Test
        fun `빈 프로필명은 거부한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.set(guildId = 100, channelId = 200, displayName = " ", avatarUrl = null)
            }
        }
    }
