package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.adapter.outbound.persistence.BillingEventRepository
import com.discordassistant.central.licensing.adapter.outbound.persistence.LicenseRepository
import com.discordassistant.central.licensing.application.BillingService
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.application.WebhookOutcome
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Paddle webhook 이벤트 처리·멱등·매칭(실 H2). 서명은 PaddleSignatureVerifierTest 가 커버.
 *
 * @Transactional 을 쓰지 않는다: grant/revoke 는 BillingEffectRunner 의 REQUIRES_NEW 독립 트랜잭션이라 어차피
 * 테스트 롤백을 탈출해 실제 커밋된다(롤백 격리가 가짜). 대신 [cleanupCommittedRows] 로 각 테스트 후 커밋된 행을
 * 정리해 다른 테스트의 전역 집계 오염을 막는다.
 */
@SpringBootTest
class BillingServiceTest
    @Autowired
    constructor(
        val billing: BillingService,
        val licenses: LicenseService,
        val licenseRepo: LicenseRepository,
        val billingEvents: BillingEventRepository,
    ) {
        // grant/revoke 는 BillingEffectRunner 의 REQUIRES_NEW 독립 트랜잭션이라 이 클래스의 @Transactional 롤백을
        // 탈출해 실제로 커밋된다(프로덕션 의도대로). 공유 H2 에 남으면 다른 테스트의 전역 집계(countByGrant 등)를
        // 오염시키므로, 각 테스트 후 커밋된 라이선스·이벤트 행을 정리한다.
        @AfterEach
        fun cleanupCommittedRows() {
            billingEvents.deleteAll()
            licenseRepo.deleteAll()
        }

        private fun completed(
            eventId: String,
            userId: String,
        ) = """{"event_id":"$eventId","event_type":"transaction.completed",
            "data":{"id":"txn_$eventId","customer_id":"ctm_$eventId","custom_data":{"discordUserId":"$userId"}}}"""

        @Test
        fun `transaction completed → LICENSED 발급`() {
            val r = billing.handle(completed("e1", "800001"))
            assertEquals(WebhookOutcome.OK, r)
            assertEquals(LicenseStatus.LICENSED.name, licenses.view(800_001L).status)
        }

        @Test
        fun `같은 event_id 재전송 → DUPLICATE(멱등)`() {
            billing.handle(completed("e2", "800002"))
            assertEquals(WebhookOutcome.DUPLICATE, billing.handle(completed("e2", "800002")))
        }

        @Test
        fun `환불(adjustment refund) → 라이선스 회수`() {
            billing.handle(completed("e3", "800003"))
            val refund =
                """{"event_id":"e3r","event_type":"adjustment.created",
                "data":{"action":"refund","custom_data":{"discordUserId":"800003"}}}"""
            assertEquals(WebhookOutcome.OK, billing.handle(refund))
            assertEquals(LicenseStatus.EXPIRED.name, licenses.view(800_003L).status)
        }

        @Test
        fun `custom_data 없으면 UNMATCHED(수동 매칭 대상)`() {
            val r =
                billing.handle(
                    """{"event_id":"e4","event_type":"transaction.completed","data":{"id":"txn_4","customer_id":"ctm_x"}}""",
                )
            assertEquals(WebhookOutcome.UNMATCHED, r)
        }

        @Test
        fun `관심 없는 이벤트는 IGNORED`() {
            val r =
                billing.handle("""{"event_id":"e5","event_type":"subscription.created","data":{}}""")
            assertEquals(WebhookOutcome.IGNORED, r)
        }
    }
