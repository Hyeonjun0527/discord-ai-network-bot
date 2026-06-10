package com.discordassistant.central.licensing.adapter.outbound

import com.discordassistant.central.licensing.application.port.CheckoutPort
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * [CheckoutPort] 구현 — Paddle Billing transaction 을 만들어 hosted checkout URL 을 반환한다(ADR 0005).
 *
 * ENV(`paddle.api-key`·`paddle.price-id`) 미설정이면 null(결제 비활성). `paddle.env` 로 sandbox↔live 전환.
 * custom_data.discordUserId 는 **문자열**(64bit JS 정밀도 손실 방지). 호출 실패는 로깅 후 null(컨트롤러 503).
 */
@Component
class PaddleCheckoutAdapter(
    @param:Value("\${paddle.env:sandbox}") private val env: String,
    @param:Value("\${paddle.api-key:}") private val apiKey: String,
    @param:Value("\${paddle.price-id:}") private val priceId: String,
) : CheckoutPort {
    private val log = LoggerFactory.getLogger(PaddleCheckoutAdapter::class.java)
    private val baseUrl: String =
        if (env.equals("live", ignoreCase = true)) "https://api.paddle.com" else "https://sandbox-api.paddle.com"

    override fun createCheckoutUrl(userId: Long): String? {
        if (apiKey.isBlank() || priceId.isBlank()) return null // 결제 비활성
        val body =
            mapOf(
                "items" to listOf(mapOf("price_id" to priceId, "quantity" to 1)),
                "custom_data" to mapOf("discordUserId" to userId.toString()),
            )
        return try {
            val resp =
                RestClient
                    .create()
                    .post()
                    .uri("$baseUrl/transactions")
                    .header("Authorization", "Bearer $apiKey")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
            resp?.path("data")?.path("checkout")?.path("url")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
        } catch (ex: RestClientException) {
            log.error("Paddle checkout 생성 실패: user={}", userId, ex)
            null
        }
    }
}
