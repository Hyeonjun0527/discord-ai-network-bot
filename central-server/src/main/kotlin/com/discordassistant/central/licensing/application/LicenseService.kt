package com.discordassistant.central.licensing.application

import com.discordassistant.central.licensing.adapter.outbound.persistence.LicenseAuditEntity
import com.discordassistant.central.licensing.adapter.outbound.persistence.LicenseAuditRepository
import com.discordassistant.central.licensing.adapter.outbound.persistence.LicenseEntity
import com.discordassistant.central.licensing.adapter.outbound.persistence.LicenseRepository
import com.discordassistant.central.licensing.application.port.UserFirstSeenPort
import com.discordassistant.central.licensing.domain.model.Entitlement
import com.discordassistant.central.licensing.domain.model.License
import com.discordassistant.central.licensing.domain.model.LicenseGrant
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 라이선스 판정·전이 서비스(ADR 0005). 신원=Discord userId.
 *
 * - [resolve]/[view]: 우선순위 판정(REVOKED>EVENT_FREE>LICENSED>TRIAL>EXPIRED>FREE). 시간 의존이라 매 호출 평가.
 * - 첫 조회 시 체험을 lazy 시작([loadOrStartTrial]) — 시작점은 유저 가입 시각(없으면 now).
 * - 전이([grantPaddle]/[grantEvent]/[grantAdmin]/[revokeForRefund]/[setBanned])는 멱등·감사 기록.
 *
 * [Clock] 주입으로 만료/경계 테스트 가능(직접 Instant.now() 금지).
 */
