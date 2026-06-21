package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.ConversationProjectionPort
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * conversation transactional outbox publisher(NEXA-P03-T012). PENDING outbox 레코드를 읽어
 * [ConversationProjectionPort] 로 전달하고 PUBLISHED 로 전이한다. projection 전달 채널과 저장소를 분리한다.
 *
 * **재시도 멱등(acceptance T012)**: 전달은 at-least-once 다. publisher 는 PENDING 만 집어 전달 후 PUBLISHED 로
 * 표시하므로, 이미 PUBLISHED 된 레코드는 다시 발행되지 않는다. 전달 중 실패하면 PENDING 으로 남아([attempts] 증가)
 * 다음 폴링에서 재시도되며, 소비자가 멱등하므로 같은 outbox 레코드가 중복 side effect 를 만들지 않는다.
 *
 * 한 레코드의 전달 실패가 배치 전체를 막지 않도록 레코드별로 격리해 처리한다(부분 진행 허용).
 */
@Component
class ConversationOutboxPublisher(
    private val outbox: NexaConversationOutboxRepository,
    private val projection: ConversationProjectionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * PENDING outbox 레코드를 전달한다. 전달 성공 건수를 돌려준다(폴링/스케줄러가 호출).
     *
     * 각 레코드는 멱등 소비를 전제로 [ConversationProjectionPort.deliver] 후 PUBLISHED 로 전이된다.
     * 전달 예외는 해당 레코드만 PENDING 으로 남기고([attempts] 증가) 다음 레코드로 넘어간다(부분 진행).
     */
    @Transactional
    fun publishPending(): Int {
        var published = 0
        for (record in outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING.name)) {
            record.attempts += 1
            val delivered = tryDeliver(record)
            if (delivered) {
                record.status = OutboxStatus.PUBLISHED.name
                record.publishedAt = clock.instant()
                published += 1
            }
            // 실패면 PENDING 유지(attempts 만 증가) — 다음 폴링에서 재시도, 배치 전체는 막지 않는다.
            outbox.save(record)
        }
        return published
    }

    /** 한 레코드를 멱등 소비 전제로 전달한다. 전달 예외는 false 로 흡수해 해당 레코드만 PENDING 으로 남긴다. */
    private fun tryDeliver(record: NexaConversationOutboxEntity): Boolean =
        try {
            projection.deliver(EventId(record.eventId), ChannelId(record.channelId))
            true
        } catch (ex: RuntimeException) {
            log.warn("outbox 전달 실패(PENDING 유지, eventId={}, attempts={}): {}", record.eventId, record.attempts, ex.message)
            false
        }

    private companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ConversationOutboxPublisher::class.java)
    }
}
