package com.discordassistant.central.web

import com.discordassistant.central.discord.BotGuildLister
import com.discordassistant.central.policy.AutoApprovePolicy
import com.discordassistant.central.provider.ProviderRegistrationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 웹 ‘토큰 받기’(`--gui` 의 버튼) — Discord 로그인으로 **내가 고른 서버**의 프로바이더 토큰을 발급.
 *
 * 흐름: 에이전트 UI → `/provider/connect?cb=<로컬콜백>&state=<세션키>` → Discord OAuth(identify+guilds)
 * → `/provider/connect/callback` → 사용자 식별 + 소속 길드 조회 → **봇이 있는 서버 ∩ 내 서버** 후보 산출
 * → 1개면 자동, 여러 개면 선택 화면(/provider/connect/pick) → 그 서버에 가입(자동 승인 정책 따름)·일회용
 * 토큰 발급 → 로컬 `cb?token=…&state=…` 로 리디렉트(에이전트가 저장).
 *
 * 보안: client-id/secret 미설정이면 비활성(503). cb 는 localhost+경로 `/connect/callback` 만 허용
 * (토큰 탈취 방지). OAuth state·선택 키는 서버 발급·단발성(CSRF/재생 방지).
 */
@Controller
class ProviderConnectController(
    @param:Value("\${central.connect.discord-client-id:}") private val clientId: String,
    @param:Value("\${central.connect.discord-client-secret:}") private val clientSecret: String,
    @param:Value("\${central.connect.public-base-url:}") private val configuredBase: String,
    @param:Value("\${central.relay.public-url:}") private val relayPublicUrl: String,
    private val oauth: DiscordOAuthClient,
    private val registration: ProviderRegistrationService,
    private val policy: AutoApprovePolicy,
    private val botGuilds: BotGuildLister,
    private val states: ConnectStateStore = ConnectStateStore(),
    private val selections: ProviderSelectionStore = ProviderSelectionStore(),
) {
    private companion object {
        const val CALLBACK_PATH = "/connect/callback"
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")
    }

    private val enabled: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    private fun publicBase(): String {
        val raw =
            configuredBase.ifBlank {
                relayPublicUrl.replace("wss://", "https://").replace("ws://", "http://").let {
                    if (it.endsWith("/agent")) it.dropLast("/agent".length) else it
                }
            }
        return raw.trim().trimEnd('/')
    }

    @GetMapping("/provider/connect")
    fun connect(
        @RequestParam cb: String,
        @RequestParam state: String,
    ): ResponseEntity<String> {
        if (!enabled) {
            return page(
                HttpStatus.SERVICE_UNAVAILABLE,
                "‘토큰 받기’가 아직 설정되지 않았습니다. 디스코드에서 <b>/provider-join</b> 으로 토큰을 받아 붙여넣어 주세요.",
            )
        }
        if (!isLocalCallback(cb)) return page(HttpStatus.BAD_REQUEST, "잘못된 콜백 주소입니다.")
        if (state.isBlank()) return page(HttpStatus.BAD_REQUEST, "세션 정보가 없습니다.")
        val oauthState = states.put(cb, state)
        val redirectUri = publicBase() + "/provider/connect/callback"
        val authorize =
            "https://discord.com/api/oauth2/authorize?response_type=code" +
                "&client_id=" + enc(clientId) +
                "&scope=" + enc("identify guilds") +
                "&redirect_uri=" + enc(redirectUri) +
                "&state=" + enc(oauthState) +
                "&prompt=consent"
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorize)).build()
    }

    @GetMapping("/provider/connect/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
    ): ResponseEntity<String> {
        val entry =
            state?.let { states.take(it) }
                ?: return page(HttpStatus.BAD_REQUEST, "만료되었거나 잘못된 요청입니다. 처음부터 다시 시도해 주세요.")
        if (code.isNullOrBlank()) return redirectToCb(entry.cb, entry.localState, error = "cancelled")
        val redirectUri = publicBase() + "/provider/connect/callback"
        val accessToken =
            oauth.exchangeCodeForToken(code, redirectUri)
                ?: return redirectToCb(entry.cb, entry.localState, error = "token")
        val userId =
            oauth.fetchUserId(accessToken)
                ?: return redirectToCb(entry.cb, entry.localState, error = "identify")
        // 봇이 있는 서버 ∩ 내가 속한 서버 = 프로바이더가 될 수 있는 후보.
        val botIds = botGuilds.botGuildIds()
        val candidates = oauth.fetchUserGuilds(accessToken).filter { it.id in botIds }
        return when {
            candidates.isEmpty() ->
                page(
                    HttpStatus.OK,
                    "이 봇이 들어가 있는 서버 중 당신이 속한 곳이 없습니다.<br>봇이 있는 서버에 가입한 뒤 다시 시도해 주세요.",
                )
            candidates.size == 1 -> issueAndRedirect(entry.cb, entry.localState, userId, candidates[0].id)
            else -> chooserPage(entry, userId, candidates)
        }
    }

    @GetMapping("/provider/connect/pick")
    fun pick(
        @RequestParam sel: String,
        @RequestParam guild: Long,
    ): ResponseEntity<String> {
        val entry =
            selections.take(sel)
                ?: return page(HttpStatus.BAD_REQUEST, "만료되었거나 잘못된 요청입니다. 처음부터 다시 시도해 주세요.")
        if (entry.candidates.none { it.id == guild }) {
            return page(HttpStatus.BAD_REQUEST, "선택할 수 없는 서버입니다.")
        }
        return issueAndRedirect(entry.cb, entry.localState, entry.userId, guild)
    }

    /** 선택된 서버에 가입(자동 승인 정책)·토큰 발급 후 로컬 콜백으로 리디렉트. 승인 대기면 error=pending. */
    private fun issueAndRedirect(
        cb: String,
        localState: String,
        userId: Long,
        guildId: Long,
    ): ResponseEntity<String> {
        val join = registration.requestJoin(userId, guildId, autoApprove = policy.isAutoApprove(guildId))
        val token = join.token ?: registration.issueOnboardingToken(userId, guildId)
        return if (token != null) {
            redirectToCb(cb, localState, token = token)
        } else {
            // 자동 승인이 아니어서 관리자 승인 대기 상태(PENDING).
            redirectToCb(cb, localState, error = "pending")
        }
    }

    private fun chooserPage(
        entry: ConnectStateStore.Entry,
        userId: Long,
        candidates: List<GuildBrief>,
    ): ResponseEntity<String> {
        val sel = selections.put(entry.cb, entry.localState, userId, candidates)
        val items =
            candidates.joinToString("") { g ->
                "<a class=item href='/provider/connect/pick?sel=" + enc(sel) + "&guild=" + g.id + "'>" +
                    htmlEscape(g.name.ifBlank { "서버 ${g.id}" }) + "</a>"
            }
        val body =
            "<!doctype html><meta charset=utf-8><title>서버 선택</title>" +
                "<style>body{font-family:system-ui,-apple-system,'Apple SD Gothic Neo',sans-serif;" +
                "background:#070d16;color:#eef4ff;display:grid;place-items:center;min-height:100vh;margin:0}" +
                ".card{width:min(420px,92%);background:#0d1624;border:1px solid rgba(148,163,184,.18);border-radius:18px;padding:26px}" +
                "h1{font-size:20px;margin:0 0 6px}p{color:#a7b3c5;font-size:14px;margin:0 0 18px}" +
                ".item{display:block;padding:14px 16px;margin:8px 0;border-radius:12px;" +
                "border:1px solid rgba(79,125,255,.3);background:rgba(79,125,255,.06);color:#eef4ff;text-decoration:none;font-weight:700}" +
                ".item:hover{background:rgba(79,125,255,.14)}</style>" +
                "<div class=card><h1>어느 서버에 기여할까요?</h1><p>내 PC를 제공할 디스코드 서버를 고르세요.</p>" + items + "</div>"
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body)
    }

    /** cb 가 localhost 콜백(http://127.0.0.1:PORT/connect/callback)인지 엄격 검증. */
    private fun isLocalCallback(cb: String): Boolean {
        val uri = runCatching { URI.create(cb) }.getOrNull() ?: return false
        if (uri.scheme != "http") return false
        if (uri.userInfo != null) return false
        if (uri.host == null || uri.host !in LOCAL_HOSTS) return false
        if (uri.path != CALLBACK_PATH) return false
        if (uri.query != null || uri.fragment != null) return false
        return true
    }

    private fun redirectToCb(
        cb: String,
        localState: String,
        token: String? = null,
        error: String? = null,
    ): ResponseEntity<String> {
        val params =
            buildList {
                add("state=" + enc(localState))
                token?.let { add("token=" + enc(it)) }
                error?.let { add("error=" + enc(it)) }
            }.joinToString("&")
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("$cb?$params")).build()
    }

    private fun page(
        status: HttpStatus,
        message: String,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.TEXT_HTML)
            .body(
                "<!doctype html><meta charset=utf-8>" +
                    "<body style='font-family:system-ui;background:#0d0f12;color:#e8eaed;text-align:center;padding-top:80px'>" +
                    "<div style='max-width:380px;margin:0 auto;line-height:1.6'>$message</div>",
            )

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun htmlEscape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
