package com.discordassistant.central.platform.discord.nexa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.RejectedExecutionException

class NiaTurnBoundaryCoordinatorTest {
    private val start = Instant.parse("2026-07-25T00:00:00Z")

    @Test
    fun `연속 메시지는 이전 timer race를 무효화하고 최신 generation만 dispatcher에서 judge한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        var latestGeneration = 1L
        val dispatcher = ArrayDeque<Runnable>()
        val judged = mutableListOf<Long>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks =
            callbacks(
                latest = { _, generation -> generation == latestGeneration },
                enqueue = { routingId, task ->
                    assertThat(routingId).isEqualTo(20L)
                    dispatcher.addLast(task)
                    true
                },
                judge = { judged += it.messageId },
            )

        assertThat(coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks))
            .isEqualTo(NiaTurnBoundaryAdmission.DEFERRED)
        clock.advance(Duration.ofSeconds(1))
        latestGeneration = 2L
        assertThat(coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks))
            .isEqualTo(NiaTurnBoundaryAdmission.DEFERRED)

        scheduler.fire(0, evenIfCancelled = true)
        assertThat(dispatcher).isEmpty()
        scheduler.fire(1)
        assertThat(dispatcher).hasSize(1)
        dispatcher.removeFirst().run()
        assertThat(judged).containsExactly(2L)
    }

    @Test
    fun `timer 뒤 worker 시작 전에 새 메시지가 오면 queued callback도 no-op이다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        var latestGeneration = 1L
        val dispatcher = ArrayDeque<Runnable>()
        val judged = mutableListOf<Long>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks =
            callbacks(
                latest = { _, generation -> generation == latestGeneration },
                enqueue = { _, task ->
                    dispatcher.addLast(task)
                    true
                },
                judge = { judged += it.messageId },
            )

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        scheduler.fire(0)
        latestGeneration = 2L
        clock.advance(Duration.ofSeconds(1))
        coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks)

        dispatcher.removeFirst().run()
        assertThat(judged).isEmpty()
    }

    @Test
    fun `typing은 pending burst만 연장하고 generation을 바꾸지 않으며 hard max를 넘지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)
        var observedGeneration = 1L
        val callbacks = callbacks(latest = { _, generation -> generation == observedGeneration })

        assertThat(coordinator.onTyping(20L, 30L)).isFalse()
        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusMillis(4_500))

        clock.advance(Duration.ofSeconds(4))
        assertThat(coordinator.onTyping(20L, 31L)).isFalse()
        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusMillis(4_500))
        assertThat(coordinator.onTyping(20L, 30L)).isTrue()
        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusSeconds(8))
        assertThat(observedGeneration).isEqualTo(1L)

        clock.advance(Duration.ofSeconds(25))
        assertThat(coordinator.onTyping(20L, 30L)).isTrue()
        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusSeconds(30))
    }

    @Test
    fun `직접 요청 뒤 다른 사용자의 일반 메시지는 대상과 마감시각을 바꾸지 않고 장면 세대만 갱신한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        var latestGeneration = 1L
        val judged = mutableListOf<ParticipationMessageSignal>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks =
            callbacks(
                latest = { _, generation -> generation == latestGeneration },
                judge = judged::add,
            )

        coordinator.onMessage(true, 20L, 1L, signal(1L).copy(mentioned = true), callbacks)
        val originalDeadline = coordinator.pendingDeadline(20L)

        clock.advance(Duration.ofSeconds(1))
        latestGeneration = 2L
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(userId = 31L),
            callbacks,
        )

        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(originalDeadline)
        scheduler.fire(0, evenIfCancelled = true)
        scheduler.fire(1)

        assertThat(judged).hasSize(1)
        val judgedSignal = judged.single()
        assertThat(judgedSignal.messageId).isEqualTo(1L)
        assertThat(judgedSignal.userId).isEqualTo(30L)
        assertThat(judgedSignal.turnGeneration).isEqualTo(2L)
        assertThat(judgedSignal.mentioned).isTrue()
    }

    @Test
    fun `새 메시지 ingress는 FIFO 병합 전 timer race를 막고 직접 요청을 보존한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<ParticipationMessageSignal>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks = callbacks(judge = judged::add)

        coordinator.onMessage(true, 20L, 1L, signal(1L).copy(mentioned = true), callbacks)
        assertThat(coordinator.onMessageIngress(20L, 2L)).isTrue()

        scheduler.fire(0, evenIfCancelled = true)
        assertThat(judged).isEmpty()
        assertThat(coordinator.onTyping(20L, 30L)).isFalse()

        clock.advance(Duration.ofSeconds(1))
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(userId = 31L),
            callbacks,
        )
        scheduler.fire(1)

        assertThat(judged).hasSize(1)
        assertThat(judged.single().messageId).isEqualTo(1L)
        assertThat(judged.single().turnGeneration).isEqualTo(2L)
    }

    @Test
    fun `같은 사용자의 후속 메시지는 최신 문장을 대상으로 삼고 직접 요청 성격을 계승한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<ParticipationMessageSignal>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks = callbacks(judge = judged::add)

        coordinator.onMessage(true, 20L, 1L, signal(1L).copy(mentioned = true), callbacks)
        clock.advance(Duration.ofSeconds(1))
        coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks)
        scheduler.fire(1)

        assertThat(judged).hasSize(1)
        val judgedSignal = judged.single()
        assertThat(judgedSignal.messageId).isEqualTo(2L)
        assertThat(judgedSignal.userId).isEqualTo(30L)
        assertThat(judgedSignal.turnGeneration).isEqualTo(2L)
        assertThat(judgedSignal.mentioned).isTrue()
    }

    @Test
    fun `직접 요청 작성자가 다른 사람에게 답장하면 니아 판단 대상으로 계승하지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<ParticipationMessageSignal>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks = callbacks(judge = judged::add)

        coordinator.onMessage(true, 20L, 1L, signal(1L).copy(mentioned = true), callbacks)
        val originalDeadline = coordinator.pendingDeadline(20L)
        clock.advance(Duration.ofSeconds(1))
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(replyToHuman = true, replyToMessageId = 99L),
            callbacks,
        )
        scheduler.fire(1)

        assertThat(coordinator.pendingDeadline(20L)).isNull()
        assertThat(judged).hasSize(1)
        val judgedSignal = judged.single()
        assertThat(judgedSignal.messageId).isEqualTo(1L)
        assertThat(judgedSignal.turnGeneration).isEqualTo(2L)
        assertThat(judgedSignal.mentioned).isTrue()
        assertThat(judgedSignal.replyToHuman).isFalse()
        assertThat(originalDeadline).isEqualTo(start.plusMillis(4_500))
    }

    @Test
    fun `다른 사용자의 새 직접 요청은 이전 직접 요청보다 최신 대상으로 승격한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<ParticipationMessageSignal>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks = callbacks(judge = judged::add)

        coordinator.onMessage(true, 20L, 1L, signal(1L).copy(mentioned = true), callbacks)
        clock.advance(Duration.ofSeconds(1))
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(userId = 31L, mentioned = true),
            callbacks,
        )
        scheduler.fire(1)

        assertThat(judged).hasSize(1)
        val judgedSignal = judged.single()
        assertThat(judgedSignal.messageId).isEqualTo(2L)
        assertThat(judgedSignal.userId).isEqualTo(31L)
        assertThat(judgedSignal.turnGeneration).isEqualTo(2L)
    }

    @Test
    fun `대상 작성자가 바뀌면 이전 작성자의 typing 유예를 계승하지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)

        coordinator.onMessage(true, 20L, 1L, signal(1L).copy(mentioned = true), callbacks())
        clock.advance(Duration.ofSeconds(1))
        assertThat(coordinator.onTyping(20L, 30L)).isTrue()
        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusSeconds(5))

        clock.advance(Duration.ofMillis(100))
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(userId = 31L, mentioned = true),
            callbacks(),
        )

        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusMillis(3_100))
    }

    @Test
    fun `30초 넘게 끊긴 이전 대화 속도는 새 turn의 idle 계산에 쓰지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks())
        scheduler.fire(0)

        clock.advance(Duration.ofSeconds(31))
        coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks())

        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(clock.instant().plusMillis(4_500))
    }

    @Test
    fun `dispatcher가 밀려도 처리 시각이 아니라 Discord 메시지 시각으로 대화 속도를 계산한다`() {
        val clock = MutableClock(start.plusSeconds(10))
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)

        coordinator.onMessage(
            true,
            20L,
            1L,
            signal(1L).copy(tsMs = start.toEpochMilli()),
            callbacks(),
        )
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(tsMs = start.plusSeconds(5).toEpochMilli()),
            callbacks(),
        )

        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusSeconds(10))
    }

    @Test
    fun `역순으로 도착한 Discord 시각은 다음 메시지 간격의 기준점을 과거로 되돌리지 않는다`() {
        val clock = MutableClock(start.plusSeconds(10))
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)

        coordinator.onMessage(
            true,
            20L,
            1L,
            signal(1L).copy(tsMs = start.plusSeconds(5).toEpochMilli()),
            callbacks(),
        )
        coordinator.onMessage(
            true,
            20L,
            2L,
            signal(2L).copy(tsMs = start.plusSeconds(3).toEpochMilli()),
            callbacks(),
        )
        coordinator.onMessage(
            true,
            20L,
            3L,
            signal(3L).copy(tsMs = start.plusSeconds(6).toEpochMilli()),
            callbacks(),
        )

        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusSeconds(8))
    }

    @Test
    fun `늦게 도착한 이전 generation은 최신 대상과 timer를 취소하지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<Long>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks = callbacks(judge = { judged += it.messageId })

        coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks)
        assertThat(coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks))
            .isEqualTo(NiaTurnBoundaryAdmission.DEFERRED)

        assertThat(scheduler.size).isEqualTo(1)
        scheduler.fire(0)
        assertThat(judged).containsExactly(2L)
    }

    @Test
    fun `계속되는 메시지도 최초 메시지 30초에 최신 하나만 judge한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)
        var latestGeneration = 1L
        val judged = mutableListOf<Long>()
        val callbacks =
            callbacks(
                latest = { _, generation -> generation == latestGeneration },
                judge = { judged += it.messageId },
            )

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        repeat(4) { offset ->
            clock.advance(Duration.ofSeconds(6))
            latestGeneration = offset + 2L
            coordinator.onMessage(
                true,
                20L,
                latestGeneration,
                signal(latestGeneration),
                callbacks,
            )
        }

        assertThat(coordinator.pendingDeadline(20L)).isEqualTo(start.plusSeconds(30))
        clock.advance(Duration.ofSeconds(6))
        scheduler.fire(scheduler.size - 1)

        assertThat(judged).containsExactly(5L)
    }

    @Test
    fun `채널과 thread routing scope는 서로의 boundary를 취소하지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)
        val judged = mutableListOf<Pair<Long, Long>>()

        coordinator.onMessage(
            true,
            20L,
            1L,
            signal(1L),
            callbacks(judge = { judged += it.channelId to it.messageId }),
        )
        coordinator.onMessage(
            true,
            21L,
            2L,
            signal(2L).copy(channelId = 21L, threadId = 21L),
            callbacks(judge = { judged += it.channelId to it.messageId }),
        )

        scheduler.fire(0)
        scheduler.fire(1)

        assertThat(judged).containsExactly(20L to 1L, 21L to 2L)
    }

    @Test
    fun `close는 pending timer를 취소하고 이후 ingress를 fail closed한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)
        val judged = mutableListOf<Long>()
        val callbacks = callbacks(judge = { judged += it.messageId })

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        coordinator.close()
        scheduler.fire(0, evenIfCancelled = true)

        assertThat(judged).isEmpty()
        assertThat(coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks))
            .isEqualTo(NiaTurnBoundaryAdmission.FAIL_CLOSED)
    }

    @Test
    fun `mode downgrade와 dispatcher overload는 judge 없이 fail closed한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        var realSend = true
        val judged = mutableListOf<Long>()
        var failClosed = 0
        val coordinator = coordinator(clock, scheduler)
        val callbacks =
            callbacks(
                realSend = { realSend },
                enqueue = { _, _ -> false },
                judge = { judged += it.messageId },
                onFailClosed = { failClosed++ },
            )

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        realSend = false
        scheduler.fire(0)
        assertThat(judged).isEmpty()

        realSend = true
        coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks)
        scheduler.fire(1)
        assertThat(judged).isEmpty()
        assertThat(failClosed).isEqualTo(1)
    }

    @Test
    fun `edit delete invalidation은 canceled timer race와 queued worker를 모두 no-op으로 만든다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val dispatcher = ArrayDeque<Runnable>()
        val judged = mutableListOf<Long>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks =
            callbacks(
                enqueue = { _, task ->
                    dispatcher.addLast(task)
                    true
                },
                judge = { judged += it.messageId },
            )

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        assertThat(coordinator.cancel(20L)).isTrue()
        scheduler.fire(0, evenIfCancelled = true)
        assertThat(dispatcher).isEmpty()

        coordinator.onMessage(true, 20L, 2L, signal(2L), callbacks)
        scheduler.fire(1)
        assertThat(coordinator.cancel(20L)).isTrue()
        dispatcher.removeFirst().run()
        assertThat(judged).isEmpty()
    }

    @Test
    fun `guild leave와 user opt-out은 해당 pending만 즉시 취소한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<Long>()
        val coordinator = coordinator(clock, scheduler)
        val callbacks = callbacks(judge = { judged += it.messageId })

        coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks)
        coordinator.onMessage(
            true,
            21L,
            2L,
            signal(2L).copy(channelId = 21L, userId = 31L),
            callbacks,
        )
        coordinator.onMessage(
            true,
            22L,
            3L,
            signal(3L).copy(guildId = 11L, channelId = 22L),
            callbacks,
        )

        assertThat(coordinator.cancelUser(guildId = 10L, userId = 30L)).isEqualTo(1)
        assertThat(coordinator.cancelGuild(11L)).isEqualTo(1)
        scheduler.fire(0, evenIfCancelled = true)
        scheduler.fire(1)
        scheduler.fire(2, evenIfCancelled = true)

        assertThat(judged).containsExactly(2L)
    }

    @Test
    fun `모든 tracked scope가 pending이면 새 scope를 fail closed하고 map을 늘리지 않는다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator =
            NiaTurnBoundaryCoordinator(
                enabled = true,
                clock = clock,
                scheduler = scheduler,
                maximumTrackedScopes = 1,
            )

        assertThat(coordinator.onMessage(true, 20L, 1L, signal(1L), callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.DEFERRED)
        assertThat(coordinator.onMessage(true, 21L, 2L, signal(2L).copy(channelId = 21L), callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.FAIL_CLOSED)
        assertThat(scheduler.size).isEqualTo(1)
    }

    @Test
    fun `disabled나 ingress 당시 shadow는 예약하지 않고 scheduler rejection은 fail closed한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val disabled = NiaTurnBoundaryCoordinator(false, clock, scheduler)
        assertThat(disabled.onMessage(true, 20L, 1L, signal(1L), callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.BYPASS)

        val enabled = coordinator(clock, scheduler)
        assertThat(enabled.onMessage(false, 20L, 1L, signal(1L), callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.BYPASS)

        val rejecting =
            NiaTurnBoundaryCoordinator(
                enabled = true,
                clock = clock,
                scheduler = NiaTurnBoundaryScheduler { _, _ -> throw RejectedExecutionException("closed") },
            )
        assertThat(rejecting.onMessage(true, 20L, 1L, signal(1L), callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.FAIL_CLOSED)
    }

    @Test
    fun `새 메시지 처리 시 real send가 꺼졌으면 일시 정지한 pending도 제거한다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val judged = mutableListOf<Long>()
        val coordinator = coordinator(clock, scheduler)

        coordinator.onMessage(
            true,
            20L,
            1L,
            signal(1L),
            callbacks(judge = { judged += it.messageId }),
        )
        assertThat(coordinator.onMessageIngress(20L, 2L)).isTrue()
        assertThat(coordinator.onMessage(false, 20L, 2L, signal(2L), callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.BYPASS)

        scheduler.fire(0, evenIfCancelled = true)
        assertThat(coordinator.pendingDeadline(20L)).isNull()
        assertThat(judged).isEmpty()
    }

    @Test
    fun `message ingress의 점 prefix도 slash utility로 오인하지 않고 boundary를 지난다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)
        val dotMessage = signal(1L).copy(rawText = ".이건 Discord 일반 메시지")

        assertThat(coordinator.onMessage(true, 20L, 1L, dotMessage, callbacks()))
            .isEqualTo(NiaTurnBoundaryAdmission.DEFERRED)
    }

    @Test
    fun `직접 멘션도 즉시 judge하지 않고 동일한 turn boundary를 지난다`() {
        val clock = MutableClock(start)
        val scheduler = ManualScheduler()
        val coordinator = coordinator(clock, scheduler)
        val judged = mutableListOf<Long>()
        val directMention = signal(1L).copy(mentioned = true)

        assertThat(
            coordinator.onMessage(
                realSendAtIngress = true,
                routingId = 20L,
                generation = 1L,
                signal = directMention,
                callbacks = callbacks(judge = { judged += it.messageId }),
            ),
        ).isEqualTo(NiaTurnBoundaryAdmission.DEFERRED)
        assertThat(judged).isEmpty()

        scheduler.fire(0)

        assertThat(judged).containsExactly(1L)
    }

    private fun coordinator(
        clock: Clock,
        scheduler: NiaTurnBoundaryScheduler,
    ) = NiaTurnBoundaryCoordinator(enabled = true, clock = clock, scheduler = scheduler)

    private fun callbacks(
        realSend: () -> Boolean = { true },
        latest: (Long, Long) -> Boolean = { _, _ -> true },
        enqueue: (Long, Runnable) -> Boolean = { _, task ->
            task.run()
            true
        },
        judge: (ParticipationMessageSignal) -> Unit = {},
        onFailClosed: () -> Unit = {},
    ) = NiaTurnBoundaryCoordinator.Callbacks(realSend, latest, enqueue, judge, onFailClosed)

    private fun signal(messageId: Long) =
        ParticipationMessageSignal(
            guildId = 10L,
            channelId = 20L,
            messageId = messageId,
            userId = 30L,
            mentioned = false,
            recentTurns = emptyList(),
            triggerText = "message-$messageId",
            sceneSeq = 0L,
            contextVersion = 0L,
            seed = messageId,
            turnGeneration = messageId,
        )

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private class ManualScheduler : NiaTurnBoundaryScheduler {
        private val entries = mutableListOf<Entry>()
        val size: Int get() = entries.size

        override fun schedule(
            delayMillis: Long,
            task: () -> Unit,
        ): NiaTurnBoundaryScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return NiaTurnBoundaryScheduledTask { entry.cancelled = true }
        }

        fun fire(
            index: Int,
            evenIfCancelled: Boolean = false,
        ) {
            val entry = entries[index]
            if (!entry.cancelled || evenIfCancelled) entry.task()
        }

        private data class Entry(
            val delayMillis: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
