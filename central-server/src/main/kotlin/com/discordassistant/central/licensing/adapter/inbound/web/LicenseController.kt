package com.discordassistant.central.licensing.adapter.inbound.web

import com.discordassistant.central.licensing.application.EntitlementView
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.provider.application.TokenService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class LicenseMeRequest(
    val durableToken: String = "",
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
) {
    @PostMapping("/me")
    fun me(
        @RequestBody req: LicenseMeRequest,
    ): EntitlementView {
        if (!req.durableToken.startsWith("dv1.")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "durable 토큰이 필요합니다")
        }
        val binding =
            tokens.verify(req.durableToken)
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰")
        return licenses.view(binding.providerId)
    }
}
