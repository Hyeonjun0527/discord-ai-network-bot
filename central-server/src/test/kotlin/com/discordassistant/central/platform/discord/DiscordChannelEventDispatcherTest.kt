package com.discordassistant.central.platform.discord

import com.discordassistant.central.platform.discord.nexa.NiaTurnGenerationTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiscordChannelEventDispatcherTest {
    @Test
    fun `default bounds retain raw context and evaluation work for a 100-message burst`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1)
        dispatcher.use {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val completed = CountDownLatch(199)

            assertThat(
                dispatcher.submit(1L) {
                    firstStarted.countDown()
                    releaseFirst.await()
                    completed.countDown()
                },
            ).isEqualTo(DiscordChannelEventAdmission.ACCEPTED)
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()

            val admissions =
                (2..100).flatMap {
                    listOf(
                        dispatcher.submitMutation(1L) { completed.countDown() },
                        dispatcher.submit(1L) { completed.countDown() },
                    )
                }
            assertThat(admissions).allMatch { it.accepted }

            releaseFirst.countDown()
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `100 ambient-message burst pays only the in-flight and latest judge evaluations`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1)
        val generations = NiaTurnGenerationTracker()
        dispatcher.use {
            val firstJudgeStarted = CountDownLatch(1)
            val releaseFirstJudge = CountDownLatch(1)
            val evaluationsCompleted = CountDownLatch(100)
            val paidJudgeAttempts = AtomicInteger()
            generations.observe(channelId = 1L, generation = 1L)

            assertThat(
                dispatcher.submit(1L) {
                    if (generations.isLatest(channelId = 1L, generation = 1L)) {
                        paidJudgeAttempts.incrementAndGet()
                        firstJudgeStarted.countDown()
                        releaseFirstJudge.await()
                    }
                    evaluationsCompleted.countDown()
                },
            ).isEqualTo(DiscordChannelEventAdmission.ACCEPTED)
            assertThat(firstJudgeStarted.await(1, TimeUnit.SECONDS)).isTrue()

            val admissions =
                (2L..100L).flatMap { generation ->
                    generations.observe(channelId = 1L, generation = generation)
                    listOf(
                        dispatcher.submitMutation(1L) {},
                        dispatcher.submit(1L) {
                            if (generations.isLatest(channelId = 1L, generation = generation)) {
                                paidJudgeAttempts.incrementAndGet()
                            }
                            evaluationsCompleted.countDown()
                        },
                    )
                }
            assertThat(admissions).allMatch { it.accepted }

            releaseFirstJudge.countDown()
            assertThat(evaluationsCompleted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(paidJudgeAttempts.get()).isEqualTo(2)
        }
    }

    @Test
    fun `same channel stays FIFO while another stripe progresses`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 2, queueCapacityPerStripe = 4, ordinaryQueueCapacityPerStripe = 4)
        dispatcher.use {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val sameChannelDone = CountDownLatch(1)
            val otherChannelDone = CountDownLatch(1)
            val order = CopyOnWriteArrayList<String>()

            assertThat(
                dispatcher
                    .submit(1L) {
                        order += "first-start"
                        firstStarted.countDown()
                        releaseFirst.await()
                        order += "first-end"
                    }.accepted,
            ).isTrue()
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(
                dispatcher
                    .submit(1L) {
                        order += "second"
                        sameChannelDone.countDown()
                    }.accepted,
            ).isTrue()
            assertThat(
                dispatcher
                    .submit(2L) {
                        order += "other"
                        otherChannelDone.countDown()
                    }.accepted,
            ).isTrue()

            assertThat(otherChannelDone.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(sameChannelDone.count).isEqualTo(1L)
            releaseFirst.countDown()
            assertThat(sameChannelDone.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(order.indexOf("first-end")).isLessThan(order.indexOf("second"))
        }
    }

    @Test
    fun `ordinary saturation fails closed and mutation evicts queued ordinary work`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1, queueCapacityPerStripe = 2, ordinaryQueueCapacityPerStripe = 1)
        dispatcher.use {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val mutationsDone = CountDownLatch(2)
            val queuedOrdinaryRan = AtomicBoolean(false)

            assertThat(
                dispatcher
                    .submit(1L) {
                        firstStarted.countDown()
                        releaseFirst.await()
                    }.accepted,
            ).isTrue()
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(dispatcher.submit(1L) { queuedOrdinaryRan.set(true) }).isEqualTo(DiscordChannelEventAdmission.ACCEPTED)
            assertThat(dispatcher.submit(1L) {}).isEqualTo(DiscordChannelEventAdmission.REJECTED)
            assertThat(dispatcher.submitMutation(1L) { mutationsDone.countDown() })
                .isEqualTo(DiscordChannelEventAdmission.ACCEPTED)
            assertThat(dispatcher.submitMutation(1L) { mutationsDone.countDown() })
                .isEqualTo(DiscordChannelEventAdmission.ACCEPTED_AFTER_EVICTION)

            releaseFirst.countDown()
            assertThat(mutationsDone.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(queuedOrdinaryRan.get()).isFalse()
        }
    }

    @Test
    fun `mutation-only saturation enters ordered overflow instead of losing redaction`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1, queueCapacityPerStripe = 1, ordinaryQueueCapacityPerStripe = 1)
        dispatcher.use {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val mutationsDone = CountDownLatch(2)
            val order = CopyOnWriteArrayList<String>()

            assertThat(
                dispatcher
                    .submit(1L) {
                        order += "first"
                        firstStarted.countDown()
                        releaseFirst.await()
                    }.accepted,
            ).isTrue()
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(
                dispatcher.submitMutation(1L) {
                    order += "mutation-1"
                    mutationsDone.countDown()
                },
            ).isEqualTo(DiscordChannelEventAdmission.ACCEPTED)
            assertThat(
                dispatcher.submitMutation(1L) {
                    order += "mutation-2"
                    mutationsDone.countDown()
                },
            ).isEqualTo(DiscordChannelEventAdmission.ACCEPTED_TO_MUTATION_OVERFLOW)
            assertThat(dispatcher.submit(1L) {}).isEqualTo(DiscordChannelEventAdmission.REJECTED)

            releaseFirst.countDown()

            assertThat(mutationsDone.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(order).containsExactly("first", "mutation-1", "mutation-2")
        }
    }

    @Test
    fun `failed task does not prevent later channel work`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1, queueCapacityPerStripe = 2, ordinaryQueueCapacityPerStripe = 2)
        dispatcher.use {
            val laterTaskRan = CountDownLatch(1)

            assertThat(dispatcher.submit(1L) { throw IllegalStateException("synthetic failure") }.accepted).isTrue()
            assertThat(dispatcher.submit(1L) { laterTaskRan.countDown() }.accepted).isTrue()

            assertThat(laterTaskRan.await(1, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `close discards queued ordinary work but drains queued mutation`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1, queueCapacityPerStripe = 3, ordinaryQueueCapacityPerStripe = 2)
        val firstStarted = CountDownLatch(1)
        val mutationRan = CountDownLatch(1)
        val queuedOrdinaryRan = AtomicBoolean(false)

        assertThat(
            dispatcher
                .submit(1L) {
                    firstStarted.countDown()
                    Thread.sleep(200)
                }.accepted,
        ).isTrue()
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(dispatcher.submit(1L) { queuedOrdinaryRan.set(true) }.accepted).isTrue()
        assertThat(dispatcher.submitMutation(1L) { mutationRan.countDown() }.accepted).isTrue()

        dispatcher.close()

        assertThat(mutationRan.count).isZero()
        assertThat(queuedOrdinaryRan.get()).isFalse()
    }

    @Test
    fun `forced close drains queued and overflow mutations in original order`() {
        val dispatcher =
            DiscordChannelEventDispatcher(
                stripeCount = 1,
                queueCapacityPerStripe = 1,
                ordinaryQueueCapacityPerStripe = 1,
                closeGraceMillis = 0,
                forcedCloseGraceMillis = 5,
            )
        val firstStarted = CountDownLatch(1)
        val firstInterrupted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val mutationOrder = CopyOnWriteArrayList<String>()

        assertThat(
            dispatcher
                .submit(1L) {
                    firstStarted.countDown()
                    try {
                        releaseFirst.await()
                    } catch (_: InterruptedException) {
                        firstInterrupted.countDown()
                        releaseFirst.await()
                    }
                }.accepted,
        ).isTrue()
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(dispatcher.submitMutation(1L) { mutationOrder += "edit" })
            .isEqualTo(DiscordChannelEventAdmission.ACCEPTED)
        assertThat(dispatcher.submitMutation(1L) { mutationOrder += "delete" })
            .isEqualTo(DiscordChannelEventAdmission.ACCEPTED_TO_MUTATION_OVERFLOW)

        val closeThread =
            Thread {
                dispatcher.close()
                closeFinished.countDown()
            }
        closeThread.start()

        assertThat(firstInterrupted.await(1, TimeUnit.SECONDS)).isTrue()
        releaseFirst.countDown()

        assertThat(closeFinished.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(mutationOrder).containsExactly("edit", "delete")
    }

    @Test
    fun `closed dispatcher rejects new work`() {
        val dispatcher = DiscordChannelEventDispatcher(stripeCount = 1, queueCapacityPerStripe = 1, ordinaryQueueCapacityPerStripe = 1)

        dispatcher.close()

        assertThat(dispatcher.submit(1L) {}).isEqualTo(DiscordChannelEventAdmission.REJECTED)
        assertThat(dispatcher.submitMutation(1L) {}).isEqualTo(DiscordChannelEventAdmission.REJECTED)
    }
}
