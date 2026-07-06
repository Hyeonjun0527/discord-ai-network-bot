package com.discordassistant.central.participation.adapter.outbound.rollout

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.participation.application.rollout.CanaryHaltAlert
import com.discordassistant.central.participation.application.rollout.OperatorAlertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Canary 자동 중단 운영자 알림 아웃바운드 어댑터(NEXA-P18-T023, participation adapter).
 *
 * 자동 중단이 일어나면 구조화된 `log.warn` 로 당직 운영자에게 즉시 가시화하고, 같은 사실을 [AuditLog] 에
 * append-only 로 남긴다(무엇이/왜/어디로 강등됐는지 추적). PII/원문 비포함 — 가명·강등 단계·사유 코드·취소 건수만
 * (알림 [CanaryHaltAlert] 자체가 원문을 담지 않는다).
 *
 * 알림 채널(Slack/PagerDuty 등) 실연동은 후속 — 지금은 로그+감사가 운영자 가시성의 최소 보장이다.
 */
@Component
class LoggingOperatorAlertAdapter(
    private val audit: AuditLog,
) : OperatorAlertPort {
    private val log = LoggerFactory.getLogger(LoggingOperatorAlertAdapter::class.java)

    override fun notifyAutoHalt(alert: CanaryHaltAlert) {
        val reasons = alert.reasons.joinToString(",") { it.name }
        log.warn(
            "NEXA canary 자동 중단 — guild={} target={} reasons={} cancelledPending={}",
            alert.guildPseudonym,
            alert.target,
            reasons,
            alert.cancelledPending,
        )
        audit.record(
            action = "nexa_canary_auto_halt",
            actor = "system-auto-halt",
            target = "guild:${alert.guildPseudonym}",
            detail = "target:${alert.target} reasons:$reasons cancelledPending:${alert.cancelledPending}",
        )
    }
}
