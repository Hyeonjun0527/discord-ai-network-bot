package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.domain.model.LicenseGrant
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** 라이선스 전이·lazy 체험·멱등 통합(실 H2 repo). 만료 시간 로직은 LicenseDomainTest 가 커버. */
@SpringBootTest
@Transactional
class LicenseServiceTest
    @Autowired
    constructor(
        val service: LicenseService,
    ) {
        @Test
        fun `첫 조회 시 체험 lazy 시작 → TRIAL`() {
            val v = service.view(700_001L)
            assertEquals(LicenseStatus.TRIAL.name, v.status)
            assertTrue(v.hasPaidAccess)
            assertEquals("700001", v.userId) // 64bit → 문자열
        }

        @Test
        fun `grantPaddle → LICENSED, 멱등 재처리`() {
            service.grantPaddle(700_002L, "cus_1", "txn_1")
            assertEquals(LicenseStatus.LICENSED.name, service.view(700_002L).status)
            service.grantPaddle(700_002L, "cus_1", "txn_1") // 멱등(동일 tx)
            assertEquals(LicenseStatus.LICENSED.name, service.view(700_002L).status)
            assertEquals(1L, service.countByGrant(LicenseGrant.PADDLE))
        }

        @Test
        fun `grantEvent → EVENT_FREE`() {
            service.grantEvent(700_003L)
            assertEquals(LicenseStatus.EVENT_FREE.name, service.view(700_003L).status)
        }

        @Test
        fun `revokeForRefund → EXPIRED + 환불 이력 기록`() {
            service.grantPaddle(700_004L, "cus", "txn")
            service.revokeForRefund(700_004L, "customer_request")
            assertEquals(LicenseStatus.EXPIRED.name, service.view(700_004L).status)
            assertFalse(service.view(700_004L).hasPaidAccess)
            assertTrue(service.hasRefundHistory(700_004L))
        }

        @Test
        fun `환불 후 재구매 → 다시 LICENSED`() {
            service.grantPaddle(700_005L, "cus", "txn1")
            service.revokeForRefund(700_005L, "refund")
            service.grantPaddle(700_005L, "cus", "txn2") // 재구매(허용)
            assertEquals(LicenseStatus.LICENSED.name, service.view(700_005L).status)
        }

        @Test
        fun `setBanned → REVOKED 최우선`() {
            service.grantPaddle(700_006L, "cus", "txn")
            service.setBanned(700_006L, banned = true, adminId = 9L)
            assertEquals(LicenseStatus.REVOKED.name, service.view(700_006L).status)
            service.setBanned(700_006L, banned = false, adminId = 9L)
            assertEquals(LicenseStatus.LICENSED.name, service.view(700_006L).status) // 정지 해제 → 복원
        }

        @Test
        fun `hasPaidAccess 게이트 판정 — TRIAL·LICENSED true, 환불 후 false`() {
            assertTrue(service.hasPaidAccess(700_007L)) // 신규 → TRIAL
            service.grantAdmin(700_007L, adminId = 1L)
            assertTrue(service.hasPaidAccess(700_007L)) // ADMIN → LICENSED
        }
    }
