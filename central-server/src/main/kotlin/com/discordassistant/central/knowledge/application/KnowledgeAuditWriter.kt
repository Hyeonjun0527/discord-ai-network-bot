package com.discordassistant.central.knowledge.application

import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 지식 인제스트 감사 로그 기록 + 사유 스크럽 협력자.
 *
 * **@Transactional 미부여(의도적)**: `audits.save` 는 호출자(파사드 write 메서드)의 활성 트랜잭션에
 * 합류한다(새 TX 미발생). [sanitizeReason] 은 순수 함수로 민감정보 스크럽 로직을 1바이트 불변으로 보존한다.
 */
@Component
class KnowledgeAuditWriter(
    private val audits: CustomizationAuditLogRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun audit(
        guildId: Long,
        channelId: Long?,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits?.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId ?: 0,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = sanitizeReason(summary).take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }

    fun sanitizeReason(reason: String): String = KnowledgeSafety.redactReason(reason)
}
