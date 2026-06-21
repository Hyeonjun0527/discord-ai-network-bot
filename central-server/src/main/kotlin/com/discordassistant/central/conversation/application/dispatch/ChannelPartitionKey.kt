package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent

/**
 * conversation projection 의 채널 파티션 키(NEXA-P03-T013, ADR 0011).
 *
 * 같은 채널 이벤트가 **한 순서 스트림**으로 처리되도록 partition key 를 정의한다. ADR 0011 결정에 따라
 * partition key 는 `channelId` 다 — 스레드는 부모 채널에 합치지 않고 자기 `channelId` 로 독립 스트림을
 * 형성한다(separate). Discord 에서 스레드는 자기 고유 snowflake 를 가진 별개 채널이며 이벤트의
 * [ChannelId] 가 스레드 자신의 id 라, `channelId` 동등성만으로 분리가 성립한다(도메인이 스레드↔부모
 * 토폴로지를 알 필요 없음 — discord-adapter-boundary.md 순수성 유지).
 *
 * 이 값은 projector/buffer/dedup 이 공유하는 **단일 파티션 산출 규칙**이다(DRY). 산출 규칙이 바뀌면
 * (예: merge 전략) 여기 한곳만 바꾸면 모든 소비자가 일관되게 따라온다.
 *
 * 순수성: application.dispatch 소속이지만 도메인 타입([ChannelId]/[NormalizedDiscordEvent])만 참조한다 —
 * Spring/JPA/JDA 타입을 보지 않는다.
 */
@JvmInline
value class ChannelPartitionKey(
    val channelId: ChannelId,
) {
    companion object {
        /** 이벤트가 속한 순서 스트림의 파티션 키(ADR 0011: channelId = 파티션, 스레드 분리). */
        fun of(event: NormalizedDiscordEvent): ChannelPartitionKey = ChannelPartitionKey(event.channelId)

        /** [ChannelId] 로부터 직접 파티션 키를 만든다(스트림 조회용). */
        fun of(channelId: ChannelId): ChannelPartitionKey = ChannelPartitionKey(channelId)
    }
}
