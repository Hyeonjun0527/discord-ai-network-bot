package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.application.LicenseGate
import com.discordassistant.central.licensing.application.LicenseService
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** 유료 기능 게이트 — 체험/라이선스는 통과(null), 만료는 거부 사유 반환. */
@SpringBootTest
@Transactional
class LicenseGateTest
    @Autowired
    constructor(
        val gate: LicenseGate,
        val licenses: LicenseService,
    ) {
        @Test
        fun `체험 중이면 통과(null)`() {
            assertNull(gate.denyReason(820_001L)) // 신규 → TRIAL
        }

        @Test
        fun `라이선스 보유면 통과`() {
            licenses.grantPaddle(820_002L, "c", "t")
            assertNull(gate.denyReason(820_002L)) // LICENSED
        }

        @Test
        fun `환불 후 만료면 거부 사유`() {
            licenses.grantPaddle(820_003L, "c", "t")
            licenses.revokeForRefund(820_003L, "refund")
            assertNotNull(gate.denyReason(820_003L)) // EXPIRED → 거부
        }

        @Test
        fun `정지(REVOKED)면 거부 사유`() {
            licenses.setBanned(820_004L, banned = true, adminId = 1L)
            assertNotNull(gate.denyReason(820_004L))
        }
    }
