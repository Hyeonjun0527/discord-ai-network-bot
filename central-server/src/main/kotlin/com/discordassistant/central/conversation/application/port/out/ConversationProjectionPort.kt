package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId

/**
 * conversation outbox 가 적재된 이벤트를 projection worker 로 전달하는 아웃바운드 포트(NEXA-P03-T012).
 *
 * transactional outbox 패턴: 이벤트 append 와 outbox 기록은 한 트랜잭션에서 일어나고(T011), 별도 publisher 가
 * outbox 의 PENDING 레코드를 읽어 이 포트로 전달한다. 전달은 **at-least-once** 라 소비자는 멱등해야 한다 —
 * 같은 [EventId] 를 두 번 받아도 중복 side effect 를 만들지 않는다(acceptance T012).
 *
 * 구현 어댑터(실제 projection 채널)는 후속 task. 전달 키([eventId]/[channelId])만 운반하며 원문은 담지 않는다.
 */
fun interface ConversationProjectionPort {
    /** 적재된 이벤트를 projection 으로 전달한다(멱등 소비 전제 — 같은 키 재전달이 안전해야 한다). */
    fun deliver(
        eventId: EventId,
        channelId: ChannelId,
    )
}
