package com.discordassistant.central.conversation.application.replay

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import java.time.Instant

// 내부 replay 의 아웃바운드 포트들(NEXA-P03-T019).
//
// replay 는 저장된 이벤트를 다시 projection 으로 흘려보내는 내부 운영 유스케이스다. 외부(Discord) 전송은
// 절대 일어나지 않는다(acceptance T019) — 그래서 replay 가 의존하는 포트는 읽기(ReplayEventSourcePort)와
// projection 재적용(ReplayProjectionSink)뿐이며, Discord 전송 포트를 일절 참조하지 않는다(타입 수준 보장).

/**
 * replay 가 읽는 이벤트 소스(읽기 전용). guild/channel/time range 로 저장된 이벤트의 **재생 키**를 결정론적
 * 순서로 돌려준다. 원문을 담지 않는다(키·메타만) — projection 재적용은 키 기준으로 멱등하게 일어난다.
 */
fun interface ReplayEventSourcePort {
    /**
     * [criteria] 범위의 이벤트 재생 키를 결정론적 순서(채널 순서 → 시각)로 돌려준다. 빈 범위면 빈 목록.
     */
    fun streamForReplay(criteria: ReplayCriteria): List<ReplayEventKey>
}

/**
 * replay 가 이벤트를 **projection 으로 재적용**하는 sink(읽기 모델 갱신까지만). 구현은 새 projection version 의
 * 읽기 모델을 갱신한다 — **Discord 전송 등 외부 side effect 를 절대 수행하지 않는다**(acceptance T019).
 *
 * fun interface 라 테스트가 전송 없는 sink 를 쉽게 주입한다. dedup([ProjectionDeduplicator])은 호출자(유스케이스)
 * 가 적용하므로 sink 는 "이 키를 이 version 으로 재적용하라"는 명령만 받는다.
 */
fun interface ReplayProjectionSink {
    /** [eventKey] 를 [projectionVersion] 의 읽기 모델로 재적용한다(외부 전송 없음). */
    fun reapply(
        eventKey: ReplayEventKey,
        projectionVersion: Int,
    )
}

/**
 * replay 대상 범위. guild 는 필수, channel 은 선택(없으면 guild 의 모든 채널), time range 는 [from, to) 반열림.
 * [projectionVersion] 은 재생 결과를 적용할 대상 projection 버전이다(새 projection 으로 재투영).
 */
data class ReplayCriteria(
    val guildId: GuildId,
    val channelId: ChannelId?,
    val from: Instant,
    val to: Instant,
    val projectionVersion: Int,
) {
    init {
        require(!from.isAfter(to)) { "replay 범위 from 은 to 이후일 수 없다: from=$from to=$to" }
        require(projectionVersion >= 0) { "projectionVersion 은 음수일 수 없다: $projectionVersion" }
    }
}

/** replay 재생 키(원문 미포함). projection 재적용·dedup 의 기준이다. */
data class ReplayEventKey(
    val eventId: EventId,
    val channelId: ChannelId,
)
