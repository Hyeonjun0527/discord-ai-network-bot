package com.discordassistant.central.web

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
 * 웹 ‘토큰 받기’(`--gui` 의 버튼) — Discord 로그인으로 프로바이더 토큰을 발급해 로컬 에이전트로 전달.
 *
 * 흐름: 에이전트 UI → `/provider/connect?cb=<로컬콜백>&state=<세션키>` → Discord OAuth(identify)
 * → `/provider/connect/callback` → 사용자 식별 → DM 글로벌 풀 자동 가입(기존 /provider-join 과 동일
 * 의미) → 일회용 토큰 발급 → 로컬 `cb?token=…&state=…` 로 리디렉트(에이전트가 저장).
 *
 * 보안:
 * - client-id/secret 미설정이면 비활성(503 안내) — 운영자가 Discord OAuth 앱을 붙여야 켜진다.
 * - `cb` 는 **localhost(127.0.0.1/localhost/::1) + 경로 `/connect/callback`** 만 허용(토큰 탈취 방지).
 * - OAuth `state` 는 서버 발급·단발성([ConnectStateStore]) — CSRF/재생 방지. cb 는 발급 시점에 봉인.
 */
@Controller
class ProviderConnectController(
    @param:Value("\${central.connect.discord-client-id:}") private val clientId: String,
    @param:Value("\${central.connect.discord-client-secret:}") private val clientSecret: String,
    @param:Value("\${central.connect.public-base-url:}") private val configuredBase: String,
    @param:Value("\${central.relay.public-url:}") private val relayPublicUrl: String,
    private val oauth: DiscordOAuthClient,
    private val registration: ProviderRegistrationService,
    private val states: ConnectStateStore = ConnectStateStore(),
) {
    private companion object {
        const val DM_SCOPE = 0L // DM 글로벌 풀(관리자 없이 자발적 기여 → 자동 승인). CommandService.DM_SCOPE 와 동일.
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
        if (!isLocalCallback(cb)) {
            return page(HttpStatus.BAD_REQUEST, "잘못된 콜백 주소입니다.")
        }
        if (state.isBlank()) {
            return page(HttpStatus.BAD_REQUEST, "세션 정보가 없습니다.")
        }
        val oauthState = states.put(cb, state)
        val redirectUri = publicBase() + "/provider/connect/callback"
        val authorize =
            "https://discord.com/api/oauth2/authorize?response_type=code" +
                "&client_id=" + enc(clientId) +
                "&scope=identify" +
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
        if (code.isNullOrBlank()) {
            return redirectToCb(entry.cb, entry.localState, error = "cancelled")
        }
        val redirectUri = publicBase() + "/provider/connect/callback"
        val accessToken =
            oauth.exchangeCodeForToken(code, redirectUri)
                ?: return redirectToCb(entry.cb, entry.localState, error = "token")
        val userId =
            oauth.fetchUserId(accessToken)
                ?: return redirectToCb(entry.cb, entry.localState, error = "identify")
        // DM 글로벌 풀 가입(자동 승인) → 일회용 토큰. 이미 활성이면 재발급.
        val join = registration.requestJoin(userId, DM_SCOPE, autoApprove = true)
        val token =
            join.token ?: registration.issueOnboardingToken(userId, DM_SCOPE)
                ?: return redirectToCb(entry.cb, entry.localState, error = "approve")
        return redirectToCb(entry.cb, entry.localState, token = token)
    }

    /** cb 가 localhost 콜백(http://127.0.0.1:PORT/connect/callback)인지 엄격 검증. */
    private fun isLocalCallback(cb: String): Boolean {
        val uri = runCatching { URI.create(cb) }.getOrNull() ?: return false
        if (uri.scheme != "http") return false // 로컬 콜백은 http
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
                    "<div style='max-width:380px;margin:0 auto'>$message</div>",
            )

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
