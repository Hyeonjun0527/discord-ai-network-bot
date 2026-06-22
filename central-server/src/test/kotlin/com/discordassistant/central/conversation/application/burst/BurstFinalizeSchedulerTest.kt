package com.discordassistant.central.conversation.application.burst

import com.discordassistant.central.conversation.domain.event.BurstTerminationReason
import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P04-T019 acceptance: 실제 sleep 없이 시간 이동 테스트가 가능하고, 재시작 후 deadline 을 복구한다.
 */
class BurstFinalizeSchedulerTest {
    private val guild = GuildId(1L)
    private val t0 = BurstTestFragments.T0
    private val gap = Duration.ofSeconds(7)

    private fun mutableClock(initial: Instant): MutableClockHolder = MutableClockHolder(initial)

    private fun openBurst(messageId: Long): UtteranceBurst =
        UtteranceBurst.open(guild, BurstTestFragments.fragment(messageId, seq = messageId, at = t0))

    @Test
    fun `deadline 이전에는 finalize 하지 않고 시간 이동 후 finalize 한다 (sleep 없음)`() {
        val holder = mutableClock(t0)
        val scheduler = BurstFinalizeScheduler(holder.clock(), segmentationVersion = 2)
        val burst = openBurst(1)
        scheduler.schedule(burst, deadline = burst.lastFragmentAt.plus(gap))

        // 시각이 deadline 이전 → sweep 결과 없음, pending 유지.
        holder.set(t0.plusSeconds(3))
        assertTrue(scheduler.sweep().isEmpty())
        assertEquals(1, scheduler.pendingCount())

        // 시간 이동(sleep 없이 Clock 만 전진) → deadline 경과 → finalize.
        holder.set(t0.plusSeconds(8))
        val finalized = scheduler.sweep()
        assertEquals(1, finalized.size)
        assertEquals(burst.burstId, finalized.single().burstId)
        assertEquals(2, finalized.single().segmentationVersion)
        assertEquals(BurstTerminationReason.STREAM_END, finalized.single().terminationReason)
        assertEquals(0, scheduler.pendingCount(), "finalize 된 버스트는 큐에서 제거")
    }

    @Test
    fun `재시작 후 deadline 을 데이터에서 복구한다`() {
        // 재시작 시뮬레이션: 새 스케줄러가 영속 OPEN 버스트로부터 deadline 을 재계산해 복구.
        val holder = mutableClock(t0.plusSeconds(100))
        val restored = BurstFinalizeScheduler(holder.clock(), segmentationVersion = 1)
        val open1 = openBurst(1)
        val open2 = UtteranceBurst.open(guild, BurstTestFragments.fragment(2, seq = 2, at = t0.plusSeconds(200)))

        restored.restore(listOf(open1, open2)) { it.lastFragmentAt.plus(gap) }
        assertEquals(2, restored.pendingCount())

        // 현재 시각(t0+100)은 open1 deadline(t0+7) 은 지났지만 open2 deadline(t0+207)은 미도래.
        val due = restored.sweep()
        assertEquals(1, due.size, "복구된 deadline 으로 도래분만 finalize")
        assertEquals(open1.burstId, due.single().burstId)
        assertEquals(1, restored.pendingCount())
    }

    @Test
    fun `같은 시각·같은 큐면 결정론적 순서로 finalize 한다`() {
        val holder = mutableClock(t0.plusSeconds(100))
        val scheduler = BurstFinalizeScheduler(holder.clock(), segmentationVersion = 1)
        val a = openBurst(1)
        val b = openBurst(2)
        // 같은 deadline → burstId 순 타이브레이크.
        scheduler.schedule(a, t0.plusSeconds(7))
        scheduler.schedule(b, t0.plusSeconds(7))
        val ids = scheduler.sweep().map { it.burstId.value }
        assertEquals(ids.sorted(), ids, "deadline 동률은 burstId 순")
    }

    /** Thread.sleep 없이 Clock 만 갈아끼우는 테스트 헬퍼(시간 이동). */
    private class MutableClockHolder(
        private var now: Instant,
    ) {
        fun set(instant: Instant) {
            now = instant
        }

        fun clock(): Clock =
            object : Clock() {
                override fun instant(): Instant = now

                override fun getZone(): ZoneOffset = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?): Clock = this
            }
    }
}
