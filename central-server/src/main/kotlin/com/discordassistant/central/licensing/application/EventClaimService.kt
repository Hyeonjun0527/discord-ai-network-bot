package com.discordassistant.central.licensing.application

import com.discordassistant.central.licensing.application.port.ContributionPort
import com.discordassistant.central.licensing.domain.model.LicenseGrant
import com.discordassistant.central.licensing.domain.model.LicenseStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/** 이벤트 신청 결과. */
enum class ClaimOutcome {
    GRANTED,
    ALREADY,
    CLOSED,
    NO_CONTRIBUTION,
    REFUND_BLOCKED,
}

/** 이벤트 현황(앱 표시용). */
data class EventStatus(
    val open: Boolean,
    val grantedCount: Long,
)

/**
 * 런칭 이벤트(얼리버드 평생 무료) 신청(ADR 0005, 차수 6, P10·P11·P12).
 *
 * 자격: 이벤트 열림 + 환불 이력 없음 + 풀 기여 ≥1건. 부여는 [LicenseService.grantEvent](EVENT_FREE = $10 동일 권리).
 * 운영자가 `central.license.event-open` 으로 무기한 토글(정원 없음).
 */
@Service
class EventClaimService(
    private val licenses: LicenseService,
    private val contribution: ContributionPort,
    @param:Value("\${central.license.event-open:false}") private val eventOpen: Boolean,
) {
    fun status(): EventStatus = EventStatus(eventOpen, licenses.countByGrant(LicenseGrant.EVENT))

    /** 멱등: 이미 EVENT_FREE 면 ALREADY. 자격 미달은 사유 반환(부여 안 함). */
    fun claim(userId: Long): ClaimOutcome {
        if (!eventOpen) return ClaimOutcome.CLOSED
        if (licenses.resolve(userId).status == LicenseStatus.EVENT_FREE) return ClaimOutcome.ALREADY
        if (licenses.hasRefundHistory(userId)) return ClaimOutcome.REFUND_BLOCKED
        if (!contribution.hasContributed(userId)) return ClaimOutcome.NO_CONTRIBUTION
        licenses.grantEvent(userId)
        return ClaimOutcome.GRANTED
    }
}
