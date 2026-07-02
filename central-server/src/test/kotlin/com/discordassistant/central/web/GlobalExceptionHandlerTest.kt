package com.discordassistant.central.web

import com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler
import com.discordassistant.central.global.error.ApiErrorCodes
import com.discordassistant.central.global.error.ConflictException
import com.discordassistant.central.global.error.DomainException
import com.discordassistant.central.global.error.InvalidStateTransitionException
import com.discordassistant.central.global.error.NotFoundException
import com.discordassistant.central.global.error.PreconditionFailedException
import com.discordassistant.central.global.security.RequestIdFilter
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 전역 예외 처리 검증(예외 원칙 9). 더미 컨트롤러가 도메인/검증 예외를 던지면
 * [GlobalExceptionHandler] 가 계층형 상태코드 + 바디(`{success,status,error{code,message,...}}`)로
 * 변환하는지 확인한다. standaloneSetup 으로 전체 컨텍스트 없이 advice 만 격리 검증한다.
 *
 * 핵심: **모든 에러가 모든 필드를 갖지 않는다.** 단순 에러엔 선택 필드가 빠지고(아래 `doesNotExist`),
 * 상태 전이/선행 조건 에러에서만 해당 필드가 채워진다.
 */
class GlobalExceptionHandlerTest {
    class ServerFailureException :
        DomainException(
            httpStatus = 503,
            errorCode = ApiErrorCodes.SERVICE_UNAVAILABLE,
            message = "central upstream failed",
        )

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

        @GetMapping("/test/state-transition")
        fun stateTransition(): Nothing =
            throw InvalidStateTransitionException(
                message = "체험이 만료되어 프리미엄을 사용할 수 없습니다.",
                currentState = "EXPIRED",
                requiredState = "LICENSED",
                blockedAction = "USE_PREMIUM_FEATURE",
                actionGuide = "앱에서 \$10 라이선스를 구매하거나 이벤트 무료를 신청해 주세요.",
            )

        @GetMapping("/test/precondition")
        fun precondition(): Nothing =
            throw PreconditionFailedException(
                message = "지원하지 않는 이미지 형식입니다.",
                failedCondition = "profile_image_mime_allowed",
                details = mapOf("allowedValues" to listOf("image/jpeg", "image/png"), "actualValue" to "image/heic"),
                blockedAction = "UPDATE_PROFILE_IMAGE",
                actionGuide = "JPEG 또는 PNG 이미지를 올려 주세요.",
            )

        @GetMapping("/test/server-failure")
        fun serverFailure(): Nothing = throw ServerFailureException()

