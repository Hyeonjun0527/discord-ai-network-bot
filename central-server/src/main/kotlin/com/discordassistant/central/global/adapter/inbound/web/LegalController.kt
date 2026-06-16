package com.discordassistant.central.global.adapter.inbound.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * 법적 페이지 별칭 라우트. Discord 개발자포털의 개인정보/약관 URL 로 쓰기 위해 깔끔한 경로
 * `/privacy`·`/terms`·`/legal` 를 정적 `legal.html`(섹션 `#terms`·`#privacy` 포함) 로 **서버 포워드**한다.
 *
 * 리다이렉트(302)가 아니라 forward 이므로 같은 요청에서 200 으로 페이지를 반환한다(포털이 200 을 기대).
 * 공개 접근은 [com.discordassistant.central.global.security.SecurityConfig] 의 permitAll 로 보장한다.
 */
@Controller
class LegalController {
    @GetMapping("/legal", "/privacy", "/terms")
    fun legal(): String = "forward:/legal.html"
}
