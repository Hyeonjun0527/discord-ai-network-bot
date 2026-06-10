package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.application.BillingService
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.application.WebhookOutcome
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** Paddle webhook 이벤트 처리·멱등·매칭(실 H2). 서명은 PaddleSignatureVerifierTest 가 커버. */
@SpringBootTest
@Transactional
class BillingServiceTest
    @Autowired
    constructor(
        val billing: BillingService,
        val licenses: LicenseService,
    ) {
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
