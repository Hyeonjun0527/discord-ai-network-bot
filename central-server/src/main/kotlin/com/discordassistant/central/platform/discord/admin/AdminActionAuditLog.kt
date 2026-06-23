package com.discordassistant.central.platform.discord.admin

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * AI 관리 비서 실행 감사 로그(과제 안전장치 6). 누가(actorId)·무엇(action)·대상(targetRef)·결과를 한 줄로 남긴다.
 * **원문/토큰/키는 남기지 않는다** — 대상은 ID 또는 이름의 안정 참조만, 사유 텍스트는 길이 제한해 PII 누출을 줄인다.
 * 별도 영속 인프라(ActionAuditEvent)는 speech 도메인 스키마라 형태가 맞지 않아, 운영 로그로 append-only 기록한다.
 */
@Component
class AdminActionAuditLog {
    private val log = LoggerFactory.getLogger("admin-action-audit")

    fun record(
        guildId: Long,
        actorId: Long,
        plan: AdminActionPlan,
        result: AdminActionResult,
        confirmed: Boolean,
    ) {
        val outcome = if (result is AdminActionResult.Done) "OK" else "REJECTED"
        // 대상 참조만(원문 없음): channelId/userId/targetId 같은 식별 인자만 추려 남긴다.
        val targetRef =
            listOfNotNull(
                plan.arg("channelId")?.let { "channel=$it" },
                plan.arg("userId")?.let { "user=$it" },
                plan.arg("targetId")?.let { "target=$it" },
                plan.arg("name")?.let { "name=${it.take(60)}" },
            ).joinToString(",").ifBlank { "-" }
        log.info(
            "admin-action guild={} actor={} action={} risk={} confirmed={} target=[{}] outcome={}",
            guildId,
            actorId,
            plan.type.toolName,
            plan.type.risk,
            confirmed,
            targetRef,
            outcome,
        )
    }
}
