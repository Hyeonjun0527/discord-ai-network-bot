package com.discordassistant.central.onboarding.adapter.inbound.web

import com.discordassistant.central.onboarding.adapter.outbound.GuildBrief
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 웹 ‘토큰 받기’ 화면/리디렉트 렌더러. 컨트롤러에 인라인이던 HTML 템플릿·이스케이프·리디렉트 쿼리 조립을
 * 전담한다(컨트롤러는 렌더러 결과를 body/리디렉트로만 사용). HTML 출력은 분해 이전과 1바이트도 다르지 않다.
 *
 * 보안: [htmlEscape]/[enc] 이스케이프 로직을 **그대로** 보존한다(XSS 회귀 금지). 길드 이름 등 사용자 제어
 * 문자열은 카드 렌더링 시 반드시 [htmlEscape] 를 거친다.
 */
@Component
class ConnectPageRenderer {
    /** 후보 길드가 여러 개일 때 보여줄 선택 카드 페이지. `sel` 은 단발성 선택 키. */
    fun chooserPage(
        sel: String,
        candidates: List<GuildBrief>,
    ): ResponseEntity<String> {
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

    /** 안내/에러 페이지(상태코드 + 메시지). */
    fun page(
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

    /** 로컬 콜백(cb)으로 결과(state/token/error/guild/guildName)를 실어 302 리디렉트. */
    fun redirectToCb(
        cb: String,
        localState: String,
        token: String? = null,
        error: String? = null,
        guildId: Long? = null,
        guildName: String? = null,
    ): ResponseEntity<String> {
        val params =
            buildList {
                add("state=" + enc(localState))
                token?.let { add("token=" + enc(it)) }
                error?.let { add("error=" + enc(it)) }
                guildId?.let { add("guild=" + it) }
                guildName?.let { if (it.isNotBlank()) add("guildName=" + enc(it)) }
            }.joinToString("&")
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("$cb?$params")).build()
    }

    /** Discord OAuth authorize 로 302 리디렉트(authorize URL 은 서비스가 산출). */
    fun redirectToAuthorize(authorizeUrl: String): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizeUrl)).build()

    fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun htmlEscape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
