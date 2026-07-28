package com.discordassistant.central.onboarding.adapter.inbound.web

import com.discordassistant.central.onboarding.application.NiaWebDemoFailure
import com.discordassistant.central.onboarding.application.NiaWebDemoResult
import com.discordassistant.central.onboarding.application.NiaWebDemoService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class NiaWebDemoMessageRequest(
    val conversationId: String? = null,
    val message: String? = null,
)

data class NiaWebDemoStatusResponse(
    val enabled: Boolean,
    val perUserWindowLimit: Int,
    val windowSeconds: Long,
    val messageMaxChars: Int,
)

data class NiaWebDemoMessageResponse(
    val answer: String,
    val remaining: Int,
    val limit: Int,
)

data class NiaWebDemoErrorResponse(
    val code: String,
    val message: String,
)

@RestController
@RequestMapping("/api/nia-demo")
class NiaWebDemoController(
    private val service: NiaWebDemoService,
) {
    @GetMapping("/status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(
        @AuthenticationPrincipal user: OAuth2User?,
    ): ResponseEntity<Any> {
        authenticatedUserId(user) ?: return unauthorized()
        val info = service.info()
        return ResponseEntity.ok(
            NiaWebDemoStatusResponse(
                enabled = info.enabled,
                perUserWindowLimit = info.perUserWindowLimit,
                windowSeconds = info.windowSeconds,
                messageMaxChars = info.messageMaxChars,
            ),
        )
    }

    @PostMapping(
        "/messages",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun send(
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestHeader(name = REQUEST_MARKER_HEADER, required = false) requestMarker: String?,
        @RequestBody request: NiaWebDemoMessageRequest,
    ): ResponseEntity<Any> {
        val userId = authenticatedUserId(user) ?: return unauthorized()
        if (requestMarker != REQUEST_MARKER_VALUE) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "올바르지 않은 웹 체험 요청입니다.")
        }
        return when (val result = service.send(userId, request.conversationId, request.message)) {
            is NiaWebDemoResult.Sent ->
                ResponseEntity.ok(
                    NiaWebDemoMessageResponse(
                        answer = result.answer,
                        remaining = result.remaining,
                        limit = result.limit,
                    ),
                )
            is NiaWebDemoResult.Rejected -> rejection(result.reason)
        }
    }

    private fun authenticatedUserId(user: OAuth2User?): String? =
        user
            ?.getAttribute<Any>("id")
            ?.toString()
            ?.takeIf { it.matches(DISCORD_USER_ID) }

    private fun rejection(reason: NiaWebDemoFailure): ResponseEntity<Any> =
        when (reason) {
            NiaWebDemoFailure.DISABLED ->
                error(HttpStatus.SERVICE_UNAVAILABLE, "demo_disabled", "지금은 웹 체험을 쉬고 있습니다.")
            NiaWebDemoFailure.INVALID_CONVERSATION ->
                error(HttpStatus.BAD_REQUEST, "invalid_conversation", "대화 세션이 올바르지 않습니다. 새로고침해 주세요.")
            NiaWebDemoFailure.INVALID_MESSAGE ->
                error(HttpStatus.BAD_REQUEST, "invalid_message", "메시지 길이를 확인해 주세요.")
            NiaWebDemoFailure.BUSY ->
                error(HttpStatus.TOO_MANY_REQUESTS, "request_in_progress", "이전 답변이 끝난 뒤 다시 보내 주세요.")
            NiaWebDemoFailure.PER_MINUTE_LIMIT ->
                error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit", "너무 빠르게 보내고 있어요. 잠시 후 다시 시도해 주세요.")
            NiaWebDemoFailure.PER_USER_WINDOW_LIMIT ->
                error(HttpStatus.TOO_MANY_REQUESTS, "daily_limit", "웹 체험 한도를 모두 사용했습니다.")
            NiaWebDemoFailure.GLOBAL_WINDOW_LIMIT ->
                error(HttpStatus.TOO_MANY_REQUESTS, "capacity_limit", "오늘 준비한 전체 체험 한도를 모두 사용했습니다.")
            NiaWebDemoFailure.QUOTA_UNAVAILABLE ->
                error(HttpStatus.SERVICE_UNAVAILABLE, "quota_unavailable", "한도 확인이 안 되어 비용 보호를 위해 잠시 막았습니다.")
            NiaWebDemoFailure.CLOUD_UNAVAILABLE,
            NiaWebDemoFailure.GENERATION_UNAVAILABLE,
            ->
                error(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable", "니아가 잠깐 응답하지 못하고 있어요. 조금 뒤 다시 시도해 주세요.")
        }

    private fun unauthorized(): ResponseEntity<Any> = error(HttpStatus.UNAUTHORIZED, "login_required", "Discord 로그인이 필요합니다.")

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<Any> = ResponseEntity.status(status).body(NiaWebDemoErrorResponse(code, message))

    private companion object {
        val DISCORD_USER_ID = Regex("[0-9]{5,20}")
        const val REQUEST_MARKER_HEADER = "X-Nia-Web-Demo"
        const val REQUEST_MARKER_VALUE = "1"
    }
}
