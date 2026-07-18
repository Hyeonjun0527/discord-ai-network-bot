package com.discordassistant.central.actionruntime.adapter.inbound.scheduler

import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationHandler
import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationOutboxPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** PENDING WAIT child 판단을 멱등 handler로 전달하고 성공한 행만 PUBLISHED로 끝낸다. */
@Component
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class WaitReevaluationOutboxPublisher(
    private val outbox: WaitReevaluationOutboxPort,
    private val handler: WaitReevaluationHandler,
) {
    private val log = LoggerFactory.getLogger(WaitReevaluationOutboxPublisher::class.java)

    @Scheduled(fixedDelayString = "\${central.nexa.wait-reevaluation.poll-interval-ms:2000}")
    fun tick() {
        outbox.claimPending(BATCH_SIZE).forEach { command ->
            runCatching {
                if (handler.handle(command)) {
                    outbox.markPublished(command.childDecisionId)
                } else {
                    outbox.releaseClaim(command.childDecisionId)
                }
            }.onFailure { error ->
                outbox.releaseClaim(command.childDecisionId)
                log.warn("NEXA WAIT 재평가 전달 실패(child={}) — 다음 tick 재시도: {}", command.childDecisionId, error.message)
            }
        }
    }

    private companion object {
        const val BATCH_SIZE: Int = 50
    }
}
