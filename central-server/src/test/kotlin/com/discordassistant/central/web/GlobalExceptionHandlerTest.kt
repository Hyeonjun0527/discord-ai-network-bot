package com.discordassistant.central.web

import com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler
import com.discordassistant.central.global.error.ConflictException
import com.discordassistant.central.global.error.NotFoundException
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전역 예외 처리 검증(예외 원칙 9). 더미 컨트롤러가 도메인/검증 예외를 던지면
 * [GlobalExceptionHandler] 가 구조화된 상태코드 + 바디로 변환하는지 확인한다.
 * standaloneSetup 으로 전체 컨텍스트 없이 advice 만 격리 검증한다.
 */
class GlobalExceptionHandlerTest {
    @RestController
    class ThrowingController {
        @GetMapping("/test/not-found")
        fun notFound(): Nothing = throw NotFoundException("preset not found: id=42")

        @GetMapping("/test/conflict")
        fun conflict(): Nothing = throw ConflictException("version conflict: expected=3 actual=4")

        @GetMapping("/test/bad-arg")
        fun badArg() {
            require(false) { "guildId must be positive" }
        }
    }

    private val mvc =
        MockMvcBuilders
            .standaloneSetup(ThrowingController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `NotFoundException maps to 404 with structured body`() {
        mvc
            .perform(get("/test/not-found"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("preset not found: id=42"))
    }

    @Test
    fun `ConflictException maps to 409`() {
        mvc
            .perform(get("/test/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("CONFLICT"))
    }

    @Test
    fun `require() failure maps to 400 INVALID_REQUEST preserving message`() {
        mvc
            .perform(get("/test/bad-arg"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("guildId must be positive"))
    }
}
