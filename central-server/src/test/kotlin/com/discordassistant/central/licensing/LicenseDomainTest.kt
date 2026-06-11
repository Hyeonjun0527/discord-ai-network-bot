package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.domain.model.License
import com.discordassistant.central.licensing.domain.model.LicenseGrant
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 라이선스 판정(순수 도메인) — 우선순위·체험 경계·환불·정지. Clock 불필요(Instant 직접). */
class LicenseDomainTest {
    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val months = 3L

    private fun lic(
        grant: LicenseGrant = LicenseGrant.NONE,
        trialStart: Instant = t0,
        revokedAt: Instant? = null,
        refundFlag: Boolean = false,
        banned: Boolean = false,
    ) = License(1L, grant, trialStart, grantedAt = null, revokedAt = revokedAt, refundFlag = refundFlag, banned = banned)

    @Test
    fun `체험 시작 직후는 TRIAL, 종료는 시작+3개월`() {
        val e = lic().resolve(t0.plus(1, ChronoUnit.DAYS), months)
        assertEquals(LicenseStatus.TRIAL, e.status)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), e.trialEndsAt)
        assertTrue(e.hasPaidAccess())
    }

    @Test
    fun `체험 종료 후는 EXPIRED(무료 기능만)`() {
        val e = lic().resolve(Instant.parse("2026-09-02T00:00:00Z"), months)
        assertEquals(LicenseStatus.EXPIRED, e.status)
        assertFalse(e.hasPaidAccess())
    }

    @Test
    fun `만료 경계 — 정확히 종료시각은 더 이상 TRIAL 아님`() {
        val ends = Instant.parse("2026-09-01T00:00:00Z")
        assertEquals(LicenseStatus.TRIAL, lic().resolve(ends.minusSeconds(1), months).status)
        assertEquals(LicenseStatus.EXPIRED, lic().resolve(ends, months).status)
    }

    @Test
    fun `PADDLE 구매는 LICENSED(체험 종료 후에도)`() {
        val e = lic(grant = LicenseGrant.PADDLE).resolve(Instant.parse("2027-01-01T00:00:00Z"), months)
        assertEquals(LicenseStatus.LICENSED, e.status)
        assertNull(e.trialEndsAt)
    }

    @Test
    fun `EVENT 는 EVENT_FREE(LICENSED 와 동일 권리)`() {
        val e = lic(grant = LicenseGrant.EVENT).resolve(Instant.parse("2030-01-01T00:00:00Z"), months)
        assertEquals(LicenseStatus.EVENT_FREE, e.status)
        assertTrue(e.hasPaidAccess())
    }

    @Test
    fun `ADMIN 부여도 LICENSED`() {
        assertEquals(LicenseStatus.LICENSED, lic(grant = LicenseGrant.ADMIN).resolve(t0, months).status)
    }

    @Test
    fun `banned 는 REVOKED 최우선(유료 부여보다 먼저)`() {
        val e = lic(grant = LicenseGrant.PADDLE, banned = true).resolve(t0, months)
        assertEquals(LicenseStatus.REVOKED, e.status)
        assertFalse(e.hasPaidAccess())
    }

    @Test
    fun `환불(refundFlag)이면 체험 기간 남아도 EXPIRED`() {
        // 체험 시작 직후라도 환불 이력이면 재부여 금지 → EXPIRED.
        val e = lic(refundFlag = true, revokedAt = t0).resolve(t0.plus(1, ChronoUnit.DAYS), months)
        assertEquals(LicenseStatus.EXPIRED, e.status)
    }

    @Test
    fun `환불 후 재구매(PADDLE)는 다시 LICENSED`() {
        // 재구매: refundFlag 는 남아도 새 PADDLE 부여 + revokedAt=null 이면 LICENSED.
        val e = lic(grant = LicenseGrant.PADDLE, refundFlag = true, revokedAt = null).resolve(t0, months)
        assertEquals(LicenseStatus.LICENSED, e.status)
    }
}
