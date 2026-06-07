package com.discordassistant.central.globalpromptset.adapter.inbound.web

import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetView
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 길드 전역 프롬프트셋(서버 전체 기본 AI 성격) 관리 API.
 *
 * `/api/ai-network` 보안 경계 안에 있어 [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter]
 * 의 관리자 게이트를 거친다(목록 GET 도 sensitive read 로 보호). 신원은 요청 body 가 아니라 인증 주체
 * ([DashboardActor])에서 유도한다(클라이언트 플래그 불신).
 *
 * 비공개: builtin(니아) 셋은 전문(content)을 응답에 담지 않고 preview 만 노출한다(서비스에서 보장).
 */
@RestController
@RequestMapping("/api/ai-network/guild-prompt-set")
class GlobalPromptSetController(
    private val service: GlobalPromptSetService,
) {
    @GetMapping("/{guildId}")
    fun list(
        @PathVariable guildId: Long,
    ): List<GlobalPromptSetView> = service.list(guildId)

    @PostMapping("/{guildId}")
    fun add(
        @PathVariable guildId: Long,
        @RequestBody request: AddGlobalPromptSetRequest,
        httpRequest: HttpServletRequest,
    ): List<GlobalPromptSetView> {
        service.add(guildId, request.name, request.content, DashboardActor.from(httpRequest).userId)
        return service.list(guildId)
    }

    @PostMapping("/{guildId}/{id}/default")
    fun setDefault(
        @PathVariable guildId: Long,
        @PathVariable id: String,
    ): List<GlobalPromptSetView> {
        service.setDefault(guildId, id)
        return service.list(guildId)
    }

    @DeleteMapping("/{guildId}/{id}")
    fun delete(
        @PathVariable guildId: Long,
        @PathVariable id: String,
    ): List<GlobalPromptSetView> {
        service.delete(guildId, id)
        return service.list(guildId)
    }
}

data class AddGlobalPromptSetRequest(
    val name: String = "",
    val content: String = "",
)
