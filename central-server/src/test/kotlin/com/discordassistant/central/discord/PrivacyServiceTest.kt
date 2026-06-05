package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.guild.application.PrivacyService
import com.discordassistant.central.guild.domain.model.PrivacyMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PrivacyService::class)
class PrivacyServiceTest
    @Autowired
    constructor(
        val privacy: PrivacyService,
    ) {
        @Test
        fun `기본 모드 C — 일반 유저와 관리자 모두 답변 문구에서는 provider snowflake 를 숨긴다`() {
            assertEquals(PrivacyMode.C_ADMIN_ONLY, privacy.mode(100))
            val user = privacy.processedNotice(100, ModelBurden.LIGHT, 7, isAdmin = false)
            assertEquals("함께 만드는 AI 네트워크에서 처리됨", user)
            val admin = privacy.processedNotice(100, ModelBurden.LIGHT, 7, isAdmin = true)
            assertEquals(
                "함께 만드는 AI 네트워크에서 처리됨 · 모델 수준 LIGHT · Provider 상세는 관리자 대시보드에서 확인",
                admin,
            )
            assertTrue(!admin.contains("provider #7"))
        }

        @Test
        fun `모드 A 익명`() {
            privacy.setMode(100, PrivacyMode.A_ANONYMOUS)
            val notice = privacy.processedNotice(100, ModelBurden.STANDARD, 7, isAdmin = true)
            assertEquals("커뮤니티 로컬 AI 풀에서 처리됨 · 모델 수준 STANDARD", notice)
        }

        @Test
        fun `모드 B 부분 공개`() {
            privacy.setMode(100, PrivacyMode.B_PARTIAL)
            assertTrue(privacy.processedNotice(100, ModelBurden.HEAVY, 7, isAdmin = false).contains("처리 위치"))
        }
    }
