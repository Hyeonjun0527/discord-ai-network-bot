package com.discordassistant.central.web

import com.discordassistant.central.discord.BotChannelInfo
import com.discordassistant.central.discord.BotGuildInfo
import com.discordassistant.central.discord.BotGuildLister
import com.discordassistant.central.policy.AutoApprovePolicy
import com.discordassistant.central.provider.AuditLog
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.TokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ProviderConnectControllerTest {
    private val cb = "http://127.0.0.1:51234/connect/callback"

    private class FakeOAuth(
        var token: String? = "access",
        var userId: Long? = 777L,
        var guilds: List<GuildBrief> = listOf(GuildBrief(100, "내 서버")),
    ) : DiscordOAuthClient {
        override fun exchangeCodeForToken(
            code: String,
            redirectUri: String,
        ): String? = token

        override fun fetchUserId(accessToken: String): Long? = userId

        override fun fetchUserGuilds(accessToken: String): List<GuildBrief> = guilds
    }

    private fun controller(
        oauth: DiscordOAuthClient = FakeOAuth(),
        clientId: String = "cid",
        clientSecret: String = "csecret",
        registration: ProviderRegistrationService = ProviderRegistrationService(TokenService(600), AuditLog()),
        botGuildIds: Set<Long> = setOf(100L, 200L),
        autoApprove: Boolean = true,
        states: ConnectStateStore = ConnectStateStore(),
        selections: ProviderSelectionStore = ProviderSelectionStore(),
    ) = ProviderConnectController(
        clientId = clientId,
        clientSecret = clientSecret,
        configuredBase = "https://discord-ai.yeon.world",
        relayPublicUrl = "wss://discord-ai.yeon.world/agent",
        oauth = oauth,
        registration = registration,
        policy = AutoApprovePolicy { autoApprove },
        botGuilds =
            object : BotGuildLister {
                override fun botGuildIds() = botGuildIds

                override fun botGuilds() = botGuildIds.map { BotGuildInfo(it, "Guild $it") }

                override fun botChannels(guildId: Long) = emptyList<BotChannelInfo>()
            },
        states = states,
        selections = selections,
    )

    private fun stateFrom(location: String): String =
        location.substringAfter("state=").substringBefore("&").let { java.net.URLDecoder.decode(it, "UTF-8") }

    @Test
    fun `미설정이면 비활성(503 안내)`() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller(clientId = "").connect(cb, "sk").statusCode)
    }

    @Test
    fun `localhost 아닌 콜백은 거부`() {
        val c = controller()
        assertEquals(HttpStatus.BAD_REQUEST, c.connect("https://evil.com/connect/callback", "sk").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, c.connect("http://127.0.0.1:9/steal", "sk").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, c.connect("http://127.0.0.1:9/connect/callback?x=1", "sk").statusCode)
    }

    @Test
    fun `connect 는 identify+guilds 스코프로 리디렉트`() {
        val r = controller().connect(cb, "sk")
        assertEquals(HttpStatus.FOUND, r.statusCode)
        val loc = r.headers.location!!.toString()
        assertTrue(loc.startsWith("https://discord.com/api/oauth2/authorize"))
        assertTrue(loc.contains("client_id=cid") && loc.contains("scope=identify+guilds")) // URLEncoder: 공백→+
    }

    @Test
    fun `후보 서버 1개면 자동 발급 후 로컬 cb 로 리디렉트`() {
        val states = ConnectStateStore()
        val c = controller(states = states) // 사용자 길드 100, 봇 길드 100·200 → 후보 100 하나
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
        assertTrue(loc.contains("token=") && loc.contains("state=sk"), loc)
    }

    @Test
    fun `후보 서버 여러 개면 선택 화면을 보여준다`() {
        val states = ConnectStateStore()
        val oauth = FakeOAuth(guilds = listOf(GuildBrief(100, "A"), GuildBrief(200, "B")))
        val c = controller(oauth = oauth, botGuildIds = setOf(100L, 200L), states = states)
        val oauthState =
            stateFrom(
                c
                    .connect(cb, "sk")
                    .headers.location!!
                    .toString(),
            )
        val r = c.callback(code = "x", state = oauthState)
        assertEquals(HttpStatus.OK, r.statusCode)
        val html = r.body!!
        assertTrue(html.contains("어느 서버에 기여") && html.contains(">A<") && html.contains(">B<"))
        assertTrue(html.contains("/provider/connect/pick?sel="))
    }

    @Test
    fun `봇이 있는 서버에 내가 없으면 안내 페이지`() {
        val states = ConnectStateStore()
        val oauth = FakeOAuth(guilds = listOf(GuildBrief(999, "남의 서버")))
        val c = controller(oauth = oauth, botGuildIds = setOf(100L), states = states)
        val oauthState =
            stateFrom(
                c
                    .connect(cb, "sk")
                    .headers.location!!
                    .toString(),
            )
        val r = c.callback(code = "x", state = oauthState)
        assertEquals(HttpStatus.OK, r.statusCode)
        assertTrue(r.body!!.contains("속한 곳이 없습니다"))
    }

    @Test
    fun `자동 승인 아니면 pending 으로 리디렉트(토큰 없음)`() {
        val states = ConnectStateStore()
        val c = controller(autoApprove = false, states = states) // 신규 가입인데 자동승인 X → PENDING
        val oauthState =
            stateFrom(
                c
                    .connect(cb, "sk")
                    .headers.location!!
                    .toString(),
            )
        val r = c.callback(code = "x", state = oauthState)
        val loc = r.headers.location!!.toString()
        assertTrue(loc.contains("error=pending") && !loc.contains("token="), loc)
    }

    @Test
    fun `잘못되거나 만료된 state 는 400`() {
        assertEquals(HttpStatus.BAD_REQUEST, controller().callback(code = "x", state = "nope").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller().callback(code = "x", state = null).statusCode)
    }

    @Test
    fun `취소(code 없음)면 cb 로 error`() {
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
        assertTrue(
            r.headers.location!!
                .toString()
                .contains("error=cancelled"),
        )
    }
}