@Service
class LicenseService(
    private val repo: LicenseRepository,
    private val auditRepo: LicenseAuditRepository,
    private val firstSeen: UserFirstSeenPort,
    @param:Value("\${central.license.trial-months:3}") private val trialMonths: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun resolve(userId: Long): Entitlement = loadOrStartTrial(userId).toDomain().resolve(clock.instant(), trialMonths)

    fun view(userId: Long): EntitlementView = EntitlementView.of(userId, resolve(userId))

    /** 유료(서버 관리 프리미엄) 접근 권한 여부 — 게이트의 단일 판정 지점. */
    fun hasPaidAccess(userId: Long): Boolean = resolve(userId).hasPaidAccess()

    /** 레코드가 없으면 체험을 시작(가입 시각 기준)해 저장한다. UNIQUE 레이스는 재조회로 멱등 처리. */
    @Transactional
    fun loadOrStartTrial(userId: Long): LicenseEntity {
        repo.findByUserId(userId)?.let { return it }
        val now = clock.instant()
        val start = firstSeen.firstSeenAt(userId) ?: now // 가입 시각, 없거나 오염값이면 지금부터
        val fresh =
            LicenseEntity(
                userId = userId,
                grantType = LicenseGrant.NONE.name,
                trialStartedAt = start,
                createdAt = now,
                updatedAt = now,
            )
        return try {
            val saved = repo.save(fresh)
            audit(userId, "trial_start", "system", "start=$start")
            saved
        } catch (_: DataIntegrityViolationException) {
            // 동시 첫 조회 레이스 — 다른 트랜잭션이 먼저 생성. 그 레코드를 사용.
            repo.findByUserId(userId) ?: throw IllegalStateException("license upsert race unresolved: user=$userId")
        }
    }

    /** $10 구매 완료 → LICENSED. 환불 플래그·폐기 해제(재구매 허용). 멱등(같은 transaction id 면 변화 없음). */
    @Transactional
    fun grantPaddle(
        userId: Long,
        customerId: String?,
        transactionId: String?,
    ) {
        val e = loadOrStartTrial(userId)
        if (e.grantType == LicenseGrant.PADDLE.name && e.revokedAt == null && e.paddleTransactionId == transactionId) {
            return // 멱등: 동일 구매 재처리
        }
        e.grantType = LicenseGrant.PADDLE.name
        e.grantedAt = clock.instant()
        e.revokedAt = null
        e.paddleCustomerId = customerId
        e.paddleTransactionId = transactionId
        e.updatedAt = clock.instant()
        repo.save(e)
        audit(userId, "grant_paddle", "paddle", "tx=$transactionId")
    }

    /** 런칭 이벤트 평생 무료 부여(자격 검증은 호출자 책임). EVENT_FREE = $10 와 동일 권리. */
    @Transactional
    fun grantEvent(userId: Long) {
        val e = loadOrStartTrial(userId)
        if (e.grantType == LicenseGrant.EVENT.name && e.revokedAt == null) return // 멱등
        e.grantType = LicenseGrant.EVENT.name
        e.grantedAt = clock.instant()
        e.revokedAt = null
        e.updatedAt = clock.instant()
        repo.save(e)
        audit(userId, "grant_event", "system", "lifetime")
    }

    /** 운영 수동 부여(지원·보상). */
    @Transactional
    fun grantAdmin(
        userId: Long,
        adminId: Long,
    ) {
        val e = loadOrStartTrial(userId)
        e.grantType = LicenseGrant.ADMIN.name
        e.grantedAt = clock.instant()
        e.revokedAt = null
        e.updatedAt = clock.instant()
        repo.save(e)
        audit(userId, "grant_admin", "admin:$adminId", "manual")
    }

    /** 환불 → 즉시 회수. grant 해제 + refund_flag(체험/이벤트 재부여 금지). 재구매는 grantPaddle 로 가능. */
    @Transactional
    fun revokeForRefund(
        userId: Long,
        reason: String,
    ) {
        val e = loadOrStartTrial(userId)
        e.grantType = LicenseGrant.NONE.name
        e.revokedAt = clock.instant()
        e.refundFlag = true
        e.updatedAt = clock.instant()
        repo.save(e)
        audit(userId, "revoke_refund", "paddle", reason)
    }

    /** 약관 위반 정지/해제(REVOKED 최우선 판정). */
    @Transactional
    fun setBanned(
        userId: Long,
        banned: Boolean,
        adminId: Long,
    ) {
        val e = loadOrStartTrial(userId)
        e.banned = banned
        e.updatedAt = clock.instant()
        repo.save(e)
        audit(userId, if (banned) "ban" else "unban", "admin:$adminId", "")
    }

    /** 환불 이력 계정 여부(체험/이벤트 재부여 자격 판단용). */
    fun hasRefundHistory(userId: Long): Boolean = repo.findByUserId(userId)?.refundFlag == true

    /** 상태별 라이선스 수(퍼널/운영 집계). */
    fun countByGrant(grant: LicenseGrant): Long = repo.countByGrantType(grant.name)

    /**
     * 전환 퍼널 집계(P9, central 집계만 — 앱 텔레메트리 무확장). status 는 시간 의존이라 전체 스캔 후 resolve.
     * 어드민 전용·저빈도 호출.
     */
    fun funnel(): LicenseFunnel {
        val now = clock.instant()
        var trial = 0L
        var expired = 0L
        var licensed = 0L
        var eventFree = 0L
        var revoked = 0L
        var refunded = 0L
        for (e in repo.findAll()) {
            if (e.refundFlag) refunded++
            when (e.toDomain().resolve(now, trialMonths).status) {
                com.discordassistant.central.licensing.domain.model.LicenseStatus.TRIAL -> trial++
                com.discordassistant.central.licensing.domain.model.LicenseStatus.EXPIRED -> expired++
                com.discordassistant.central.licensing.domain.model.LicenseStatus.LICENSED -> licensed++
                com.discordassistant.central.licensing.domain.model.LicenseStatus.EVENT_FREE -> eventFree++
                com.discordassistant.central.licensing.domain.model.LicenseStatus.REVOKED -> revoked++
                com.discordassistant.central.licensing.domain.model.LicenseStatus.FREE -> {}
            }
        }
        return LicenseFunnel(trial, expired, licensed, eventFree, revoked, refunded)
    }

    private fun audit(
        userId: Long,
        action: String,
        actor: String,
        detail: String,
    ) {
        auditRepo.save(
            LicenseAuditEntity(
                userId = userId,
                action = action,
                actor = actor,
                detail = detail.ifBlank { null },
                createdAt = clock.instant(),
            ),
        )
    }

    private fun LicenseEntity.toDomain(): License =
        License(
            userId = userId,
            grant = runCatching { LicenseGrant.valueOf(grantType) }.getOrDefault(LicenseGrant.NONE),
            trialStartedAt = trialStartedAt,
            grantedAt = grantedAt,
            revokedAt = revokedAt,
            refundFlag = refundFlag,
            banned = banned,
        )
}
