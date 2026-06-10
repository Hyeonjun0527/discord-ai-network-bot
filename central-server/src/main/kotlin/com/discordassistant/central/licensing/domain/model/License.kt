package com.discordassistant.central.licensing.domain.model

import java.time.Instant
import java.time.ZoneOffset

/**
 * 라이선스 부여 종류. NONE=체험만(부여 없음). PADDLE=$10 구매. EVENT=런칭 이벤트 평생무료. ADMIN=운영 수동 부여.
 */
enum class LicenseGrant {
    NONE,
    PADDLE,
    EVENT,
    ADMIN,
}

/**
 * entitlement 판정 결과(유저에게 내려가는 라이선스 상태). 우선순위:
 * REVOKED > EVENT_FREE > LICENSED > TRIAL > EXPIRED > FREE.
 */
enum class LicenseStatus {
    /** 체험 미시작(이론적 초기 상태 — 보통 lazy 생성으로 TRIAL 부터 시작). */
    FREE,

    /** 체험 중(유료 기능 사용 가능). */
    TRIAL,

    /** 체험 만료 또는 환불 후 — 무료 기능만. */
    EXPIRED,

    /** $10 영구 라이선스(또는 ADMIN 부여). */
    LICENSED,

    /** 런칭 이벤트 평생 무료(권리는 LICENSED 와 동일). */
    EVENT_FREE,

    /** 약관 위반 등으로 강제 회수(정지). */
    REVOKED,

    ;

    /** 유료(서버 관리 프리미엄) 기능 접근 권한 여부. */
    fun hasPaidAccess(): Boolean = this == TRIAL || this == LICENSED || this == EVENT_FREE
}

/** 판정 결과 + 체험 종료 시각(TRIAL/EXPIRED 일 때만 의미). */
data class Entitlement(
    val status: LicenseStatus,
    val trialEndsAt: Instant?,
) {
    fun hasPaidAccess(): Boolean = status.hasPaidAccess()
}

/**
 * 사용자 라이선스 도메인 애그리거트(순수 Kotlin — Spring/JPA/JDA 의존 0, ArchUnit `migratedDomainsArePure`).
 *
 * 신원은 Discord userId. 체험 시계(trial_started_at = 유저 가입 시점 기준 + 달력 [trialMonths]개월),
 * 유료/이벤트 부여([grant]), 환불 회수([refundFlag])·정지([banned])를 모델링한다(ADR 0005).
 * 시각은 모두 UTC Instant. 판정은 [resolve] 단일 함수가 우선순위대로 계산한다.
 */
data class License(
    val userId: Long,
    val grant: LicenseGrant,
    val trialStartedAt: Instant,
    val grantedAt: Instant?,
    val revokedAt: Instant?,
    val refundFlag: Boolean,
    val banned: Boolean,
) {
    /** 체험 종료 시각 = 시작 + 달력 [trialMonths]개월(UTC 기준, 말일 보정은 java.time 규칙). */
    fun trialEndsAt(trialMonths: Long): Instant =
        trialStartedAt.atZone(ZoneOffset.UTC).plusMonths(trialMonths).toInstant()

    /**
     * 우선순위대로 entitlement 를 판정한다. 시간 의존(TRIAL↔EXPIRED)은 [now] 로 계산하므로
     * 저장 상태가 아니라 매 호출 평가한다(환불/만료 즉시 반영).
     */
    fun resolve(
        now: Instant,
        trialMonths: Long,
    ): Entitlement {
        if (banned) return Entitlement(LicenseStatus.REVOKED, null)
        if (revokedAt == null) {
            when (grant) {
                LicenseGrant.EVENT -> return Entitlement(LicenseStatus.EVENT_FREE, null)
                LicenseGrant.PADDLE, LicenseGrant.ADMIN -> return Entitlement(LicenseStatus.LICENSED, null)
                LicenseGrant.NONE -> {} // 체험 판정으로 진행
            }
        }
        // 부여 없음 / 환불·폐기됨 → 체험 판정. 환불 이력(refundFlag)이면 체험 재부여 금지(즉시 EXPIRED).
        val ends = trialEndsAt(trialMonths)
        return if (!refundFlag && now.isBefore(ends)) {
            Entitlement(LicenseStatus.TRIAL, ends)
        } else {
            Entitlement(LicenseStatus.EXPIRED, ends)
        }
    }
}
