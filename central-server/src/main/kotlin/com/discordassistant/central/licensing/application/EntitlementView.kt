package com.discordassistant.central.licensing.application

import com.discordassistant.central.licensing.domain.model.Entitlement

/** 전환 퍼널 집계(P9 — 어드민 대시보드용, 모두 central 데이터). */
data class LicenseFunnel(
    val trial: Long,
    val expired: Long,
    val licensed: Long,
    val eventFree: Long,
    val revoked: Long,
    val refunded: Long,
)

/**
 * entitlement 조회 응답 DTO(camelCase 와이어). userId 는 **문자열**(Discord 64bit ID 의 JS Number
 * 정밀도 손실 방지 — 이 코드베이스의 guildId 정밀도 사고 재발 방지). trialEndsAt 은 ISO-8601(UTC).
 */
data class EntitlementView(
    val userId: String,
    val status: String,
    val trialEndsAt: String?,
    val hasPaidAccess: Boolean,
) {
    companion object {
        fun of(
            userId: Long,
            e: Entitlement,
        ): EntitlementView =
            EntitlementView(
                userId = userId.toString(),
                status = e.status.name,
                trialEndsAt = e.trialEndsAt?.toString(),
                hasPaidAccess = e.hasPaidAccess(),
            )
    }
}
