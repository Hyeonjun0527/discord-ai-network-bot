package com.discordassistant.central.onboarding.adapter.inbound.web

import com.discordassistant.central.onboarding.domain.model.InstallGuide
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * 설치 랜딩 페이지(`/`, `/install`) 서빙. 정적 `install.html` 템플릿에 **[InstallGuide]** 의 OS별 설치
 * 데이터를 주입한다 — 디스코드 슬래시 안내([com.discordassistant.central.platform.discord.ProviderOnboarding])와
 * **같은 SSOT** 를 읽으므로 웹·디스코드 두 화면이 드리프트하지 않는다(Linux 미노출, GUI 앱 설치).
 */
@Controller
class InstallPageController(
    objectMapper: ObjectMapper,
) {
    // 시작 시 한 번 템플릿을 읽어 SSOT JSON 을 주입해 캐싱(요청마다 재생성 불필요).
    private val rendered: String = render(objectMapper)

    @GetMapping(value = ["/", "/install"], produces = ["text/html;charset=UTF-8"])
    @ResponseBody
    fun installPage(): String = rendered

    private companion object {
        // install.html 의 자리표시 줄(정적으로 열리면 null 폴백, 서버 렌더 시 이 줄을 SSOT JSON 으로 치환).
        const val MARKER = "const INSTALL_GUIDE = null; /*__INSTALL_GUIDE__*/"

        fun render(mapper: ObjectMapper): String {
            val template =
                ClassPathResource("static/install.html").inputStream.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            val guideJson =
                mapper.writeValueAsString(
                    InstallGuide.OSES.map { g ->
                        mapOf(
                            "key" to g.key,
                            "label" to g.label,
                            "emoji" to g.emoji,
                            "shellName" to g.shellName,
                            "terminalHint" to g.terminalHint,
                            "codeLines" to g.codeLines,
                            "connect" to g.connect,
                        )
                    },
                )
            require(template.contains(MARKER)) { "install.html 에 INSTALL_GUIDE 자리표시(MARKER)가 없습니다" }
            return template.replace(MARKER, "const INSTALL_GUIDE = $guideJson;")
        }
    }
}
