package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 채널 AI 커스터마이징 감사 로그 기록 협력자.
 *
 * **@Transactional 미부여(의도적)**: 별 빈으로 빼도 새 TX 가 열리지 않는다. `audits.save` 는 호출자
 * (파사드 write 메서드)의 활성 트랜잭션에 합류하므로, 추출 전 같은 빈 내부 호출과 원자성이 동일하다.
 */
@Component
class CustomizationAuditRecorder(
    private val audits: CustomizationAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun audit(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = summary.take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }
}
