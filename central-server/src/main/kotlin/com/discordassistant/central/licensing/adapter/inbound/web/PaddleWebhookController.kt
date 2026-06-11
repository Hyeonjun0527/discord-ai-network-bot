package com.discordassistant.central.licensing.adapter.inbound.web

import com.discordassistant.central.licensing.application.BillingService
import com.discordassistant.central.licensing.application.PaddleSignatureVerifier
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Paddle webhook 수신(ADR 0005, 차수 5). raw body 로 받아 **서명 검증 먼저**(위조 거부) → 멱등 처리.
 * SecurityConfig 에서 permitAll(서명이 인증). 서명 없으면 401(과거 admin permitAll 누락 사고 회귀 방지).
 */
@RestController
@RequestMapping("/billing/webhook")
class PaddleWebhookController(
    private val verifier: PaddleSignatureVerifier,
    private val billing: BillingService,
) {
    @PostMapping("/paddle")
    fun paddle(
        @RequestBody rawBody: String,
        @RequestHeader(name = "Paddle-Signature", required = false) signature: String?,
    ): Map<String, String> {
        if (!verifier.verify(rawBody, signature)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "서명 검증 실패")
        }
        val outcome = billing.handle(rawBody)
        return mapOf("outcome" to outcome.name)
    }
}
