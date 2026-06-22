package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent

/**
 * 채널 순서 번호 projector(NEXA-P03-T014). gateway [NormalizedDiscordEvent.sourceSequence] 와
 * [NormalizedDiscordEvent.receivedAt] 을 이용해 한 파티션([ChannelPartitionKey]) 안에서 **내부 단조 증가
 * context sequence** 를 부여한다.
 *
 * **재시작 후 역행 금지(acceptance T014)**: 재시작 시 호출자가 이미 부여한 마지막 context sequence 로
 * [ContextSequenceProjector] 를 복원([resumeFrom])하면, 새로 들어오는 이벤트는 그 값보다 큰 번호만 받는다 —
 * 재시작 전후로 sequence 가 절대 역행하지 않는다. projector 는 파티션별 high-water mark 만 유지하므로
 * 멱등하지 않다(같은 이벤트를 두 번 넣으면 번호가 두 번 증가) — dedup([ProjectionDeduplicator]) 이 선행한다.
 *
 * **충돌 결정 규칙(acceptance T014)**: 동일 파티션의 두 이벤트가 같은 [sourceSequence] 를 가질 수 있다
 * (gateway resume·중복 디스패치 경계). 이때 [NormalizedDiscordEvent.chronology] 와 동일한 전순서
 * (`sourceSequence → occurredAt → eventId`)로 **결정론적으로** 한쪽을 앞에 둔다 — 입력 순서·기계
 * 차이와 무관하게 같은 입력 집합이면 같은 번호 배정이 나온다.
 *
 * 순수성: application.dispatch 소속이며 도메인 타입만 참조한다(Spring/JPA/JDA 미참조). 시계·DB 를 직접
 * 만지지 않고, high-water mark 만 메모리에서 단조 증가시킨다(테스트가 결정론적).
 */
class ContextSequenceProjector {
    private val highWaterMark = mutableMapOf<ChannelPartitionKey, Long>()

    /**
     * 재시작 복원: [partition] 의 마지막으로 부여된 context sequence 를 주입한다(영속 read model 에서 읽어옴).
     * 이후 [assign] 은 이 값보다 큰 번호만 부여해 재시작 전후 역행을 막는다. 음수는 허용하지 않는다.
     */
    fun resumeFrom(
        partition: ChannelPartitionKey,
        lastAssigned: Long,
    ) {
        require(lastAssigned >= 0) { "context sequence high-water mark 는 음수일 수 없다: $lastAssigned" }
        val current = highWaterMark[partition]
        // 복원은 high-water mark 를 낮추지 않는다(역행 금지) — 더 큰 값만 채택.
        if (current == null || lastAssigned > current) {
            highWaterMark[partition] = lastAssigned
        }
    }

    /**
     * [event] 에 그 파티션의 다음 context sequence(직전 + 1)를 부여한다. 부여 후 high-water mark 가 갱신돼
     * 다음 호출은 더 큰 번호를 받는다(파티션 내 단조 증가, 역행 없음).
     *
     * 충돌(같은 sourceSequence)은 [NormalizedDiscordEvent.chronology] 전순서로 정렬해 호출자가 결정론적
     * 순서로 넘기면 그대로 단조 번호가 매겨진다 — 배치 부여는 [assignBatch] 가 정렬을 보장한다.
     */
    fun assign(event: NormalizedDiscordEvent): AssignedSequence {
        val partition = ChannelPartitionKey.of(event)
        val next = (highWaterMark[partition] ?: 0L) + 1
        highWaterMark[partition] = next
        return AssignedSequence(partition = partition, contextSequence = next, event = event)
    }

    /**
     * 한 파티션의 이벤트 집합에 결정론적 순서로 번호를 부여한다. 입력 순서·중복 sourceSequence 와 무관하게
     * [chronology] 전순서(`sourceSequence → occurredAt → eventId`)로 정렬한 뒤 [assign] 하므로, 같은 입력
     * 집합이면 항상 같은 (eventId → contextSequence) 배정이 나온다(충돌 결정 규칙).
     */
    fun assignBatch(events: List<NormalizedDiscordEvent>): List<AssignedSequence> =
        events
            .sortedWith(deterministicOrder)
            .map(::assign)

    /** 파티션의 현재 high-water mark(부여된 마지막 context sequence; 미부여면 0). 영속 read model 갱신용. */
    fun highWaterMarkOf(partition: ChannelPartitionKey): Long = highWaterMark[partition] ?: 0L

    private companion object {
        // chronology(sourceSequence → occurredAt) + eventId 최종 타이브레이크 = 완전 결정론적 전순서.
        private val deterministicOrder: Comparator<NormalizedDiscordEvent> =
            NormalizedDiscordEvent.chronology.thenBy { it.eventId.value }
    }
}

/**
 * projector 가 한 이벤트에 부여한 내부 context sequence 결과.
 *
 * [contextSequence] 는 [partition] 안에서 단조 증가하는 내부 순서 번호다(gateway sourceSequence 와 별개 —
 * 재시작·재정렬 후에도 역행하지 않는 conversation 내부 순서).
 */
data class AssignedSequence(
    val partition: ChannelPartitionKey,
    val contextSequence: Long,
    val event: NormalizedDiscordEvent,
)
