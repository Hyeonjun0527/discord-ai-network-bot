package com.discordassistant.central.web

import com.discordassistant.central.provider.AuditLog
import com.discordassistant.central.provider.ProviderRegistrationService
import com.discordassistant.central.provider.TokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ProviderConnectControllerTest {
    private val cb = "http://127.0.0.1:51234/connect/callback"

    private class FakeOAuth(
        var token: String? = "access",
        var userId: Long? = 777L,
    ) : DiscordOAuthClient {
        var lastCode: String? = null

        override fun exchangeCodeForToken(
            code: String,
            redirectUri: String,
        ): String? {
            lastCode = code
            return token
        }

        override fun fetchUserId(accessToken: String): Long? = userId
    }

    private fun controller(
        oauth: DiscordOAuthClient = FakeOAuth(),
        clientId: String = "cid",
        clientSecret: String = "csecret",
        registration: ProviderRegistrationService = ProviderRegistrationService(TokenService(600), AuditLog()),
        states: ConnectStateStore = ConnectStateStore(),
    ) = ProviderConnectController(
        clientId = clientId,
        clientSecret = clientSecret,
        configuredBase = "https://discord-ai.yeon.world",
        relayPublicUrl = "wss://discord-ai.yeon.world/agent",
        oauth = oauth,
        registration = registration,
        states = states,
    )

    private fun stateFrom(location: String): String =
        location.substringAfter("state=").substringBefore("&").let { java.net.URLDecoder.decode(it, "UTF-8") }

    @Test
    fun `미설정이면 비활성(503 안내)`() {
        val r = controller(clientId = "").connect(cb, "sk")
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.statusCode)
    }

    @Test
    fun `localhost 아닌 콜백은 거부`() {
        val c = controller()
        assertEquals(HttpStatus.BAD_REQUEST, c.connect("https://evil.com/connect/callback", "sk").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, c.connect("http://127.0.0.1:9/steal", "sk").statusCode) // 경로 불일치
        assertEquals(HttpStatus.BAD_REQUEST, c.connect("http://127.0.0.1:9/connect/callback?x=1", "sk").statusCode) // 쿼리 금지
    }

    @Test
    fun `connect 는 Discord authorize 로 리디렉트하고 state 를 봉인`() {
        val r = controller().connect(cb, "sk")
        assertEquals(HttpStatus.FOUND, r.statusCode)
        val loc = r.headers.location!!.toString()
        assertTrue(loc.startsWith("https://discord.com/api/oauth2/authorize"))
        assertTrue(loc.contains("client_id=cid") && loc.contains("scope=identify"))
        assertTrue(loc.contains("redirect_uri=") && loc.contains("state="))
    }

    @Test
    fun `전체 흐름 — 식별 후 토큰 발급, 로컬 cb 로 리디렉트`() {
        val states = ConnectStateStore()
        val reg = ProviderRegistrationService(TokenService(600), AuditLog())
        val c = controller(registration = reg, states = states)
        val oauthState =
            stateFrom(
                c
                    .connect(cb, "sk")
                    .headers.location!!
                    .toString(),
            )

        val r = c.callback(code = "authcode", state = oauthState)
        assertEquals(HttpStatus.FOUND, r.statusCode)
        val loc = r.headers.location!!.toString()
        assertTrue(loc.startsWith(cb + "?"))
        assertTrue(loc.contains("token="), "토큰을 cb 로 전달해야 함: $loc")
        assertTrue(loc.contains("state=sk"), "로컬 세션키를 되돌려줘야 함: $loc")

        // 발급된 토큰은 일회용 토큰 포맷(사람이 읽는 하이픈 그룹)
        val token = loc.substringAfter("token=").substringBefore("&").let { java.net.URLDecoder.decode(it, "UTF-8") }
        assertTrue(token.contains("-"), "일회용 토큰 포맷이어야 함: $token")
    }

    @Test
    fun `잘못되거나 만료된 state 는 400`() {
        assertEquals(HttpStatus.BAD_REQUEST, controller().callback(code = "x", state = "nope").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller().callback(code = "x", state = null).statusCode)
    }

    @Test
    fun `사용자가 취소(code 없음)하면 cb 로 error 전달`() {
        val states = ConnectStateStore()
        val c = controller(states = states)
        val oauthState =
            stateFrom(
                c
                    .connect(cb, "sk")
                    .headers.location!!
                    .toString(),
            )
        val r = c.callback(code = null, state = oauthState)
        assertEquals(HttpStatus.FOUND, r.statusCode)
        assertTrue(
            r.headers.location!!
                .toString()
                .contains("error=cancelled"),
        )
    }

    @Test
    fun `토큰 교환 실패 시 cb 로 error`() {
        val states = ConnectStateStore()
        val c = controller(oauth = FakeOAuth(token = null), states = states)
        val oauthState =
            stateFrom(
                c
                    .connect(cb, "sk")
                    .headers.location!!
                    .toString(),
            )
        val r = c.callback(code = "x", state = oauthState)
        assertTrue(
            r.headers.location!!
                .toString()
                .contains("error=token"),
        )
    }
}
