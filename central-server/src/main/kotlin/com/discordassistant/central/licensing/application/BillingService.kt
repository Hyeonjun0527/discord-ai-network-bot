package com.discordassistant.central.licensing.application

import com.discordassistant.central.licensing.adapter.outbound.persistence.BillingEventEntity
import com.discordassistant.central.licensing.adapter.outbound.persistence.BillingEventRepository
import com.discordassistant.central.licensing.adapter.outbound.persistence.LicenseRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** webhook 처리 결과(컨트롤러 응답·로깅용). */
enum class WebhookOutcome {
    OK,
    DUPLICATE,
    IGNORED,
    UNMATCHED,
}

/**
 * Paddle webhook 처리(ADR 0005, 차수 5). 서명 검증은 컨트롤러가 먼저 수행하고, 여기서는
 * **멱등(event_id UNIQUE)** + 이벤트 디스패치만 한다.
 *
 * - transaction.completed/paid → [LicenseService.grantPaddle] (custom_data.discordUserId 매칭)
 * - adjustment.created(action=refund) → [LicenseService.revokeForRefund] (custom_data 없으면 customer_id 역조회)
 * - custom_data·customer 둘 다 매칭 실패 → UNMATCHED 기록(운영 알림, 수동 매칭 대상)
 */
@Service
class BillingService(
    private val licenses: LicenseService,
    private val events: BillingEventRepository,
    private val licenseRepo: LicenseRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(BillingService::class.java)

    @Transactional
    fun handle(rawBody: String): WebhookOutcome {
        val node = mapper.readTree(rawBody)
        val eventId = node.path("event_id").asText("")
        require(eventId.isNotBlank()) { "event_id 누락" }
        if (events.existsByEventId(eventId)) return WebhookOutcome.DUPLICATE // 멱등(중복 재전송)

        val eventType = node.path("event_type").asText("")
        val data = node.path("data")
        var error: String? = null
        var outcome = WebhookOutcome.OK
        try {
            outcome =
                when {
                    eventType in COMPLETED_TYPES -> {
                        val userId = extractUserId(data)
                        if (userId == null) {
                            log.warn("결제 완료 webhook custom_data 매칭 실패: event={}", eventId)
                            WebhookOutcome.UNMATCHED
                        } else {
                            licenses.grantPaddle(
                                userId,
                                data.path("customer_id").asTextOrNull(),
                                data.path("id").asTextOrNull(),
                            )
                            WebhookOutcome.OK
                        }
                    }
                    eventType == "adjustment.created" && data.path("action").asText("") == "refund" -> {
                        val userId = extractUserId(data) ?: findByCustomer(data)
                        if (userId == null) {
                            log.warn("환불 webhook 매칭 실패: event={}", eventId)
                            WebhookOutcome.UNMATCHED
                        } else {
                            licenses.revokeForRefund(userId, "paddle:refund")
                            WebhookOutcome.OK
                        }
                    }
                    else -> WebhookOutcome.IGNORED
                }
        } catch (ex: RuntimeException) {
            // 처리 실패도 이벤트는 기록(재전송 시 멱등 위해). 예외 숨기지 않고 로깅(예외 원칙).
            error = ex.message ?: ex.javaClass.simpleName
            log.error("webhook 처리 실패: event={} type={}", eventId, eventType, ex)
        }
        events.save(
            BillingEventEntity(
                eventId = eventId,
                eventType = eventType,
                raw = rawBody,
                processedAt = clock.instant(),
                error = error,
                createdAt = clock.instant(),
            ),
        )
        return outcome
    }

    private fun extractUserId(data: JsonNode): Long? =
        data
            .path("custom_data")
            .path("discordUserId")
            .asTextOrNull()
            ?.toLongOrNull()

    private fun findByCustomer(data: JsonNode): Long? {
        val customerId = data.path("customer_id").asTextOrNull() ?: return null
        return licenseRepo.findByPaddleCustomerId(customerId).singleOrNull()?.userId
    }

    private fun JsonNode.asTextOrNull(): String? = if (isNull || isMissingNode) null else asText().ifBlank { null }

    private companion object {
        val COMPLETED_TYPES = setOf("transaction.completed", "transaction.paid")
    }
}
