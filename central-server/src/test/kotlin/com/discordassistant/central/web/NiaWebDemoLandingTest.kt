package com.discordassistant.central.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaWebDemoLandingTest {
    private val html =
        requireNotNull(javaClass.classLoader.getResource("static/install.html")) {
            "install.html resource not found"
        }.readText()

    @Test
    fun `첫 화면은 별도 섹션 대신 로그인 기반 니아 대화창을 연다`() {
        assertThat(html).contains(
            """id="niaDemoOpen"""",
            """id="niaDemoDialog"""",
            """href="/oauth2/authorization/discord"""",
            """id="niaDemoLogin"""",
            """id="niaDemoChat"""",
        )
        assertThat(html).contains("heroCtaTryNia")
        assertThat(html).contains("https://discord.com/oauth2/authorize?client_id=1509346092850876416")
    }

    @Test
    fun `대화창은 서버 설정과 인증 API를 사용하고 비용 요청 표식을 보낸다`() {
        assertThat(html).contains(
            "fetch('/api/me'",
            "fetch('/api/nia-demo/status'",
            "fetch('/api/nia-demo/messages'",
            "'X-Nia-Web-Demo': '1'",
            """maxlength="500"""",
        )
        assertThat(html).doesNotContain("innerHTML = text")
    }
}
