package com.discordassistant.central.web

import com.discordassistant.central.discord.ProviderOnboarding
import com.discordassistant.central.domain.InstallGuide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 설치 가이드 SSOT 보증: 디스코드 슬래시 안내([ProviderOnboarding])와 웹 랜딩(`/install`)이
 * **같은 [InstallGuide]** 만 읽어 드리프트하지 않는지, 그리고 Linux 가이드/다운로드가 폐기됐는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstallPageSsotTest
    @Autowired
    constructor(
        val mvc: MockMvc,
    ) {
        @Test
        fun `SSOT 는 GUI 앱 배포 대상(mac·Windows)만 — Linux 미지원`() {
            assertEquals(listOf("mac", "win"), InstallGuide.OSES.map { it.key })
            assertNull(InstallGuide.forOs("linux"))
        }

        @Test
        fun `디스코드 안내가 SSOT 설치 명령을 그대로 사용한다`() {
            val mac = ProviderOnboarding.installCommand("mac", "TOK-1", "")
            assertTrue(mac.contains(InstallGuide.MAC.appInstall), mac)
            val win = ProviderOnboarding.installCommand("windows", "TOK-2", "")
            assertTrue(win.contains("Nyassistant.DiscordAiNetworkBot"), win)
            // 폴백 메시지도 두 OS 의 SSOT 설치 명령을 노출한다.
            val msg = ProviderOnboarding.message("TOK-3", "")
            assertTrue(msg.contains(InstallGuide.MAC.appInstall), msg)
            assertTrue(msg.contains(InstallGuide.WIN.appInstall), msg)
        }

        @Test
        fun `웹 install 페이지가 SSOT 를 주입하고 Linux 흔적이 없다`() {
            val html =
                mvc
                    .perform(get("/install"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            // 자리표시가 실제 SSOT JSON 으로 치환됐다(정적 null 폴백이 아니다).
            assertFalse(html.contains("const INSTALL_GUIDE = null;"), "SSOT 주입 안 됨")
            assertTrue(html.contains(InstallGuide.MAC.appInstall), "MAC appInstall 누락")
            assertTrue(html.contains("Nyassistant.DiscordAiNetworkBot"))
            // Linux 탭/다운로드는 폐기.
            assertFalse(html.contains("data-os=\"linux\""))
            assertFalse(html.contains("discord-ai-network-bot-linux"))
        }
    }
