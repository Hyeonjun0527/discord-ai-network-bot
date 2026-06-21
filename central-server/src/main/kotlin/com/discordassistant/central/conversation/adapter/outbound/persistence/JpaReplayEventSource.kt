package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.replay.ReplayCriteria
import com.discordassistant.central.conversation.application.replay.ReplayEventKey
import com.discordassistant.central.conversation.application.replay.ReplayEventSourcePort
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [ReplayEventSourcePort] 의 JPA 구현 어댑터(NEXA-P03-T019). 저장된 이벤트를 guild(+선택 channel)·occurred
 * 시각 범위로 읽어 **재생 키**(eventId·channelId)만 결정론적 순서로 돌려준다 — 원문은 담지 않는다.
 *
 * 정렬: channel 미지정이면 `channelId → sourceSequence → occurredAt`(채널별 순서 보존), channel 지정이면
 * `sourceSequence → occurredAt`. 둘 다 한 채널 안에서는 채널 순서를 그대로 유지한다(replay 가 원래 순서로 재생).
 *
 * 읽기 전용 — 이 어댑터는 어떤 쓰기·외부 전송도 하지 않는다(replay side-effect-free 보장의 한 축).
 */
@Repository
class JpaReplayEventSource(
    private val events: NexaEventRepository,
) : ReplayEventSourcePort {
    @Transactional(readOnly = true)
    override fun streamForReplay(criteria: ReplayCriteria): List<ReplayEventKey> {
        val rows =
            if (criteria.channelId == null) {
                events.findByGuildIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByChannelIdAscSourceSequenceAscOccurredAtAsc(
                    criteria.guildId.value,
                    criteria.from,
                    criteria.to,
                )
            } else {
                events.findByGuildIdAndChannelIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderBySourceSequenceAscOccurredAtAsc(
                    criteria.guildId.value,
                    criteria.channelId.value,
                    criteria.from,
                    criteria.to,
                )
            }
        return rows.map { ReplayEventKey(eventId = EventId(it.eventId), channelId = ChannelId(it.channelId)) }
    }
}
