package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.application.ClaimOutcome
import com.discordassistant.central.licensing.application.EventClaimService
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.application.port.ContributionPort
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** 런칭 이벤트 신청 자격·멱등(실 LicenseService + fake 기여 포트). */
@SpringBootTest
@Transactional
class EventClaimServiceTest
    @Autowired
    constructor(
        val licenses: LicenseService,
    ) {
        private fun svc(
            open: Boolean,
            contributed: Boolean,
        ) = EventClaimService(licenses, ContributionPort { contributed }, open)

        @Test
        fun `이벤트 닫힘 → CLOSED`() {
            assertEquals(ClaimOutcome.CLOSED, svc(open = false, contributed = true).claim(810_001L))
        }

        @Test
        fun `기여 없음 → NO_CONTRIBUTION`() {
            assertEquals(ClaimOutcome.NO_CONTRIBUTION, svc(open = true, contributed = false).claim(810_002L))
        }

        @Test
        fun `자격 충족 → GRANTED, EVENT_FREE 부여`() {
            assertEquals(ClaimOutcome.GRANTED, svc(open = true, contributed = true).claim(810_003L))
            assertEquals(LicenseStatus.EVENT_FREE.name, licenses.view(810_003L).status)
        }

        @Test
        fun `이미 신청 → ALREADY(멱등)`() {
            val s = svc(open = true, contributed = true)
            s.claim(810_004L)
            assertEquals(ClaimOutcome.ALREADY, s.claim(810_004L))
        }

        @Test
        fun `환불 이력 → REFUND_BLOCKED`() {
            licenses.grantPaddle(810_005L, "c", "t")
            licenses.revokeForRefund(810_005L, "refund")
            assertEquals(ClaimOutcome.REFUND_BLOCKED, svc(open = true, contributed = true).claim(810_005L))
        }

        @Test
        fun `현황 — 열림 여부 노출`() {
            assertEquals(true, svc(open = true, contributed = false).status().open)
            assertEquals(false, svc(open = false, contributed = false).status().open)
        }
    }
