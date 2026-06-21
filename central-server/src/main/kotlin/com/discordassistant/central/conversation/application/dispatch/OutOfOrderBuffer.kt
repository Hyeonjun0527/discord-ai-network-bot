package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * out-of-order 이벤트 버퍼(NEXA-P03-T015). gateway 가 at-least-once·재정렬로 이벤트를 **역순**으로 줄 수
 * 있다. 짧은 허용 창([window]) 안의 역순 이벤트를 모아 [chronology] 전순서로 재정렬해 내보내고, 창이
 * 만료되었거나 버퍼가 가득 차면 더는 기다리지 않고 **late event** 로 흘려보낸다.
 *
 * **경계 시각·최대 버퍼(acceptance T015)**: 고정 [Clock] 으로 결정론적이다. 한 이벤트가 버퍼에 들어온
 * 시각 + [window] 가 현재 시각보다 **이전이거나 같으면** 만료다([poll] 이 ready 로 방출). 만료 전이라도
 * 파티션 버퍼가 [maxBufferSize] 를 초과하면 가장 오래된(전순서 최소) 이벤트를 강제 방출해 메모리를 보호한다
 * (무한 적체 금지). 방출은 항상 [chronology](`sourceSequence → occurredAt`) 순서다.
 *
 * **순수성**: application.dispatch 소속이며 도메인 타입과 표준 [Clock]/[Instant]/[Duration] 만 본다
 * (Spring/JPA/JDA 미참조). 버퍼는 파티션([ChannelPartitionKey])별로 격리된다 — 한 채널의 적체가 다른
 * 채널 순서에 영향을 주지 않는다.
 *
 * 이 버퍼는 dedup·context sequence 부여 **앞단**이다 — 재정렬된 순서로 방출하면 projector 가 단조 번호를
 * 매긴다. late event(창 만료 후 도착)는 [offer] 가 [OfferResult.LATE] 로 표시해 호출자가 분리 처리한다.
 */
class OutOfOrderBuffer(
    private val window: Duration,
    private val maxBufferSize: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!window.isNegative && !window.isZero) { "허용 창은 양의 Duration 이어야 한다: $window" }
        require(maxBufferSize > 0) { "maxBufferSize 는 양수여야 한다: $maxBufferSize" }
    }

    private val buffers = mutableMapOf<ChannelPartitionKey, MutableList<Buffered>>()

    /** 버퍼에 들어온 이벤트 + 입장 시각(만료 판정 기준). */
    private data class Buffered(
        val event: NormalizedDiscordEvent,
        val enqueuedAt: Instant,
    )

    /**
     * [event] 를 버퍼에 넣는다. 결과로 즉시 방출 가능한(ready) 이벤트 목록과, 이 이벤트가 **late** 인지를
     * 돌려준다.
     *
     * late 판정: 같은 파티션에서 이미 더 큰 전순서([chronology])의 이벤트가 방출된 적이 있고 이 이벤트가
     * 그보다 **이전 순서**면(즉 이미 지나간 순서로 뒤늦게 도착) [OfferResult.LATE]. late 여도 호출자가
     * 보존 정책에 따라 따로 처리할 수 있게 이벤트 자체는 반환한다(버퍼에는 넣지 않는다).
     *
     * 정상 이벤트는 버퍼에 넣고, [poll] 로직(만료·버퍼 초과)을 적용해 방출 가능한 것을 함께 돌려준다.
     */
    fun offer(event: NormalizedDiscordEvent): OfferResult {
        val partition = ChannelPartitionKey.of(event)
        val lastEmitted = lastEmittedOrder[partition]
        if (lastEmitted != null && chronologyKey(event) <= lastEmitted) {
            // 이미 지나간 순서로 뒤늦게 도착 — 재정렬 창을 놓쳤다(late).
            return OfferResult(late = true, lateEvent = event, ready = emptyList())
        }
        val buffer = buffers.getOrPut(partition) { mutableListOf() }
        buffer.add(Buffered(event, clock.instant()))
        return OfferResult(late = false, lateEvent = null, ready = drain(partition))
    }

    /**
     * 시간 경과로 만료된 이벤트를 방출한다(스케줄러가 주기 호출). offer 없이 시간만 지났을 때 적체분을
     * 흘려보낸다. 파티션별 ready 목록을 [chronology] 순서로 합쳐 돌려준다.
     */
    fun poll(): List<NormalizedDiscordEvent> = buffers.keys.toList().flatMap { drain(it) }

    /** 현재 버퍼에 적체된 총 이벤트 수(관측용). */
    fun bufferedCount(): Int = buffers.values.sumOf { it.size }

    /**
     * 한 파티션에서 **방출 조건을 만족한** 이벤트를 전순서로 빼낸다.
     *
     * 방출 조건(둘 중 하나):
     * 1) 만료: `enqueuedAt + window <= now` — 허용 창이 지났다(더 이른 순서가 더 올 가망을 포기).
     * 2) 버퍼 초과: 파티션 크기가 [maxBufferSize] 초과 — 가장 이른 순서부터 강제 방출(메모리 보호).
     *
     * 방출은 항상 [chronology] 최소부터다 — 재정렬된 결정론적 순서를 보장한다.
     */
    private fun drain(partition: ChannelPartitionKey): List<NormalizedDiscordEvent> {
        val buffer = buffers[partition] ?: return emptyList()
        val now = clock.instant()
        val emitted = mutableListOf<NormalizedDiscordEvent>()

        // 전순서로 정렬해 항상 최소부터 검토한다.
        buffer.sortWith(bufferedOrder)
        while (buffer.isNotEmpty()) {
            val head = buffer.first()
            val expired = !head.enqueuedAt.plus(window).isAfter(now) // enqueuedAt + window <= now
            val overflow = buffer.size > maxBufferSize
            if (!expired && !overflow) break
            buffer.removeAt(0)
            emitted.add(head.event)
            lastEmittedOrder[partition] = chronologyKey(head.event)
        }
        if (buffer.isEmpty()) buffers.remove(partition)
        return emitted
    }

    private val lastEmittedOrder = mutableMapOf<ChannelPartitionKey, OrderKey>()

    /** [chronology] 와 동일한 전순서를 비교 가능한 키로 — `sourceSequence → occurredAt → eventId`. */
    private fun chronologyKey(event: NormalizedDiscordEvent): OrderKey =
        OrderKey(event.sourceSequence, event.occurredAt, event.eventId.value)

    private data class OrderKey(
        val sourceSequence: Long,
        val occurredAt: Instant,
        val eventId: String,
    ) : Comparable<OrderKey> {
        override fun compareTo(other: OrderKey): Int =
            compareValuesBy(this, other, { it.sourceSequence }, { it.occurredAt }, { it.eventId })
    }

    private companion object {
        private val bufferedOrder: Comparator<Buffered> =
            compareBy<Buffered> { it.event.sourceSequence }
                .thenBy { it.event.occurredAt }
                .thenBy { it.event.eventId.value }
    }
}

/**
 * [OutOfOrderBuffer.offer] 결과.
 *
 * - [ready]: 이번 offer 로 방출 가능해진(재정렬·만료된) 이벤트들([chronology] 순서).
 * - [late]/[lateEvent]: offer 한 이벤트가 허용 창을 놓친 late event 면 true + 그 이벤트(버퍼 미적재).
 */
data class OfferResult(
    val late: Boolean,
    val lateEvent: NormalizedDiscordEvent?,
    val ready: List<NormalizedDiscordEvent>,
)
