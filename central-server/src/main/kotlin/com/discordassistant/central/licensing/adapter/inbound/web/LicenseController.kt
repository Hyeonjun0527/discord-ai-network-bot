package com.discordassistant.central.licensing.adapter.inbound.web

import com.discordassistant.central.global.error.PreconditionFailedException
import com.discordassistant.central.licensing.application.EntitlementView
import com.discordassistant.central.licensing.application.EventClaimService
import com.discordassistant.central.licensing.application.EventStatus
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.application.port.CheckoutPort
import com.discordassistant.central.provider.application.TokenService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LicenseMeRequest(
    val durableToken: String = "",
)

data class CheckoutResponse(
    val url: String,
)

data class ClaimResponse(
    val outcome: String,
    val entitlement: EntitlementView,
)

/**
 * 라이선스 조회 API(ADR 0005). 데스크톱 앱이 **durable 토큰**(dv1., providerId=Discord userId)으로
 * 본인 인증 후 자기 entitlement 를 조회한다(권한 상승 없음 — 자기 자신만). 일회용 토큰은 소모되므로 거부.
 *
 * 인증을 바디 토큰으로 직접 하므로 SecurityConfig 에서 permitAll(과거 /provider/admin permitAll 누락 사고 회귀 방지).
 * 주 전달 경로는 /provider/agent/sync 동봉(차수 4)이고, 이 엔드포인트는 명시 조회용.
 */
@RestController
@RequestMapping("/provider/license")
class LicenseController(
    private val tokens: TokenService,
    private val licenses: LicenseService,
    private val checkout: CheckoutPort,
    private val events: EventClaimService,
) {
    @PostMapping("/me")
    fun me(
        @RequestBody req: LicenseMeRequest,
    ): EntitlementView = licenses.view(authedUserId(req.durableToken, "VIEW_LICENSE_ENTITLEMENT"))

    /** 런칭 이벤트 현황(공개 — 열림 여부·부여 수). */
    @GetMapping("/event/status")
    fun eventStatus(): EventStatus = events.status()

    /** 런칭 이벤트 평생 무료 신청(본인 인증). 자격 미달은 outcome 으로 사유 반환. */
    @PostMapping("/event/claim")
    fun eventClaim(
        @RequestBody req: LicenseMeRequest,
    ): ClaimResponse {
        val userId = authedUserId(req.durableToken, "CLAIM_LAUNCH_EVENT")
        val outcome = events.claim(userId)
        return ClaimResponse(outcome.name, licenses.view(userId))
    }

    /** 앱 내 구매: $10 라이선스 checkout URL 생성(custom_data 에 본인 userId 귀속). 결제 비활성이면 503. */
    @PostMapping("/checkout")
    fun checkout(
        @RequestBody req: LicenseMeRequest,
    ): CheckoutResponse {
        val userId = authedUserId(req.durableToken, "CREATE_LICENSE_CHECKOUT")
        val url =
            checkout.createCheckoutUrl(userId)
                ?: throw PreconditionFailedException(
                    message = "결제가 현재 비활성 상태입니다",
                    failedCondition = "license_checkout_enabled",
                    blockedAction = "CREATE_LICENSE_CHECKOUT",
                    actionGuide = "앱에서 결제가 다시 활성화된 뒤 시도해 주세요.",
                    httpStatus = 503,
                    errorCode = "SERVICE_UNAVAILABLE",
                    // 결제 미설정은 운영자가 통제하는 예상된 상태다 — 503 계약은 유지하되 Sentry 서버결함 캡처는 끈다.
                    captureAsServerError = false,
                )
        return CheckoutResponse(url)
    }

    /** durable 토큰(dv1.)으로 본인 신원 확인 후 userId 반환. 일회용 토큰·위조는 401. */
    private fun authedUserId(
        durableToken: String,
        blockedAction: String,
    ): Long {
        if (!durableToken.startsWith("dv1.")) {
            throw PreconditionFailedException(
                message = "durable 토큰이 필요합니다",
                failedCondition = "durable_token_present",
                blockedAction = blockedAction,
                actionGuide = "데스크톱 앱에서 다시 로그인한 뒤 요청해 주세요.",
                httpStatus = 401,
                errorCode = "UNAUTHORIZED",
            )
        }
        return tokens.verify(durableToken)?.providerId
            ?: throw PreconditionFailedException(
                message = "유효하지 않은 토큰입니다",
                failedCondition = "durable_token_valid",
                blockedAction = blockedAction,
                actionGuide = "데스크톱 앱에서 다시 로그인한 뒤 요청해 주세요.",
                httpStatus = 401,
                errorCode = "UNAUTHORIZED",
            )
    }
}