        @GetMapping("/test/framework-status")
        fun frameworkStatus(): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "결제 비활성")

        @GetMapping("/test/missing-resource")
        fun missingResource(): Nothing = throw NoResourceFoundException(HttpMethod.GET, "/assets/missing.exe")

        @GetMapping("/test/illegal-state")
        fun illegalState(): Nothing = throw IllegalStateException("license upsert race unresolved: token=secret")

        @GetMapping("/test/unexpected")
        fun unexpected(): Nothing = throw RuntimeException("database password leaked detail")

        @PostMapping("/test/body")
        fun body(
            @RequestBody dto: Map<String, Any>,
        ): Map<String, Any> = dto
    }

    private val mvc =
        MockMvcBuilders
            .standaloneSetup(ThrowingController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `NotFoundException maps to 404 with nested code+message and no extra fields`() {
        mvc
            .perform(get("/test/not-found"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("preset not found: id=42"))
            // 계층화 핵심: 단순 에러엔 상태·조건 필드가 직렬화되지 않는다(과설계 방지).
            .andExpect(jsonPath("$.error.currentState").doesNotExist())
            .andExpect(jsonPath("$.error.requiredState").doesNotExist())
            .andExpect(jsonPath("$.error.failedCondition").doesNotExist())
            .andExpect(jsonPath("$.error.blockedAction").doesNotExist())
            .andExpect(jsonPath("$.error.actionGuide").doesNotExist())
            .andExpect(jsonPath("$.error.details").doesNotExist())
    }

    @Test
    fun `ConflictException maps to 409`() {
        mvc
            .perform(get("/test/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))
    }

    @Test
    fun `require() failure maps to 400 INVALID_REQUEST preserving message`() {
        mvc
            .perform(get("/test/bad-arg"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("guildId must be positive"))
    }

    @Test
    fun `state transition error carries currentState requiredState blockedAction actionGuide`() {
        mvc
            .perform(get("/test/state-transition"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"))
            .andExpect(jsonPath("$.error.currentState").value("EXPIRED"))
            .andExpect(jsonPath("$.error.requiredState").value("LICENSED"))
            .andExpect(jsonPath("$.error.blockedAction").value("USE_PREMIUM_FEATURE"))
            .andExpect(jsonPath("$.error.actionGuide").exists())
            // 상태 전이 에러엔 조건 필드가 없다.
            .andExpect(jsonPath("$.error.failedCondition").doesNotExist())
    }

    @Test
    fun `precondition error carries failedCondition details and domain-specific code`() {
        MDC.put(RequestIdFilter.MDC_KEY, "metadata-contract-1")
        try {
            mvc
                .perform(get("/test/precondition"))
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.requestId").value("metadata-contract-1"))
                .andExpect(jsonPath("$.error.code").value("PROFILE_IMAGE_MIME_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.failedCondition").value("profile_image_mime_allowed"))
                .andExpect(jsonPath("$.error.details.actualValue").value("image/heic"))
                .andExpect(jsonPath("$.error.blockedAction").value("UPDATE_PROFILE_IMAGE"))
                .andExpect(jsonPath("$.error.actionGuide").exists())
                // 선행 조건 에러는 상태 전이가 아니다.
                .andExpect(jsonPath("$.error.currentState").doesNotExist())
                .andExpect(jsonPath("$.error.requiredState").doesNotExist())
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY)
        }
    }

    @Test
    fun `5xx domain error maps response and enters server capture path`() {
        mvc
            .perform(get("/test/server-failure"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.message").value("central upstream failed"))
    }

    @Test
    fun `ResponseStatusException is reshaped to unified body preserving status and status-name code`() {
        // 컨트롤러가 도메인 예외 대신 Spring RSE 를 던져도(예: License 503) 같은 모양으로 통일된다.
        mvc
            .perform(get("/test/framework-status"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.message").value("결제 비활성"))
            .andExpect(jsonPath("$.error.actionGuide").exists())
    }

    @Test
    fun `malformed JSON body maps to 400 unified body without leaking parser internals`() {
        mvc
            .perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("요청 본문을 해석할 수 없습니다"))
            .andExpect(jsonPath("$.error.failedCondition").value("request_body_json_parseable"))
            .andExpect(jsonPath("$.error.blockedAction").value("PARSE_REQUEST_BODY"))
            .andExpect(jsonPath("$.error.actionGuide").exists())
    }

    @Test
    fun `missing static resource stays 404 and does not become internal server error`() {
        mvc
            .perform(get("/test/missing-resource"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("요청한 리소스를 찾을 수 없습니다."))
            .andExpect(jsonPath("$.error.failedCondition").value("resource_exists"))
            .andExpect(jsonPath("$.error.blockedAction").value("GET /test/missing-resource"))
            .andExpect(jsonPath("$.error.actionGuide").exists())
    }

    @Test
    fun `illegal state maps to explicit server-state envelope without leaking state detail`() {
        mvc
            .perform(get("/test/illegal-state"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error.code").value("INVALID_SERVER_STATE"))
            .andExpect(jsonPath("$.error.message").value("서버 상태가 요청을 처리할 준비가 아닙니다."))
            .andExpect(jsonPath("$.error.failedCondition").value("server_state_allows_request"))
            .andExpect(jsonPath("$.error.blockedAction").value("GET /test/illegal-state"))
            .andExpect(jsonPath("$.error.actionGuide").exists())
    }

    @Test
    fun `unexpected exception maps to internal envelope without leaking implementation detail`() {
        mvc
            .perform(get("/test/unexpected"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버가 요청을 처리하지 못했습니다."))
            .andExpect(jsonPath("$.error.failedCondition").value("server_request_processing"))
            .andExpect(jsonPath("$.error.blockedAction").value("GET /test/unexpected"))
            .andExpect(jsonPath("$.error.actionGuide").exists())
    }
}
